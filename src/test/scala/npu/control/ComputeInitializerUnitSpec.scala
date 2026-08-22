package npu.control

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ComputeInitializerUnitSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  behavior of "ComputeInitializerUnit"

  def idleInputs(dut: ComputeInitializerUnit): Unit = {
    dut.io.cmdValid.poke(false.B)
    dut.io.rfWriteDone.poke(false.B)
    dut.io.lutReady.poke(false.B)
    dut.io.layerDone.poke(false.B)
  }

  it should "hold initStall=1 and busy=0 in S_IDLE before cmd_valid" in {
    test(new ComputeInitializerUnit) { dut =>
      idleInputs(dut)
      dut.clock.step(3)
      dut.io.initStall.expect(true.B)
      dut.io.busy.expect(false.B)
      dut.io.state.expect(CiuState.sIdle)
    }
  }

  it should "fire soft_reset exactly on the cmd_valid transition cycle, then move to S_STRUCT_LOAD" in {
    test(new ComputeInitializerUnit) { dut =>
      idleInputs(dut)
      dut.io.cmdValid.poke(true.B)
      dut.io.softReset.expect(true.B) // 전이 트리거 되는 그 클럭에 1 (조합 로직)
      dut.io.busy.expect(false.B)     // state는 아직 sIdle (레지스터, 클럭 전이 전)
      dut.clock.step(1)
      dut.io.cmdValid.poke(false.B)

      dut.io.state.expect(CiuState.sStructLoad)
      dut.io.busy.expect(true.B)       // 전이 완료 후에는 busy=1
      dut.io.softReset.expect(false.B) // 1-cycle 이므로 다음 클럭엔 내려감
      dut.io.npuStructLoad.expect(true.B) // S_STRUCT_LOAD 진입 첫 클럭
    }
  }

  it should "wait in S_STRUCT_LOAD until rf_write_done, npu_struct_load only pulses once" in {
    test(new ComputeInitializerUnit) { dut =>
      idleInputs(dut)
      dut.io.cmdValid.poke(true.B)
      dut.clock.step(1)
      dut.io.cmdValid.poke(false.B)

      dut.io.state.expect(CiuState.sStructLoad)
      dut.io.npuStructLoad.expect(true.B)
      dut.clock.step(1)

      // rf_write_done 안 오면 계속 머물고, pulse는 더 이상 안 뜸
      dut.io.state.expect(CiuState.sStructLoad)
      dut.io.npuStructLoad.expect(false.B)
      dut.clock.step(2)
      dut.io.state.expect(CiuState.sStructLoad)

      dut.io.rfWriteDone.poke(true.B)
      dut.clock.step(1)
      dut.io.rfWriteDone.poke(false.B)
      dut.io.state.expect(CiuState.sTagWbLut)
    }
  }

  it should "run S_TAG_WB_LUT and S_START_PRELOAD as unconditional 1-cycle pulses" in {
    test(new ComputeInitializerUnit) { dut =>
      idleInputs(dut)
      dut.io.cmdValid.poke(true.B)
      dut.clock.step(1)
      dut.io.cmdValid.poke(false.B)
      dut.io.rfWriteDone.poke(true.B)
      dut.clock.step(1)
      dut.io.rfWriteDone.poke(false.B)

      dut.io.state.expect(CiuState.sTagWbLut)
      dut.io.lutProgramMode.expect(true.B)
      dut.clock.step(1)

      dut.io.state.expect(CiuState.sStartPreload)
      dut.io.lutProgramMode.expect(false.B)
      dut.io.lutProgramAndPreload.expect(true.B)
      dut.clock.step(1)

      dut.io.state.expect(CiuState.sWaitLutHandoff)
      dut.io.lutProgramAndPreload.expect(false.B)
      dut.io.initStall.expect(true.B) // hand-off 전까지는 계속 stall
    }
  }

  it should "drop initStall exactly on lut_ready hand-off, and reassert on layer_done" in {
    test(new ComputeInitializerUnit) { dut =>
      idleInputs(dut)
      // S_WAIT_LUT_AND_HANDOFF 까지 진행
      dut.io.cmdValid.poke(true.B)
      dut.clock.step(1)
      dut.io.cmdValid.poke(false.B)
      dut.io.rfWriteDone.poke(true.B)
      dut.clock.step(1)
      dut.io.rfWriteDone.poke(false.B)
      dut.clock.step(2) // sTagWbLut -> sStartPreload -> sWaitLutHandoff

      dut.io.state.expect(CiuState.sWaitLutHandoff)
      dut.io.initStall.expect(true.B)

      dut.io.lutReady.poke(true.B)
      dut.clock.step(1)
      dut.io.lutReady.poke(false.B)

      dut.io.state.expect(CiuState.sRunWaitDone)
      dut.io.initStall.expect(false.B) // hand-off 완료, stall 해제
      dut.io.busy.expect(true.B)       // 여전히 busy (compute 진행 중)

      dut.io.layerDone.poke(true.B)
      dut.clock.step(1)
      dut.io.layerDone.poke(false.B)

      dut.io.state.expect(CiuState.sIdle)
      dut.io.initStall.expect(true.B) // 다시 잠금
      dut.io.busy.expect(false.B)
    }
  }
}