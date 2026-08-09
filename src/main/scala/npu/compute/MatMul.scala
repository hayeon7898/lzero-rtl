package scalar

import circt.stage.ChiselStage // import first
import chisel3._
import chisel3.util._

/** =========================================================================
  *  [1] MacUnit — 시스톨릭 어레이의 최소 단위 (PE, Processing Element)
  *  ------------------------------------------------------------------------
  *  "Weight Stationary" 방식:
  *    - weight는 set_w 신호가 들어올 때 한 번만 레지스터에 로딩되어 그 자리에 고정됨
  *    - 그 이후로는 activation(in_a)만 매 클럭 옆으로 흘려보내며 재사용
  *    - 이렇게 하면 매 클럭 SRAM에서 weight를 다시 읽어올 필요가 없어져서
  *      메모리 접근(=가장 비싼 연산)을 크게 줄일 수 있음
  *
  *  out_mac = RegNext(in_a * weight + in_c)
  *    → 곱셈+누적 결과를 레지스터에 담아 1클럭 딜레이 후 아래 PE로 전달
  *  out_in  = RegNext(in_a)
  *    → 입력 activation을 1클럭 딜레이 후 오른쪽 PE로 전달
  *  이렇게 두 출력 모두 레지스터를 거치기 때문에, 데이터가 정확히
  *  "한 클럭에 한 칸씩" 이동하는 systolic(맥박) 패턴이 만들어짐
  * ========================================================================= */
class MacUnit extends Module {
  val io = IO(new Bundle {
    val in_a      = Input(UInt(8.W))  // 왼쪽 PE(또는 외부)에서 들어오는 activation
    val in_c      = Input(UInt(16.W)) // 위쪽 PE(또는 외부)에서 들어오는 partial sum (bias 포함 가능)

    val set_w     = Input(Bool())     // 1클럭 동안 켜서 in_w 값을 weight 레지스터에 로딩
    val clear_w   = Input(Bool())     // weight 레지스터를 0으로 초기화
    val in_w      = Input(UInt(8.W))

    val out_in    = Output(UInt(8.W))  // 오른쪽 PE로 전달할 activation
    val out_mac   = Output(UInt(16.W)) // 아래쪽 PE로 전달할 partial sum
  })

  val weight = RegInit(0.U(8.W))

  // clear가 set보다 우선순위 높음 (elsewhen 순서 그대로 회로에 반영됨)
  when (io.clear_w) {
    weight := 0.U
  } .elsewhen (io.set_w) {
    weight := io.in_w
  }

  io.out_mac  := RegNext((io.in_a * weight) + io.in_c)
  io.out_in   := RegNext(io.in_a)
}

/** =========================================================================
  *  [2] MatMulUnit_16 — MacUnit 256개(16x16)를 격자로 배선한 MXU
  *  ------------------------------------------------------------------------
  *  배선 규칙 (systolic array의 핵심):
  *    - 가로 방향: PE(r,c-1).out_in → PE(r,c).in_a   (activation이 오른쪽으로 흐름)
  *      단, c==0인 맨 왼쪽 열은 외부 입력 io.in_A(r)을 직접 받음
  *    - 세로 방향: PE(r-1,c).out_mac → PE(r,c).in_c  (partial sum이 아래로 흐름)
  *      단, r==0인 맨 윗 행은 0에서 시작 (초기 partial sum 없음)
  *    - 맨 아래 행(r=15)의 out_mac이 이 열의 최종 결과 = io.out_MAC
  *
  *  주의: 입력 io.in_A는 그냥 넣으면 안 되고, 행마다 타이밍을 사선으로
  *        밀어서(skew) 넣어야 함 (r+c 클럭 뒤에 투입). 이 skewing은
  *        DataOrchUnit_16이 담당하거나, 테스트에서는 소프트웨어로 미리
  *        skew된 배열을 만들어 클럭마다 순서대로 넣어줌.
  * ========================================================================= */
class MatMulUnit_16 extends Module {
  // input output
  val io = IO(new Bundle{
    val in_A    = Input(Vec(16, UInt(8.W)))

    val set_W   = Input(Bool())
    val clear_W = Input(Bool())
    val in_W    = Input(Vec(16, Vec(16, UInt(8.W)))) // in_W(r)(c) = PE(r,c)에 로딩할 weight

    val out_MAC = Output(Vec(16, UInt(16.W))) // 16개 열의 최종 결과
  })

  // Seq.fill(16,16)(...) 한 줄로 PE 256개를 찍어냄 (Verilog generate for 두 겹과 동일한 효과)
  val macs = Seq.fill(16, 16)(Module(new MacUnit()))

  // weight loading
  for ( r <- 0 until 16 ){
    for ( c <- 0 until 16 ){
      macs(r)(c).io.in_w  := io.in_W(r)(c)
      macs(r)(c).io.set_w := io.set_W
    }
  }

