# NPU 명세서 목차 (Specs Index)

> Notion에서 옮겨온 명세서 모음. 
> 폴더 구조는 `src/main/scala/npu/*` 패키지와 최대한 대응되도록 정리함.

## Overview

| 문서 | 상태 |
| --- | --- |
| [NPU Overview (Architecture Diagram)](overview/npu-overview.md) | ✅ 참고용 |

## Control Unit (`npu.control`)

| 문서 | 상태 |
| --- | --- |
| [Shoot-and-Go ISA](control/shoot-and-go-isa.md) | ✅ 구현 완료 (Rs1Decoder) |
| [Register File & MMIO Interface](control/register-file-mmio.md) | ✅ 구현 완료 (RegisterFile) |
| [Compute Initializer Unit (Main Brain FSM)](control/compute-initializer-unit.md) | ✅ 구현 완료 |
| [DMA to RF Writer (Command Fetcher)](control/dma-to-rf-writer.md) | 📄 참고용 |
| [Interrupt Generator](control/interrupt-generator.md) | 📄 참고용 |
| [Stall Generator](control/stall-generator.md) | 📄 참고용 |
| [DMA Arbiter (Smart Scheduler)](control/dma-arbiter.md) | 📄 참고용 |

## Memory (`npu.memory`)

| 문서 | 상태 |
| --- | --- |
| [OCM Controller (통합본)](memory/ocm-controller.md) | 📄 참고용 |

## Compute (`npu.compute`)

| 문서 | 상태 |
| --- | --- |
| [Compute Core Complex (ComputeUnit)](compute/compute-unit.md) | 📄 참고용 |
| [Compute Timer (Local Datapath Sequencer)](compute/compute-timer.md) | 📄 참고용 |
| [Normalizer Phase Transition 및 동기화 아키텍처](compute/normalizer-phase-transition.md) | 📄 참고용 |
| [MXU (Systolic Array) 구현](compute/mxu-systolic-array.md) | ✅ 구현완료 |
| [후처리 파이프라인 가이드 (Accumulator~Normalization)](compute/post-processing-pipeline-guide.md) | 🚧 미구현 (학습 가이드) |

---

**범례:** 
1. ✅ 본인 구현 완료 
2. 🚧 진행중/논의중 
3. 📄 참고용(타 담당 또는 레퍼런스)