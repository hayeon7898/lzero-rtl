#define DRAM_BASE 0x80000000
#define NPU_BASE  0x10030000   // NPURegFileWrapper 주소 (RS1_REG = offset 0x00)

.section .text.start, "ax", @progbits
.globl _start
_start:
  csrwi 0x7c1, 0 // disable chicken bits
  li s0, DRAM_BASE
  csrr a0, mhartid
  la a1, _dtb
  jr s0

.section .text.hang, "ax", @progbits
.globl _hang
_hang:
  csrwi 0x7c1, 0 // disable chicken bits
  csrr a0, mhartid
  la a1, _dtb
  csrwi mie, 0

  // ---------------------------------------------------------------
  // NPU RegisterFile 왕복 테스트: RS1_REG(offset 0x00)에 값을 쓰고 다시 읽음
  // 커밋 로그(C0: ... W[t2=...])에서 t2 값이 t1과 같은지 눈으로 확인 가능
  // ---------------------------------------------------------------
  li   t0, NPU_BASE       // t0 = 0x10030000 (NPU RS1_REG 주소)
  li   t1, 0x1234ABCD     // t1 = 테스트 값 (상위 비트 0이라 sign-extend 이슈 없음)
  sd   t1, 0(t0)          // RS1_REG에 64bit write
  ld   t2, 0(t0)          // RS1_REG에서 다시 64bit read -> t2
  addi t3, t2, 0  

1:
  wfi
  j 1b

.section .rodata.dtb, "a", @progbits
.globl _dtb
.align 5, 0
_dtb:
.ascii "DTB goes here"