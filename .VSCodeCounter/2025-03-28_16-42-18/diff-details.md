# Diff Details

Date : 2025-03-28 16:42:18

Directory /home/stormy/curricular/keyan/code/XiangShan-fv/src/main/scala/xiangshan

Total : 54 files,  -5211 codes, -1269 comments, -893 blanks, all -7373 lines

[Summary](results.md) / [Details](details.md) / [Diff Summary](diff.md) / Diff Details

## Files
| filename | language | code | comment | blank | total |
| :--- | :--- | ---: | ---: | ---: | ---: |
| [src/main/scala/device/AXI4DummySD.scala](/src/main/scala/device/AXI4DummySD.scala) | Scala | -120 | -15 | -21 | -156 |
| [src/main/scala/device/AXI4Flash.scala](/src/main/scala/device/AXI4Flash.scala) | Scala | -49 | -15 | -10 | -74 |
| [src/main/scala/device/AXI4IntrGenerator.scala](/src/main/scala/device/AXI4IntrGenerator.scala) | Scala | -48 | -22 | -12 | -82 |
| [src/main/scala/device/AXI4Keyboard.scala](/src/main/scala/device/AXI4Keyboard.scala) | Scala | -29 | -16 | -8 | -53 |
| [src/main/scala/device/AXI4Plic.scala](/src/main/scala/device/AXI4Plic.scala) | Scala | -102 | -57 | -24 | -183 |
| [src/main/scala/device/AXI4RAM.scala](/src/main/scala/device/AXI4RAM.scala) | Scala | -83 | -15 | -19 | -117 |
| [src/main/scala/device/AXI4SlaveModule.scala](/src/main/scala/device/AXI4SlaveModule.scala) | Scala | -139 | -17 | -26 | -182 |
| [src/main/scala/device/AXI4Timer.scala](/src/main/scala/device/AXI4Timer.scala) | Scala | -38 | -15 | -10 | -63 |
| [src/main/scala/device/AXI4UART.scala](/src/main/scala/device/AXI4UART.scala) | Scala | -32 | -15 | -7 | -54 |
| [src/main/scala/device/AXI4VGA.scala](/src/main/scala/device/AXI4VGA.scala) | Scala | -147 | -78 | -35 | -260 |
| [src/main/scala/device/RocketDebugWrapper.scala](/src/main/scala/device/RocketDebugWrapper.scala) | Scala | -101 | -24 | -25 | -150 |
| [src/main/scala/device/TLPMA/TLPMA.scala](/src/main/scala/device/TLPMA/TLPMA.scala) | Scala | -49 | -1 | -9 | -59 |
| [src/main/scala/device/TLTimer.scala](/src/main/scala/device/TLTimer.scala) | Scala | -49 | -15 | -14 | -78 |
| [src/main/scala/gpu/GPU.scala](/src/main/scala/gpu/GPU.scala) | Scala | 0 | -227 | -2 | -229 |
| [src/main/scala/system/SoC.scala](/src/main/scala/system/SoC.scala) | Scala | -267 | -24 | -50 | -341 |
| [src/main/scala/top/ArgParser.scala](/src/main/scala/top/ArgParser.scala) | Scala | -75 | -17 | -6 | -98 |
| [src/main/scala/top/BusPerfMonitor.scala](/src/main/scala/top/BusPerfMonitor.scala) | Scala | -82 | 0 | -10 | -92 |
| [src/main/scala/top/Configs.scala](/src/main/scala/top/Configs.scala) | Scala | -411 | -23 | -17 | -451 |
| [src/main/scala/top/Top.scala](/src/main/scala/top/Top.scala) | Scala | -169 | -25 | -28 | -222 |
| [src/main/scala/top/XiangShanStage.scala](/src/main/scala/top/XiangShanStage.scala) | Scala | -37 | -16 | -6 | -59 |
| [src/main/scala/utils/BinaryArbiterNode.scala](/src/main/scala/utils/BinaryArbiterNode.scala) | Scala | -91 | -3 | -13 | -107 |
| [src/main/scala/utils/BitUtils.scala](/src/main/scala/utils/BitUtils.scala) | Scala | -300 | -31 | -43 | -374 |
| [src/main/scala/utils/CircularQueuePtr.scala](/src/main/scala/utils/CircularQueuePtr.scala) | Scala | -76 | -17 | -21 | -114 |
| [src/main/scala/utils/DataDontCareNode.scala](/src/main/scala/utils/DataDontCareNode.scala) | Scala | -36 | -15 | -9 | -60 |
| [src/main/scala/utils/DataModuleTemplate.scala](/src/main/scala/utils/DataModuleTemplate.scala) | Scala | -178 | -28 | -32 | -238 |
| [src/main/scala/utils/DebugIdentityNode.scala](/src/main/scala/utils/DebugIdentityNode.scala) | Scala | -35 | -15 | -9 | -59 |
| [src/main/scala/utils/ECC.scala](/src/main/scala/utils/ECC.scala) | Scala | -157 | -41 | -33 | -231 |
| [src/main/scala/utils/ExcitingUtils.scala](/src/main/scala/utils/ExcitingUtils.scala) | Scala | -84 | -15 | -17 | -116 |
| [src/main/scala/utils/ExtractVerilogModules.scala](/src/main/scala/utils/ExtractVerilogModules.scala) | Scala | -173 | -34 | -26 | -233 |
| [src/main/scala/utils/GTimer.scala](/src/main/scala/utils/GTimer.scala) | Scala | -9 | -15 | -4 | -28 |
| [src/main/scala/utils/Hold.scala](/src/main/scala/utils/Hold.scala) | Scala | -59 | -31 | -11 | -101 |
| [src/main/scala/utils/IntBuffer.scala](/src/main/scala/utils/IntBuffer.scala) | Scala | -20 | 0 | -7 | -27 |
| [src/main/scala/utils/LFSR64.scala](/src/main/scala/utils/LFSR64.scala) | Scala | -14 | -15 | -4 | -33 |
| [src/main/scala/utils/LatencyPipe.scala](/src/main/scala/utils/LatencyPipe.scala) | Scala | -18 | -16 | -8 | -42 |
| [src/main/scala/utils/LogUtils.scala](/src/main/scala/utils/LogUtils.scala) | Scala | -85 | -20 | -17 | -122 |
| [src/main/scala/utils/LookupTree.scala](/src/main/scala/utils/LookupTree.scala) | Scala | -39 | -17 | -12 | -68 |
| [src/main/scala/utils/MIMOQueue.scala](/src/main/scala/utils/MIMOQueue.scala) | Scala | -119 | -26 | -28 | -173 |
| [src/main/scala/utils/Misc.scala](/src/main/scala/utils/Misc.scala) | Scala | -88 | -28 | -16 | -132 |
| [src/main/scala/utils/OverrideableQueue.scala](/src/main/scala/utils/OverrideableQueue.scala) | Scala | -34 | 0 | -8 | -42 |
| [src/main/scala/utils/ParallelMux.scala](/src/main/scala/utils/ParallelMux.scala) | Scala | -110 | -16 | -20 | -146 |
| [src/main/scala/utils/PerfCounterUtils.scala](/src/main/scala/utils/PerfCounterUtils.scala) | Scala | -188 | -24 | -30 | -242 |
| [src/main/scala/utils/PipelineConnect.scala](/src/main/scala/utils/PipelineConnect.scala) | Scala | -154 | -21 | -22 | -197 |
| [src/main/scala/utils/PriorityMuxDefault.scala](/src/main/scala/utils/PriorityMuxDefault.scala) | Scala | -38 | -15 | -6 | -59 |
| [src/main/scala/utils/PriorityMuxGen.scala](/src/main/scala/utils/PriorityMuxGen.scala) | Scala | -98 | -39 | -8 | -145 |
| [src/main/scala/utils/RegMap.scala](/src/main/scala/utils/RegMap.scala) | Scala | -46 | -17 | -5 | -68 |
| [src/main/scala/utils/Replacement.scala](/src/main/scala/utils/Replacement.scala) | Scala | -30 | -19 | -8 | -57 |
| [src/main/scala/utils/SRAMTemplate.scala](/src/main/scala/utils/SRAMTemplate.scala) | Scala | -162 | -36 | -37 | -235 |
| [src/main/scala/utils/StopWatch.scala](/src/main/scala/utils/StopWatch.scala) | Scala | -16 | -15 | -5 | -36 |
| [src/main/scala/utils/TLClientsMerger.scala](/src/main/scala/utils/TLClientsMerger.scala) | Scala | -61 | -2 | -7 | -70 |
| [src/main/scala/utils/TLDump.scala](/src/main/scala/utils/TLDump.scala) | Scala | -427 | -15 | -40 | -482 |
| [src/main/scala/utils/TLEdgeBuffer.scala](/src/main/scala/utils/TLEdgeBuffer.scala) | Scala | -87 | 0 | -12 | -99 |
| [src/main/scala/utils/Trigger.scala](/src/main/scala/utils/Trigger.scala) | Scala | -16 | -15 | -4 | -35 |
| [src/main/scala/xstransforms/PrintControl.scala](/src/main/scala/xstransforms/PrintControl.scala) | Scala | -114 | -15 | -21 | -150 |
| [src/main/scala/xstransforms/PrintModuleName.scala](/src/main/scala/xstransforms/PrintModuleName.scala) | Scala | -22 | -16 | -11 | -49 |

[Summary](results.md) / [Details](details.md) / [Diff Summary](diff.md) / Diff Details