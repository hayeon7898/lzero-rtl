# OCM (On-Chip Memory) Controller 명세서 (통합본)

> 아래 4개의 개별 Notion 문서를 하나로 병합함: "OCM (On-Chip Memory) Controller 설계 명세서"(updated '26.08.15),
> "OCM Controller Architecture 명세서", "Edge LLM NPU: OCM 아키텍처 및 제어 명세서 (Final Spec)",
> 그리고 아키텍트 제공 Chisel 레퍼런스 코드. 원문 병합 과정에서 내용을 요약/수정하지 않았으며,
> 섹션 구성만 재배치함.

---

## 1. 아키텍처 철학

**Elastic Pipeline** 지향: 중앙 제어(Control Unit)의 FSM 복잡도를 낮추고 병목(Backpressure)을 하드웨어
버퍼링과 비동기 제어로 흡수.

- **Decoupled 제어:**
  - 데이터 변형(Padding, Compaction)은 OCM이 관여하지 않음 — Compute Unit(CU) 내부로 캡슐화
  - Write(Data-driven, Push): CU/DMA에서 `valid`와 함께 들어오면 OCM이 자체적으로 주소 증가시키며 기록
  - Read(Control-driven, Pull): 중앙 Control Unit 타이머가 허락할 때만 방출
- **Stall-Awareness:** `stall` 신호 발생 시 중앙 제어기 타이머(Accum Counter)가 즉시 Freeze,
  파이프라인 데이터 정합성 100% 보장
- **내부는 통일(Uniformity):** 모든 버퍼의 상태 머신과 Stall 방어 기제(15/16 Watermark)는 동일 RTL 공유
- **외부는 이종(Heterogeneity):** 시작점은 중앙 제어(Push), 중간/끝점은 데이터 흐름 기반 자기 주도적
  요청(Pull & In-band Tag)

## 2. 글로벌 버스 및 대역폭 규격

- NPU 동작 클럭: 100 MHz
- TileLink(DMA) 버스 폭: 64 Byte/cycle (512-bit)
- 연산기(CU) 내부 데이터 폭: 16 Byte/cycle (128-bit)
- 최대 요구 대역폭(DRAM): 12.8 GB/s (DDR4-2400 물리 대역폭 19.2GB/s의 약 66% 점유)
- 백프레셔 억제: DMA Write Port 64B/c (데이터 생성 속도 최대 32B/c 대비 2배 배출 속도)

## 3. 공통 I/O 인터페이스

### Input

| Port | 연결 대상 | 설명 |
| --- | --- | --- |
| `soft_reset` | Compute Initializer | 새 명령어 실행 전 FSM/핑퐁 포인터 초기화, 1-cycle 펄스 |
| `enable` | Compute Initializer | 현재 레이어에서 해당 OCM 사용 여부 |
| `shoot` | Compute Initializer | Pre-loading 종료 후 연산 시작 트리거 |
| `dma_data_valid` / `dma_data_last` | DMA Read | 데이터 유입 / 텐서 종료 (Prefetch 상태 차단) |
| `dma_write_ready` | DMA Write | DMA가 OCM 데이터를 DRAM으로 방출(Spill)받을 준비 완료 |

### Output

| Port | 연결 대상 | 로직 | 설명 |
| --- | --- | --- | --- |
| `ready` | Compute Initializer | `(ping_bank_full) \|\| (enable==0)` | Pre-loading 완료, Barrier 동기화용 |
| `hungry` | DMA Arbiter | - | "다음 뱅크 비었으니 4KB Fetch해라" |
| `impending` | Stall Generator | - | "데이터 고갈 직전, Global Stall 준비" |

## 4. 버퍼별 스펙 및 하드웨어 매핑

| 버퍼명 | 크기 | 뱅크 구조 | Write (In) | Read (Out) | 하드웨어 매핑 (KV260) |
| --- | --- | --- | --- | --- | --- |
| WB (Weight) | 8 KB | Ping-Pong (4KBx2) | 64B/c (DMA) | 16B/c (CU) | BRAM 16개 |
| UB (Unified) | 8 KB | Ping-Pong (4KBx2) | 64B/c (DMA) | 16B/c (CU) | BRAM 16개 |
| VB (VPU) | 4 KB | Single Bank | 16B/c (CU) | 16B/c (CU) | BRAM 2개 |
| PB (Parameter) | 1 KB | Single Bank | 64B/c (DMA) | 16B/c (CU) | LUTRAM/Reg (깊이 16) |
| NB (Normalizer) | 128 KB | Ping-Pong (64KBx2) | 64B/c (CU/IB) | 16B/c (CU) | URAM 16개 |
| IB (Intermediate) | 1,152 KB | Multi-Bank | 64B/c (DMA) | 64B/c (DMA) | URAM 32개 |

## 5. OCM별 특화 Configuration

### UB (Unified Buffer) & FB (Frequency Buffer) 공통

- `compact_mode`: 1이면 16cycle마다 1번 Read Pointer 증가(UB) 또는 16 Lane 동일 데이터 공유(FB).
  GEMV 모드 시 Address Generator가 16클럭당 주소 1씩 증가, DMA Ping-Pong 스왑/Request도 자동 16배 지연.

### WB (Weight Buffer)

- `lut_prog_mode`: 1이면 출력 데이터 라우팅을 MXU 대신 VPU/Norm LUT 초기화 포트로 연결

### NB (Normalizer Buffer)

- `spill_to_dram`: 1(DRAM 방출) / 0(Zero-DRAM, Ping 뱅크 고정 재사용)
- `phase2_req`: Normalizer Scale 계산 완료 시 Phase 2 방출 트리거 펄스
- 가변 버스트: 4KB 고정인 타 버퍼와 달리 `burst_length`(16×colNum)를 Control Unit으로부터 Config
  받아 Ping-Pong 길이 동적 조절

### PB (Parameter Buffer)

- `req_quant` / `req_angle`: Quant/Angle 파라미터 요구 신호
- 듀얼 트래킹: `addr_quant`, `addr_angle` 독립 Read Pointer
- `quant_done && angle_done` 시 뱅크 Flip
- Zero-Latency Load: 1KB(16칸) LUTRAM, DMA가 64B씩 16번 쏘면 즉시 꽉 참

### VPU Buffer

- `fusion_req_in`: TPU 파이프라인 In-band 태그, 1이 되면 1cycle 뒤 VPU로 출력 (데이터 주도형, `shoot` 비의존)

## 6. 내부 상태 머신 (FSM States)

| 상태 | 설명 |
| --- | --- |
| `S_IDLE` | `enable=0` 또는 리셋 직후 |
| `S_PRELOAD` | `enable=1` 진입, 첫 `hungry` 전송, Ping 뱅크 채움 대기 → `S_READY` |
| `S_READY` | `shoot` 펄스 대기 (Barrier) |
| `S_WORKING` | 데이터 활발히 소비/수신 |
| `S_STALL` | 언더플로우, `valid=0` |

## 7. 도메인별 제어 패러다임 (Heterogeneous Control)

파이프라인 위치에 따라 데이터 방출 트리거 방식을 완전히 분리.

### Front-end: UB & WB (Push 패러다임)

**Shoot 기반 트리거:** Control Unit이 초기 어드레스 세팅 후 `shoot` 1클럭 펄스 → Stall 올 때까지
무조건 데이터를 Compute Unit으로 밀어냄.

### Mid-end: NB (Pull 패러다임)

**Data-driven Request 트리거:** `shoot` 미사용. Phase 1 마지막에 Normalizer가 Scale Factor를 완성하는
즉시 `phase2_req` 신호를 직접 NB에 보냄. 첫 4KB Pinned로 Zero-Stall Transition.

### Back-end: VPU Buffer (In-band Pull 패러다임)

**In-band Fusion Flag:** TPU 데이터 스트림 옆에 `fusion_req`(1-bit) 태그를 붙여 전달. 이 플래그가
TPU 마지막 출력단에 도달하면 VPU Buffer의 `read_en`을 직접 트리거. 내부 카운터 없는 극단적
'Dumb Memory' 설계 (1-Cycle Lookahead Sync).

## 8. 핵심 신호 추출 로직 (Signal Generation)

> 아래는 "설계 명세서"(§4) 기준 상세 로직. "Architecture 명세서"에는 이보다 단순화된 공통 버전
> (`hungry = working AND pong_valid==0`, `deadline_impending = working AND active_bank_cnt<=8 AND
> pong_valid==0`)이 별도로 존재 — 초기 버전으로 추정, 최신 버전인 아래 로직을 기준으로 삼는 것을 권장.

### hungry (To DMA Arbiter)

- 공통: `dma_data_last` 수신 시 `last_data_received=1`, 이후 `hungry` 영구 차단
- UB/WB/FB: `(S_WORKING || S_PRELOAD) && pong_empty && !last_data_received`
- PB (AND 조건): `(quant_done && angle_done) && pong_empty && !last_data_received`
- Write OCM (NB_Spill, IB): `S_WORKING && ping_full`

### impending (To Stall Generator)

- Read OCM 정적 워터마크 (WB, NB_Phase2): `S_WORKING && (active_bank_cnt<=8) && pong_empty && !last_data_received`
- Read OCM 동적 워터마크 (UB, FB): 위와 동일하되 Threshold가 `compact_mode`에 따라 상대적 소모량 기준
- PB (OR 듀얼 조건): `((quant_left<=8) || (angle_left<=8)) && pong_empty && !last_data_received`
- Write OCM (NB_Spill, IB): `S_WORKING && (active_bank_filled>=80%) && pong_full`

### working

- `S_WORKING` 여부, Host `STATUS_REG`에 매핑됨

## 9. 제어 유닛과 DMA의 역할

### Central Control Unit

- Configuration: 타일 연산 전 `gemv_mode`, `burst_length` 등 환경 변수 설정
- Read Sequencer: `Accum Counter` 기반 `read_en` 펄스
- Stall 대응: `stall` 시 `Accum Counter` 정지
- DMA 제어 지시: Strided Write용 Stride Offset 레지스터 세팅 (KV Cache 레이아웃 지원)

### 멀티포트 DMA (Arbiter)

- Round-Robin 중재: OCM들의 `req` 신호를 공평하게 받아 TileLink 버스트 전송
- Strided Write 처리: 중앙 제어기 설정에 따라 주소를 건너뛰며(Scatter) 쓰기 (CU는 16B 패킹 출력만 유지)

## 10. 시스템 리소스 현황

Rocket Chip 싱글 코어(L1 32KB/32KB) 체제, 확보한 라우팅 자원을 대용량 IB(1.12MB)에 투자.

- Total BRAM(36Kb) 예상 소모: 약 92/144개 (63%) — OCM 34개, Rocket Core 24개, Bus/DMA/AsyncQueue 34개
- Total URAM(288Kb) 예상 소모: 48/64개 (75%) — NB 16개, IB 32개

> Architect's Note: BRAM 60%대, URAM 75% 점유율은 Xilinx FPGA 라우터가 배선 혼잡 없이 100MHz 타이밍을
> 맞출 수 있는 '골디락스 존'.

## 11. 레퍼런스 구현 (Chisel, 아키텍트 제공)

### OCM_ReadAddrGen (스마트 타이머)

```scala
package npu.memory

import chisel3._
import chisel3.util._

class OCM_ReadAddrGen(val numLines: Int = 16, val addrBits: Int = 8) extends Module {
  val io = IO(new Bundle {
    val read_en   = Input(Bool())
    val gemv_mode = Input(Bool()) // 1: 16-cycle당 1주소 증가 (UB 전용)
    val max_addr  = Input(UInt(addrBits.W)) // 타일당 읽을 최대 주소 (일반 255)

    val addr_out  = Output(UInt(addrBits.W))
    val valid_out = Output(Bool())
    val done      = Output(Bool()) // Ping-Pong 스왑용 펄스
  })

  val addr_reg = RegInit(0.U(addrBits.W))
  val hold_cnt = RegInit(0.U(log2Ceil(numLines).W))

  io.addr_out  := addr_reg
  io.valid_out := io.read_en

  val is_last_addr = (addr_reg === io.max_addr)
  val is_last_hold = (hold_cnt === (numLines - 1).U)

  io.done := io.read_en && is_last_addr && (!io.gemv_mode || is_last_hold)

  when(io.read_en) {
    when(io.gemv_mode) {
      hold_cnt := hold_cnt + 1.U
      when(is_last_hold) {
        addr_reg := Mux(is_last_addr, 0.U, addr_reg + 1.U)
      }
    } .otherwise {
      addr_reg := Mux(is_last_addr, 0.U, addr_reg + 1.U)
    }
  }
}
```

### OCM_WriteAddrGen (Valid 기반)

```scala
class OCM_WriteAddrGen(val addrBits: Int = 8) extends Module {
  val io = IO(new Bundle {
    val write_valid = Input(Bool())
    val max_addr    = Input(UInt(addrBits.W))

    val addr_out    = Output(UInt(addrBits.W))
    val done        = Output(Bool())
  })

  val addr_reg = RegInit(0.U(addrBits.W))

  io.addr_out := addr_reg
  val is_last = (addr_reg === io.max_addr)
  io.done := io.write_valid && is_last

  when(io.write_valid) {
    addr_reg := Mux(is_last, 0.U, addr_reg + 1.U)
  }
}
```

### OCM_Controller (Ping-Pong Buffer Top)

```scala
class OCM_Controller(
  val dataWidth: Int = 128,
  val depth: Int = 256, // 256 * 16B = 4KB per bank
  val isDMA_Writer: Boolean = true
) extends Module {
  val addrBits = log2Ceil(depth)

  val io = IO(new Bundle {
    val gemv_mode  = Input(Bool())
    val burst_len  = Input(UInt(addrBits.W))

    val wr_data    = Input(UInt(dataWidth.W))
    val wr_valid   = Input(Bool())
    val wr_ready   = Output(Bool())

    val rd_en      = Input(Bool())
    val rd_data    = Output(UInt(dataWidth.W))
    val rd_valid   = Output(Bool())
  })

  val bank_ping = SyncReadMem(depth, UInt(dataWidth.W))
  val bank_pong = SyncReadMem(depth, UInt(dataWidth.W))

  val write_to_pong = RegInit(false.B)
  val read_from_pong = RegInit(false.B)

  val rd_gen = Module(new OCM_ReadAddrGen(16, addrBits))
  rd_gen.io.read_en   := io.rd_en
  rd_gen.io.gemv_mode := io.gemv_mode
  rd_gen.io.max_addr  := io.burst_len

  val wr_gen = Module(new OCM_WriteAddrGen(addrBits))
  wr_gen.io.write_valid := io.wr_valid
  wr_gen.io.max_addr    := io.burst_len

  when(rd_gen.io.done) { read_from_pong := !read_from_pong }
  when(wr_gen.io.done) { write_to_pong := !write_to_pong }

  val wr_addr = wr_gen.io.addr_out
  when(io.wr_valid) {
    when(!write_to_pong) { bank_ping.write(wr_addr, io.wr_data) }
    .otherwise           { bank_pong.write(wr_addr, io.wr_data) }
  }

  val rd_addr = rd_gen.io.addr_out
  val ping_rd_data = bank_ping.read(rd_addr, io.rd_en && !read_from_pong)
  val pong_rd_data = bank_pong.read(rd_addr, io.rd_en && read_from_pong)

  io.rd_data  := Mux(read_from_pong, pong_rd_data, ping_rd_data)
  io.rd_valid := rd_gen.io.valid_out

  // Write쪽 버퍼가 꽉 차지 않았다면 쓸 수 있음
  // 단순화: "Write 사이드와 Read 사이드가 같지 않으면 쓸 공간이 있음"
  val is_full = (write_to_pong =/= read_from_pong)
  io.wr_ready := !is_full
}
```

---

**담당:** 타 팀원 (참고용)

**연동 확인 사항 (2026-08 기준):**
- `soft_reset` 입력 포트는 최신 업데이트본(§3)에서 명시적으로 추가됨 — Compute Initializer Unit의
  `soft_reset` 출력과 매칭 확인됨
- §8의 두 가지 `hungry`/`impending` 로직(상세 버전 vs Architecture 문서의 단순 버전)이 같은 명세인지
  버전 차이인지는 미확인 — 통합 시 팀 확인 필요