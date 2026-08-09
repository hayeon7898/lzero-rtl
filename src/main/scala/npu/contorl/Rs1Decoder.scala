package npu.control

import chisel3._
import chisel3.util._

/*
 * ============================================================================
 *  Shoot-and-Go ISA - rs1 (64-bit) Decoder
 * ============================================================================
 *
 *  rs1 Bit Allocation Map (MSB -> LSB):
 *
 *   [63:61] reserved        3b
 *   [60:58] cache_enable    3b   ID0
 *   [57]    nb_enable       1b   ID1
 *   [56]    fusion_en       1b   ID2
 *   [55:51] lut_write       5b   ID3
 *   [50:44] hw_enables      7b   ID4   
 *   [43:42] transpose_en_rd 2b   ID5
 *   [41]    transpose_en_wr 1b   ID6
 *   [40:39] tile_strided_rd 2b   ID7
 *   [38]    tile_strided_wr 1b   ID8
 *   [37:36] input_point     2b   ID9
 *   [35:34] out_point       2b   ID10
 *   [33]    vector_compact_in  1b  ID11
 *   [32]    vector_compact_out 1b  ID12
 *   [31:16] valid_row       16b  ID13
 *   [15:0]  constant_operand 16b ID14
 * ============================================================================
 */

// ----------------------------------------------------------------------------
// hw_enables 서브필드 (rs1[50:44], 7bit) 
//
//   [6]   mxu_en    (1b) - TPU 매트릭스 연산기 가동
//   [5:4] alu_mode  (2b) - VPU1 ALU 모드 (00: Bypass, 01: Add, 10: Mul)
//   [3]   act_en    (1b) - VPU1 양자화 + SiLU 활성화
//   [2:1] norm_mode (2b) - VPU2 Norm 모드
//   [0]   rope_en   (1b) - VPU2 RoPE 위치 인코딩 활성화
// ----------------------------------------------------------------------------
class HwEnables extends Bundle {
  val mxuEn   = Bool()
  val aluMode = UInt(2.W)
  val actEn   = Bool()
  val normMode = UInt(2.W)
  val ropeEn  = Bool()
}

object HwEnables {
  // alu_mode 인코딩
  object AluMode {
    val BYPASS = 0.U(2.W)
    val ADD    = 1.U(2.W)
    val MUL    = 2.U(2.W)
    // 3(0b11) : reserved
  }

  // 7bit raw 값(rs1[50:44])을 받아서 HwEnables Bundle로 분해
  def decode(raw: UInt): HwEnables = {
    require(raw.getWidth == 7, s"hw_enables must be 7 bits, got ${raw.getWidth}")
    val w = Wire(new HwEnables)
    w.mxuEn    := raw(6)
    w.aluMode  := raw(5, 4)
    w.actEn    := raw(3)
    w.normMode := raw(2, 1)
    w.ropeEn   := raw(0)
    w
  }
}

// ----------------------------------------------------------------------------
// lut_write 서브필드 (rs1[55:51], 5bit)
//   [4]:Act, [3]:Exp, [2]:Scale, [1]:Sin, [0]:Cos
// ----------------------------------------------------------------------------
class LutWrite extends Bundle {
  val act   = Bool()
  val exp   = Bool()
  val scale = Bool()
  val sin   = Bool()
  val cos   = Bool()
}

object LutWrite {
  def decode(raw: UInt): LutWrite = {
    require(raw.getWidth == 5, s"lut_write must be 5 bits, got ${raw.getWidth}")
    val w = Wire(new LutWrite)
    w.act   := raw(4)
    w.exp   := raw(3)
    w.scale := raw(2)
    w.sin   := raw(1)
    w.cos   := raw(0)
    w
  }
}

// ----------------------------------------------------------------------------
// cache_enable 서브필드 (rs1[60:58], 3bit)
//   [2]:output, [1]:weight, [0]:input   (IB write enable)
// ----------------------------------------------------------------------------
class CacheEnable extends Bundle {
  val output = Bool()
  val weight = Bool()
  val input  = Bool()
}

object CacheEnable {
  def decode(raw: UInt): CacheEnable = {
    require(raw.getWidth == 3, s"cache_enable must be 3 bits, got ${raw.getWidth}")
    val w = Wire(new CacheEnable)
    w.output := raw(2)
    w.weight := raw(1)
    w.input  := raw(0)
    w
  }
}

// ----------------------------------------------------------------------------
// rs1 전체 디코딩 결과
// ----------------------------------------------------------------------------
class Rs1Fields extends Bundle {
  val cacheEnable      = new CacheEnable
  val nbEnable         = Bool()
  val fusionEn         = Bool()
  val lutWrite         = new LutWrite
  val hwEnables        = new HwEnables       
  val transposeEnRd    = UInt(2.W)            // [1]:input, [0]:weight
  val transposeEnWr    = Bool()
  val tileStridedRd    = UInt(2.W)            // [1]:input, [0]:weight
  val tileStridedWr    = Bool()
  val inputPoint       = UInt(2.W)            // 00:mxu, 01:vpu1, 10:vpu2
  val outPoint         = UInt(2.W)            // 00:vpu2, 01:vpu1
  val vectorCompactIn  = Bool()
  val vectorCompactOut = Bool()
  val validRow         = UInt(16.W)           // write mask bitmap
  val constantOperand  = UInt(16.W)
}

object InputPoint {
  val MXU  = 0.U(2.W)
  val VPU1 = 1.U(2.W)
  val VPU2 = 2.U(2.W)
}

object OutPoint {
  val VPU2 = 0.U(2.W)
  val VPU1 = 1.U(2.W)
}

// ----------------------------------------------------------------------------
// Rs1Decoder - rs1(64bit) -> Rs1Fields
// ----------------------------------------------------------------------------
class Rs1Decoder extends Module {
  val io = IO(new Bundle {
    val rs1    = Input(UInt(64.W))
    val fields = Output(new Rs1Fields)
  })

  val rs1 = io.rs1

  io.fields.cacheEnable      := CacheEnable.decode(rs1(60, 58))
  io.fields.nbEnable         := rs1(57)
  io.fields.fusionEn         := rs1(56)
  io.fields.lutWrite         := LutWrite.decode(rs1(55, 51))
  io.fields.hwEnables        := HwEnables.decode(rs1(50, 44))
  io.fields.transposeEnRd    := rs1(43, 42)
  io.fields.transposeEnWr    := rs1(41)
  io.fields.tileStridedRd    := rs1(40, 39)
  io.fields.tileStridedWr    := rs1(38)
  io.fields.inputPoint       := rs1(37, 36)
  io.fields.outPoint         := rs1(35, 34)
  io.fields.vectorCompactIn  := rs1(33)
  io.fields.vectorCompactOut := rs1(32)
  io.fields.validRow         := rs1(31, 16)
  io.fields.constantOperand  := rs1(15, 0)
}

// ----------------------------------------------------------------------------
// 간단 동작 확인용 
// (chiseltest 없이 emitVerilog로 신텍스만 확인하고 싶을 때)
// sbt> runMain npu.control.Rs1DecoderMain
// ----------------------------------------------------------------------------
object Rs1DecoderMain extends App {
  emitVerilog(new Rs1Decoder, Array("--target-dir", "generated"))
}