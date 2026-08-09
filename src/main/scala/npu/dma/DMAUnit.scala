package npu.dma

import chisel3._
import chisel3.util._

/*
 * ============================================================================
 *  NPU DMA Unit - 전체 스켈레톤
 * ============================================================================
 *
 *  다이어그램 기준으로 아래 서브 컨트롤러들로 분리했습니다:
 *
 *   1) RoCC Command Decoder      : custom0 rs1, rs2 명령 수신 + rs1 Decoder
 *   2) TLBurstInitializer        : TileLink(또는 유사 AXI-lite) 버스트 트랜잭션 생성
 *   3) DramIbReadController      : DRAM -> Intermediate Buffer  (Memory Target & Read Burst)
 *   4) DramIbWriteController     : OCM/IB -> DRAM               (OCM src & Write Burst)
 *   5) OcmToComputeReadController: OCM -> Compute Unit          (OCM src & read Addr Gen)
 *   6) ComputeToOcmWriteController: Compute Unit -> OCM         (write Addr Gen)
 *   7) DmaToOcmRfWriteController : DRAM에서 온 데이터 -> OCM/RF (OCM Target & write Addr Gen)
 *   8) DmaToRfWriteController    : Register File(파라미터) 직접 쓰기
 *   9) DmaToLutWriteController   : LUT 프로그래밍용 쓰기
 *  10) InterruptGenerator        : 완료/에러 시 irq 발생
 *
 * ============================================================================
 */

// ----------------------------------------------------------------------------
// 공통 파라미터
// ----------------------------------------------------------------------------
case class DmaParams(
  dramAddrWidth: Int = 34,      // TODO: 실제 DRAM 주소폭
  ocmAddrWidth: Int  = 20,      // TODO: 실제 OCM 주소폭
  dataWidth: Int     = 512,     // 64byte = 512bit burst
  maxBurstLen: Int   = 16,      // TODO: 최대 burst beat 수
  numOcmBanks: Int   = 16       // Unified/Weight Buffer 등 BRAM*16 기준
)

// ----------------------------------------------------------------------------
// OCM 포트 - "OCM Controller"가 노출하는 공통 인터페이스 (추정)
// ----------------------------------------------------------------------------
class OcmPort(p: DmaParams) extends Bundle {
  val req    = Decoupled(new Bundle {
    val addr  = UInt(p.ocmAddrWidth.W)
    val wdata = UInt(p.dataWidth.W)
    val wmask = UInt((p.dataWidth / 8).W)
    val write = Bool()
  })
  val rdata      = Flipped(Valid(UInt(p.dataWidth.W)))
  val bankSelect = Output(UInt(log2Ceil(p.numOcmBanks).W)) // 어느 버퍼(BRAM 뱅크)인지
}

// ----------------------------------------------------------------------------
// 외부 메모리(DRAM) 마스터 포트 - TileLink 실제 연결 전 임시 추상화
// 나중에 freechips.rocketchip.tilelink.TLBundle로 교체 예정이면 여기만 손보면 됨
// ----------------------------------------------------------------------------
class DramMasterPort(p: DmaParams) extends Bundle {
  val req = Decoupled(new Bundle {
    val addr  = UInt(p.dramAddrWidth.W)
    val write = Bool()
    val len   = UInt(log2Ceil(p.maxBurstLen + 1).W)
    val wdata = UInt(p.dataWidth.W)
  })
  val resp = Flipped(Decoupled(new Bundle {
    val rdata = UInt(p.dataWidth.W)
    val last  = Bool()
  }))
}

// ----------------------------------------------------------------------------
// RoCC 커맨드 (custom0 rs1, rs2) - Central Control Unit 앞단
// ----------------------------------------------------------------------------
class RoccCmd extends Bundle {
  val rs1 = UInt(64.W) // compute unit control field 등
  val rs2 = UInt(64.W) // base address, offset, mask, length 등
}

// rs1 필드 디코더: Access Pattern(npu_struct, lut_program, pre_loading, compute) 판별
class Rs1Decoder extends Module {
  val io = IO(new Bundle {
    val rs1 = Input(UInt(64.W))
    val accessPattern = Output(UInt(2.W)) // TODO: 실제 인코딩 확인 필요
    val accessStart    = Output(Bool())
  })
  // TODO: 실제 rs1 비트필드 매핑으로 교체
  io.accessPattern := io.rs1(1, 0)
  io.accessStart    := io.rs1(2)
}

