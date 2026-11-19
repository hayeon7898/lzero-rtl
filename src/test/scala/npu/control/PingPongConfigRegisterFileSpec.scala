package npu.control

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/*
 * PingPongConfigRegisterFile 검증
 * 실행: sbt "testOnly npu.control.PingPongConfigRegisterFileSpec"
 *
 * 검증하지 않는 것:
 *  - instDone 연속 high / idle 중 instDone / done과 irq write 동시 발행 /
 *    swap cycle의 비-마지막 fetch -> RTL assertion이 검출(시뮬레이션 중단)
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
    dut.io.instDone.poke(false.B)
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

  private def rs1Of(tag: Int): BigInt = BigInt(tag * 0x10 + 1)
  private def rs2Of(tag: Int): BigInt = BigInt(tag * 0x1000 + 0x200)
  private def wordsOfTag(tag: Int): Seq[BigInt] = (0 until N).map(descWord(tag * 0x1000, _))

  /** rs1, rs2, descriptor beat 0/1까지만 쓴 "미완성" instruction (마지막 beat 전) */
  private def preloadPartial(dut: PingPongConfigRegisterFile, tag: Int): Unit = {
    axiWrite(dut, 0x00, rs1Of(tag))
    axiWrite(dut, 0x08, rs2Of(tag))
    fetchBeat(dut, 0, beatWords(tag * 0x1000, 0))
    fetchBeat(dut, 1, beatWords(tag * 0x1000, 1))
  }

  /** 완성된 instruction 하나 적재. 반환 직후(마지막 beat의 edge 직후)에 상태가 갱신되어 있다. */
  private def loadInstruction(dut: PingPongConfigRegisterFile, tag: Int): Unit = {
    preloadPartial(dut, tag)
    fetchBeat(dut, 2, beatWords(tag * 0x1000, 2))
  }

  /** instDone 1-cycle pulse. 반환 직후(done의 edge 직후)에 상태가 갱신되어 있다. */
  private def done(dut: PingPongConfigRegisterFile): Unit = {
    dut.io.instDone.poke(true.B)
    dut.clock.step()
    dut.io.instDone.poke(false.B)
  }

  private def activeWordsOf(dut: PingPongConfigRegisterFile): Seq[BigInt] =
    (0 until N).map(k => dut.io.activeConfig.taskDescriptor.words(k).peek().litValue)

  private def expectResetState(dut: PingPongConfigRegisterFile): Unit = {
    dut.io.activeValid.expect(false.B)
    dut.io.shadowValid.expect(false.B)
    dut.io.activeBankIndex.expect(0.U)
    dut.io.rs1.expect(0.U)
    dut.io.rs2.expect(0.U)
    dut.io.shadowDescriptorAddress.expect(0.U)
    assert(activeWordsOf(dut).forall(_ == 0))
  }

  // ------------------------------------------------------------------ tests
  it should "reset 후 active=bank0, idle, 모든 config 0" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      expectResetState(dut)
      dut.io.instStart.expect(false.B)
    }
  }

  it should "idle에서 instruction이 오면 bank0에 들어가고 마지막 beat에서 바로 시작한다 (beat2 dummy 무시)" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      preloadPartial(dut, 0xA)
      dut.io.activeValid.expect(false.B)                 // 마지막 beat 전에는 시작 안 함
      dut.io.instStart.expect(false.B)
      fetchBeat(dut, 2, beatWords(0xA000, 2))
      dut.io.activeValid.expect(true.B)
      dut.io.activeBankIndex.expect(0.U)
      dut.io.instStart.expect(true.B)                    // 시작 pulse, config는 같은 cycle부터 유효
      dut.io.rs1.expect(rs1Of(0xA).U)
      dut.io.rs2.expect(rs2Of(0xA).U)
      val ws = activeWordsOf(dut)
      assert(ws == wordsOfTag(0xA))                      // word16 포함 전부 정확
      assert(!ws.contains(Dummy))                        // beat2 상위 448bit가 다른 word를 덮지 않음
      dut.clock.step()
      dut.io.instStart.expect(false.B)                   // 1-cycle pulse
    }
  }

  it should "실행 중 write는 shadow에만 들어가고 active는 변하지 않는다" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      loadInstruction(dut, 0xA)
      loadInstruction(dut, 0xB)                          // A 실행 중 B preload
      dut.io.activeBankIndex.expect(0.U)
      dut.io.rs1.expect(rs1Of(0xA).U)
      dut.io.rs2.expect(rs2Of(0xA).U)
      assert(activeWordsOf(dut) == wordsOfTag(0xA))
      dut.io.shadowValid.expect(true.B)
      dut.io.shadowDescriptorAddress.expect(rs2Of(0xB).U)
    }
  }

  it should "done + shadow 완성: swap하고 다음 연산을 시작, 모든 출력이 같은 bank를 가리킨다" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      loadInstruction(dut, 0xA)
      loadInstruction(dut, 0xB)
      done(dut)                                          // 마지막 beat 바로 다음 cycle에 done
      dut.io.activeBankIndex.expect(1.U)
      dut.io.activeValid.expect(true.B)
      dut.io.shadowValid.expect(false.B)
      dut.io.instStart.expect(true.B)
      dut.io.rs1.expect(rs1Of(0xB).U)
      dut.io.rs2.expect(rs2Of(0xB).U)
      assert(activeWordsOf(dut) == wordsOfTag(0xB))      // 마지막 word(16)도 누락 없음
      // 기존 pointers/dims 해석 (packing 유지 확인)
      dut.io.pointers.inputAddr.expect(descWord(0xB000, 0).U)
      dut.io.pointers.normBuffAddr.expect(descWord(0xB000, 11).U)
      dut.io.dims.outRowNum.expect(BigInt(0xB000 + 12).U)
      dut.io.dims.outIntermNum.expect(BigInt(0xB000 + 0x100 + 12).U)
      dut.io.dims.outputOffset.expect(BigInt(0xB000 + 16).U)
    }
  }

  it should "done + shadow 비어 있음: reset 상태로 복귀해 대기하고 시작하지 않는다" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      loadInstruction(dut, 0xA)
      done(dut)
      expectResetState(dut)                              // 옛 instruction을 재실행하지 않음 (값도 지워짐)
      dut.io.instStart.expect(false.B)
    }
  }

  it should "bank1에서 끝나고 비어 있어도 bank0으로 복귀하고, 늦게 온 instruction이 bank0에서 시작한다" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      loadInstruction(dut, 0xA)
      loadInstruction(dut, 0xB)
      done(dut)                                          // B active (bank1)
      dut.io.activeBankIndex.expect(1.U)
      dut.clock.step()
      done(dut)                                          // 다음 instruction 없음 -> reset 복귀
      expectResetState(dut)                              // activeBankIndex=0, 두 bank 모두 0

      loadInstruction(dut, 0xC)                          // 한참 뒤에 도착
      dut.io.activeBankIndex.expect(0.U)
      dut.io.activeValid.expect(true.B)
      dut.io.instStart.expect(true.B)
      dut.io.rs1.expect(rs1Of(0xC).U)
      assert(activeWordsOf(dut) == wordsOfTag(0xC))
    }
  }

  it should "마지막 beat가 done과 같은 cycle에 와도 swap되어 시작한다 (마지막에 도착)" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      loadInstruction(dut, 0xA)
      preloadPartial(dut, 0xB)                           // shadow 미완성
      dut.io.shadowValid.expect(false.B)
      dut.io.fetchChunkIdx.poke(2.U)
      dut.io.fetchWdata.poke(pack(beatWords(0xB000, 2)).U(512.W))
      dut.io.fetchWen.poke(true.B)
      dut.io.instDone.poke(true.B)                       // 같은 cycle
      dut.clock.step()
      dut.io.fetchWen.poke(false.B)
      dut.io.instDone.poke(false.B)
      dut.io.activeBankIndex.expect(1.U)
      dut.io.activeValid.expect(true.B)
      dut.io.instStart.expect(true.B)
      dut.io.rs1.expect(rs1Of(0xB).U)
      assert(activeWordsOf(dut) == wordsOfTag(0xB))      // 같은 cycle에 온 word16도 반영
    }
  }

  it should "done 시점에 shadow가 미완성이면 일부 write를 버리지 않고, 나머지 beat가 같은 bank로 이어져 시작한다" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      loadInstruction(dut, 0xA)
      preloadPartial(dut, 0xB)                           // rs1/rs2/beat0/beat1만 도착
      done(dut)                                          // 완성 전에 A 종료 -> idle 복귀
      dut.io.activeValid.expect(false.B)
      dut.io.instStart.expect(false.B)
      dut.io.rs1.expect(rs1Of(0xB).U)                    // 적재 중이던 bank가 이어짐 (일부 write 보존)
      dut.io.shadowDescriptorAddress.expect(0.U)         // 끝난 A의 옛 bank는 지워짐

      fetchBeat(dut, 2, beatWords(0xB000, 2))            // 나머지가 늦게 도착
      dut.io.activeValid.expect(true.B)
      dut.io.instStart.expect(true.B)
      dut.io.rs1.expect(rs1Of(0xB).U)
      dut.io.rs2.expect(rs2Of(0xB).U)
      assert(activeWordsOf(dut) == wordsOfTag(0xB))
    }
  }

  it should "MMIO read는 active bank, write는 shadow bank (실행 중)" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      loadInstruction(dut, 0xA)
      loadInstruction(dut, 0xB)
      assert(axiRead(dut, 0x00) == rs1Of(0xA))           // 아직 B는 shadow에만 있음
      done(dut)
      assert(axiRead(dut, 0x00) == rs1Of(0xB))           // 전환 후 active에서 보임
    }
  }

  it should "Host byte strobe write는 해당 byte만 바꾼다 (idle에서는 bank0에 바로)" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      axiWrite(dut, 0x00, BigInt("1122334455667788", 16))
      axiWrite(dut, 0x00, BigInt(0xAA), strb = 0x01)
      dut.io.rs1.expect(BigInt("11223344556677AA", 16).U)
      dut.io.activeBankIndex.expect(0.U)
    }
  }

  it should "INT_FLAG(W1C)/STATUS는 공통 register이고 done/reset 복귀와 무관하다" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      dut.io.setIntFlag.poke(true.B); dut.clock.step(); dut.io.setIntFlag.poke(false.B)
      dut.io.intFlag.expect(1.U)
      dut.io.irq.expect(true.B)
      loadInstruction(dut, 0xA)
      done(dut)                                          // reset 복귀
      dut.io.intFlag.expect(1.U)                         // clear하지 않음
      dut.io.busy.poke(true.B)
      val v = axiRead(dut, 0x10)                         // [31:0]=INT_FLAG, [63:32]=STATUS
      assert((v & BigInt("FFFFFFFF", 16)) == 1)
      assert(((v >> 32) & 0x3) == 1)                     // busy=1, global_stall=0
      axiWrite(dut, 0x10, BigInt(1), strb = 0x0F)        // W1C
      dut.io.intFlag.expect(0.U)
    }
  }

  it should "done cycle에는 Host write를 수락하지 않고, 다음 cycle에 새 shadow로 들어간다" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      loadInstruction(dut, 0xA)
      loadInstruction(dut, 0xB)                          // shadow 완성 -> done이면 swap
      // AW만 먼저 수락시켜 W 대기 상태로 (rs2 주소)
      dut.io.axi.awaddr.bits.addr.poke(0x08.U)
      dut.io.axi.awaddr.valid.poke(true.B)
      dut.clock.step()
      dut.io.axi.awaddr.valid.poke(false.B)
      dut.io.axi.wdata.bits.data.poke(0x55.U)
      dut.io.axi.wdata.bits.strb.poke(0xFF.U)
      dut.io.axi.wdata.valid.poke(true.B)
      dut.io.axi.wdata.ready.expect(true.B)

      dut.io.instDone.poke(true.B)
      dut.io.axi.wdata.ready.expect(false.B)             // done cycle: 수락 거부 (받고 버리지 않음)
      dut.clock.step()                                   // swap 발생, 쓰기는 일어나지 않음
      dut.io.instDone.poke(false.B)
      dut.io.axi.wdata.ready.expect(true.B)
      dut.clock.step()                                   // 이제 수락 -> 새 shadow(옛 active bank0)
      dut.io.axi.wdata.valid.poke(false.B)
      while (!dut.io.axi.bresp.valid.peek().litToBoolean) dut.clock.step()
      dut.clock.step()

      dut.io.rs1.expect(rs1Of(0xB).U)                    // 현재 active(B)는 그대로
      dut.io.rs2.expect(rs2Of(0xB).U)
      dut.io.shadowDescriptorAddress.expect(0x55.U)      // 새 shadow(bank0)에 들어가 있음
    }
  }

  it should "A 실행 중 B preload, B 실행 중 C preload, 이어서 끝나면 reset 복귀 (bank 번호가 번갈아 바뀜)" in {
    test(new PingPongConfigRegisterFile) { dut =>
      initIO(dut); dut.clock.step()
      loadInstruction(dut, 0xA)                          // idle -> bank0 A 시작
      dut.io.activeBankIndex.expect(0.U)

      loadInstruction(dut, 0xB)                          // A 실행 중 B preload (bank1)
      dut.io.rs1.expect(rs1Of(0xA).U)                    // A 출력 불변
      dut.clock.step()
      done(dut)                                          // B 시작 (bank1)
      dut.io.activeBankIndex.expect(1.U)
      dut.io.rs1.expect(rs1Of(0xB).U)

      loadInstruction(dut, 0xC)                          // B 실행 중 C preload (bank0)
      dut.io.rs1.expect(rs1Of(0xB).U)
      dut.clock.step()
      done(dut)                                          // C 시작 (bank0)
      dut.io.activeBankIndex.expect(0.U)
      assert(activeWordsOf(dut) == wordsOfTag(0xC))

      dut.clock.step()
      done(dut)                                          // 더 없음 -> reset 복귀
      expectResetState(dut)
    }
  }
}