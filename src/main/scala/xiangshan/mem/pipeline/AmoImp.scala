/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan.mem

import org.chipsalliance.cde.config.Parameters
import chisel3._
import chisel3.util._
import utils._
import utility._
import xiangshan._
import xiangshan.ExceptionNO._
import xiangshan.backend.fu.PMPRespBundle
import xiangshan.backend.fu.FuType
import xiangshan.backend.Bundles.{MemExuInput, MemExuOutput}
import xiangshan.backend.fu.NewCSR.TriggerUtil
import xiangshan.backend.fu.util.SdtrigExt
import xiangshan.cache.mmu.Pbmt
import xiangshan.cache.{AtomicWordIO, HasDCacheParameters, MemoryOpConstants}
import xiangshan.cache.mmu.{TlbCmd, TlbRequestIO}
import difftest._

class AmoIO()(implicit p: Parameters, params: MemUnitParams) extends MemUnitIO with HasMemBlockParameters {
  val storeDataIn = Flipped(Vec(StdCnt, Valid(new MemExuOutput)))
  val flushSbuffer = new SbufferFlushBundle
  val exceptionInfo = ValidIO(new ExceptionAddrIO)
  val amoDCacheIO = new AtomicWordIO
}

class AmoImp(override val wrapper: MemUnit)(implicit p: Parameters, params: MemUnitParams) extends MemUnitImp(wrapper)
  with MemoryOpConstants
  with HasDCacheParameters
  with SdtrigExt {

  io.suggestName("none")
  override lazy val io = IO(new AmoIO).suggestName("io")

  private val amoIn = Wire(DecoupledIO(new MemExuInput))
  amoIn.valid := s0_out.valid
  amoIn.bits  := s0_out.bits.toMemExuInputBundle()
  s0_out.ready := amoIn.ready
  private val amoDCacheIO = io.amoDCacheIO

  val s_invalid :: s_tlbAndFlushSbufferReq :: s_pm :: s_waitFlushSbufferResp :: s_cacheReq :: s_cacheResp :: s_cacheRespLatch :: s_finish :: s_finish2 :: Nil = Enum(9)
  val state = RegInit(s_invalid)
  val outValid = RegInit(false.B)
  val dataValid = RegInit(false.B)

  val uop = Reg(amoIn.bits.uop.cloneType)
  val isLr = LSUOpType.isLr(uop.fuOpType)
  val isSc = LSUOpType.isSc(uop.fuOpType)
  val isAMOCAS = LSUOpType.isAMOCAS(uop.fuOpType)
  val isNotLr = !isLr
  val isNotSc = !isSc
  // AMOCAS.Q needs to write two int registers, therefore backend issues two sta uops for AMOCAS.Q.
  // `pdest2` is used to record the pdest of the second uop
  val pdest1, pdest2 = Reg(UInt(PhyRegIdxWidth.W))
  val pdest1Valid, pdest2Valid = RegInit(false.B)
  /**
    * The # of std uops that an atomic instruction require:
    * (1) For AMOs (except AMOCAS) and LR/SC, 1 std uop is wanted: X(rs2) with uopIdx = 0
    * (2) For AMOCAS.W/D, 2 std uops are wanted: X(rd), X(rs2) with uopIdx = 0, 1
    * (3) For AMOCAS.Q, 4 std uops are wanted: X(rd), X(rs2), X(rd+1), X(rs2+1) with uopIdx = 0, 1, 2, 3
    * stds are not needed for write-back.
    *
    * The # of sta uops that an atomic instruction require, also the # of write-back:
    * (1) For AMOs(except AMOCAS.Q) and LR/SC, 1 sta uop is wanted: X(rs1) with uopIdx = 0
    * (2) For AMOCAS.Q, 2 sta uop is wanted: X(rs1)*2 with uopIdx = 0, 2
    */
  val rs1, rs2_l, rs2_h, rd_l, rd_h = Reg(UInt(XLEN.W))
  val stds = Seq(rd_l, rs2_l, rd_h, rs2_h)
  val rs2 = Cat(rs2_h, Mux(isAMOCAS, rs2_l, stds.head))
  val rd = Cat(rd_h, rd_l)
  val stdCnt = RegInit(0.U(log2Ceil(stds.length + 1).W))

  val exceptionVec = RegInit(0.U.asTypeOf(ExceptionVec()))
  val trigger = RegInit(TriggerAction.None)
  val atomOverrideXtval = RegInit(false.B)
  val haveSendFirstTlbReq = RegInit(false.B)
  // paddr after translation
  val paddr = Reg(UInt())
  val gpaddr = Reg(UInt())
  val vaddr = rs1

  val isMmio = Reg(Bool())
  val isNc = RegInit(false.B)
  val isForVSnonLeafPTE = Reg(Bool())

  // dcache response data
  val respData = Reg(UInt())
  val respDataWire = WireInit(0.U)
  val success = Reg(Bool())
  // sbuffer is empty or not
  val sbufferEmpty = io.flushSbuffer.empty

  // Only the least significant AMOFuOpWidth = 6 bits of fuOpType are used,
  // therefore the MSBs are reused to identify uopIdx
  val stdUopIdxs = io.storeDataIn.map(_.bits.uop.fuOpType >> LSUOpType.AMOFuOpWidth)
  val staUopIdx = amoIn.bits.uop.fuOpType >> LSUOpType.AMOFuOpWidth

  // assign default value to output signals
  amoIn.ready          := false.B

  amoDCacheIO.req.valid  := false.B
  amoDCacheIO.req.bits   := DontCare

  toTlb.req.valid    := false.B
  toTlb.req.bits     := DontCare
  toTlb.req_kill     := false.B
  fromTlb.resp.ready   := true.B

  io.flushSbuffer.valid := false.B

  when (state === s_invalid) {
    when (amoIn.fire) {
      uop := amoIn.bits.uop
      rs1 := amoIn.bits.src_rs1
      state := s_tlbAndFlushSbufferReq
      haveSendFirstTlbReq := false.B
    }
  }

  when (amoIn.fire) {
    val pdest = amoIn.bits.uop.pdest
    when (staUopIdx === 0.U) {
      pdest1Valid := true.B
      pdest1 := pdest
    }.elsewhen (staUopIdx === 2.U) {
      pdest2Valid := true.B
      pdest2 := pdest
    }.otherwise {
      assert(false.B, "unrecognized sta uopIdx")
    }
  }

  stds.zipWithIndex.foreach { case (data, i) =>
    val sels = io.storeDataIn.zip(stdUopIdxs).map { case (in, uopIdx) =>
      val sel = in.fire && uopIdx === i.U
      when (sel) { data := in.bits.data }
      sel
    }
    OneHot.checkOneHot(sels)
  }
  stdCnt := stdCnt + PopCount(io.storeDataIn.map(_.fire))

  val StdCntNCAS = 1 // LR/SC and AMO need only 1 src besides rs1
  val StdCntCASWD = 2 // AMOCAS.W/D needs 2 src regs (rs2 and rd) besides rs1
  val StdCntCASQ = 4 // AMOCAS.Q needs 4 src regs (rs2, rs2+1, rd, rd+1) besides rs1
  when (!dataValid) {
    dataValid := state =/= s_invalid && (
      LSUOpType.isAMOCASQ(uop.fuOpType) && stdCnt === StdCntCASQ.U ||
      LSUOpType.isAMOCASWD(uop.fuOpType) && stdCnt === StdCntCASWD.U ||
      !isAMOCAS && stdCnt === StdCntNCAS.U
    )
  }
  assert(stdCnt <= stds.length.U, "unexpected std")
  assert(!(Cat(io.storeDataIn.map(_.fire)).orR && dataValid), "atomic unit re-receive data")
  assert(state === s_invalid ||
    uop.fuOpType(1,0) === "b10".U ||
    uop.fuOpType(1,0) === "b11".U ||
    LSUOpType.isAMOCASQ(uop.fuOpType),
    "Only word or doubleword or quadword is supported"
  )

  // atomic trigger
  val tdata = Reg(Vec(TriggerNum, new MatchTriggerIO))
  val tEnableVec = RegInit(VecInit(Seq.fill(TriggerNum)(false.B)))
  tEnableVec := fromCtrl.csr.mem_trigger.tEnableVec
  when (fromCtrl.csr.mem_trigger.tUpdate.valid) {
    tdata(fromCtrl.csr.mem_trigger.tUpdate.bits.addr) := fromCtrl.csr.mem_trigger.tUpdate.bits.tdata
  }

  val debugMode = fromCtrl.csr.mem_trigger.debugMode
  val triggerCanRaiseBpExp = fromCtrl.csr.mem_trigger.triggerCanRaiseBpExp
  val backendTriggerTimingVec = VecInit(tdata.map(_.timing))
  val backendTriggerChainVec = VecInit(tdata.map(_.chain))
  val backendTriggerHitVec = WireInit(VecInit(Seq.fill(TriggerNum)(false.B)))
  val backendTriggerCanFireVec = RegInit(VecInit(Seq.fill(TriggerNum)(false.B)))

  // store trigger
  val storeHit = Wire(Vec(TriggerNum, Bool()))
  for (j <- 0 until TriggerNum) {
    storeHit(j) := !tdata(j).select && !debugMode && isNotLr && TriggerCmp(
      vaddr,
      tdata(j).tdata2,
      tdata(j).matchType,
      tEnableVec(j) && tdata(j).store
    )
  }
  // load trigger
  val loadHit = Wire(Vec(TriggerNum, Bool()))
  for (j <- 0 until TriggerNum) {
    loadHit(j) := !tdata(j).select && !debugMode && isNotSc && TriggerCmp(
      vaddr,
      tdata(j).tdata2,
      tdata(j).matchType,
      tEnableVec(j) && tdata(j).load
    )
  }
  backendTriggerHitVec := storeHit.zip(loadHit).map { case (sh, lh) => sh || lh }
  // triggerCanFireVec will update at T+1
  TriggerCheckCanFire(TriggerNum, backendTriggerCanFireVec, backendTriggerHitVec,
    backendTriggerTimingVec, backendTriggerChainVec)

  val actionVec = VecInit(tdata.map(_.action))
  val triggerAction = Wire(TriggerAction())
  TriggerUtil.triggerActionGen(triggerAction, backendTriggerCanFireVec, actionVec, triggerCanRaiseBpExp)
  val triggerDebugMode = TriggerAction.isDmode(triggerAction)
  val triggerBreakpoint = TriggerAction.isExp(triggerAction)

  // tlb translation, manipulating signals && deal with exception
  // at the same time, flush sbuffer
  when (state === s_tlbAndFlushSbufferReq) {
    // do not accept tlb resp in the first cycle
    // this limition is for hw prefetcher
    // when !haveSendFirstTlbReq, tlb resp may come from hw prefetch
    haveSendFirstTlbReq := true.B

    when (fromTlb.resp.fire && haveSendFirstTlbReq) {
      paddr   := fromTlb.resp.bits.paddr(0)
      gpaddr  := fromTlb.resp.bits.gpaddr(0)
      vaddr   := fromTlb.resp.bits.fullva
      isForVSnonLeafPTE := fromTlb.resp.bits.isForVSnonLeafPTE
      // exception handling
      val addrAligned = LookupTree(uop.fuOpType(1,0), List(
        "b10".U -> (vaddr(1,0) === 0.U), // W
        "b11".U -> (vaddr(2,0) === 0.U), // D
        "b00".U -> (vaddr(3,0) === 0.U)  // Q
      ))
      exceptionVec(loadAddrMisaligned)  := !addrAligned && isLr
      exceptionVec(storeAddrMisaligned) := !addrAligned && !isLr
      exceptionVec(storePageFault)      := fromTlb.resp.bits.excp(0).pf.st
      exceptionVec(loadPageFault)       := fromTlb.resp.bits.excp(0).pf.ld
      exceptionVec(storeAccessFault)    := fromTlb.resp.bits.excp(0).af.st
      exceptionVec(loadAccessFault)     := fromTlb.resp.bits.excp(0).af.ld
      exceptionVec(storeGuestPageFault) := fromTlb.resp.bits.excp(0).gpf.st
      exceptionVec(loadGuestPageFault)  := fromTlb.resp.bits.excp(0).gpf.ld

      exceptionVec(breakPoint) := triggerBreakpoint
      trigger                  := triggerAction

      when (!fromTlb.resp.bits.miss) {
        isNc := Pbmt.isNC(fromTlb.resp.bits.pbmt(0))
        toBackend.writeback.head.bits.uop.debugInfo.tlbRespTime := GTimer()
        when (!addrAligned || triggerDebugMode || triggerBreakpoint) {
          // NOTE: when addrAligned or trigger fire, do not need to wait tlb actually
          // check for miss aligned exceptions, tlb exception are checked next cycle for timing
          // if there are exceptions, no need to execute it
          state := s_finish
          outValid := true.B
          atomOverrideXtval := true.B
        }.otherwise {
          state := s_pm
        }
      }
    }
  }

  val pbmtReg = RegEnable(fromTlb.resp.bits.pbmt(0), fromTlb.resp.fire && !fromTlb.resp.bits.miss)
  when (state === s_pm) {
    val pmp = WireInit(fromPmp)
    isMmio := Pbmt.isIO(pbmtReg) || (Pbmt.isPMA(pbmtReg) && pmp.mmio)

    // NOTE: only handle load/store exception here, if other exception happens, don't send here
    val exception_va = exceptionVec(storePageFault) || exceptionVec(loadPageFault) ||
      exceptionVec(storeGuestPageFault) || exceptionVec(loadGuestPageFault) ||
      exceptionVec(storeAccessFault) || exceptionVec(loadAccessFault)
    val exception_pa = pmp.st || pmp.ld || pmp.mmio
    when (exception_va || exception_pa) {
      state := s_finish
      outValid := true.B
      atomOverrideXtval := true.B
    }.otherwise {
      // if sbuffer has been flushed, go to query dcache, otherwise wait for sbuffer.
      state := Mux(sbufferEmpty, s_cacheReq, s_waitFlushSbufferResp);
    }
    // update storeAccessFault bit
    exceptionVec(loadAccessFault) := exceptionVec(loadAccessFault) || (pmp.ld || pmp.mmio) && isLr
    exceptionVec(storeAccessFault) := exceptionVec(storeAccessFault) || pmp.st || (pmp.ld || pmp.mmio) && !isLr
  }

  when (state === s_waitFlushSbufferResp) {
    when (sbufferEmpty) {
      state := s_cacheReq
    }
  }

  def genWdataAMO(data: UInt, sizeEncode: UInt): UInt = {
    LookupTree(sizeEncode(1, 0), List(
      "b10".U -> Fill(4, data(31, 0)),
      "b11".U -> Fill(2, data(63, 0)),
      "b00".U -> data(127, 0)
    ))
  }

  def genWmaskAMO(addr: UInt, sizeEncode: UInt): UInt = {
    /**
      * `MainPipeReq` uses `word_idx` to recognize which 64-bits data bank to operate on. Double-word atomics are
      * always 8B aligned and quad-word atomics are always 16B aligned except for misaligned exception, therefore
      * `word_idx` is enough and there is no need to shift according address. Only word atomics needs LSBs of the
      * address to shift mask inside a 64-bits aligned range.
      */
    LookupTree(sizeEncode(1, 0), List(
      "b10".U -> (0xf.U << addr(2,0)), // W
      "b11".U -> 0xff.U, // D
      "b00".U -> 0xffff.U // Q
    ))
  }

  when (state === s_cacheReq) {
    when (amoDCacheIO.req.fire) {
      state := s_cacheResp
    }
  }

  val dcacheRespData  = Reg(UInt())
  val dcacheRespId    = Reg(UInt())
  val dcacheRespError = Reg(Bool())

  when (state === s_cacheResp) {
    // when not miss
    // everything is OK, simply send response back to sbuffer
    // when miss and not replay
    // wait for missQueue to handling miss and replaying our request
    // when miss and replay
    // req missed and fail to enter missQueue, manually replay it later
    // TODO: add assertions:
    // 1. add a replay delay counter?
    // 2. when req gets into MissQueue, it should not miss any more
    when (amoDCacheIO.resp.fire) {
      when (amoDCacheIO.resp.bits.miss) {
        when (amoDCacheIO.resp.bits.replay) {
          state := s_cacheReq
        }
      }.otherwise {
        dcacheRespData := amoDCacheIO.resp.bits.data
        dcacheRespId := amoDCacheIO.resp.bits.id
        dcacheRespError := amoDCacheIO.resp.bits.error
        state := s_cacheRespLatch
      }
    }
  }

  when (state === s_cacheRespLatch) {
    success := dcacheRespId
    val rdataSel = Mux(
      paddr(2, 0) === 0.U,
      dcacheRespData,
      dcacheRespData >> 32
    )
    assert(paddr(2, 0) === "b000".U || paddr(2, 0) === "b100".U)

    respDataWire := Mux(
      isSc,
      dcacheRespData,
      LookupTree(uop.fuOpType(1,0), List(
        "b10".U -> SignExt(rdataSel(31, 0), QuadWordBits), // W
        "b11".U -> SignExt(rdataSel(63, 0), QuadWordBits), // D
        "b00".U -> rdataSel // Q
      ))
    )

    when (dcacheRespError && io.fromCtrl.csr.cache_error_enable) {
      exceptionVec(loadAccessFault)  := isLr
      exceptionVec(storeAccessFault) := !isLr
      assert(!exceptionVec(loadAccessFault))
      assert(!exceptionVec(storeAccessFault))
    }

    respData := respDataWire
    state := s_finish
    outValid := true.B
  }

  when (state === s_finish) {
    when (toBackend.writeback.head.fire) {
      when (LSUOpType.isAMOCASQ(uop.fuOpType)) {
        // enter `s_finish2` to write the 2nd uop back
        state := s_finish2
        outValid := true.B
      }.otherwise {
        // otherwise the FSM ends here
        resetFSM()
      }
    }
  }

  when (state === s_finish2) {
    when (toBackend.writeback.head.fire) {
      resetFSM()
    }
  }

  when (fromCtrl.redirect.valid) {
    atomOverrideXtval := false.B
  }

  def resetFSM(): Unit = {
    state := s_invalid
    outValid := false.B
    dataValid := false.B
    stdCnt := 0.U
    pdest1Valid := false.B
    pdest2Valid := false.B
  }

  /**
    * IO assignment
    */
  io.exceptionInfo.valid := atomOverrideXtval
  io.exceptionInfo.bits := DontCare
  io.exceptionInfo.bits.vaddr := vaddr
  io.exceptionInfo.bits.gpaddr := gpaddr
  io.exceptionInfo.bits.isForVSnonLeafPTE := isForVSnonLeafPTE

  // Send TLB feedback to store issue queue
  // we send feedback right after we receives request
  // also, we always treat amo as tlb hit
  // since we will continue polling tlb all by ourself
  toBackend.iqFeedback.feedbackSlow.valid       := GatedValidRegNext(GatedValidRegNext(amoIn.valid))
  toBackend.iqFeedback.feedbackSlow.bits.hit    := true.B
  toBackend.iqFeedback.feedbackSlow.bits.robIdx  := RegEnable(amoIn.bits.uop.robIdx, amoIn.valid)
  toBackend.iqFeedback.feedbackSlow.bits.sqIdx   := RegEnable(amoIn.bits.uop.sqIdx, amoIn.valid)
  toBackend.iqFeedback.feedbackSlow.bits.lqIdx   := RegEnable(amoIn.bits.uop.lqIdx, amoIn.valid)
  toBackend.iqFeedback.feedbackSlow.bits.flushState := DontCare
  toBackend.iqFeedback.feedbackSlow.bits.sourceType := DontCare
  toBackend.iqFeedback.feedbackSlow.bits.dataInvalidSqIdx := DontCare
  toBackend.iqFeedback.feedbackSlow.bits.isLoad := false.B

  // send req to dtlb
  // keep firing until tlb hit
  toTlb.req.valid       := state === s_tlbAndFlushSbufferReq
  toTlb.req.bits.vaddr  := vaddr
  toTlb.req.bits.fullva := vaddr
  toTlb.req.bits.checkfullva := true.B
  toTlb.req.bits.cmd    := Mux(isLr, TlbCmd.atom_read, TlbCmd.atom_write)
  toTlb.req.bits.debug.pc := uop.pc
  toTlb.req.bits.debug.robIdx := uop.robIdx
  toTlb.req.bits.debug.isFirstIssue := false.B
  fromTlb.resp.ready      := true.B
  toBackend.writeback.head.bits.uop.debugInfo.tlbFirstReqTime := GTimer() // FIXME lyq: it will be always assigned

  // send req to sbuffer to flush it if it is not empty
  io.flushSbuffer.valid := !sbufferEmpty && state === s_tlbAndFlushSbufferReq

  // When is sta issue port ready:
  // (1) AtomicsUnit is idle, or
  // (2) For AMOCAS.Q, the second uop with the pdest of the higher bits of rd is not received yet
  amoIn.ready := state === s_invalid || LSUOpType.isAMOCASQ(uop.fuOpType) && (!pdest2Valid || !pdest1Valid)

  toBackend.writeback.head.valid := outValid && Mux(state === s_finish2, pdest2Valid, pdest1Valid)
  XSError((state === s_finish || state === s_finish2) =/= outValid, "outValid reg error\n")
  toBackend.writeback.head.bits := DontCare
  toBackend.writeback.head.bits.uop := uop
  toBackend.writeback.head.bits.uop.fuType := FuType.mou.U
  toBackend.writeback.head.bits.uop.pdest := Mux(state === s_finish2, pdest2, pdest1)
  toBackend.writeback.head.bits.uop.exceptionVec := exceptionVec
  toBackend.writeback.head.bits.uop.trigger := trigger
  toBackend.writeback.head.bits.data := Mux(state === s_finish2, respData >> XLEN, respData)

  amoDCacheIO.req.valid := Mux(
    amoDCacheIO.req.bits.cmd === M_XLR,
    !amoDCacheIO.block_lr, // block lr to survive in lr storm
    dataValid // wait until src(1) is ready
  ) && state === s_cacheReq
  val pipeReq = amoDCacheIO.req.bits
  pipeReq := DontCare
  pipeReq.cmd := LookupTree(uop.fuOpType, List(
    // TODO: optimize this
    LSUOpType.lr_w      -> M_XLR,
    LSUOpType.sc_w      -> M_XSC,
    LSUOpType.amoswap_w -> M_XA_SWAP,
    LSUOpType.amoadd_w  -> M_XA_ADD,
    LSUOpType.amoxor_w  -> M_XA_XOR,
    LSUOpType.amoand_w  -> M_XA_AND,
    LSUOpType.amoor_w   -> M_XA_OR,
    LSUOpType.amomin_w  -> M_XA_MIN,
    LSUOpType.amomax_w  -> M_XA_MAX,
    LSUOpType.amominu_w -> M_XA_MINU,
    LSUOpType.amomaxu_w -> M_XA_MAXU,
    LSUOpType.amocas_w  -> M_XA_CASW,

    LSUOpType.lr_d      -> M_XLR,
    LSUOpType.sc_d      -> M_XSC,
    LSUOpType.amoswap_d -> M_XA_SWAP,
    LSUOpType.amoadd_d  -> M_XA_ADD,
    LSUOpType.amoxor_d  -> M_XA_XOR,
    LSUOpType.amoand_d  -> M_XA_AND,
    LSUOpType.amoor_d   -> M_XA_OR,
    LSUOpType.amomin_d  -> M_XA_MIN,
    LSUOpType.amomax_d  -> M_XA_MAX,
    LSUOpType.amominu_d -> M_XA_MINU,
    LSUOpType.amomaxu_d -> M_XA_MAXU,
    LSUOpType.amocas_d  -> M_XA_CASD,

    LSUOpType.amocas_q  -> M_XA_CASQ
  ))
  pipeReq.miss := false.B
  pipeReq.probe := false.B
  pipeReq.probe_need_data := false.B
  pipeReq.source := AMO_SOURCE.U
  pipeReq.addr   := get_block_addr(paddr)
  pipeReq.vaddr  := get_block_addr(vaddr)
  pipeReq.word_idx  := get_word(paddr)
  pipeReq.amo_data := genWdataAMO(rs2, uop.fuOpType)
  pipeReq.amo_mask := genWmaskAMO(paddr, uop.fuOpType)
  pipeReq.amo_cmp  := genWdataAMO(rd, uop.fuOpType)

  if (env.EnableDifftest) {
    val difftest = DifftestModule(new DiffAtomicEvent)
    val en = amoDCacheIO.req.fire
    difftest.coreid := fromCtrl.hartId
    difftest.valid  := state === s_cacheRespLatch
    difftest.addr   := RegEnable(paddr, en)
    difftest.data   := RegEnable(amoDCacheIO.req.bits.amo_data.asTypeOf(difftest.data), en)
    difftest.mask   := RegEnable(amoDCacheIO.req.bits.amo_mask, en)
    difftest.cmp    := RegEnable(amoDCacheIO.req.bits.amo_cmp.asTypeOf(difftest.cmp), en)
    difftest.fuop   := RegEnable(uop.fuOpType, en)
    difftest.out    := respDataWire.asTypeOf(difftest.out)
  }

  if (env.EnableDifftest || env.AlwaysBasicDiff) {
    val uop = toBackend.writeback.head.bits.uop
    val difftest = DifftestModule(new DiffLrScEvent)
    difftest.coreid := fromCtrl.hartId
    difftest.valid := toBackend.writeback.head.fire && state === s_finish && isSc
    difftest.success := success
  }
}