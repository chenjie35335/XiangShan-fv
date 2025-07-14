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

package xiangshan.frontend
import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import device.EnableFormal
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp}
import rvspeccore.checker.{RVB, RVI, RVM, RVZicsr}
import utils._
import xiangshan._
import xiangshan.backend.fu.{PFEvent, PMP, PMPChecker, PMPReqBundle}
import xiangshan.cache.mmu._
import xiangshan.frontend.icache._

class FakeFrontend()(implicit p: Parameters) extends LazyModule with HasXSParameter{
  val icache = LazyModule(new ICacheEmpty())

  val instrUncache = LazyModule(new InstrUncacheEmpty())

  lazy val module = new FakeFrontendImp(this)
}
// 应该这么写
// 首先有一个newest_entry_ptr和newest_entry_target, 这个东西应该是和写优先相关的东西
// 在ftq中只有说分支预测和重定向的时候才会使用
// 这里有一个commit_ptr，这个就比较麻烦，初步判断应该只和分支预测相关
// 所以说真正重定向是这样的
// 首先分支预测会写ftq_pc_mem， 其次重定向的时候也会写
// 然后重定向后修改bpuptr，从而重新开始取指令
class FakeFrontendImp(outer: FakeFrontend) extends LazyModuleImp(outer) with HasXSParameter
  with HasPerfEvents
{// 接下来需要考虑一下ftq的问题
  val io = IO(new Bundle() {
    val hartId = Input(UInt(8.W)) // no use
    val reset_vector = Input(UInt(PAddrBits.W)) // no use
    val fencei = Input(Bool()) // fence related, but no use
    val ptw = new TlbPtwIO(6) // ptw available. no use now
    val backend = new FrontendToCtrlIO // very important, we should implement this carefully
    val sfence = Input(new SfenceBundle) // no use
    val tlbCsr = Input(new TlbCsrBundle) // no use
    val csrCtrl = Input(new CustomCSRCtrlIO) //no use
    val csrUpdate = new DistributedCSRUpdateReq // cache use
    val error  = new L1CacheErrorInfo // cache use
    val frontendInfo = new Bundle { // no use
      val ibufFull  = Output(Bool())
      val bpuInfo = new Bundle {
        val bpRight = Output(UInt(XLEN.W))
        val bpWrong = Output(UInt(XLEN.W))
      }
    }
  })
  val redirect = io.backend.toFtq.redirect
  // pc
  val startaddr = RegInit(io.reset_vector)
  startaddr := Mux(redirect.valid, redirect.bits.cfiUpdate.target, startaddr + (4 * 4).U)
  val bpBranch = Wire(new BranchPredictionBundle)
  bpBranch := DontCare
  for(i <- 0 until 5) {
    bpBranch.valid(i) := true.B
    bpBranch.pc(i) := startaddr
  }
  val Component = Wire(new Ftq_RF_Components())
  Component.fromBranchPrediction(bpBranch)
  val PcMemWen = WireInit(false.B)
  // fake ftq. this will replace the pc register vector
  val ifPtr, wbPtr = RegInit(FtqPtr(false.B, 0.U)) // 这个相当于说是头指针和尾指针，相当于一个环形队列

  PcMemWen := true.B
  val PcMem = RegInit(VecInit(Seq.fill(FtqSize)(0.U.asTypeOf(new Ftq_RF_Components()))))
  when(PcMemWen) {
    PcMem(wbPtr.value) := Component
  }
  when(redirect.valid) {
    wbPtr := redirect.bits.ftqIdx + 1.U
  }.elsewhen(PcMemWen) {
    wbPtr := wbPtr + 1.U
  }
  //
  // never launch ptw request
  io.ptw := DontCare
  io.ptw.req.foreach(_.valid := false.B)
  io.ptw.req.foreach(_.bits := 0.U.asTypeOf(new PtwReq()))
  io.ptw.resp.ready := false.B
  // no use signal assign as DontCare
  io.frontendInfo.ibufFull := false.B
  io.frontendInfo.bpuInfo.bpRight := 0.U
  io.frontendInfo.bpuInfo.bpWrong := 0.U
  io.csrUpdate := DontCare
  io.csrUpdate.w.valid := false.B
  io.csrUpdate.w.bits.data := 0.U
  io.csrUpdate.w.bits.addr := 0.U
  io.error := DontCare
  //val pc = VecInit((0 until FetchWidth).map(i => FetchComp.startAddr + (i * 4).U)) // 这里我暂时不想考虑压缩指令
  val instr = WireInit(VecInit(Seq.fill(DecodeWidth)(0.U(XLEN.W))))
  val decodePtr = RegInit(0.U(log2Up(FetchWidth).W))
  decodePtr := Mux(redirect.valid, 0.U, decodePtr + 2.U)
  when(redirect.valid) {
    ifPtr := redirect.bits.ftqIdx + 1.U
  }.elsewhen(decodePtr === (FetchWidth - 2).U) {
    ifPtr := ifPtr + 1.U
  }
  val FetchComp = Mux(wbPtr.value === ifPtr.value, Component ,PcMem(ifPtr.value))
  BoringUtils.addSink(instr, "formalInstr")
  // the backend interface 对于每一项， 两边均接受之后就可以传递数据了
  val pc = WireInit(VecInit(Seq.fill(FetchWidth)(0.U(VAddrBits.W))))
  for(i <- 0 until FetchWidth) {
    pc(i) := FetchComp.startAddr + (i * 4).U
  }
  // fake inst buffer 这里定义一个简易的instbuffer, 主要存储pc
  for(i <- 0 until DecodeWidth) {
    io.backend.cfVec(i).valid := true.B
    if (env.EnableFormal) {
      implicit val checker_xlen = 64
      assume(
        RVI.regImm(io.backend.cfVec(i).bits.instr) || RVI.regReg(io.backend.cfVec(i).bits.instr) || RVI.control(io.backend.cfVec(i).bits.instr) || RVI.other(io.backend.cfVec(i).bits.instr) ||
          RVB.zba(io.backend.cfVec(i).bits.instr) || RVB.zbb(io.backend.cfVec(i).bits.instr) ||
          RVB.zbc(io.backend.cfVec(i).bits.instr) || RVB.zbkb(io.backend.cfVec(i).bits.instr) || RVB.zbkc(io.backend.cfVec(i).bits.instr) || RVB.zbkx(io.backend.cfVec(i).bits.instr) ||
          RVZicsr.reg(io.backend.cfVec(i).bits.instr) || RVZicsr.imm(io.backend.cfVec(i).bits.instr)
      )
      //|| RVM.mulOp(io.backend.cfVec(i).bits.instr) || RVM.divOp(io.backend.cfVec(i).bits.instr))
    }
    io.backend.cfVec(i).bits.instr := instr(i)
    io.backend.cfVec(i).bits.pc := pc(decodePtr + i.U) //pc(i)
    io.backend.cfVec(i).bits.foldpc := XORFold(pc(decodePtr + i.U), MemPredPCWidth)
    io.backend.cfVec(i).bits.exceptionVec := 0.U.asTypeOf(ExceptionVec())
    io.backend.cfVec(i).bits.pd.brType := BrType.notCFI
    io.backend.cfVec(i).bits.pd.isRet := false.B
    io.backend.cfVec(i).bits.pd.isRVC := false.B
    io.backend.cfVec(i).bits.pd.isCall := false.B
    io.backend.cfVec(i).bits.pred_taken := false.B
    io.backend.cfVec(i).bits.crossPageIPFFix := false.B
    io.backend.cfVec(i).bits.ftqPtr := ifPtr
    io.backend.cfVec(i).bits.ftqOffset := decodePtr + i.U
  }
  //ftq 相关的还是需要考虑，这部分先放在这里
  io.backend.fromFtq.pc_mem_wen := PcMemWen
  io.backend.fromFtq.pc_mem_waddr := wbPtr.value
  io.backend.fromFtq.pc_mem_wdata := Component
  io.backend.fromFtq.newest_entry_ptr := Mux(redirect.valid, redirect.bits.ftqIdx + 1.U, wbPtr)
  io.backend.fromFtq.newest_entry_target := Mux(redirect.valid, redirect.bits.cfiUpdate.target, Component.startAddr)
  //然后就是对于FTQ的前后端的同步问题， 而且这个是比较麻烦的就是说需要几个周期进行预取
  // 所以说这个backend的valid信号可能需要修改

  override val perfEvents = Seq()
  generatePerfEvent()
}

