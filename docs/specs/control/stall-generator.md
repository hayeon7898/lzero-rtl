# Stall Generator (Global Freeze Controller) 명세서

## 1. 개요 및 역할

칩 내부에 분산된 다양한 OCM(UB, WB, NB, PB) 컨트롤러와 DMA Write 큐에서 발생하는 "위험 신호
(`impending`)"들을 실시간으로 취합하는 중앙 안전망. 데이터 고갈 8클럭 전(Underflow 임박)이나 큐가
꽉 차기 직전(Overflow 임박)을 감지해 칩 전체 연산 파이프라인(TPU, VPU, Accumulator)을 일시 정지
(Freeze)시키는 `global_stall` 신호를 분배한다.

## 2. 입출력 포트

| Port | 방향 | 크기 | 연결 대상 | 설명 |
| --- | --- | --- | --- | --- |
| `ub_impending` | Input | 1 | UB Ctrl | Input 데이터 고갈 임박 |
| `wb_impending` | Input | 1 | WB Ctrl | Weight 데이터 고갈 임박 |
| `pb_impending` | Input | 1 | PB Ctrl | Parameter 데이터 고갈 임박 |
| `nb_impending` | Input | 1 | NB Ctrl | Normalizer 데이터 고갈(Phase 2) 또는 방출 정체 |
| `dma_w_impending` | Input | 1 | Write DMA | DMA Write FIFO 80% 포화 |
| `global_stall` | Output | 1 | Compute Unit | 전체 연산 유닛 Clock Enable 차단 신호 |

## 3. 내부 로직

### Stall 취합 (OR Tree)

```verilog
wire raw_stall = ub_impending | wb_impending | pb_impending | nb_impending | dma_w_impending;
```

### 타이밍 격리 (Timing Isolation)

`global_stall`은 칩 전역 수만 개의 FF로 뿌려지는 초대형 Fan-out 신호. 조합 논리를 그대로 뿌리면
Timing Violation 발생 → **1-Cycle D-FF로 반드시 파이프라이닝**.

```scala
global_stall := RegNext(raw_stall, false.B)
```

OCM Controller들이 데이터 고갈 '0'이 아닌 '8클럭 전(Watermark=8)'에 `impending`을 미리 발생시키므로,
이 신호가 칩 전역에 도달하는 데 1~2클럭이 걸려도 파이프라인에 쓰레기 데이터가 들어가지 않음.

### Register Duplication (Optional)

칩 크기가 커질 경우 `max_fanout` 제약을 걸어 합성 단계에서 자동 레지스터 복제.

---

**담당:** 타 팀원 (참고용)

**연동 확인 사항:** RF의 `STATUS_REG[1] = global_stall` 소스로 확정 사용 (`RegisterFile.scala`)
