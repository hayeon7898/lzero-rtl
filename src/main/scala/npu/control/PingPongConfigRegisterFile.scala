package npu.control

import chisel3._
import chisel3.util._

/*
 * ============================================================================
 *  PingPongConfigRegisterFile 
 * ============================================================================
 *  기존 RegisterFile의 task configuration(rs1, rs2, descriptor 0x20..0xA0)을
 *  동일한 두 bank로 복제하고, 1bit selector로 active/shadow 역할을 정한다.
 *    - 실행부는 active bank만 읽는다.
 *    - 실행 중에는 모든 config write(Host / Command Fetcher / Interrupt Gen)가 shadow로만 간다.
 *    - instDone(실행 종료 pulse)이 오면 RF가 직접 다음 동작을 정한다.
 *        shadow에 완성된 instruction이 있으면  -> selector만 toggle(swap), 다음 연산 시작
 *        shadow가 비어 있으면                 -> reset 상태로 복귀(idle), 다음 instruction 대기
 *    - idle(실행 중 instruction 없음)에서 instruction이 도착하면 active bank에 바로 쓰고
 *      마지막 beat가 들어오는 즉시 시작한다. (reset 직후/비어서 복귀한 경우 active=bank0)
 *    - INT_FLAG_REG / STATUS_REG는 공통 1개 (swap/reset 복귀와 무관).
 *
 *  이 파일은 원본 RegisterFile.scala(AXI4-Lite 버전)의 아래 정의를 그대로 재사용한다.
 *    RfParams, Axi4LiteSlaveIO, AxiResp, RfPointers, RfDims, RegMap
 *
 *  ── 구현 시 둔 가정 (명세에 없거나 모호해서 정한 것) ──────────────────────
 *  [A1] TODO(spec-confirm) Interrupt Generator의 zero-copy 쓰기(irqWen/irqWaddr/
 *       irqWdata, 보통 0x70 OUTPUT_ADDR)도 "모든 config write는 shadow" 원칙에 따라
 *       shadow bank로 보낸다. 완료된 active 명령의 결과 주소를 MMIO로 읽게 하려면
 *       active로 보내야 하지만, 명세 §5가 active 직접 write bypass를 금지하므로
 *       팀 확인 전까지 원칙대로 shadow로 둔다.
 *  [A2] busy 기반 config write 차단과 SLVERR 응답은 제거한다. 쓰기가 shadow로만
 *       가므로 active는 구조적으로 보호된다(명세 §5). busy는 STATUS_REG 표시용.
 *  [A3] 정의되지 않은 주소: 읽으면 0, 쓰면 무시. (원본은 dead storage였음)
 *  [A4] 쓰기 중재는 원본 그대로 유지: irq > fetch > host. 상위 소스가 있는
 *       cycle에는 하위 소스 write가 통째로 드랍된다(원본의 기존 동작).
 *  [A5] TODO(spec-confirm) "instruction이 완성됐다"의 기준은 Command Fetcher의
 *       마지막 descriptor beat(fetchChunkIdx==2) 도착으로 둔다. (rs1/rs2는 그 전에 도착)
 *  [A6] done 시점에 shadow가 미완성(rs1/rs2만 오고 descriptor 도중)이면 그 일부 write를
 *       버리지 않는다. idle 복귀 후 그 bank가 "적재 중 bank"가 되어 나머지 beat가 같은
 *       bank로 이어서 들어간다. 일부 write가 전혀 없을 때만 active=bank0으로 복귀하고
 *       두 bank를 reset 값으로 지운다.
 *  [A7] 복귀한 idle 상태에서 active 출력은 reset 값(0) 또는 적재 중인 값이다.
 *       소비자는 activeValid / instStart로 유효 여부를 판단한다.
 * ============================================================================
 */

// ----------------------------------------------------------------------------
// Bundle 정의
// ----------------------------------------------------------------------------
object TaskDescriptorConfig {
  val BaseRegIdx: Int = RegMap.DMA_REGION_LO                                    // 0x20 >> 3 = 4
  val NumWords: Int   = RegMap.DMA_REGION_HI - RegMap.DMA_REGION_LO + 1         // 17 (136B)
  require(NumWords == 17, s"descriptor must be 17 x 64bit words, got $NumWords")
}

/** 기존 descriptor 영역(0x20..0xA0)을 64bit word 단위 그대로 보관. word k <-> 0x20 + 8k */
class TaskDescriptorConfig extends Bundle {
  val words = Vec(TaskDescriptorConfig.NumWords, UInt(64.W))
}

