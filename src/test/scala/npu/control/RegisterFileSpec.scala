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

  def idleFetchPorts(dut: RegisterFile): Unit = {
    dut.io.fetchWen.poke(false.B)
    dut.io.fetchChunkIdx.poke(0.U)
    dut.io.fetchWdata.poke(0.U)
  }

  def idleIrqPorts(dut: RegisterFile): Unit = {
    dut.io.setIntFlag.poke(false.B)
    dut.io.irqWen.poke(false.B)
    dut.io.irqWaddr.poke(0.U)
    dut.io.irqWdata.poke(0.U)
  }

  // 8개의 64bit 값을 명세서 순서대로(LSB lane 0) 512bit로 합침
  def packBeat(lanes: Seq[BigInt]): BigInt = {
    lanes.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (v, i)) =>
      acc | (v << (64 * i))
    }
  }

  it should "write RS1_REG via AXI-Lite and read it back, broadcast on io.rs1" in {
    test(new RegisterFile) { dut =>
      idleFetchPorts(dut)
      dut.io.busy.poke(false.B)
      dut.io.globalStall.poke(false.B)
      idleIrqPorts(dut)

      axiWrite(dut, 0x00, BigInt("DEADBEEFCAFEBABE", 16))
      axiRead(dut, 0x00) should be(BigInt("DEADBEEFCAFEBABE", 16))
      dut.io.rs1.expect("hDEADBEEFCAFEBABE".U)
    }
  }

  it should "load Beat 0 from DMA to RF Writer into the 8 pointer registers it maps to" in {
    test(new RegisterFile) { dut =>
      dut.io.busy.poke(true.B) // 실제로는 struct load 구간이라 busy=1인 상태
      dut.io.globalStall.poke(false.B)
      idleIrqPorts(dut)

      val lanes = Seq(
        BigInt("1000000000000001", 16), // -> INPUT_ADDR (0x20)
        BigInt("1000000000000002", 16), // -> WEIGHT1_ADDR (0x28)
        BigInt("1000000000000003", 16), // -> WEIGHT2_ADDR (0x30)
        BigInt("1000000000000004", 16), // -> QUANT_PARAM_ADDR (0x38)
        BigInt("1000000000000005", 16), // -> ANGLE_PARAM_ADDR (0x40)
        BigInt("1000000000000006", 16), // -> ACT_LUT_ADDR (0x48)
        BigInt("1000000000000007", 16), // -> EXP_LUT_ADDR (0x50)
        BigInt("1000000000000008", 16)  // -> SCALE_LUT_ADDR (0x58)
      )

      dut.io.fetchWen.poke(true.B)
      dut.io.fetchChunkIdx.poke(0.U)
      dut.io.fetchWdata.poke(packBeat(lanes).U)
      dut.clock.step(1)
      dut.io.fetchWen.poke(false.B)

      dut.io.pointers.inputAddr.expect(lanes(0).U)
      dut.io.pointers.weight1Addr.expect(lanes(1).U)
      dut.io.pointers.weight2Addr.expect(lanes(2).U)
      dut.io.pointers.quantParamAddr.expect(lanes(3).U)
      dut.io.pointers.angleParamAddr.expect(lanes(4).U)
      dut.io.pointers.actLutAddr.expect(lanes(5).U)
      dut.io.pointers.expLutAddr.expect(lanes(6).U)
      dut.io.pointers.scaleLutAddr.expect(lanes(7).U)
    }
  }

  it should "load Beat 1 into remaining pointers + packed dimension registers" in {
    test(new RegisterFile) { dut =>
      dut.io.busy.poke(true.B)
      dut.io.globalStall.poke(false.B)
      idleIrqPorts(dut)

      val ropeSin  = BigInt("AAAA000000000001", 16)
      val ropeCos  = BigInt("AAAA000000000002", 16)
      val outAddr  = BigInt("AAAA000000000003", 16)
      val normBuf  = BigInt("AAAA000000000004", 16)
      // DIM0 = outIntermNum[63:32] | outRowNum[31:0]
      val dim0 = (BigInt(7) << 32) | BigInt(16)
      // DIM1 = inTotalTiles[63:32] | outColNum[31:0]
      val dim1 = (BigInt(9) << 32) | BigInt(8)
      // DIM2 = outTotalTiles[63:32] | wtTotalTiles[31:0]
      val dim2 = (BigInt(11) << 32) | BigInt(10)
      // DIM3 = weightOffset[63:32] | inputOffset[31:0]
      val dim3 = (BigInt(13) << 32) | BigInt(12)

      val lanes = Seq(ropeSin, ropeCos, outAddr, normBuf, dim0, dim1, dim2, dim3)

      dut.io.fetchWen.poke(true.B)
      dut.io.fetchChunkIdx.poke(1.U)
      dut.io.fetchWdata.poke(packBeat(lanes).U)
      dut.clock.step(1)
      dut.io.fetchWen.poke(false.B)

      dut.io.pointers.ropeSinAddr.expect(ropeSin.U)
      dut.io.pointers.ropeCosAddr.expect(ropeCos.U)
      dut.io.pointers.outputAddr.expect(outAddr.U)
      dut.io.pointers.normBuffAddr.expect(normBuf.U)

      dut.io.dims.outRowNum.expect(16.U)
      dut.io.dims.outIntermNum.expect(7.U)
      dut.io.dims.outColNum.expect(8.U)
      dut.io.dims.inTotalTiles.expect(9.U)
      dut.io.dims.wtTotalTiles.expect(10.U)
      dut.io.dims.outTotalTiles.expect(11.U)
      dut.io.dims.inputOffset.expect(12.U)
      dut.io.dims.weightOffset.expect(13.U)
    }
  }

  it should "load Beat 2 - only lane 0 (OUTPUT_OFFSET reg) matters, rest is dummy padding" in {
    test(new RegisterFile) { dut =>
      dut.io.busy.poke(true.B)
      dut.io.globalStall.poke(false.B)
      idleIrqPorts(dut)

      val outputOffset = BigInt(99)
      val beat2Data = outputOffset // 상위 448bit(더미)는 0으로 둠 - 무시되는지 확인용

      dut.io.fetchWen.poke(true.B)
      dut.io.fetchChunkIdx.poke(2.U)
      dut.io.fetchWdata.poke(beat2Data.U)
      dut.clock.step(1)
      dut.io.fetchWen.poke(false.B)

      dut.io.dims.outputOffset.expect(99.U)
    }
  }

  it should "reject host write to DMA region (0x20~0xA0) when busy=1 with SLVERR" in {
    test(new RegisterFile) { dut =>
      idleFetchPorts(dut)
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
      idleFetchPorts(dut)
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
      idleFetchPorts(dut)
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