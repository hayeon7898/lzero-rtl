# Interrupt Generator (Event Detector & Zero-Copy Manager) 명세서

## 1. 개요 및 역할

NPU 연산의 "끝"을 감지하는 이벤트 센서. 출력 DMA(Write)의 최종 전송 완료를 감지하여 Register File(RF)에
인터럽트 펄스(`set_intflag`)를 날린다. 또한 방금 연산된 출력 결과가 저장된 칩 내부 Intermediate
Buffer(IB) 주소를 RF에 기록하여 메모리 복사 없는 레이어 전환(Zero-Copy Pipelining)을 완성한다.

## 2. 입출력 포트

| Port | 방향 | 크기 | 연결 대상 | 설명 |
| --- | --- | --- | --- | --- |
| `dma_write_last` | Input | 1 | Output DMA | 마지막 타일 전송 완료 |
| `ib_write_addr` | Input | 64 | Output DMA | 출력이 IB로 향했을 경우의 최상단 주소 |
| `set_intflag` | Output | 1 | Register File | `INT_FLAG_REG` Set용 1-클럭 펄스 |
| `rf_waddr` | Output | 8 | Register File | Zero-copy 주소 반환용 (**0x70 = OUTPUT_ADDR, 확정**) |
| `rf_wdata` | Output | 64 | Register File | 다음 레이어 `INPUT_ADDR`로 재활용될 결과물 주소 |
| `rf_wen` | Output | 1 | Register File | RF 쓰기 활성화 펄스 |

## 3. 내부 로직

### Interrupt Pulse Generation

`dma_write_last`의 Rising Edge를 감지, 그 1클럭 동안 `set_intflag:=1`. 이후 RF가 직접 `irq_pin`을
올리고 호스트의 W1C를 대기.

### Zero-Copy Address Feedback

`set_intflag==1`인 동일 클럭에:
- `rf_wen := 1`
- `rf_waddr := 0x70` (`OUTPUT_ADDR`)
- `rf_wdata := ib_write_addr | (1ULL << 63)`

**MSB(63번 비트) Flagging:** 결과를 내부 IB(SRAM)에 썼을 경우 MSB를 1로 마스킹. Host CPU는 인터럽트
직후 이 값을 그대로 다음 레이어의 `INPUT_ADDR`에 넣으면, DMA Arbiter가 MSB=1을 인식해 외부 DRAM이 아닌
칩 내부 IB로 트래픽을 라우팅(Zero-Copy).

---

**담당:** 타 팀원 (참고용)

**변경 이력:**
- 최초 명세서에는 `rf_waddr=0x30`으로 잘못 기재되어 있었음 → **0x70으로 확정** (오기재 확인, 수정 완료)
- 이 zero-copy 피드백 기능 자체는 추후 설계 변경으로 폐기될 가능성 있음 (아키텍트 코멘트)

**연동 구현:** `RegisterFile.scala`의 `setIntFlag`/`irqWen`/`irqWaddr`/`irqWdata` 포트
