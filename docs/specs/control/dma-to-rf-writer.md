# DMA to RF Writer (Command Fetcher) 명세서

## 1. 개요 및 역할

Host CPU가 `RS2_REG`에 구조체 시작 주소를 던져주고 로딩 명령을 내리면, DMA가 DRAM으로부터
512-bit(64 Byte)씩 읽어오는 데이터를 Bit-slicing 한 뒤, RF의 지정된 주소 범위(0x20~0xA0)에 한 번에
8개씩 병렬로 기록하는 명령어 페치 전담 모듈.

## 2. 입출력 포트

| Port | 방향 | 크기 | 연결 대상 | 설명 |
| --- | --- | --- | --- | --- |
| `fetch_start` | Input | 1 | RS1 Decoder(문서 표기; 실제로는 DMA 내부에서 npu_struct_load 이후 첫 데이터 준비 시점에 자체 생성) | 구조체 로딩 시작 1-클럭 펄스 |
| `dma_rdata` | Input | 512 | DMA (AXI R) | DRAM에서 읽어온 64 Byte |
| `dma_rvalid` | Input | 1 | DMA (AXI R) | 데이터 유효 |
| `dma_rlast` | Input | 1 | DMA (AXI R) | 버스트 마지막 데이터 |
| `rf_chunk_idx` | Output | 2 | Register File | 현재 beat 번호 (0,1,2) |
| `rf_wdata` | Output | 512 | Register File | 64 Byte 데이터 (RF가 8등분하여 씀) |
| `rf_wen` | Output | 1 | Register File | 쓰기 활성화 펄스 |
| `fetch_done` | Output | 1 | Compute Init | 136 Byte 구조체 로딩 완료 (= Compute Init의 `rf_write_done`) |

## 3. FSM

- **`S_IDLE`:** `fetch_start` 대기, `chunk_idx=0` 초기화
- **`S_FETCH`:** `dma_rvalid==1`마다 `rf_wdata:=dma_rdata`, `rf_chunk_idx:=chunk_idx`, `rf_wen:=1`,
  다음 클럭에 `chunk_idx+=1`. `dma_rlast==1` 또는 `chunk_idx==2` 도달 시 `S_DONE`.
- **`S_DONE`:** `fetch_done:=1` 펄스, 무조건 다음 클럭 `S_IDLE`.

## 4. 512-bit Data Slicing 및 RF Mapping

### Beat 0 (`chunk_idx==0`)

| 슬라이스 | RF 주소 | 멤버 |
| --- | --- | --- |
| `[63:0]` | 0x20 | `INPUT_ADDR` |
| `[127:64]` | 0x28 | `WEIGHT1_ADDR` |
| `[191:128]` | 0x30 | `WEIGHT2_ADDR` |
| `[255:192]` | 0x38 | `QUANT_PARAM_ADDR` |
| `[319:256]` | 0x40 | `ANGLE_PARAM_ADDR` |
| `[383:320]` | 0x48 | `ACT_LUT_ADDR` |
| `[447:384]` | 0x50 | `EXP_LUT_ADDR` |
| `[511:448]` | 0x58 | `SCALE_LUT_ADDR` |

### Beat 1 (`chunk_idx==1`)

| 슬라이스 | RF 주소 | 멤버 |
| --- | --- | --- |
| `[63:0]` | 0x60 | `ROPE_SIN_ADDR` |
| `[127:64]` | 0x68 | `ROPE_COS_ADDR` |
| `[191:128]` | 0x70 | `OUTPUT_ADDR` |
| `[255:192]` | 0x78 | `NORM_BUFF_ADDR` |
| `[319:256]` | 0x80 | `OUT_INTERM_NUM[63:32]`, `OUT_ROW_NUM[31:0]` |
| `[383:320]` | 0x88 | `IN_TOTAL_TILES[63:32]`, `OUT_COL_NUM[31:0]` |
| `[447:384]` | 0x90 | `OUT_TOTAL_TILES[63:32]`, `WT_TOTAL_TILES[31:0]` |
| `[511:448]` | 0x98 | `WEIGHT_OFFSET[63:32]`, `INPUT_OFFSET[31:0]` |

### Beat 2 (`chunk_idx==2`)

| 슬라이스 | RF 주소 | 멤버 |
| --- | --- | --- |
| `[63:0]` | 0xA0 | `RESERVED[63:32]`, `OUTPUT_OFFSET[31:0]` |
| `[511:64]` | N/A | 136Byte 초과 더미, 하드웨어에서 무시 |

## 5. 아키텍처적 이점

1. **3-Cycle 셋업:** 512-bit Wide Bus 대역폭을 그대로 활용해 3클럭 만에 하드웨어 셋업 완료.
2. **1-Stage Demux 라우팅:** RF는 `rf_chunk_idx` 하나만 보고 8개 D-FF에 동시에 Enable.
3. **No Alignment Issue:** `npu_ctrl`의 메모리 레이아웃과 RTL 비트 슬라이싱 배치가 100% 동일.

---

**담당:** 타 팀원 (참고용)
**연동 구현:** `RegisterFile.scala`의 `fetchWen`/`fetchChunkIdx`/`fetchWdata` 포트, `RegMap.CHUNK0/1/2_IDX`
