package npu.control

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/*
 * PingPongConfigRegisterFile 검증 (명세 §11 invariant 중 RF 단독으로 확인 가능한 것)
 * 실행: sbt "testOnly npu.control.PingPongConfigRegisterFileSpec"
 *
 * 검증하지 않는 것:
 *  - swap pulse 연속 high / swap과 fetch·irq write 동시 발행 -> RTL assertion이 검출(시뮬레이션 중단)
 *  - old consumer 상태 등 환경 조건 -> 후속 Central Controller 단계
 */
class PingPongConfigRegisterFileSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "PingPongConfigRegisterFile"

  private val N = TaskDescriptorConfig.NumWords // 17

  // ---------------------------------------------------------------- helpers
  private def initIO(dut: PingPongConfigRegisterFile): Unit = {
    dut.io.axi.awaddr.valid.poke(false.B)
    dut.io.axi.awaddr.bits.addr.poke(0.U)
    dut.io.axi.wdata.valid.poke(false.B)
    dut.io.axi.wdata.bits.data.poke(0.U)
    dut.io.axi.wdata.bits.strb.poke(0.U)
    dut.io.axi.bresp.ready.poke(true.B)
    dut.io.axi.araddr.valid.poke(false.B)
    dut.io.axi.araddr.bits.addr.poke(0.U)
    dut.io.axi.rdata.ready.poke(true.B)
    dut.io.swapConfig.poke(false.B)
    dut.io.fetchWen.poke(false.B)
    dut.io.fetchChunkIdx.poke(0.U)
    dut.io.fetchWdata.poke(0.U)
    dut.io.setIntFlag.poke(false.B)
    dut.io.irqWen.poke(false.B)
    dut.io.irqWaddr.poke(0.U)
    dut.io.irqWdata.poke(0.U)
    dut.io.busy.poke(false.B)
    dut.io.globalStall.poke(false.B)
  }

  /** Host write (AW -> W -> B). 반환 시점에 FSM은 idle. */
  private def axiWrite(dut: PingPongConfigRegisterFile, addr: Int, data: BigInt, strb: Int = 0xFF): Unit = {
    dut.io.axi.awaddr.bits.addr.poke(addr.U)
    dut.io.axi.awaddr.valid.poke(true.B)
    while (!dut.io.axi.awaddr.ready.peek().litToBoolean) dut.clock.step()
    dut.clock.step()
    dut.io.axi.awaddr.valid.poke(false.B)

    dut.io.axi.wdata.bits.data.poke(data.U)
    dut.io.axi.wdata.bits.strb.poke(strb.U)
    dut.io.axi.wdata.valid.poke(true.B)
    while (!dut.io.axi.wdata.ready.peek().litToBoolean) dut.clock.step()
    dut.clock.step()
    dut.io.axi.wdata.valid.poke(false.B)

    while (!dut.io.axi.bresp.valid.peek().litToBoolean) dut.clock.step()
    dut.clock.step()
  }

  private def axiRead(dut: PingPongConfigRegisterFile, addr: Int): BigInt = {
    dut.io.axi.araddr.bits.addr.poke(addr.U)
    dut.io.axi.araddr.valid.poke(true.B)
    while (!dut.io.axi.araddr.ready.peek().litToBoolean) dut.clock.step()
    dut.clock.step()
    dut.io.axi.araddr.valid.poke(false.B)
    while (!dut.io.axi.rdata.valid.peek().litToBoolean) dut.clock.step()
    val v = dut.io.axi.rdata.bits.data.peek().litValue
    dut.clock.step()
    v
  }

  /** descriptor word k를 구분 가능하게: 상위 32bit = base+0x100+k, 하위 32bit = base+k */
  private def descWord(base: Int, k: Int): BigInt =
    (BigInt(base + 0x100 + k) << 32) | BigInt(base + k)

  private val Dummy = BigInt("DEADBEEFDEADBEEF", 16)

  private def pack(words: Seq[BigInt]): BigInt =
    words.zipWithIndex.map { case (w, i) => w << (64 * i) }.foldLeft(BigInt(0))(_ | _)

  private def fetchBeat(dut: PingPongConfigRegisterFile, chunk: Int, words: Seq[BigInt]): Unit = {
    dut.io.fetchChunkIdx.poke(chunk.U)
    dut.io.fetchWdata.poke(pack(words).U(512.W))
    dut.io.fetchWen.poke(true.B)
    dut.clock.step()
    dut.io.fetchWen.poke(false.B)
  }

  private def beatWords(base: Int, chunk: Int): Seq[BigInt] = chunk match {
    case 0 => (0 until 8).map(descWord(base, _))
    case 1 => (8 until 16).map(descWord(base, _))
    case _ => descWord(base, 16) +: Seq.fill(7)(Dummy)   // beat2: word16 + 쓰레기 448bit
  }

  private def fillDescriptor(dut: PingPongConfigRegisterFile, base: Int): Unit =
    (0 until 3).foreach(c => fetchBeat(dut, c, beatWords(base, c)))

  private def loadInstruction(dut: PingPongConfigRegisterFile, tag: Int): Unit = {
    axiWrite(dut, 0x00, BigInt(tag * 0x10 + 1))           // rs1
    axiWrite(dut, 0x08, BigInt(tag * 0x1000 + 0x200))     // rs2
    fillDescriptor(dut, tag * 0x1000)
  }

  private def swap(dut: PingPongConfigRegisterFile): Unit = {
    dut.io.swapConfig.poke(true.B)
    dut.clock.step()
    dut.io.swapConfig.poke(false.B)
  }

  private def activeWordsOf(dut: PingPongConfigRegisterFile): Seq[BigInt] =
    (0 until N).map(k => dut.io.activeConfig.taskDescriptor.words(k).peek().litValue)

  // ------------------------------------------------------------------ tests
  it should "reset 후 active=bank0, 모든 config 0" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      dut.io.activeBankIndex.expect(0.U)
      dut.io.rs1.expect(0.U)
      dut.io.rs2.expect(0.U)
      dut.io.shadowDescriptorAddress.expect(0.U)
      assert(activeWordsOf(dut).forall(_ == 0))
    }
  }

  it should "write는 shadow에만 들어가고 swap 전에는 active가 변하지 않는다 (rs2 preload 포함)" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      loadInstruction(dut, 0xA)
      dut.io.activeBankIndex.expect(0.U)
      dut.io.rs1.expect(0.U)
      dut.io.rs2.expect(0.U)
      assert(activeWordsOf(dut).forall(_ == 0))
      dut.io.shadowDescriptorAddress.expect(BigInt(0xA * 0x1000 + 0x200).U)
    }
  }

  it should "swap 후 rs1/rs2/descriptor 전체와 기존 이름 출력이 같은 bank를 가리킨다" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      loadInstruction(dut, 0xA)
      swap(dut)
      dut.io.activeBankIndex.expect(1.U)
      dut.io.rs1.expect(BigInt(0xA * 0x10 + 1).U)
      dut.io.rs2.expect(BigInt(0xA * 0x1000 + 0x200).U)
      assert(activeWordsOf(dut) == (0 until N).map(descWord(0xA * 0x1000, _)))
      // 기존 pointers/dims 해석 (packing 유지 확인)
      dut.io.pointers.inputAddr.expect(descWord(0xA000, 0).U)
      dut.io.pointers.normBuffAddr.expect(descWord(0xA000, 11).U)
      dut.io.dims.outRowNum.expect(BigInt(0xA000 + 12).U)
      dut.io.dims.outIntermNum.expect(BigInt(0xA000 + 0x100 + 12).U)
      dut.io.dims.outputOffset.expect(BigInt(0xA000 + 16).U)
    }
  }

  it should "swap은 selector만 toggle하고 payload를 copy/clear하지 않는다 (두 번 swap하면 원복)" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      loadInstruction(dut, 0xA)
      swap(dut)                       // active=bank1(A)
      swap(dut)                       // active=bank0(초기 0), A는 shadow에 그대로 남아야 함
      dut.io.activeBankIndex.expect(0.U)
      dut.io.rs1.expect(0.U)
      dut.io.shadowDescriptorAddress.expect(BigInt(0xA * 0x1000 + 0x200).U)
      swap(dut)                       // 다시 A
      dut.io.rs1.expect(BigInt(0xA * 0x10 + 1).U)
      assert(activeWordsOf(dut) == (0 until N).map(descWord(0xA * 0x1000, _)))
    }
  }

  it should "마지막 descriptor beat 바로 다음 cycle에 swap해도 마지막 word가 누락되지 않는다" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      fetchBeat(dut, 0, beatWords(0xA000, 0))
      fetchBeat(dut, 1, beatWords(0xA000, 1))
      // beat2 write (T) -> 다음 cycle swap (T+1)
      dut.io.fetchChunkIdx.poke(2.U)
      dut.io.fetchWdata.poke(pack(beatWords(0xA000, 2)).U(512.W))
      dut.io.fetchWen.poke(true.B)
      dut.clock.step()
      dut.io.fetchWen.poke(false.B)
      dut.io.swapConfig.poke(true.B)
      dut.clock.step()
      dut.io.swapConfig.poke(false.B)
      dut.io.activeConfig.taskDescriptor.words(16).expect(descWord(0xA000, 16).U)
    }
  }

  it should "beat2의 dummy 448bit가 다른 word를 덮어쓰지 않는다" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      fillDescriptor(dut, 0xA000)      // beat2 상위 448bit는 DEADBEEF...
      swap(dut)
      val ws = activeWordsOf(dut)
      assert(ws == (0 until N).map(descWord(0xA000, _)))
      assert(!ws.contains(Dummy))
    }
  }

  it should "MMIO read는 active bank, write는 shadow bank (write 직후 readback은 active 값)" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      axiWrite(dut, 0x00, BigInt(0xA1))
      assert(axiRead(dut, 0x00) == 0)          // 아직 shadow에만 있음
      swap(dut)
      assert(axiRead(dut, 0x00) == 0xA1)       // swap 후 active에서 보임
    }
  }

  it should "Host byte strobe write는 shadow의 해당 byte만 바꾼다" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      axiWrite(dut, 0x00, BigInt("1122334455667788", 16))
      axiWrite(dut, 0x00, BigInt(0xAA), strb = 0x01)
      swap(dut)
      dut.io.rs1.expect(BigInt("11223344556677AA", 16).U)
    }
  }

  it should "INT_FLAG(W1C)/STATUS는 공통 register이고 swap과 무관하다" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      dut.io.setIntFlag.poke(true.B); dut.clock.step(); dut.io.setIntFlag.poke(false.B)
      dut.io.intFlag.expect(1.U)
      dut.io.irq.expect(true.B)
      swap(dut)
      dut.io.intFlag.expect(1.U)               // swap이 clear하지 않음
      dut.io.busy.poke(true.B)
      val v = axiRead(dut, 0x10)               // [31:0]=INT_FLAG, [63:32]=STATUS
      assert((v & BigInt("FFFFFFFF", 16)) == 1)
      assert(((v >> 32) & 0x3) == 1)           // busy=1, global_stall=0
      axiWrite(dut, 0x10, BigInt(1), strb = 0x0F)   // W1C
      dut.io.intFlag.expect(0.U)
    }
  }

  it should "swap cycle에는 Host write를 수락하지 않고, 다음 cycle에 새 shadow로 들어간다" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      // AW만 먼저 수락시켜 W 대기 상태로
      dut.io.axi.awaddr.bits.addr.poke(0x00.U)
      dut.io.axi.awaddr.valid.poke(true.B)
      dut.clock.step()
      dut.io.axi.awaddr.valid.poke(false.B)
      dut.io.axi.wdata.bits.data.poke(0x55.U)
      dut.io.axi.wdata.bits.strb.poke(0xFF.U)
      dut.io.axi.wdata.valid.poke(true.B)
      dut.io.axi.wdata.ready.expect(true.B)

      dut.io.swapConfig.poke(true.B)
      dut.io.axi.wdata.ready.expect(false.B)   // swap cycle: 수락 거부 (받고 버리지 않음)
      dut.clock.step()                         // swap 발생, 쓰기는 일어나지 않음
      dut.io.swapConfig.poke(false.B)
      dut.io.axi.wdata.ready.expect(true.B)
      dut.clock.step()                         // 이제 수락 -> 새 shadow(옛 active bank0)
      dut.io.axi.wdata.valid.poke(false.B)
      while (!dut.io.axi.bresp.valid.peek().litToBoolean) dut.clock.step()
      dut.clock.step()

      dut.io.rs1.expect(0.U)                   // 현재 active(bank1)는 그대로
      swap(dut)
      dut.io.rs1.expect(0x55.U)                // bank0에 들어가 있었음
    }
  }

  it should "A 실행 중 B preload, swap 후 B 실행, 이어서 C preload (RF는 누락 field를 지워주지 않는다)" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      loadInstruction(dut, 0xA); swap(dut)                     // A active
      dut.io.activeBankIndex.expect(1.U)
      dut.io.rs1.expect(BigInt(0xA * 0x10 + 1).U)

      loadInstruction(dut, 0xB)                                // A 실행 중 B preload
      dut.io.rs1.expect(BigInt(0xA * 0x10 + 1).U)              // A 출력 불변
      dut.io.shadowDescriptorAddress.expect(BigInt(0xB * 0x1000 + 0x200).U)
      swap(dut)                                                // B active
      dut.io.activeBankIndex.expect(0.U)
      dut.io.rs1.expect(BigInt(0xB * 0x10 + 1).U)
      assert(activeWordsOf(dut) == (0 until N).map(descWord(0xB * 0x1000, _)))

      axiWrite(dut, 0x00, BigInt(0xC1))                        // C는 rs1만 채움 (writer 책임 위반 예시)
      swap(dut)                                                // C active
      dut.io.rs1.expect(0xC1.U)
      dut.io.rs2.expect(BigInt(0xA * 0x1000 + 0x200).U)        // 옛 A의 값이 그대로 남아있음
    }
  }
}
