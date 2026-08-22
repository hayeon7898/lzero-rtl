# DMA Arbiter (Smart Scheduler) 명세서

## 1. 개요 및 역할

NPU 내부의 이기종 온칩 메모리(UB, WB, PB, NB)들이 DRAM과 통신하기 위해 보내는 요청을 취합하고,
한정된 AXI/TileLink 버스 대역폭을 최적 효율로 분배하는 중앙 우선순위 중재기. 복잡한 수학 연산 없이
워터마크 플래그(`impending`, `starving`, `hungry`)와 라운드 로빈으로 Starvation/Stall을 방어.

## 2. 입출력 포트

| Port | 방향 | 크기 | 연결 대상 | 설명 |
| --- | --- | --- | --- | --- |
| `ub_status` | Input | 3 | UB Ctrl | `[impending, hungry, full]` |
| `wb_status` | Input | 3 | WB Ctrl | `[impending, hungry, full]` |
| `nb_status` | Input | 4 | NB Ctrl | `[impending, starving, hungry, full]` |
| `pb_status` | Input | 3 | PB Ctrl | `[impending, hungry, full]` |
| `axi_ready` | Input | 1 | DMA Engine | 새 버스트 요청 수락 가능 여부 |
| `burst_done` | Input | 1 | DMA Engine | 현재 버스트 전송 완료 |
| `target_ocm` | Output | 2 | DMA Engine | 목적지 OCM ID (0:UB 1:WB 2:NB 3:PB) |
| `trigger` | Output | 1 | DMA Engine | 버스트 요청 시작 펄스 |

> `starving`은 64KB NB 전용. 나머지 OCM은 `hungry`/`impending`만 사용.

## 3. 3-Tier 스케줄링 정책

### Priority 0: 생존 방어 모드 (Urgent)

- 조건: 어떤 OCM이든 `impending==1`
- 동작: 모든 규칙 무시, 최우선 버스 할당
- Tie-breaker: 고정 우선순위 (예: UB > WB > NB)

### Priority 1: 정상 핑퐁 동기화 (Normal)

- 조건: `impending` 없는 상태에서 `hungry==1` 또는 `starving==1`(NB, 16KB 이하)
- Tie-breaker: Round-Robin (`UB → WB → NB → PB → UB...`) — 큰 버퍼의 대역폭 독식 방지

### Priority 2: 백그라운드 프리페치

- 조건: Priority 0/1 요청 없어 버스 유휴 상태, `full==0`
- Tie-breaker: Round-Robin

## 4. 하드웨어 아키텍처적 의의

1. **동적 평형:** 초반엔 큰 NB(64KB)가 Prefetch(Priority 2)로만 채워짐. `starving`(16KB 이하) 발동
   시 Priority 1 합류해 UB/WB와 RR로 공평 분배 → 모든 버퍼가 4KB 단위 핑퐁 주기로 자기동기화.
2. **Zero-Multiplier:** 소모 계수 곱셈 연산기/비교기 제거, 워터마크 비트 OR/AND + RR Arbiter 매크로만
   사용 → Critical Path Delay 0, Fmax 보장.

---

**담당:** 타 팀원 (참고용)
