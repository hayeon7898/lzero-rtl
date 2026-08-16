package npu.control

import chisel3._
import chisel3.util._

/*
 * ============================================================================
 *  NPU Register File & MMIO Interface
 * ============================================================================
 *  "NPU Register File & MMIO Interface 명세서" 기준 구현.
 *
 *  Address Map (byte offset):
 *    0x00  RS1_REG          64b  R/W(Host) / RO(HW)
 *    0x08  RS2_REG          64b  R/W(Host) / RO(HW)
 *    0x10  INT_FLAG_REG[31:0] + STATUS_REG[63:32]
 *           - INT_FLAG_REG : W1C(Host) / Set(HW)
 *           - STATUS_REG   : RO(Host)  / Write(HW, 내부 조합 로직)
 *    0x20~0x78  DMA Auto-Populated Pointers x12 (64b each)
 *    0x80~0xA0  DMA Auto-Populated Dimensions/Offsets x5 (32b x2 packed)
 *
 *  하드웨어 요구사항 4가지:
 *    1) Dual Write-Port: fetch_working 신호로 Host(AXI-Lite) vs
 *       Command Fetcher(DMA) 쓰기 권한 MUX
 *    2) Execution Lock: busy==1 이면 0x20~0xA0 영역 Host write 무시(SLVERR)
 *    3) W1C: INT_FLAG_REG는 Host가 1을 쓴 비트만 0으로 클리어
 *    4) Zero-Latency Broadcast: 모든 출력 포트는 레지스터에 직결 (추가 D-FF 없음)
 * ============================================================================
 */

// ----------------------------------------------------------------------------
// 파라미터
// ----------------------------------------------------------------------------
object RfParams {
  val addrWidth = 12 // 4KB 공간
  val dataWidth = 64
  val strbWidth = dataWidth / 8
}

// ----------------------------------------------------------------------------
// AXI4-Lite Slave 인터페이스 (간이 구현 - AW/W/B/AR/R 5채널)
// ----------------------------------------------------------------------------
class Axi4LiteAddr(val addrWidth: Int) extends Bundle {
  val addr = UInt(addrWidth.W)
}

class Axi4LiteWriteData(val dataWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
  val strb = UInt((dataWidth / 8).W)
}

class Axi4LiteWriteResp extends Bundle {
  val resp = UInt(2.W) // 0:OKAY 2:SLVERR
}

class Axi4LiteReadData(val dataWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
  val resp = UInt(2.W)
}

object AxiResp {
  val OKAY   = 0.U(2.W)
  val SLVERR = 2.U(2.W)
}

class Axi4LiteSlaveIO(addrWidth: Int, dataWidth: Int) extends Bundle {
  val awaddr = Flipped(Decoupled(new Axi4LiteAddr(addrWidth)))
  val wdata  = Flipped(Decoupled(new Axi4LiteWriteData(dataWidth)))
  val bresp  = Decoupled(new Axi4LiteWriteResp)
  val araddr = Flipped(Decoupled(new Axi4LiteAddr(addrWidth)))
  val rdata  = Decoupled(new Axi4LiteReadData(dataWidth))
}

// ----------------------------------------------------------------------------
// 0x20~0x78 : DMA Pointer 영역 (64b x 12)
// ----------------------------------------------------------------------------
class RfPointers extends Bundle {
  val inputAddr      = UInt(64.W) // 0x20
  val weight1Addr     = UInt(64.W) // 0x28
  val weight2Addr     = UInt(64.W) // 0x30
  val quantParamAddr = UInt(64.W) // 0x38
  val angleParamAddr = UInt(64.W) // 0x40
  val actLutAddr      = UInt(64.W) // 0x48
  val expLutAddr      = UInt(64.W) // 0x50
  val scaleLutAddr    = UInt(64.W) // 0x58
  val ropeSinAddr     = UInt(64.W) // 0x60
  val ropeCosAddr     = UInt(64.W) // 0x68
  val outputAddr      = UInt(64.W) // 0x70
  val normBuffAddr    = UInt(64.W) // 0x78
}

// ----------------------------------------------------------------------------
// 0x80~0xA0 : Dimension/Offset 영역 (32b x 9, 64b x5 로 패킹)
// ----------------------------------------------------------------------------
class RfDims extends Bundle {
  val outRowNum     = UInt(32.W) // 0x80[31:0]
  val outIntermNum  = UInt(32.W) // 0x80[63:32]
  val outColNum     = UInt(32.W) // 0x88[31:0]
  val inTotalTiles  = UInt(32.W) // 0x88[63:32]
  val wtTotalTiles  = UInt(32.W) // 0x90[31:0]
  val outTotalTiles = UInt(32.W) // 0x90[63:32]
  val inputOffset   = UInt(32.W) // 0x98[31:0]
  val weightOffset  = UInt(32.W) // 0x98[63:32]
  val outputOffset  = UInt(32.W) // 0xA0[31:0]
  // 0xA0[63:32] RESERVED
}

