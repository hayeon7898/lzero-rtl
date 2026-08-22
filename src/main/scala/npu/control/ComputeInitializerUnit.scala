package npu.control

import chisel3._
import chisel3.util._

/*
 * ============================================================================
 *  Compute Initializer Unit (Main Brain FSM)
 * ============================================================================
 *  "Compute Initializer Unit (Main Brain FSM) 명세서" 기준 구현.
 *
 *  6단계 FSM:
 *  S_IDLE -> S_STRUCT_LOAD -> S_TAG_WB_LUT -> S_START_PRELOAD
 *         -> S_WAIT_LUT_AND_HANDOFF -> S_RUN_WAIT_DONE -> (S_IDLE)
 *
 *  핵심 설계: 제어권 위임(Hand-off). 
 *  S_WAIT_LUT_AND_HANDOFF에서 lut_ready만 확인하고 initStall을 내려버리면, 
 *  이후 실제 연산 시작 타이밍은 OCM들의 impending 신호 -> Stall Generator의 global_stall이 자연스럽게 이어받는다.
 *  즉 이 모듈은 OCM ready 여부를 직접 체크하지 않는다.
 * ============================================================================
 */

object CiuState extends ChiselEnum {
  val sIdle, sStructLoad, sTagWbLut, sStartPreload, sWaitLutHandoff, sRunWaitDone = Value
}

class ComputeInitializerUnit extends Module {
  import CiuState._

  val io = IO(new Bundle {
    // ---- Inputs ----
    val cmdValid   = Input(Bool()) // from RoCC (RS1 Decoder 쪽)
    val rfWriteDone = Input(Bool()) // from DMA to RF Writer
    val lutReady    = Input(Bool()) // from LUT Programmer
    val layerDone   = Input(Bool()) // from Interrupt Generator

    // ---- Outputs ----
    val initStall            = Output(Bool()) // to Stall Generator
    val softReset             = Output(Bool()) // to OCM Controllers, 1-cycle pulse
    val npuStructLoad         = Output(Bool()) // to DMA, 1-cycle pulse
    val lutProgramMode        = Output(Bool()) // to WB Controller, 1-cycle pulse
    val lutProgramAndPreload  = Output(Bool()) // to DMA, 1-cycle pulse

    // busy
    val busy = Output(Bool())

    // 디버깅/관측용 (선택)
    val state = Output(CiuState())
  })

  val state     = RegInit(sIdle)
  val prevState = RegNext(state, sIdle)

  // 어떤 상태에 "진입한 첫 클럭"인지 판별 (1-cycle pulse 생성용)
  def entering(s: CiuState.Type): Bool = (state === s) && (prevState =/= s)

  // --------------------------------------------------------------------
  // 상태 전이
  // --------------------------------------------------------------------
  switch(state) {
    is(sIdle) {
      when(io.cmdValid) { state := sStructLoad }
    }
    is(sStructLoad) {
      when(io.rfWriteDone) { state := sTagWbLut }
    }
    is(sTagWbLut) {
      // 1클럭만 머물고 무조건 다음 상태로 (배리어 없음)
      state := sStartPreload
    }
    is(sStartPreload) {
      // 펄스 발사 직후 바로 대기 상태로
      state := sWaitLutHandoff
    }
    is(sWaitLutHandoff) {
      when(io.lutReady) { state := sRunWaitDone } // Hand-off: initStall 해제되는 순간
    }
    is(sRunWaitDone) {
      when(io.layerDone) { state := sIdle }
    }
  }

  // --------------------------------------------------------------------
  // 출력 로직
  // --------------------------------------------------------------------

  // initStall: S_RUN_WAIT_DONE 에서만 0, 나머지 전 상태에서 1
  io.initStall := state =/= sRunWaitDone

  // soft_reset: S_IDLE에서 cmd_valid로 전이가 트리거되는 그 클럭에 1
  io.softReset := (state === sIdle) && io.cmdValid

  // 각 상태 진입 첫 클럭에만 발사되는 1-cycle 펄스들
  io.npuStructLoad        := entering(sStructLoad)
  io.lutProgramMode        := entering(sTagWbLut)
  io.lutProgramAndPreload  := entering(sStartPreload)

  // busy: S_IDLE이 아닌 모든 상태 (연산 시퀀스 시작~종료까지 유지)
  io.busy := state =/= sIdle

  io.state := state
}