class Frontend()(implicit p: Parameters) extends LazyModule with HasXSParameter{

  val instrUncache  = LazyModule(new InstrUncache())
  //val icache        = LazyModule(new Fake_ICache())
  val icache = LazyModule(new ICache())

  lazy val module = new FrontendImp(this)
}


class FrontendImp (outer: Frontend) extends LazyModuleImp(outer)
  with HasXSParameter
  with HasPerfEvents
{
  val io = IO(new Bundle() {
    val hartId = Input(UInt(8.W))
    val reset_vector = Input(UInt(PAddrBits.W))
    val fencei = Input(Bool())
    val ptw = new TlbPtwIO(6)
    val backend = new FrontendToCtrlIO
    val sfence = Input(new SfenceBundle)
    val tlbCsr = Input(new TlbCsrBundle)
    val csrCtrl = Input(new CustomCSRCtrlIO)
    val csrUpdate = new DistributedCSRUpdateReq
    val error  = new L1CacheErrorInfo
    val frontendInfo = new Bundle {
      val ibufFull  = Output(Bool())
      val bpuInfo = new Bundle {
        val bpRight = Output(UInt(XLEN.W))
        val bpWrong = Output(UInt(XLEN.W))
      }
    }
  })

  //decouped-frontend modules
  val instrUncache = outer.instrUncache.module
  val icache       = outer.icache.module
  val bpu     = Module(new Predictor)
  val ifu     = Module(new NewIFU)
  val ibuffer =  Module(new Ibuffer)
  val ftq = Module(new Ftq)

  val tlbCsr = DelayN(io.tlbCsr, 2)
  val csrCtrl = DelayN(io.csrCtrl, 2)
  val sfence = RegNext(RegNext(io.sfence))

  // trigger
  ifu.io.frontendTrigger := csrCtrl.frontend_trigger

  // bpu ctrl
  bpu.io.ctrl := csrCtrl.bp_ctrl

// pmp
  val pmp = Module(new PMP())
  val pmp_check = VecInit(Seq.fill(4)(Module(new PMPChecker(3, sameCycle = true)).io))
  pmp.io.distribute_csr := csrCtrl.distribute_csr
  val pmp_req_vec     = Wire(Vec(4, Valid(new PMPReqBundle())))
  pmp_req_vec(0) <> icache.io.pmp(0).req
  pmp_req_vec(1) <> icache.io.pmp(1).req
  pmp_req_vec(2) <> icache.io.pmp(2).req
  pmp_req_vec(3) <> ifu.io.pmp.req

  for (i <- pmp_check.indices) {
    pmp_check(i).apply(tlbCsr.priv.imode, pmp.io.pmp, pmp.io.pma, pmp_req_vec(i))
  }
  icache.io.pmp(0).resp <> pmp_check(0).resp
  icache.io.pmp(1).resp <> pmp_check(1).resp
  icache.io.pmp(2).resp <> pmp_check(2).resp
  ifu.io.pmp.resp <> pmp_check(3).resp

  // val tlb_req_arb     = Module(new Arbiter(new TlbReq, 2))
  // tlb_req_arb.io.in(0) <> ifu.io.iTLBInter.req
  // tlb_req_arb.io.in(1) <> icache.io.itlb(1).req

  val itlb_requestors = Wire(Vec(6, new BlockTlbRequestIO))
  itlb_requestors(0) <> icache.io.itlb(0)
  itlb_requestors(1) <> icache.io.itlb(1)
  itlb_requestors(2) <> icache.io.itlb(2)
  itlb_requestors(3) <> icache.io.itlb(3)
  itlb_requestors(4) <> icache.io.itlb(4)
  itlb_requestors(5) <> ifu.io.iTLBInter

  // itlb_requestors(1).req <>  tlb_req_arb.io.out

  // ifu.io.iTLBInter.resp  <> itlb_requestors(1).resp
  // icache.io.itlb(1).resp <> itlb_requestors(1).resp

  io.ptw <> TLB(
    //in = Seq(icache.io.itlb(0), icache.io.itlb(1)),
    in = Seq(itlb_requestors(0),itlb_requestors(1),itlb_requestors(2),itlb_requestors(3),itlb_requestors(4),itlb_requestors(5)),
    sfence = DelayN(io.sfence, 1),
    csr = DelayN(io.tlbCsr, 1),
    width = 6,
    nRespDups = 1,
    shouldBlock = true,
    itlbParams
  )

  icache.io.prefetch <> ftq.io.toPrefetch

  val needFlush = RegNext(io.backend.toFtq.redirect.valid)

  //IFU-Ftq
  ifu.io.ftqInter.fromFtq <> ftq.io.toIfu
  ftq.io.toIfu.req.ready :=  ifu.io.ftqInter.fromFtq.req.ready && icache.io.fetch.req.ready

  ftq.io.fromIfu          <> ifu.io.ftqInter.toFtq
  bpu.io.ftq_to_bpu       <> ftq.io.toBpu
  ftq.io.fromBpu          <> bpu.io.bpu_to_ftq

  ftq.io.mmioCommitRead   <> ifu.io.mmioCommitRead
  //IFU-ICache

  icache.io.fetch.req <> ftq.io.toICache.req
  ftq.io.toICache.req.ready :=  ifu.io.ftqInter.fromFtq.req.ready && icache.io.fetch.req.ready

  ifu.io.icacheInter.resp <>    icache.io.fetch.resp
  ifu.io.icacheInter.icacheReady :=  icache.io.toIFU
  icache.io.stop := ifu.io.icacheStop

  ifu.io.icachePerfInfo := icache.io.perfInfo

  icache.io.csr.distribute_csr <> csrCtrl.distribute_csr
  io.csrUpdate := RegNext(icache.io.csr.update)

  icache.io.csr_pf_enable     := RegNext(csrCtrl.l1I_pf_enable)
  icache.io.csr_parity_enable := RegNext(csrCtrl.icache_parity_enable)

  //IFU-Ibuffer
  ifu.io.toIbuffer    <> ibuffer.io.in

  ftq.io.fromBackend <> io.backend.toFtq
  io.backend.fromFtq <> ftq.io.toBackend
  io.frontendInfo.bpuInfo <> ftq.io.bpuInfo

  ifu.io.rob_commits <> io.backend.toFtq.rob_commits

  ibuffer.io.flush := needFlush
  io.backend.cfVec <> ibuffer.io.out

  instrUncache.io.req   <> ifu.io.uncacheInter.toUncache
  ifu.io.uncacheInter.fromUncache <> instrUncache.io.resp
  instrUncache.io.flush := false.B
  io.error <> RegNext(RegNext(icache.io.error))

  icache.io.hartId := io.hartId

  val frontendBubble = PopCount((0 until DecodeWidth).map(i => io.backend.cfVec(i).ready && !ibuffer.io.out(i).valid))
  XSPerfAccumulate("FrontendBubble", frontendBubble)
  io.frontendInfo.ibufFull := RegNext(ibuffer.io.full)

  // PFEvent
  val pfevent = Module(new PFEvent)
  pfevent.io.distribute_csr := io.csrCtrl.distribute_csr
  val csrevents = pfevent.io.hpmevent.take(8)

  val perfFromUnits = Seq(ifu, ibuffer, ftq, icache, bpu).flatMap(_.getPerfEvents)
  val perfFromIO    = Seq()
  val perfBlock     = Seq()
  // let index = 0 be no event
  val allPerfEvents = Seq(("noEvent", 0.U)) ++ perfFromUnits ++ perfFromIO ++ perfBlock

  if (printEventCoding) {
    for (((name, inc), i) <- allPerfEvents.zipWithIndex) {
      println("Frontend perfEvents Set", name, inc, i)
    }
  }

  val allPerfInc = allPerfEvents.map(_._2.asTypeOf(new PerfEvent))
  override val perfEvents = HPerfMonitor(csrevents, allPerfInc).getPerfEvents
  generatePerfEvent()
}