// ----------------------------------------------------------------------------
// 레지스터 인덱스 맵 (addr(7,3) 로 결정되는 8byte-line 인덱스)
// ----------------------------------------------------------------------------
object RegMap {
  val RS1        = 0x00 >> 3 // 0
  val RS2        = 0x08 >> 3 // 1
  val INT_STATUS = 0x10 >> 3 // 2  (특수 처리: W1C + RO 조합)
  // 0x18 : 3, unused/reserved

  val INPUT_ADDR       = 0x20 >> 3 // 4
  val WEIGHT1_ADDR      = 0x28 >> 3 // 5
  val WEIGHT2_ADDR      = 0x30 >> 3 // 6
  val QUANT_PARAM_ADDR = 0x38 >> 3 // 7
  val ANGLE_PARAM_ADDR = 0x40 >> 3 // 8
  val ACT_LUT_ADDR      = 0x48 >> 3 // 9
  val EXP_LUT_ADDR      = 0x50 >> 3 // 10
  val SCALE_LUT_ADDR    = 0x58 >> 3 // 11
  val ROPE_SIN_ADDR     = 0x60 >> 3 // 12
  val ROPE_COS_ADDR     = 0x68 >> 3 // 13
  val OUTPUT_ADDR       = 0x70 >> 3 // 14
  val NORM_BUFF_ADDR    = 0x78 >> 3 // 15

  val DIM0 = 0x80 >> 3 // 16 : outRowNum / outIntermNum
  val DIM1 = 0x88 >> 3 // 17 : outColNum / inTotalTiles
  val DIM2 = 0x90 >> 3 // 18 : wtTotalTiles / outTotalTiles
  val DIM3 = 0x98 >> 3 // 19 : inputOffset / weightOffset
  val DIM4 = 0xA0 >> 3 // 20 : outputOffset / reserved

  // Dual-write / Execution-lock 보호 대상 영역 (DMA Auto-Populated Region)
  val DMA_REGION_LO = INPUT_ADDR
  val DMA_REGION_HI = DIM4

  val NUM_REGS = 32 // 0x00~0xF8 커버 (5bit index 여유있게)

  def idx(addr: UInt): UInt = addr(7, 3)
}

// ----------------------------------------------------------------------------
// Register File 최상위 모듈
// ----------------------------------------------------------------------------
class RegisterFile extends Module {
  val io = IO(new Bundle {
    val axi = new Axi4LiteSlaveIO(RfParams.addrWidth, RfParams.dataWidth)

    // ---- Command Fetcher(DMA)의 두 번째 쓰기 포트 ----
    // fetch_working=1 인 동안 이 포트가 0x20~0xA0 영역 쓰기 권한을 독점함
    val fetchWorking = Input(Bool())
    val fetchWrEn    = Input(Bool())
    val fetchWrAddr  = Input(UInt(RfParams.addrWidth.W)) // byte addr, RF 맵과 동일 체계
    val fetchWrData  = Input(UInt(RfParams.dataWidth.W))

    // ---- Interrupt Generator의 세 번째 쓰기 포트 (Event Detector & Zero-Copy Manager) ----
    // set_intflag: INT_FLAG_REG bit0 Set 펄스
    // rf_wen/rf_waddr/rf_wdata: Zero-Copy 주소 피드백 (명세서 상 0x30 = OUT_BASE_ADDR 고정)

    val setIntFlag = Input(Bool())
    val irqWen     = Input(Bool())
    val irqWaddr   = Input(UInt(8.W))  // 명세서 그대로 8bit (0x30 등 byte addr)
    val irqWdata   = Input(UInt(64.W))

    // ---- 칩 전역 상태 입력 ----
    val busy         = Input(Bool())       // Execution Lock 트리거
    val globalStall  = Input(Bool())       // STATUS_REG 조합 로직에 반영

    // ---- Zero-Latency Broadcast 출력 (레지스터에 직결) ----
    val rs1      = Output(UInt(64.W))
    val rs2      = Output(UInt(64.W))
    val pointers = Output(new RfPointers)
    val dims     = Output(new RfDims)
    val intFlag  = Output(UInt(32.W))
    val irq      = Output(Bool())
  })

  // --------------------------------------------------------------------
  // 저장소: 일반 레지스터 배열 + INT_FLAG 전용 레지스터
  // --------------------------------------------------------------------
  val regs     = RegInit(VecInit(Seq.fill(RegMap.NUM_REGS)(0.U(RfParams.dataWidth.W))))
  val intFlagReg = RegInit(0.U(32.W)) // INT_FLAG_REG (W1C/Set 특수 로직)

