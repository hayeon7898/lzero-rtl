# Compute Timer (Local Datapath Sequencer) 명세서

## 1. 개요 및 역할

Central Control Unit의 중앙 타이머를 대체하는 분산형 데이터패스 타이머. Accumulator 및 하위
파이프라인(VPU, Normalizer) 바로 앞단에 위치하며, 데이터가 실제로 유효할 때만(`row0_valid`) 작동.
Stall 발생 시 타이머가 자동으로 멈추는 완벽한 Stall-Free 동기화를 달성하며, 각 모듈에 필요한 상태
태그(`accum_first`, `param_update`, `fusion_change`, `norm_phase_change`)를 In-band로 생성.

## 2. 입출력 포트

| Port | 방향 | 크기 | 연결 대상 | 설명 |
| --- | --- | --- | --- | --- |
| `row0_valid` | Input | 1 | Input SRAM / MXU | Row 0 데이터 유효 (Timer Enable) |
| `intermNum` | Input | 32 | Register File | K차원 누적 타일 수 (1/16 scale) |
| `colNum` | Input | 32 | Register File | 출력 타일 열 수 (1/16 scale) |
| `norm_phase_load` | Input | 32 | Register File | Normalizer Phase 2 전환 주기 로드값 |
| `accum_first` | Output | 1 | Accumulator | 누산기 클리어/덮어쓰기 플래그 |
| `param_update` | Output | 1 | VPU / PB | 파라미터 뱅크/세트 업데이트 플래그 |
| `fusion_change` | Output | 1 | VPU Buffer | VPU가 TPU 출력을 받아들일 타이밍 트리거 |
| `norm_phase_change` | Output | 1 | Normalizer (NB) | Phase 1→2 전환 플래그 |

## 3. 내부 카운터 로직

1개의 Prescaler + 연쇄 트리거되는 4개의 상태 카운터.

### Prescaler (Divide-by-16)

`row0_valid` High일 때마다 15→0 Decrement. 0 도달 시 `tick_16` 1클럭 펄스, 다시 15로 로드.

### Accumulation Timer (Interm Counter)

- Start: 0, Load: `intermNum - 1`
- `tick_16`마다 1 Decrement
- `accum_first`: 카운터==0일 때 지속 출력 (첫 누적 타일)
- `accum_done_event`: 0인 상태에서 `tick_16` 발생 시 1클럭 펄스

### Row Change Counter

- Start: 0, Load: `colNum - 1`
- `accum_done_event`마다 1 Decrement
- `param_update`: 카운터==0일 때 출력

### Fusion Change Counter

- Start: 15, Load: 31 (파이프라인 뎁스 오프셋 튜닝)
- `accum_done_event`마다 1 Decrement
- `fusion_change`: 카운터==0일 때 출력

### Norm Phase Change Counter

- Start: 0, Load: `norm_phase_load` (RF 동적 설정)
- `accum_done_event`마다 1 Decrement
- `norm_phase_change`: 카운터==0일 때 출력

## 4. 하드웨어 아키텍처적 의의

1. **Global Stall 의존성 0%:** `row0_valid`가 안 들어오면 모든 카운터가 자동 정지. 별도 Stall Gating 불필요.
2. **In-band Tagging:** 4개 출력 플래그가 128-bit Data와 함께 패킹되어 하위 유닛으로 전달.
3. **Start Value 튜닝:** Fusion Change Counter를 15로 시작해 파이프라인 물리 지연을 카운터 오프셋으로 해결.

---

**담당:** 타 팀원 (참고용)

**확인된 이슈:** `norm_phase_load`는 RF Address Map에 실제로는 불필요 — Normalizer가 자체 request
기반으로 Phase 전환을 로컬 관리하므로 RF를 통한 전역 공급이 필요 없음 (이전 디자인의 흔적으로 확인됨,
RF에 해당 필드 추가하지 않기로 결론).
