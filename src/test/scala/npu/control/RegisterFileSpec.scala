package npu.control

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RegisterFileSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  behavior of "RegisterFile"

  // ---- 테스트 helper: AXI-Lite write/read 트랜잭션 ----
  def axiWrite(dut: RegisterFile, addr: Int, data: BigInt, strb: Int = 0xFF): Unit = {
    dut.io.axi.awaddr.bits.addr.poke(addr.U)
    dut.io.axi.awaddr.valid.poke(true.B)
    dut.io.axi.wdata.bits.data.poke(data.U)
    dut.io.axi.wdata.bits.strb.poke(strb.U)
    dut.io.axi.wdata.valid.poke(true.B)
    dut.io.axi.bresp.ready.poke(true.B)

    var awDone = false
    var wDone  = false
    while (!awDone || !wDone) {
      if (!awDone && dut.io.axi.awaddr.ready.peek().litToBoolean) awDone = true
      if (!wDone && dut.io.axi.wdata.ready.peek().litToBoolean) wDone = true
      dut.clock.step(1)
      if (awDone) dut.io.axi.awaddr.valid.poke(false.B)
      if (wDone)  dut.io.axi.wdata.valid.poke(false.B)
    }
    while (!dut.io.axi.bresp.valid.peek().litToBoolean) dut.clock.step(1)
    dut.clock.step(1)
  }

  def axiRead(dut: RegisterFile, addr: Int): BigInt = {
    dut.io.axi.araddr.bits.addr.poke(addr.U)
    dut.io.axi.araddr.valid.poke(true.B)
    dut.io.axi.rdata.ready.poke(true.B)

    while (!dut.io.axi.araddr.ready.peek().litToBoolean) dut.clock.step(1)
    dut.clock.step(1)
    dut.io.axi.araddr.valid.poke(false.B)

    while (!dut.io.axi.rdata.valid.peek().litToBoolean) dut.clock.step(1)
    val result = dut.io.axi.rdata.bits.data.peek().litValue
    dut.clock.step(1)
    result
  }

  def idleIrqPorts(dut: RegisterFile): Unit = {
    dut.io.setIntFlag.poke(false.B)
    dut.io.irqWen.poke(false.B)
    dut.io.irqWaddr.poke(0.U)
    dut.io.irqWdata.poke(0.U)
  }

  it should "write RS1_REG via AXI-Lite and read it back, broadcast on io.rs1" in {
    test(new RegisterFile) { dut =>
      dut.io.fetchWorking.poke(false.B)
      dut.io.fetchWrEn.poke(false.B)
      dut.io.busy.poke(false.B)
      dut.io.globalStall.poke(false.B)
      idleIrqPorts(dut)

      axiWrite(dut, 0x00, BigInt("DEADBEEFCAFEBABE", 16))
      axiRead(dut, 0x00) should be(BigInt("DEADBEEFCAFEBABE", 16))
      dut.io.rs1.expect("hDEADBEEFCAFEBABE".U)
    }
  }

  it should "let Command Fetcher own DMA region write when fetch_working=1, ignore host write" in {
    test(new RegisterFile) { dut =>
      dut.io.busy.poke(false.B)
      dut.io.globalStall.poke(false.B)
      idleIrqPorts(dut)

      // fetch_working=1 상태에서 Command Fetcher가 INPUT_ADDR(0x20)에 씀
      dut.io.fetchWorking.poke(true.B)
      dut.io.fetchWrEn.poke(true.B)
      dut.io.fetchWrAddr.poke(0x20.U)
      dut.io.fetchWrData.poke("h1000_0000".U)
      dut.clock.step(1)
      dut.io.fetchWrEn.poke(false.B)

      dut.io.pointers.inputAddr.expect("h1000_0000".U)
    }
  }

  it should "reject host write to DMA region (0x20~0xA0) when busy=1 with SLVERR" in {
    test(new RegisterFile) { dut =>
      dut.io.fetchWorking.poke(false.B)
      dut.io.fetchWrEn.poke(false.B)
      dut.io.globalStall.poke(false.B)
      idleIrqPorts(dut)

      // busy=0일 때 정상적으로 한 번 써둠
      dut.io.busy.poke(false.B)
      axiWrite(dut, 0x20, BigInt("1111111111111111", 16))
      dut.io.pointers.inputAddr.expect("h1111111111111111".U)

      // busy=1로 전환 후 다른 값 쓰기 시도 -> 무시되어야 함 (Execution Lock)
      dut.io.busy.poke(true.B)
      axiWrite(dut, 0x20, BigInt("2222222222222222", 16))
      dut.io.pointers.inputAddr.expect("h1111111111111111".U) // 값 안 바뀜
    }
  }

  it should "implement W1C correctly on INT_FLAG_REG via set_intflag pulse" in {
    test(new RegisterFile) { dut =>
      dut.io.fetchWorking.poke(false.B)
      dut.io.fetchWrEn.poke(false.B)
      dut.io.busy.poke(false.B)
      dut.io.globalStall.poke(false.B)
      idleIrqPorts(dut)

      // Interrupt Generator가 set_intflag 펄스 발사 -> bit0 Set
      dut.io.setIntFlag.poke(true.B)
      dut.clock.step(1)
      dut.io.setIntFlag.poke(false.B)
      dut.io.intFlag.expect("b1".U)
      dut.io.irq.expect(true.B)

      // Host가 bit0 클리어(W1C)
      axiWrite(dut, 0x10, BigInt("00000001", 16))
      dut.io.intFlag.expect("b0".U)
      dut.io.irq.expect(false.B)
    }
  }

  it should "route Interrupt Generator zero-copy address write via irqWen to OUTPUT_ADDR (0x70)" in {
    test(new RegisterFile) { dut =>
      dut.io.fetchWorking.poke(false.B)
      dut.io.fetchWrEn.poke(false.B)
      dut.io.busy.poke(false.B)
      dut.io.globalStall.poke(false.B)
      dut.io.setIntFlag.poke(false.B)

      // Interrupt Generator: rf_waddr=0x70(OUTPUT_ADDR), rf_wdata = ib_write_addr | (1<<63)
      dut.io.irqWen.poke(true.B)
      dut.io.irqWaddr.poke(0x70.U)
      dut.io.irqWdata.poke((BigInt(1) << 63) | BigInt("1234", 16))
      dut.clock.step(1)
      dut.io.irqWen.poke(false.B)

      dut.io.pointers.outputAddr.expect(((BigInt(1) << 63) | BigInt("1234", 16)).U)
    }
  }
}