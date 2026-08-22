# Edge LLM NPU: Shoot-and-Go Instruction Set Architecture (ISA) 명세서

## 1. 아키텍처 및 제어 철학 (Overview)

본 NPU는 "Shoot and Go (Task Descriptor 기반 자율 주행)" 철학을 따릅니다. 호스트 CPU(RISC-V)는 매 타일마다
NPU를 간섭하지 않습니다. CPU는 DRAM에 작업 명세서(`npu_ctrl` struct)를 올려두고, 단 한 번의 RoCC 커스텀
명령어(`rs1`, `rs2`)를 발행하여 NPU 내부의 DMA와 Sequencer를 깨웁니다.

### 핵심 하드웨어 메커니즘

- **Input Dual Ping-Pong Transposer:** TPU의 Input(X)/Weight(W) 입력단에 각각 Transposer가 탑재되어,
  0-cycle 패널티로 행렬을 전치.
- **Accumulator De-skewing:** TPU 출력이 Accumulator Ping-Pong 버퍼를 거치며 자동 De-skew되어,
  VPU가 완벽한 Row-Oriented 데이터로 Online Normalization 수행.
- **Dummy Flush 및 Bitmap Masking (GEMV 대응):** M < 16인 Edge 타일 연산 시, `valid_row` 비트맵에 맞춰
  AXI로 나가는 데이터에 Write Mask 생성.
- **Vector Compaction:** `vector_compact_mode`로 Zero Padder 가동, Compact Vector를 Tiled Layout 행렬과
  연산 가능한 형태로 변환.

## 2. RoCC Instruction 포맷 (rs1 64-bit)

### rs1 Bit Allocation Map

| bitfield | [63:61] | [60:58] | [57] | [56] | [55:51] | [50:44] | [43:42] | [41] | [40:39] | [38] | [37:36] | [35:34] | [33] | [32] | [31:16] | [15:0] |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| bit | 3b | 3b | 1b | 1b | 5b | 7b | 2b | 1b | 2b | 1b | 2b | 2b | 1b | 1b | 16b | 16b |
| ID | reserved | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 |

| ID | Field Name | Size | Description |
| --- | --- | --- | --- |
| 0 | cache_enable | 3b | IB write enable [0]:input [1]:weight [2]:output |
| 1 | nb_enable | 1b | Normalizer phase1 output NB write enable (0: DRAM write, 1: NB write) |
| 2 | fusion_en | 1b | TPU → VPU1 → VPU2 End-to-End 라우팅 |
| 3 | lut_write | 5b | LUT/Param write [4]:Act [3]:Exp [2]:Scale [1]:Sin [0]:Cos |
| 4 | hw_enables | 7b | [6]:mxu_en(1b), [5:4]:alu_mode(2b), [3]:act_en(1b), [2:1]:norm_mode(2b), [0]:rope_en(1b) |
| 5 | transpose_en_rd | 2b | [1]:input, [0]:weight transpose 여부 |
| 6 | transpose_en_wr | 1b | Output Transposer 통과 여부 |
| 7 | tile_strided_rd | 2b | [1]:input, [0]:weight stride 적용 |
| 8 | tile_strided_wr | 1b | Write stride 적용 |
| 9 | input_point | 2b | 00:mxu, 01:vpu1, 10:vpu2 |
| 10 | out_point | 2b | 00:vpu2, 01:vpu1 |
| 11 | vector_compact_in | 1b | 0:tiled, 1:compact |
| 12 | vector_compact_out | 1b | 0:tiled, 1:compact |
| 13 | valid_row | 16b | Write Mask용 Bitmap |
| 14 | constant_operand | 16b | 상수배/상수덧셈용 |

> 총 55비트 사용, 상위 비트는 Reserved.

### hw_enables (7 bits, rs1[50:44]) 상세

- `mxu_en` (1b): TPU 매트릭스 연산기 가동
- `alu_mode` (2b): VPU1 ALU 모드 (00: Bypass, 01: Add, 10: Mul)
- `act_en` (1b): VPU1 양자화 + SiLU 활성화
- `norm_mode` (2b): VPU2 Norm 모드
- `rope_en` (1b): VPU2 RoPE 위치 인코딩 활성화

### valid_row (Bitmap Masking)

16x16 TPU/Transposer는 무조건 16사이클을 읽고 씀. 16bit 필드가 유효 Row를 Bitmap으로 표시하며,
DMA 쓰기 시 이 비트맵이 그대로 Write Mask로 변환되어 무효 데이터를 드롭.

### constant_operand (16 bits)

메모리 참조 없이 레지스터로 직접 상수배(Scaling)/상수덧셈(Bias) 처리.

## 3. Task Descriptor 구조체 포맷 (rs2 / 메모리 상주)

`rs2`는 DRAM에 기록된 `npu_ctrl` 구조체의 시작 주소.

```c
typedef struct {
    // 1. Data Pointers
    void* input_addr;
    void* weight1_addr;
    void* weight2_addr;       // VPU1 Residual Add 2nd Operand

    // 2. Parameter Pointers
    void* quant_param_addr;
    void* angle_param_addr;

    // 3. LUT & Parameter Pointers
    void* act_lut_addr;
    void* exp_lut_addr;
    void* scale_lut_addr;
    void* rope_sin_addr;
    void* rope_cos_addr;

    // 4. Output Pointers
    void* output_addr;
    void* norm_buff_addr;

    // 5. Dimensions
    unsigned out_rowNum;      // 1/16 scale
    unsigned out_intermNum;   // 1/16 scale
    unsigned out_colNum;      // 1/16 scale

    // 6. Strided Offset
    unsigned input_offset;    // 1/16 scale
    unsigned weight_offset;   // 1/16 scale
    unsigned output_offset;   // 1/16 scale
} npu_ctrl; // Total Size: 8x13 byte = 104 Bytes
```

### Strided Read/Write Rule

구조체에 명시적 stride 변수를 두지 않음. `rs1`의 `tile_strided_rd`/`wr` 비트가 켜져 있으면, DMA Address
Generator가 `out_rowNum`/`out_colNum`을 바탕으로 오프셋을 내부적으로 계산.

## 4. 하드웨어 타이밍 및 파이프라인 주의사항

- **Input Transposer 지연 은닉:** `transpose_en_rd` 켜지면 DMA 입력이 16사이클 동안 90도 틀어져
  Ping-Pong 버퍼에 저장. FSM은 이전 타일 연산 중 다음 타일을 선제적으로 Prefetch해야 함.
- **LayerNorm 2-Phase 자동화:** `norm_mode` 활성 시, Phase 1(통계량 누적) 루프를 완전히 종료한 후
  자동으로 Phase 2(스케일링 적용) 루프를 시작하는 Two-pass 스케줄러 필요.
- **Zero Padding (Vector Compaction):** `vector_compact_mode=1`이면 Compact Vector 스트림 사이에
  하드웨어적으로 Zero 패딩하여 16x16 TPU 배열에 맞춤.

---

**구현 파일:** `src/main/scala/npu/control/Rs1Decoder.scala`