class InstructionConfig extends Bundle {
  val commandBits       = UInt(64.W)               // rs1 (RS1_REG, 0x00)
  val descriptorAddress = UInt(64.W)               // rs2 (RS2_REG, 0x08)
  val taskDescriptor    = new TaskDescriptorConfig // 0x20..0xA0
}

object InstructionConfig {
  /** 설정 word를 [rs1, rs2, descriptor word 0..16] 순서로 펼친다. */
  def wordsOf(c: InstructionConfig): Seq[UInt] =
    Seq(c.commandBits, c.descriptorAddress) ++ c.taskDescriptor.words.toSeq

  /** wordsOf 순서에 대응하는 RF 8byte-line 인덱스 (addr(7,3)) */
  val CfgRegIdx: Seq[Int] =
    Seq(RegMap.RS1, RegMap.RS2) ++
      (0 until TaskDescriptorConfig.NumWords).map(TaskDescriptorConfig.BaseRegIdx + _)

  val NumCfgWords: Int = CfgRegIdx.length // 19

  private def w(td: TaskDescriptorConfig, regIdx: Int): UInt =
    td.words(regIdx - TaskDescriptorConfig.BaseRegIdx)

  /** 기존 RegisterFile의 broadcast 출력과 같은 해석 (pointers) */
  def pointersOf(td: TaskDescriptorConfig): RfPointers = {
    val p = Wire(new RfPointers)
    p.inputAddr      := w(td, RegMap.INPUT_ADDR)
    p.weight1Addr    := w(td, RegMap.WEIGHT1_ADDR)
    p.weight2Addr    := w(td, RegMap.WEIGHT2_ADDR)
    p.quantParamAddr := w(td, RegMap.QUANT_PARAM_ADDR)
    p.angleParamAddr := w(td, RegMap.ANGLE_PARAM_ADDR)
    p.actLutAddr     := w(td, RegMap.ACT_LUT_ADDR)
    p.expLutAddr     := w(td, RegMap.EXP_LUT_ADDR)
    p.scaleLutAddr   := w(td, RegMap.SCALE_LUT_ADDR)
    p.ropeSinAddr    := w(td, RegMap.ROPE_SIN_ADDR)
    p.ropeCosAddr    := w(td, RegMap.ROPE_COS_ADDR)
    p.outputAddr     := w(td, RegMap.OUTPUT_ADDR)
    p.normBuffAddr   := w(td, RegMap.NORM_BUFF_ADDR)
    p
  }

  /** 기존 RegisterFile의 broadcast 출력과 같은 해석 (dims, 32bit x2 packing) */
  def dimsOf(td: TaskDescriptorConfig): RfDims = {
    val d = Wire(new RfDims)
    d.outRowNum     := w(td, RegMap.DIM0)(31, 0)
    d.outIntermNum  := w(td, RegMap.DIM0)(63, 32)
    d.outColNum     := w(td, RegMap.DIM1)(31, 0)
    d.inTotalTiles  := w(td, RegMap.DIM1)(63, 32)
    d.wtTotalTiles  := w(td, RegMap.DIM2)(31, 0)
    d.outTotalTiles := w(td, RegMap.DIM2)(63, 32)
    d.inputOffset   := w(td, RegMap.DIM3)(31, 0)
    d.weightOffset  := w(td, RegMap.DIM3)(63, 32)
    d.outputOffset  := w(td, RegMap.DIM4)(31, 0)
    d
  }
}

// ----------------------------------------------------------------------------
// 본체
// ----------------------------------------------------------------------------
class PingPongConfigRegisterFile extends Module {
  import InstructionConfig._

  val io = IO(new Bundle {
    val axi = new Axi4LiteSlaveIO(RfParams.addrWidth, RfParams.dataWidth)

    // ---- 신규: 실행 종료 (1-cycle pulse). swap/reset 복귀 판단은 RF 내부에서 한다 ----
    val instDone = Input(Bool())

    // ---- 기존: Command Fetcher(DMA to RF Writer) 512bit beat write ----
    val fetchWen      = Input(Bool())
    val fetchChunkIdx = Input(UInt(2.W))
    val fetchWdata    = Input(UInt(512.W))

    // ---- 기존: Interrupt Generator ----
    val setIntFlag = Input(Bool())
    val irqWen     = Input(Bool())
    val irqWaddr   = Input(UInt(8.W))
    val irqWdata   = Input(UInt(64.W))

    // ---- 기존: 칩 전역 상태 (STATUS_REG 표시용) ----
    val busy        = Input(Bool())
    val globalStall = Input(Bool())

    // ---- 신규 출력 ----
    val activeConfig            = Output(new InstructionConfig)
    val shadowDescriptorAddress = Output(UInt(64.W))
    val activeBankIndex         = Output(UInt(1.W))
    val instStart               = Output(Bool()) // 새 active instruction 시작 (1-cycle pulse, config는 같은 cycle부터 유효)
    val activeValid             = Output(Bool()) // false = reset/idle 대기
    val shadowValid             = Output(Bool()) // 실행 중 shadow에 완성된 다음 instruction이 있음

    // ---- 기존 이름 호환 출력 (모두 active bank 기준) ----
    val rs1      = Output(UInt(64.W))
    val rs2      = Output(UInt(64.W))
    val pointers = Output(new RfPointers)
    val dims     = Output(new RfDims)

    // ---- 공통 register ----
    val intFlag = Output(UInt(32.W))
    val irq     = Output(Bool())
  })