  // weight clearing
  for ( r <- 0 until 16 ){
    for ( c <- 0 until 16 ){
      macs(r)(c).io.clear_w := io.clear_W
    }
  }

  // wiring mac units
  // 여기서 if(c==0), if(r==0)는 하드웨어 조건문(when)이 아니라
  // Scala의 순수 if문 -> 코드를 "찍어낼 때"(elaboration time) 미리 배선을 결정함
  for ( r <- 0 until 16 ){
    for ( c <- 0 until 16 ){
      if (c == 0) { macs(r)(0).io.in_a := io.in_A(r) }
      else { macs(r)(c).io.in_a := macs(r)(c-1).io.out_in }

      if (r == 0) { macs(r)(c).io.in_c  := 0.U }
      else { macs(r)(c).io.in_c := macs(r-1)(c).io.out_mac }
    }
  }

  // wiring MatMulUnit's input output to each of macUnit
  for (c <- 0 until 16) {
    macs(0)(c).io.in_c  := 0.U                     // 맨 윗 행은 partial sum 없이 시작 (bias 필요하면 여기를 대체)
    io.out_MAC(c)       := macs(15)(c).io.out_mac  // 맨 아래 행 출력 = 최종 결과
  }

}

/** =========================================================================
  *  [3] Orch_buffer_16 — 한 행(row)을 위한 skew용 시프트 레지스터
  *  ------------------------------------------------------------------------
  *  load_enable  : 16개 값을 한 번에 병렬로 로딩 (한 행 전체를 채워넣음)
  *  sync_enable  : 매 클럭 한 칸씩 밀면서(shift), 맨 앞 값을 하나씩 순서대로 출력
  *  즉 "16개를 한 번에 담아두고, 필요한 타이밍부터 한 클럭에 하나씩 꺼내는" 큐 역할
  * ========================================================================= */
class Orch_buffer_16 extends Module {
  val io = IO(new Bundle{
    val in                = Input(Vec(16, UInt(8.W)))
    val load_enable       = Input(Bool())
    val sync_enable       = Input(Bool())
    val next_sync_enable  = Output(Bool())
    val out               = Output(UInt(8.W))
  })
  val shift_reg = RegInit(VecInit(Seq.fill(16)(0.U(8.W))))

  when ( io.load_enable ){
    shift_reg := io.in
  } .elsewhen ( io.sync_enable ){
    for ( i <- 0 until 15 ){
      shift_reg(i) := shift_reg(i+1)
    }
    shift_reg(15) := 0.U
  }

  when ( io.sync_enable ) {
    io.out := shift_reg(0)
  } .otherwise {
    io.out := 0.U
  }

  io.next_sync_enable := io.sync_enable
}

/** =========================================================================
  *  [4] DataOrchUnit_16 — 16개 행에 대해 "시작 신호를 한 칸씩 밀어서" 전파
  *  ------------------------------------------------------------------------
  *  feed_reg(0)이 켜지면 다음 클럭에 feed_reg(1)이 켜지고, 그 다음엔 feed_reg(2)...
  *  이런 식으로 각 행의 Orch_buffer_16이 서로 다른 클럭에 sync를 시작하게 되어,
  *  결과적으로 행 r의 데이터가 r클럭만큼 늦게 흘러들어가는 사선(skew) 패턴이 자동 완성됨
  *  → MatMulUnit_16에 넣을 activation을 올바른 타이밍으로 공급하는 역할 (VPU 앞단)
  * ========================================================================= */
class DataOrchUnit_16 extends Module {
  val io = IO(new Bundle{
    val in_mat      = Input(Vec(16, Vec(16, UInt(8.W))))
    val feed_enable = Input(Bool())
    val load_enable = Input(Bool())
    val skew_vec    = Output(Vec(16, UInt(8.W)))
  })

  val d_orch = Seq.fill(16)(Module(new Orch_buffer_16()))
  val feed_reg = RegInit(VecInit(Seq.fill(16)(false.B)))

  for( r<-0 until 16 ){
    d_orch(r).io.sync_enable  := feed_reg(r)
    d_orch(r).io.load_enable  := io.load_enable
    d_orch(r).io.in           := io.in_mat(r)
    io.skew_vec(r)            := d_orch(r).io.out
  }

  feed_reg(0) := io.feed_enable
  for ( i<-0 until 15 ){
    feed_reg(i+1) := feed_reg(i)
  }
}

/** Chisel 6.x 문법에 맞춘 SystemVerilog 추출용 실행 객체 (제출/검증용, 필수는 아님) */
object TPU_Main extends App {
  println("TPU Accelerator SystemVerilog extracting...")

  ChiselStage.emitSystemVerilogFile(new MacUnit(), Array("--target-dir", "generated"))
  ChiselStage.emitSystemVerilogFile(new MatMulUnit_16(), Array("--target-dir", "generated"))
  ChiselStage.emitSystemVerilogFile(new DataOrchUnit_16(), Array("--target-dir", "generated"))

  println("Extract SV Finished! Please check 'generated' directory")
}