  // STATUS_REG: Host에는 RO, 내용은 매 사이클 하드웨어가 조합 로직으로 생성
  //   [1] global_stall : 확정 - Stall Generator의 RegNext(raw_stall) 그대로 연결
  val statusReg = Wire(UInt(32.W))
  statusReg := Cat(0.U(30.W), io.globalStall, io.busy) // [0]=busy, [1]=global_stall

  // --------------------------------------------------------------------
  // 공통 유틸: byte-strobe 적용 write
  // --------------------------------------------------------------------
  def applyStrobe(cur: UInt, wdata: UInt, wstrb: UInt): UInt = {
    val bytes = (0 until RfParams.strbWidth).map { i =>
      val curByte = cur((i + 1) * 8 - 1, i * 8)
      val newByte = wdata((i + 1) * 8 - 1, i * 8)
      Mux(wstrb(i), newByte, curByte)
    }
    Cat(bytes.reverse)
  }

  def isDmaRegion(index: UInt): Bool =
    index >= RegMap.DMA_REGION_LO.U && index <= RegMap.DMA_REGION_HI.U

  // --------------------------------------------------------------------
  // AXI4-Lite Write 채널 FSM (AW/W 래치 -> 응답 코드 계산 -> B 응답)
  //   * 실제 regs(idx) 반영은 아래 "3-way 쓰기 우선순위 MUX"에서 통합 처리한다.
  //     (AXI 응답 계산과 실제 write를 분리해야 Interrupt Gen / Fetcher와의
  //      우선순위를 한 곳에서 일관되게 관리할 수 있음)
  // --------------------------------------------------------------------
  object WrState extends ChiselEnum { val wIdle, wData, wResp = Value }
  val wState   = RegInit(WrState.wIdle)
  val awAddrReg = Reg(UInt(RfParams.addrWidth.W))
  val bRespReg  = RegInit(AxiResp.OKAY)

  io.axi.awaddr.ready := wState === WrState.wIdle
  io.axi.wdata.ready  := wState === WrState.wData
  io.axi.bresp.valid  := wState === WrState.wResp
  io.axi.bresp.bits.resp := bRespReg

  val wIdxW = RegMap.idx(awAddrReg)
  // Execution Lock: busy 이거나 fetch_working 중이면 Host의 DMA 영역 쓰기 금지
  val hostWriteAllowed = Mux(isDmaRegion(wIdxW), !io.busy && !io.fetchWorking, true.B)
  val hostWriteFire    = io.axi.wdata.fire && wIdxW =/= RegMap.INT_STATUS.U && hostWriteAllowed

  switch(wState) {
    is(WrState.wIdle) {
      when(io.axi.awaddr.fire) {
        awAddrReg := io.axi.awaddr.bits.addr
        wState    := WrState.wData
      }
    }
    is(WrState.wData) {
      when(io.axi.wdata.fire) {
        when(wIdxW === RegMap.INT_STATUS.U) {
          // INT_FLAG_REG(하위 32bit)만 W1C 대상, STATUS_REG(상위 32bit)는 쓰기 무시
          bRespReg := AxiResp.OKAY
        } .elsewhen(hostWriteAllowed) {
          bRespReg := AxiResp.OKAY
        } .otherwise {
          bRespReg := AxiResp.SLVERR // Execution Lock에 걸려 드롭됨
        }
        wState := WrState.wResp
      }
    }
    is(WrState.wResp) {
      when(io.axi.bresp.fire) { wState := WrState.wIdle }
    }
  }

  // --------------------------------------------------------------------
  // 3-way 쓰기 우선순위 MUX (일반 레지스터, INT_STATUS 제외)
  //   우선순위: ① Interrupt Generator(단발성 이벤트, 유실 방지 최우선)
  //            ② Command Fetcher/DMA (fetch_working 구간 동안 지속)
  //            ③ Host AXI-Lite
  //   주의: 같은 사이클에 ①이 발생하면 이번 사이클의 다른 인덱스에 대한
  //         ②/③ 쓰기는 전부 드랍된다 (극히 드문 케이스로 가정, 우선 스켈레톤 수준)
  // --------------------------------------------------------------------
  val irqWriteValid   = io.irqWen
  val irqWriteIdx     = RegMap.idx(io.irqWaddr)

  val fetchWriteValid = io.fetchWorking && io.fetchWrEn && isDmaRegion(RegMap.idx(io.fetchWrAddr))
  val fetchWriteIdx   = RegMap.idx(io.fetchWrAddr)