object AccessPattern {
  val NPU_STRUCT   = 0.U(2.W)
  val LUT_PROGRAM  = 1.U(2.W)
  val PRE_LOADING  = 2.U(2.W)
  val COMPUTE      = 3.U(2.W)
}

// ----------------------------------------------------------------------------
// 공통 FSM 베이스 (모든 read/write 컨트롤러가 거의 동일한 뼈대를 가짐)
// ----------------------------------------------------------------------------
object CtrlState extends ChiselEnum {
  val sIdle, sFetch, sBurst, sDrain, sDone = Value
}

// ----------------------------------------------------------------------------
// 1) DRAM/IB Read Controller  (DRAM -> Intermediate Buffer)
//    "Memory Target & Read Burst Generate"
// ----------------------------------------------------------------------------
class DramIbReadController(p: DmaParams) extends Module {
  val io = IO(new Bundle {
    val start   = Input(Bool())
    val baseAddr = Input(UInt(p.dramAddrWidth.W))
    val length   = Input(UInt(32.W)) // byte 단위 총 길이
    val done    = Output(Bool())

    val dram = new DramMasterPort(p)
    val ib   = Decoupled(UInt(p.dataWidth.W)) // Intermediate Buffer로 push
  })

  import CtrlState._
  val state    = RegInit(sIdle)
  val addrReg  = RegInit(0.U(p.dramAddrWidth.W))
  val remBytes = RegInit(0.U(32.W))
  val burstBytes = (p.dataWidth / 8).U

  io.dram.req.valid       := state === sBurst
  io.dram.req.bits.addr   := addrReg
  io.dram.req.bits.write  := false.B
  io.dram.req.bits.len    := p.maxBurstLen.U // TODO: 남은 길이에 맞춰 계산
  io.dram.req.bits.wdata  := 0.U

  io.dram.resp.ready := io.ib.ready
  io.ib.valid  := io.dram.resp.valid
  io.ib.bits   := io.dram.resp.bits.rdata

  io.done := state === sDone

  switch(state) {
    is(sIdle) {
      when(io.start) {
        addrReg  := io.baseAddr
        remBytes := io.length
        state    := sBurst
      }
    }
    is(sBurst) {
      when(io.dram.req.fire) {
        addrReg  := addrReg + burstBytes
        remBytes := remBytes - burstBytes
        when(remBytes <= burstBytes) { state := sDrain }
      }
    }
    is(sDrain) {
      when(io.dram.resp.fire && io.dram.resp.bits.last) {
        state := sDone
      }
    }
    is(sDone) {
      state := sIdle // TODO: ready pulse로 바꿀지 결정
    }
  }
}

// ----------------------------------------------------------------------------
// 2) DRAM/IB Write Controller  (OCM/IB -> DRAM)
//    "OCM src & Write Burst Generate"
// ----------------------------------------------------------------------------
class DramIbWriteController(p: DmaParams) extends Module {
  val io = IO(new Bundle {
    val start    = Input(Bool())
    val dramAddr = Input(UInt(p.dramAddrWidth.W))
    val ocmAddr  = Input(UInt(p.ocmAddrWidth.W))
    val length   = Input(UInt(32.W))
    val done     = Output(Bool())

    val ocm  = new OcmPort(p)
    val dram = new DramMasterPort(p)
  })

  import CtrlState._
  val state       = RegInit(sIdle)
  val ocmAddrReg  = RegInit(0.U(p.ocmAddrWidth.W))
  val dramAddrReg = RegInit(0.U(p.dramAddrWidth.W))
  val remBytes    = RegInit(0.U(32.W))
  val burstBytes  = (p.dataWidth / 8).U

  // OCM read 요청
  io.ocm.req.valid          := state === sFetch
  io.ocm.req.bits.addr      := ocmAddrReg
  io.ocm.req.bits.write     := false.B
  io.ocm.req.bits.wdata     := 0.U
  io.ocm.req.bits.wmask     := 0.U
  io.ocm.bankSelect         := 0.U // TODO: 타겟 버퍼 뱅크 인코딩