  // --------------------------------------------------------------------
  // 저장소: 물리 bank 2개 + selector. active/shadow는 "역할"일 뿐 별도 저장소 없음
  // --------------------------------------------------------------------
  val configBanks     = RegInit(0.U.asTypeOf(Vec(2, new InstructionConfig)))
  val activeBankIndex = RegInit(0.U(1.W))
  val shadowBankIndex = ~activeBankIndex

  val activeValid = RegInit(false.B) // active bank에 시작됐거나 실행 중인 instruction이 있는가 (false = idle)
  val shadowValid = RegInit(false.B) // 실행 중, shadow에 완성된 다음 instruction이 있는가
  val loadDirty   = RegInit(false.B) // 적재 대상 bank에 아직 소비 안 된(미완성/완성) instruction이 있는가

  val activeConfig = configBanks(activeBankIndex)
  val shadowConfig = configBanks(shadowBankIndex)

  // 쓰기 대상: 실행 중이면 shadow, idle이면 active bank(= bank0 또는 적재 중이던 bank)
  val writeBankIndex = Mux(activeValid, shadowBankIndex, activeBankIndex)
  val writeConfig    = configBanks(writeBankIndex)

  // 공통 register
  val intFlagReg = RegInit(0.U(32.W))
  val statusReg  = Wire(UInt(32.W))
  statusReg := Cat(0.U(30.W), io.globalStall, io.busy) // [0]=busy [1]=global_stall

  // --------------------------------------------------------------------
  // 종료 처리 판단 (조합). 상태 갱신은 config write 합성 뒤에서 한다.
  //   lastBeat   : Command Fetcher 마지막 descriptor beat 도착 -> instruction 완성 [A5]
  //   shadowReady: 실행 중 shadow가 완성됨 (done과 같은 cycle에 마지막 beat가 와도 인정)
  // --------------------------------------------------------------------
  val doneFire     = io.instDone && activeValid
  val fetchCfgFire = io.fetchWen && !io.irqWen          // irq > fetch 중재에서 안 밀린 fetch
  val lastBeat     = fetchCfgFire && (io.fetchChunkIdx === 2.U)
  val shadowReady  = shadowValid || (activeValid && lastBeat)
  val swapFire     = doneFire && shadowReady            // 다음 instruction으로 전환
  val resetFire    = doneFire && !shadowReady           // 비어 있음 -> reset 상태로 복귀
  val idleStart    = !activeValid && lastBeat           // idle에서 instruction 완성 -> 바로 시작

  // --------------------------------------------------------------------
  // Host AXI4-Lite write FSM (원본 구조 유지, busy 차단/SLVERR 제거: [A2])
  //   done cycle(swap/reset 복귀)에는 wdata.ready를 내려 "수락 자체를 막는다" (수락 후 드랍 금지)
  // --------------------------------------------------------------------
  object WrState extends ChiselEnum { val wIdle, wData, wResp = Value }
  val wState    = RegInit(WrState.wIdle)
  val awAddrReg = Reg(UInt(RfParams.addrWidth.W))

  io.axi.awaddr.ready    := wState === WrState.wIdle
  io.axi.wdata.ready     := (wState === WrState.wData) && !doneFire
  io.axi.bresp.valid     := wState === WrState.wResp
  io.axi.bresp.bits.resp := AxiResp.OKAY

  val wIdxW = RegMap.idx(awAddrReg)

  switch(wState) {
    is(WrState.wIdle) {
      when(io.axi.awaddr.fire) {
        awAddrReg := io.axi.awaddr.bits.addr
        wState    := WrState.wData
      }
    }
    is(WrState.wData) {
      when(io.axi.wdata.fire) { wState := WrState.wResp }
    }
    is(WrState.wResp) {
      when(io.axi.bresp.fire) { wState := WrState.wIdle }
    }
  }

