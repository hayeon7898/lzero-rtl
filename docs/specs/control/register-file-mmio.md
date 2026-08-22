# NPU Register File & MMIO Interface 명세서

## 1. Register File (RF) Address Map

RV64 대역폭을 100% 활용하기 위해 32-bit unsigned 변수 2개를 팩킹하여 64-bit 레지스터 한 줄에 할당.

### Group 0: System Control & Status

| 주소 오프셋 | 레지스터 명 | 크기 | 권한 (Host / HW) | 역할 |
| --- | --- | --- | --- | --- |
| 0x00 | `RS1_REG` | 64-bit | R/W / RO | CPU가 쏜 rs1 |
| 0x08 | `RS2_REG` | 64-bit | R/W / RO | npu_ctrl 포인터, Command Fetcher가 긁어올 시작 주소 |
| 0x10 | `INT_FLAG_REG` | 32-bit | W1C / Set | 완료 시 1 Set (irq_pin 직결), Host가 1 쓰면 Clear |
| 0x14 | `STATUS_REG` | 32-bit | RO / Write | working 상태, global_stall 등 디버깅용 |

### Group 1: DMA Auto-Populated Region

CPU가 `RS2_REG` 세팅 시 Command Fetcher(DMA)가 메인 메모리에서 `npu_ctrl` 136Byte를 긁어와 자동으로
덮어쓰는 영역. Host CPU는 디버깅 목적으로 읽기(RO)만 가능.

#### Pointers (64-bit x 12 = 96 Bytes)

| 주소 | 멤버명 |
| --- | --- |
| 0x20 | `INPUT_ADDR` |
| 0x28 | `WEIGHT1_ADDR` |
| 0x30 | `WEIGHT2_ADDR` |
| 0x38 | `QUANT_PARAM_ADDR` |
| 0x40 | `ANGLE_PARAM_ADDR` |
| 0x48 | `ACT_LUT_ADDR` |
| 0x50 | `EXP_LUT_ADDR` |
| 0x58 | `SCALE_LUT_ADDR` |
| 0x60 | `ROPE_SIN_ADDR` |
| 0x68 | `ROPE_COS_ADDR` |
| 0x70 | `OUTPUT_ADDR` |
| 0x78 | `NORM_BUFF_ADDR` |

#### Dimensions & Offsets (32-bit x 9 → 64-bit x 5 packed)

| 주소 | [31:0] | [63:32] |
| --- | --- | --- |
| 0x80 | `OUT_ROW_NUM` | `OUT_INTERM_NUM` |
| 0x88 | `OUT_COL_NUM` | `IN_TOTAL_TILES` |
| 0x90 | `WT_TOTAL_TILES` | `OUT_TOTAL_TILES` |
| 0x98 | `INPUT_OFFSET` | `WEIGHT_OFFSET` |
| 0xA0 | `OUTPUT_OFFSET` | RESERVED |

## 2. MMIO Interface 명세

- **Protocol:** AXI4-Lite (Slave)
- **Data Width:** 64-bit
- **Address Width:** 12-bit (4KB)

### 하드웨어 요구사항

1. **Dual Write-Port Architecture:** 0x20~0xA0 영역은 Host(AXI-Lite)와 Command Fetcher(DMA)
   양쪽에서 쓸 수 있어야 함. MUX로 구성.
2. **Execution Lock:** `busy==1`일 때 Host의 0x20~0xA0 쓰기는 하드웨어에서 무시(Drop), SLVERR 또는
   무시(OKAY) 응답.
3. **W1C (Write-1-to-Clear):** `INT_FLAG_REG`는 1을 기록한 비트만 클리어. 로직: `next = int_flag & ~wdata`.
4. **Zero-Latency Broadcast:** 모든 레지스터 출력은 추가 D-FF 없이 직접 하위 모듈로 배선.

---

**구현 파일:** `src/main/scala/npu/control/RegisterFile.scala`

**통합 확인 사항 (2026-08 기준):**
- Interrupt Generator의 zero-copy 쓰기 대상: **0x70 (OUTPUT_ADDR)** 확정
- DMA to RF Writer의 실제 쓰기 인터페이스는 512-bit beat(`rf_chunk_idx` 0/1/2) 기반
  (본 문서의 "Dual Write-Port"는 단일 레지스터 단위가 아니라 beat 단위 동시 쓰기로 구현됨)