  // DRAM write 요청 (OCM rdata를 그대로 실어보냄)
  io.dram.req.valid      := io.ocm.rdata.valid
  io.dram.req.bits.addr  := dramAddrReg
  io.dram.req.bits.write := true.B
  io.dram.req.bits.len   := 1.U // TODO: burst 묶음 처리
  io.dram.req.bits.wdata := io.ocm.rdata.bits
  io.dram.resp.ready     := true.B

  io.done := state === sDone

  switch(state) {
    is(sIdle) {
      when(io.start) {
        ocmAddrReg  := io.ocmAddr
        dramAddrReg := io.dramAddr
        remBytes    := io.length
        state       := sFetch
      }
    }
    is(sFetch) {
      when(io.ocm.req.fire) {
        ocmAddrReg  := ocmAddrReg + burstBytes
        dramAddrReg := dramAddrReg + burstBytes
        remBytes    := remBytes - burstBytes
        when(remBytes <= burstBytes) { state := sDone }
      }
    }
    is(sDone) { state := sIdle }
  }
}

// ----------------------------------------------------------------------------
// 3) OCM -> Compute Unit Read Controller
//    "OCM src & read Address Generate"
// ----------------------------------------------------------------------------
class OcmToComputeReadController(p: DmaParams) extends Module {
  val io = IO(new Bundle {
    val start      = Input(Bool())
    val baseAddr   = Input(UInt(p.ocmAddrWidth.W))
    val strideAddr = Input(UInt(p.ocmAddrWidth.W)) // 2D/3D stride
    val numElems   = Input(UInt(16.W))
    val done       = Output(Bool())

    val ocm = new OcmPort(p)
    val toCompute = Decoupled(UInt(p.dataWidth.W))
  })

  import CtrlState._
  val state   = RegInit(sIdle)
  val addrReg = RegInit(0.U(p.ocmAddrWidth.W))
  val cnt     = RegInit(0.U(16.W))

  io.ocm.req.valid      := state === sBurst
  io.ocm.req.bits.addr  := addrReg
  io.ocm.req.bits.write := false.B
  io.ocm.req.bits.wdata := 0.U
  io.ocm.req.bits.wmask := 0.U
  io.ocm.bankSelect     := 0.U // TODO

  io.toCompute.valid := io.ocm.rdata.valid
  io.toCompute.bits  := io.ocm.rdata.bits

  io.done := state === sDone

  switch(state) {
    is(sIdle) {
      when(io.start) {
        addrReg := io.baseAddr
        cnt     := 0.U
        state   := sBurst
      }
    }
    is(sBurst) {
      when(io.ocm.req.fire) {
        addrReg := addrReg + io.strideAddr
        cnt     := cnt + 1.U
        when(cnt + 1.U === io.numElems) { state := sDone }
      }
    }
    is(sDone) { state := sIdle }
  }
}

// ----------------------------------------------------------------------------
// 4) Compute Unit -> OCM Write Controller
//    "write Address Generate"
// ----------------------------------------------------------------------------
class ComputeToOcmWriteController(p: DmaParams) extends Module {
  val io = IO(new Bundle {
    val start    = Input(Bool())
    val baseAddr = Input(UInt(p.ocmAddrWidth.W))
    val fromCompute = Flipped(Decoupled(UInt(p.dataWidth.W))) // Compute Unit output + valid
    val done     = Output(Bool())

    val ocm = new OcmPort(p)
  })

  val addrReg = RegInit(0.U(p.ocmAddrWidth.W))
  val active  = RegInit(false.B)

  when(io.start) { addrReg := io.baseAddr; active := true.B }

  io.fromCompute.ready   := active && io.ocm.req.ready
  io.ocm.req.valid       := active && io.fromCompute.valid
  io.ocm.req.bits.addr   := addrReg
  io.ocm.req.bits.write  := true.B
  io.ocm.req.bits.wdata  := io.fromCompute.bits
  io.ocm.req.bits.wmask  := ~(0.U((p.dataWidth / 8).W))
  io.ocm.bankSelect      := 0.U // TODO

  when(io.ocm.req.fire) {
    addrReg := addrReg + (p.dataWidth / 8).U
  }

  io.done := false.B // TODO: length 카운터 붙여서 완료 판정
}