  // INT_STATUS(0x10) 쓰기는 config가 아니라 W1C 대상
  val hostWriteFire  = io.axi.wdata.fire && (wIdxW =/= RegMap.INT_STATUS.U)
  val intFlagW1cFire = io.axi.wdata.fire && (wIdxW === RegMap.INT_STATUS.U)

  // --------------------------------------------------------------------
  // config write 합성: word별로 (irq > fetch > host) 우선순위로 write 요청을 만든다  [A4]
  // --------------------------------------------------------------------
  def applyStrobe(cur: UInt, wdata: UInt, wstrb: UInt): UInt = {
    val bytes = (0 until RfParams.strbWidth).map { i =>
      val curByte = cur((i + 1) * 8 - 1, i * 8)
      val newByte = wdata((i + 1) * 8 - 1, i * 8)
      Mux(wstrb(i), newByte, curByte)
    }
    Cat(bytes.reverse)
  }

  // Fetcher beat 매핑(0x20..0x58 / 0x60..0x98 / 0xA0)이 word 인덱스와 일치하는지 elaboration 시점 확인
  RegMap.CHUNK0_IDX.zipWithIndex.foreach { case (r, s) =>
    require(r - TaskDescriptorConfig.BaseRegIdx == 0 * 8 + s, "CHUNK0 mapping mismatch") }
  RegMap.CHUNK1_IDX.zipWithIndex.foreach { case (r, s) =>
    require(r - TaskDescriptorConfig.BaseRegIdx == 1 * 8 + s, "CHUNK1 mapping mismatch") }
  require(RegMap.CHUNK2_IDX.head - TaskDescriptorConfig.BaseRegIdx == 2 * 8, "CHUNK2 mapping mismatch")

  val writeWords = wordsOf(writeConfig)
  val irqIdx     = RegMap.idx(io.irqWaddr)

  val wrEn   = Wire(Vec(NumCfgWords, Bool()))
  val wrData = Wire(Vec(NumCfgWords, UInt(64.W)))

  for (i <- 0 until NumCfgWords) {
    val r = CfgRegIdx(i)
    wrEn(i)   := false.B
    wrData(i) := writeWords(i) // 기본: 현재 쓰기 대상 bank 값 유지

    when(io.irqWen) {                       // [A1] zero-copy 쓰기도 쓰기 대상 bank(실행 중이면 shadow)
      wrEn(i)   := irqIdx === r.U
      wrData(i) := io.irqWdata
    } .elsewhen(io.fetchWen) {
      if (i >= 2) {                         // rs1/rs2는 Fetcher 대상이 아님
        val k     = i - 2                   // descriptor word 번호 0..16
        val chunk = k / 8
        val slot  = k % 8                   // chunk2는 slot0(= data[63:0])만 유효, 나머지 448bit는 무시
        wrEn(i)   := io.fetchChunkIdx === chunk.U
        wrData(i) := io.fetchWdata(64 * (slot + 1) - 1, 64 * slot)
      }
    } .elsewhen(hostWriteFire) {
      wrEn(i)   := wIdxW === r.U
      wrData(i) := applyStrobe(writeWords(i), io.axi.wdata.bits.data, io.axi.wdata.bits.strb)
    }
  }

  // 쓰기 대상 bank에만 적용 (실행 중 = shadow, idle = active bank)
  for (b <- 0 until 2) {
    val isWriteBank = writeBankIndex === b.U
    val words       = wordsOf(configBanks(b))
    for (i <- 0 until NumCfgWords) {
      when(isWriteBank && wrEn(i)) {
        words(i) := wrData(i)
      }
    }
  }

  // --------------------------------------------------------------------
  // 상태 갱신: swap / reset 복귀 / idle 시작 / shadow 완성
  //   instWrite: instruction 내용을 쓰는 write (fetch/host 중 실제 반영된 것). irq 쓰기는 제외 [A1]
  // --------------------------------------------------------------------
  val instWrite  = !io.irqWen && wrEn.asUInt.orR
  val partialNow = loadDirty || instWrite // 쓰기 대상 bank에 미완성/미소비 내용이 있음 [A6]

  when(instWrite) { loadDirty := true.B }

