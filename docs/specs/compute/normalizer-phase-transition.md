# Normalizer Phase Transition 및 동기화 아키텍처 명세서

## 1. 개요

LLM 추론 중 Normalizer(LayerNorm/RMSNorm)는 통계량 계산(Phase 1)과 정규화 적용(Phase 2)의 2-Pass 연산을
요구. 본 문서는 Phase 1↔2 사이 논리적 단절 구간에서 ① 제어 신호(메타데이터) 복구 메커니즘과
② DRAM Eviction 시 Stall을 0으로 만드는 버퍼링 구조를 정의.

## 2. 메타데이터 FIFO Controller (파라미터 업데이트 신호 복구)

### 문제 정의

Accumulator가 생성한 `param_update` 태그는 데이터가 NB나 DRAM으로 나갈 때 소실될 위험. 메모리에
태그를 함께 저장하면 대역폭 낭비.

### 아키텍처 설계: Compute Unit 내부 Metadata Queue

메모리는 순수 데이터만, 제어 상태는 CU 내부에서 비동기 관리.

- **HW 구성:** CU 내부 VPU2 앞단에 1-bit 폭 `Metadata_FIFO`
- **Push (Phase 1 Exit):** Accumulator가 타일 처리 끝날 때마다 `param_update` 여부를 FIFO에 Push
- **Pop (Phase 2 Enter):** NB로부터 Phase 2 첫 타일이 CU로 재진입(`valid==1` 첫 클럭)할 때 Pop
- **태그 복구:** Pop 값이 1이면 즉시 데이터 스트림에 `param_update=1`을 재부착, VPU2(RoPE 등) 섀도우
  레지스터 업데이트 트리거

### 기대 효과

- 대역폭 보존 (제어 신호 저장에 AXI/DRAM 대역폭 미사용)
- Decoupling (메모리 지연과 무관하게 순서대로 정확히 태그 매핑)

## 3. First 4KB 핀 고정(Pinned) 기반 1-Cycle Transition

### 문제 정의

Sequence Length가 길어져 NB(128KB) 초과 시 DRAM으로 Eviction. Phase 1 종료 직후 Phase 2를 위해
DRAM에서 재로드하면 AXI Read Latency(수십~수백 클럭)만큼 심각한 Stall 발생.

### 아키텍처 설계: First Chunk Muxing & Uniform Ping-Pong

- **Phase 1 (첫 4KB 보존):** 첫 4KB는 NB Ping 뱅크에 기록 후 절대 삭제 안 함 (Pinned). 두 번째
  4KB부터는 Pong 뱅크를 Pass-through하여 AXI Write DMA로 DRAM Spill.
- **Phase Transition:** Phase 1 종료, CU가 Scale(1/sqrt) 계산하는 짧은 Slack 동안 NB Read 포인터를
  Ping 뱅크(주소 0)로 리셋.
- **Phase 2 (Zero-Stall 시작):** 즉시 Ping 뱅크(첫 4KB) 데이터 방출 (지연 0). 소비 시작하며 자연스럽게
  `hungry` 모드 진입 → Pong 뱅크용 다음 4KB DRAM 요청. Ping 4KB 소비되는 256클럭 동안 백그라운드에서
  Pong 채움.

### 기대 효과

- No Prediction Logic: 타일 개수 예측 FSM 분기 로직 제거
- RTL 통일성: NB가 UB/WB와 동일한 Hungry & Ping-Pong 로직 공유
- 대역폭 절약: 첫 4KB에 대한 DRAM Read/Write 생략

---

**담당:** 타 팀원 (참고용)