// ----------------------------------------------------------------------------
// 5) DMA to OCM/Control RF Write Controller
//    "OCM Target & write Address Generate" - DRAM에서 온 데이터를 OCM/RF에 씀
// ----------------------------------------------------------------------------
class DmaToOcmRfWriteController(p: DmaParams) extends Module {
  val io = IO(new Bundle {
    val start    = Input(Bool())
    val ocmAddr  = Input(UInt(p.ocmAddrWidth.W))
    val targetIsRf = Input(Bool()) // OCM이 아니라 RF로 갈지
    val fromDram = Flipped(Decoupled(UInt(p.dataWidth.W)))
    val done     = Output(Bool())

    val ocm   = new OcmPort(p)
    val rfWrite = Valid(new Bundle {
      val addr = UInt(8.W)
      val data = UInt(p.dataWidth.W)
    })
  })

  val addrReg = RegInit(0.U(p.ocmAddrWidth.W))
  when(io.start) { addrReg := io.ocmAddr }

  io.fromDram.ready := Mux(io.targetIsRf, true.B, io.ocm.req.ready)

  io.ocm.req.valid      := !io.targetIsRf && io.fromDram.valid
  io.ocm.req.bits.addr  := addrReg
  io.ocm.req.bits.write := true.B
  io.ocm.req.bits.wdata := io.fromDram.bits
  io.ocm.req.bits.wmask := ~(0.U((p.dataWidth / 8).W))
  io.ocm.bankSelect     := 0.U // TODO

  io.rfWrite.valid := io.targetIsRf && io.fromDram.valid
  io.rfWrite.bits.addr := addrReg(7, 0)
  io.rfWrite.bits.data := io.fromDram.bits

  when(io.fromDram.fire) { addrReg := addrReg + (p.dataWidth / 8).U }

  io.done := false.B // TODO
}

// ----------------------------------------------------------------------------
// 6) Interrupt Generator
// ----------------------------------------------------------------------------
class InterruptGenerator extends Module {
  val io = IO(new Bundle {
    val doneEvents = Input(Vec(8, Bool())) // 각 컨트롤러의 done 신호 모음
    val intflagClr = Input(Bool())
    val irq        = Output(Bool())
  })
  val flag = RegInit(false.B)
  when(io.doneEvents.reduce(_ || _)) { flag := true.B }
  when(io.intflagClr) { flag := false.B }
  io.irq := flag
}

// ----------------------------------------------------------------------------
// 최상위 DMA Unit - 위 서브모듈들을 인스턴스화하고 rs1 디코더로 라우팅
// ----------------------------------------------------------------------------
class DmaUnit(p: DmaParams = DmaParams()) extends Module {
  val io = IO(new Bundle {
    val cmd  = Flipped(Decoupled(new RoccCmd))
    val dram = new DramMasterPort(p)
    val ocm  = Vec(p.numOcmBanks, new OcmPort(p)) // 버퍼별 OCM Controller 포트
    val irq  = Output(Bool())
  })

  val decoder = Module(new Rs1Decoder)
  decoder.io.rs1 := io.cmd.bits.rs1

  val dramIbRead  = Module(new DramIbReadController(p))
  val dramIbWrite = Module(new DramIbWriteController(p))
  val ocmRead     = Module(new OcmToComputeReadController(p))
  val ocmWrite    = Module(new ComputeToOcmWriteController(p))
  val ocmRfWrite  = Module(new DmaToOcmRfWriteController(p))
  val irqGen      = Module(new InterruptGenerator)

  // TODO: decoder.io.accessPattern에 따라 io.cmd를 알맞은 서브모듈로 라우팅
  io.cmd.ready := true.B // placeholder

  // TODO: io.ocm(bank) <-> 각 컨트롤러의 ocm 포트를 뱅크 선택에 따라 연결
  io.ocm.foreach { port =>
    port.req.valid      := false.B
    port.req.bits.addr  := 0.U
    port.req.bits.wdata := 0.U
    port.req.bits.wmask := 0.U
    port.req.bits.write := false.B
    port.bankSelect      := 0.U
  }

  io.dram <> dramIbRead.io.dram // TODO: dramIbWrite와 arbitration 필요

  irqGen.io.doneEvents := VecInit(
    dramIbRead.io.done, dramIbWrite.io.done, ocmWrite.io.done,
    ocmRfWrite.io.done, false.B, false.B, false.B, false.B
  )
  irqGen.io.intflagClr := false.B // TODO
  io.irq := irqGen.io.irq
}