  when(swapFire) {
    activeBankIndex := ~activeBankIndex // selector만 toggle, payload는 copy/clear하지 않는다
    shadowValid     := false.B
    loadDirty       := false.B          // 방금 shadow가 소비됨 (activeValid는 true 유지)
  } .elsewhen(resetFire) {
    activeValid     := false.B
    shadowValid     := false.B
    loadDirty       := partialNow
    // 일부 적재분이 있으면 그 bank(현재 shadow)를 적재 대상으로 채택, 없으면 bank0 복귀
    activeBankIndex := Mux(partialNow, ~activeBankIndex, 0.U)
  } .elsewhen(idleStart) {
    activeValid     := true.B
    loadDirty       := false.B
  } .elsewhen(activeValid && lastBeat) {
    shadowValid     := true.B
  }

  // reset 복귀 시 지우기: 적재분이 없으면 두 bank 모두, 있으면 끝난 옛 active bank만 (last-connect)
  for (b <- 0 until 2) {
    when(resetFire && (!partialNow || activeBankIndex === b.U)) {
      configBanks(b) := 0.U.asTypeOf(new InstructionConfig)
    }
  }

  // --------------------------------------------------------------------
  // 공통 register: INT_FLAG_REG (W1C + HW Set, Set 우선) -- swap과 무관
  // --------------------------------------------------------------------
  val clearMask = Wire(UInt(32.W))
  clearMask := Mux(
    intFlagW1cFire,
    Cat((0 until 4).reverse.map(i =>
      Mux(io.axi.wdata.bits.strb(i), io.axi.wdata.bits.data(8 * i + 7, 8 * i), 0.U(8.W)))),
    0.U(32.W)
  )
  val hwSetMask = Cat(0.U(31.W), io.setIntFlag)
  intFlagReg := (intFlagReg & ~clearMask) | hwSetMask

  // --------------------------------------------------------------------
  // Host AXI4-Lite read: config는 active bank, INT_FLAG/STATUS는 공통  [A3: 미정의 주소는 0]
  // --------------------------------------------------------------------
  val readTable: Seq[(UInt, UInt)] =
    (CfgRegIdx.zip(wordsOf(activeConfig)).map { case (r, v) => r.U -> v }) :+
      (RegMap.INT_STATUS.U -> Cat(statusReg, intFlagReg))

  object RdState extends ChiselEnum { val rIdle, rResp = Value }
  val rState   = RegInit(RdState.rIdle)
  val rDataReg = Reg(UInt(RfParams.dataWidth.W))

  io.axi.araddr.ready    := rState === RdState.rIdle
  io.axi.rdata.valid     := rState === RdState.rResp
  io.axi.rdata.bits.data := rDataReg
  io.axi.rdata.bits.resp := AxiResp.OKAY

  switch(rState) {
    is(RdState.rIdle) {
      when(io.axi.araddr.fire) {
        rDataReg := MuxLookup(RegMap.idx(io.axi.araddr.bits.addr), 0.U(64.W))(readTable)
        rState   := RdState.rResp
      }
    }
    is(RdState.rResp) {
      when(io.axi.rdata.fire) { rState := RdState.rIdle }
    }
  }

  // --------------------------------------------------------------------
  // 출력: 모든 active 출력은 같은 selector를 쓴다 (부분 전환 금지)
  // --------------------------------------------------------------------
  io.activeConfig            := activeConfig
  io.shadowDescriptorAddress := shadowConfig.descriptorAddress
  io.activeBankIndex         := activeBankIndex
  io.instStart               := RegNext(swapFire || idleStart, false.B)
  io.activeValid             := activeValid
  io.shadowValid             := shadowValid

  io.rs1      := activeConfig.commandBits
  io.rs2      := activeConfig.descriptorAddress
  io.pointers := pointersOf(activeConfig.taskDescriptor)
  io.dims     := dimsOf(activeConfig.taskDescriptor)

  io.intFlag := intFlagReg
  io.irq     := intFlagReg.orR

  // --------------------------------------------------------------------
  // 계약 위반 검출 (simulation assertion)
  // --------------------------------------------------------------------
  assert(!(io.instDone && RegNext(io.instDone, false.B)),
    "instDone must be a 1-cycle pulse")
  assert(!(io.instDone && !activeValid),
    "instDone while idle (no running instruction)")
  assert(!(doneFire && io.irqWen),
    "instDone and Interrupt Generator config write must not overlap")
  // swap cycle의 fetch는 "방금 완성되는 마지막 beat"만 허용 (그 외는 다음 instruction이 active를 오염)
  assert(!(swapFire && io.fetchWen && (shadowValid || !lastBeat)),
    "Command Fetcher write during swap is only allowed for the completing last beat")
}

object PingPongConfigRegisterFileMain extends App {
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new PingPongConfigRegisterFile(), Array("--target-dir", "generated"))
}