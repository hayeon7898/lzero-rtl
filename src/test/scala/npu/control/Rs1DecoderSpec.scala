package npu.control

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/*
 * hw_enables 파싱만 빠르게 검증하고 싶을 때 쓰는 테스트.
 * sbt> testOnly npu.control.Rs1DecoderSpec
 */
class Rs1DecoderSpec extends AnyFlatSpec with ChiselScalatestTester {

  behavior of "Rs1Decoder"

  it should "correctly parse hw_enables sub-fields from rs1[50:44]" in {
    test(new Rs1Decoder) { dut =>
      // hw_enables = 0b1_01_1_10_1
      //              mxu=1, alu_mode=01(ADD), act_en=1, norm_mode=10, rope_en=1
      val hwEnablesVal = "b1011101".U(7.W).litValue
      val rs1Val = hwEnablesVal << 44 // 나머지 필드는 0으로 둠

      dut.io.rs1.poke(rs1Val.U(64.W))
      dut.clock.step(1)

      dut.io.fields.hwEnables.mxuEn.expect(true.B)
      dut.io.fields.hwEnables.aluMode.expect(1.U)     // ADD
      dut.io.fields.hwEnables.actEn.expect(true.B)
      dut.io.fields.hwEnables.normMode.expect(2.U)    // 10
      dut.io.fields.hwEnables.ropeEn.expect(true.B)
    }
  }

  it should "keep hw_enables all-zero when rs1 is zero" in {
    test(new Rs1Decoder) { dut =>
      dut.io.rs1.poke(0.U(64.W))
      dut.clock.step(1)

      dut.io.fields.hwEnables.mxuEn.expect(false.B)
      dut.io.fields.hwEnables.aluMode.expect(0.U)
      dut.io.fields.hwEnables.actEn.expect(false.B)
      dut.io.fields.hwEnables.normMode.expect(0.U)
      dut.io.fields.hwEnables.ropeEn.expect(false.B)
    }
  }

  it should "not let hw_enables bleed into neighboring fields (lut_write / transpose_en_rd)" in {
    test(new Rs1Decoder) { dut =>
      // hw_enables 전부 1(0x7F)로 채우고 인접 필드는 0인지 확인 -> 비트 경계 검증
      val hwEnablesVal = "b1111111".U(7.W).litValue
      val rs1Val = hwEnablesVal << 44

      dut.io.rs1.poke(rs1Val.U(64.W))
      dut.clock.step(1)

      dut.io.fields.hwEnables.mxuEn.expect(true.B)
      dut.io.fields.hwEnables.ropeEn.expect(true.B)
      // 경계 필드들은 0이어야 함
      dut.io.fields.lutWrite.cos.expect(false.B)       // rs1[51]
      dut.io.fields.transposeEnRd.expect(0.U)          // rs1[43:42]
    }
  }
}