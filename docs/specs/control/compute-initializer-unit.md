# Compute Initializer Unit (Main Brain FSM) 명세서

## 1. 개요 및 역할

Register File(RF)에 기록된 설정값을 바탕으로 전체 NPU 파이프라인의 실행 시퀀스를 오케스트레이션하는
최상위 마스터 컨트롤러. 명령어 패킷 로딩, LUT 프로그래밍, 데이터 프리로드, 최종 연산 시작(Shoot)까지의
타이밍을 명시적 배리어(Barrier) 기반 FSM으로 통제.

## 2. 입출력 인터페이스

### Input Signals

| Port | 연결 대상 | 설명 |
| --- | --- | --- |
| `cmd_valid` | RoCC | CPU 실행 명령 트리거 |
| `rf_write_done` | DMA to RF Writer | 136B `npu_ctrl` 구조체가 RF에 모두 적재됨 (= `fetch_done`, 확인됨) |
| `lut_ready` | LUT Programmer | LUT 프로그래밍 완료 |
| `layer_done` | Interrupt Gen | 전체 연산/DMA 방출 완료 (※ Interrupt Gen 기존 포트명과 불일치, 확인 필요) |

### Output Signals

| Port | 연결 대상 | 설명 |
| --- | --- | --- |
| `initStall` | Stall Generator | 마스터 정지 신호 |
| `soft_reset` | OCM Controllers | FSM/포인터 초기화 1-cycle 펄스 |
| `npu_struct_load` | DMA | 136B 구조체 로드 지시 펄스 |
| `lut_program_mode` | WB Controller | WB 출력을 LUT 라우터로 전환 1-cycle 펄스 |
| `lut_program_and_preload` | DMA | LUT + 프리로드 데이터 페치 시작 펄스 |

## 3. 핵심 초기화 시퀀스 (FSM)

| 상태 | 내용 |
| --- | --- |
| `S_IDLE` | `cmd_valid` 대기, `initStall=1` 유지 |
| `S_STRUCT_LOAD` | `npu_struct_load` 1-cycle 펄스 발사 후 `rf_write_done` 배리어 대기 |
| `S_TAG_WB_LUT` | `lut_program_mode` 1-cycle 펄스, 무조건 다음 상태로 전이 |
| `S_START_PRELOAD` | `lut_program_and_preload` 1-cycle 펄스, 무조건 다음 상태로 전이 |
| `S_WAIT_LUT_AND_HANDOFF` | `lut_ready` 배리어 대기, 충족 시 `initStall` 해제 |
| `S_RUN_WAIT_DONE` | `layer_done` 수신까지 대기, 수신 시 `S_IDLE`로 복귀 + `initStall` 재잠금 |

### 핵심 설계: 제어권 위임 (Hand-off)

`S_WAIT_LUT_AND_HANDOFF`에서 `lut_ready`만 확인하고 `initStall`을 해제하면, 이후 실제 연산 시작
타이밍은 각 OCM Controller가 스스로 발산하는 `impending` 신호가 Stall Generator를 거쳐
`global_stall`로 자연스럽게 이어받는다. Initializer는 OCM들의 프리로드 상태를 직접 폴링하지 않는다.

## 4. 하드웨어 아키텍처적 의의

1. **FSM 최소화:** 모든 OCM의 ready를 AND로 묶어 기다릴 필요 없이 `lut_ready` 하나만 확인.
2. **자연스러운 파이프라이닝:** `initStall` 해제 순간 제어권이 Stall Generator로 매끄럽게 이양.
3. **1-Cycle Tagging:** `lut_program_mode` 펄스 하나로 WB가 자율 주행 시작.

---

**구현 파일:** `src/main/scala/npu/control/ComputeInitializerUnit.scala`
**테스트:** `src/test/scala/npu/control/ComputeInitializerUnitSpec.scala`

**통합 확인 사항 (2026-08 기준):**
- `busy` 출력: 명세서엔 없으나 팀 합의로 `state =/= S_IDLE`로 유도하여 추가 (확정)
- `rf_write_done` = `fetch_done` (DMA to RF Writer 문서 기준, 확인됨)
- `fetch_start`는 RS1 Decoder가 직접 쏘는 게 아니라, DMA가 `npu_struct_load`를 받은 뒤
  DRAM에서 첫 데이터가 준비된 순간 DMA/Command Fetcher 내부에서 자체 생성 (확인됨)
- `layer_done` ↔ Interrupt Generator 포트명 불일치 — 미해결