  when(irqWriteValid) {
    regs(irqWriteIdx) := io.irqWdata
  } .elsewhen(fetchWriteValid) {
    regs(fetchWriteIdx) := io.fetchWrData
  } .elsewhen(hostWriteFire) {
    regs(wIdxW) := applyStrobe(regs(wIdxW), io.axi.wdata.bits.data, io.axi.wdata.bits.strb)
  }

  // INT_FLAG_REG 갱신: HW Set(set_intflag 펄스 -> bit0) + Host W1C(쓰기 발생 시 클리어)
  // 같은 사이클에 Set과 Clear가 겹치면 Set이 우선(인터럽트 유실 방지)
  val intFlagW1cFire = io.axi.wdata.fire && wIdxW === RegMap.INT_STATUS.U
  val clearMask = Wire(UInt(32.W))
  clearMask := Mux(
    intFlagW1cFire,
    Cat((0 until 4).reverse.map(i =>
      Mux(io.axi.wdata.bits.strb(i), io.axi.wdata.bits.data(8 * i + 7, 8 * i), 0.U(8.W))
    )),
    0.U(32.W)
  )
  val hwSetMask = Cat(0.U(31.W), io.setIntFlag) // 현재는 bit0(compute_done)만 사용
  intFlagReg := (intFlagReg & ~clearMask) | hwSetMask

  // --------------------------------------------------------------------
  // 3) AXI4-Lite Read 채널 FSM (AR 래치 -> R 응답)
  // --------------------------------------------------------------------
  object RdState extends ChiselEnum { val rIdle, rResp = Value }
  val rState   = RegInit(RdState.rIdle)
  val rDataReg = Reg(UInt(RfParams.dataWidth.W))

  io.axi.araddr.ready    := rState === RdState.rIdle
  io.axi.rdata.valid     := rState === RdState.rResp
  io.axi.rdata.bits.data := rDataReg
  io.axi.rdata.bits.resp := AxiResp.OKAY // 읽기는 항상 허용 (디버깅 목적 RO 접근 포함)

  switch(rState) {
    is(RdState.rIdle) {
      when(io.axi.araddr.fire) {
        val rIdx = RegMap.idx(io.axi.araddr.bits.addr)
        rDataReg := Mux(rIdx === RegMap.INT_STATUS.U, Cat(statusReg, intFlagReg), regs(rIdx))
        rState   := RdState.rResp
      }
    }
    is(RdState.rResp) {
      when(io.axi.rdata.fire) { rState := RdState.rIdle }
    }
  }

  // --------------------------------------------------------------------
  // 4) Zero-Latency Broadcast: 출력 포트는 레지스터에 직결 (bit-slicing만)
  // --------------------------------------------------------------------
  io.rs1 := regs(RegMap.RS1)
  io.rs2 := regs(RegMap.RS2)

  io.pointers.inputAddr      := regs(RegMap.INPUT_ADDR)
  io.pointers.weight1Addr     := regs(RegMap.WEIGHT1_ADDR)
  io.pointers.weight2Addr     := regs(RegMap.WEIGHT2_ADDR)
  io.pointers.quantParamAddr := regs(RegMap.QUANT_PARAM_ADDR)
  io.pointers.angleParamAddr := regs(RegMap.ANGLE_PARAM_ADDR)
  io.pointers.actLutAddr      := regs(RegMap.ACT_LUT_ADDR)
  io.pointers.expLutAddr      := regs(RegMap.EXP_LUT_ADDR)
  io.pointers.scaleLutAddr    := regs(RegMap.SCALE_LUT_ADDR)
  io.pointers.ropeSinAddr     := regs(RegMap.ROPE_SIN_ADDR)
  io.pointers.ropeCosAddr     := regs(RegMap.ROPE_COS_ADDR)
  io.pointers.outputAddr      := regs(RegMap.OUTPUT_ADDR)
  io.pointers.normBuffAddr    := regs(RegMap.NORM_BUFF_ADDR)

  io.dims.outRowNum     := regs(RegMap.DIM0)(31, 0)
  io.dims.outIntermNum  := regs(RegMap.DIM0)(63, 32)
  io.dims.outColNum     := regs(RegMap.DIM1)(31, 0)
  io.dims.inTotalTiles  := regs(RegMap.DIM1)(63, 32)
  io.dims.wtTotalTiles  := regs(RegMap.DIM2)(31, 0)
  io.dims.outTotalTiles := regs(RegMap.DIM2)(63, 32)
  io.dims.inputOffset   := regs(RegMap.DIM3)(31, 0)
  io.dims.weightOffset  := regs(RegMap.DIM3)(63, 32)
  io.dims.outputOffset  := regs(RegMap.DIM4)(31, 0)

  io.intFlag := intFlagReg
  io.irq     := intFlagReg.orR
}