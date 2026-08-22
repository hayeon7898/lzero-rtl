# Edge LLM NPU: Compute Core Complex (ComputeUnit) 명세서

## 1. 아키텍처 개요

`ComputeUnit`은 CISC 기반 통합 연산 코어 콤플렉스. 16x16 타일 GEMM부터 활성화, 정규화, RoPE까지
메모리 왕복 없이 내부에서 처리하는 Stream-Through 파이프라인.

### 핵심 설계 철학

1. **Domain Isolation:** TPU는 Skewed Domain(대각선 흐름), VPU/외부 메모리는 Flat Domain. 변환은
   0-cycle로 하드웨어 버퍼 내부에서 자동 해결.
2. **Zero-Overhead Transpose:** DRAM/IB로 나갈 때 필요한 Transpose를 메인 파이프라인과 비동기화된
   종단 Ping-Pong 버퍼에서 Overlap 처리.
3. **Flexible Routing:** MUX/DEMUX 네트워크로 연산 유닛을 자유 연결, Attention/FFN 등 다양한 레이어를
   단일 HW로 충족.

## 2. 하드웨어 인터페이스 스펙

### Data Ports (128-bit, 16개 8-bit 요소)

**Input:**
- `ub_in`: 입력 Feature Map (X)
- `wb_in`: 가중치 (W)
- `vb_in`: Residual Add 등 추가 벡터
- `nb_in`: LayerNorm Phase1 통계량 또는 RoPE 벡터
- `pb_in` [256-bit]: 양자화/RoPE/LUT 갱신 파라미터 초광대역 버스

**Output:**
- `vb_out`: VPU1 중간 결과
- `nb_out`: LayerNorm 통계량 (Sum, SumSq)
- `compute_out`: 최종 결과 (Transposer 거침 또는 Bypass)

### 메인 연산 서브모듈

1. **TPU_top:** 16x16 MAC 배열, 핑퐁 누산기로 완료 타일을 De-skew하여 VPU로 전달
2. **VPU_Stage1:** `ub_in`/`vb_in`/`wb_in` 또는 TPU 결과 → GPALU(Add/Mul) + 양자화/활성화(QuantAct, SiLU/GELU)
3. **VPU_Stage2:** VPU1 출력이나 `nb_in`/`ub_in` → Normalization(RMS/LayerNorm) + RoPE
4. **PingPongTransposer:** VPU에서 수직으로 떨어지는 데이터를 16사이클 동안 수평 타일로 재배열

## 3. Control Unit (FSM) 요구사항

### Category A: 데이터 라우팅 (Routing Control)

| 신호 | 크기 | 설명 |
| --- | --- | --- |
| `sel_vpu1_op1` | 1b | VPU1 주 연산 대상 [0=ub_in / 1=TPU_out] |
| `sel_vpu1_op2` | 1b | VPU1 부 연산 대상 [0=wb_in / 1=vb_in] |
| `demux_vpu1_out` | 2b | 0:차단 1:vb_out 2:VPU2 3:compute_out 숏컷 |
| `sel_vpu2_in` | 2b | 0:nb_in 1:ub_in 2:VPU1출력 |
| `sel_comp_in` | 1b | [0=VPU2출력 / 1=VPU1출력] |
| `transpose` | 1b | 1=Bypass(1D Vector), 0=Transposer 통과(2D 타일) |

### Category B: TPU (행렬곱) 제어

- `tpu_accum_en`: 입력 타일이 MXU 지나는 동안 누적 지시 (통상 16cycle)
- `tpu_accum_first`: K차원 첫 타일일 때 1 (기존 누적값 리셋)
- `tpu_accum_snap`: K차원 마지막 타일의 마지막 행 진입 시 1cycle 펄스 (Ping→Pong 스냅샷)
- `tpu_accum_stream`: 스냅샷 완료 후 VPU로 16cycle 스트리밍 지시 (TPU는 이미 다음 타일 연산 중)

### Category C: VPU (후처리) 제어

- `vpu1_alu_mode`: [0=Bypass 1=Add 2=Multiply]
- `vpu1_qa_en`: Quantization + SiLU/GELU 활성화
- `vpu2_norm_mode`: [0=Bypass 1=RMSNorm 2=LayerNorm 3=Softmax]
- `vpu2_norm_phase`: 통계량 축적(0) vs 스케일링 적용(1)
- `vpu2_rope_en`: RoPE 연산 적용

### Category D: Parameter & LUT 프로그래밍

- `pb_in`(256-bit)에 데이터를 실은 상태에서 Write Enable 발생 (HW가 32/16-bit로 슬라이싱)
- Wr_En 신호군: `param_wr_en`, `quant_lut_wr_en`, `norm_lut_wr_en`, `rope_cos_wr_en`, `rope_sin_wr_en`
- 주소 핀: `param_addr`(파라미터), `lut_wr_addr`(LUT)
- `param_update`: 모든 로드 완료 후 적용 확정 Pulse

### Category E: 메모리 및 예외 제어

- `comp_stream_en`: DMA/Sequencer가 `compute_out`에서 데이터를 빼갈 때 1
- `stall`: 1이면 내부 Pipeline Register/Accumulator 전체 Freeze
- `trans_ready` (RO): Transposer Ping/Pong 중 하나 이상 비어 신규 데이터 수용 가능
- `lut_ready` (RO): 모든 LUT 초기화 완료
- `fatal_alert` (RO): Accumulator/Transposer 오버플로우 — FSM은 즉시 `stall` + 호스트 IRQ

## 4. 매크로 오퍼레이션 예시: FFN 레이어 (TPU MatMul + VPU1 SiLU + Transpose 출력)

1. **초기 세팅 (Cycle 0):** `sel_vpu1_op1=1`(TPU), `sel_vpu1_op2=0`, `demux_vpu1_out=3`(VPU1→Compute_Out),
   `sel_comp_in=1`, `transpose=0`(통과), `vpu1_alu_mode=0`, `vpu1_qa_en=1`
2. **타일 연산 시작 (Cycle 1~16):** `tpu_accum_en=1`, `tpu_accum_first=1`
3. **스냅샷 캡처 (Cycle 16):** `tpu_accum_snap=1` (1cycle pulse)
4. **VPU 스트리밍 & Transposer 적재 (Cycle 17~32):** `tpu_accum_stream=1`, TPU→VPU1(SiLU)→Transposer Ping
5. **DRAM/IB 배출 (Cycle 33~48):** `trans_ready` 확인 후 `comp_stream_en=1`, Transposer가 Row 방향으로
   `compute_out` 출력

---

**담당:** 타 팀원 (참고용)
