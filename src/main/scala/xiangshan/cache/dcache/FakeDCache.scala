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

package xiangshan.cache

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import xiangshan._
import utils._
import freechips.rocketchip.diplomacy.{IdRange, LazyModule, LazyModuleImp, TransferSizes}
import freechips.rocketchip.tilelink._
import device.RAMHelper
import chisel3._
import chisel3.util._
import xiangshan.cache.dcache.SyncRegMem

import chisel3._
import chisel3.util._

class RAM_Helper(size: Int) extends Module {
  // 使用 SyncReadMem 实现同步读写内存
  val ram = SyncRegMem(size, UInt(64.W))

  // 读操作封装
  def read(enable: Bool, index: UInt): UInt = {
    Mux(enable, ram.read(index), 0.U)
  }

  // 写操作封装
  def write(enable: Bool, index: UInt, data: UInt, mask: UInt): Unit = {
    when(enable) {
      val current = ram.read(index)  // 读取当前值
      val updated = (current & ~mask) | (data & mask)  // 更新值
      ram.write(index, updated)  // 写回内存
    }
  }

  def amo_helper(enable: Bool, cmd: UInt, addr: UInt, wdata: UInt, mask: UInt) : UInt = {
    // 地址对齐检查
    when(addr % 8.U === 4.U && mask === "hf0".U) {
      addr := addr - 4.U
    }.elsewhen(addr % 8.U =/= 0.U) {
      printf(p"warning: amo address ${addr} not naturally aligned to ${mask}!!\n")
    }

    // 掩码检查
    when(mask =/= "hff".U && mask =/= "hf".U && mask =/= "hf0".U) {
      printf(p"warning: amo data mask ${mask} not aligned to 32-bit\n")
    }
    val Idx   = (addr - "h80000000".U) >> 3
    val rdata = ram.read(Idx.asUInt, true.B)
    val upperR = rdata(63, 32) // 高 32 位
    val lowerR = rdata(31, 0) // 低 32 位
    val upperW = wdata(63, 32)
    val lowerW = wdata(31, 0)

    // 根据掩码选择操作数
    val rop = Mux(mask === "hff".U, rdata,
      Mux(mask === "hf".U, lowerR, upperR))
    val wop = Mux(mask === "hff".U, wdata,
      Mux(mask === "hf".U, lowerW, upperW))

    // 原子操作逻辑
    val result = Wire(UInt(64.W))
    switch(cmd) {
      is(4.U) {
        result := wop
      } // M_XA_SWAP
      is(6.U) {
        result := rdata
      } // M_XLR
      is(7.U) {
        result := wop
      } // M_XSC
      is(8.U) {
        result := wop + rop
      } // M_XA_ADD
      is(9.U) {
        result := wop ^ rop
      } // M_XA_XOR
      is(10.U) {
        result := wop | rop
      } // M_XA_OR
      is(11.U) {
        result := wop & rop
      } // M_XA_AND
      is(12.U) {
        result := Mux(wop.asSInt > rop.asSInt, rop, wop)
      } // M_XA_MIN
      is(13.U) {
        result := Mux(wop.asSInt > rop.asSInt, wop, rop)
      } // M_XA_MAX
      is(14.U) {
        result := Mux(wop > rop, rop, wop)
      } // M_XA_MINU
      is(15.U) {
        result := Mux(wop > rop, wop, rop)
      } // M_XA_MAXU
    }

    ram.write(Idx.asUInt, result)
    Mux(mask === "hf0".U, rop << 32, rop).asUInt
  }
}

class FakeDCache_Fv()(implicit p: Parameters) extends XSModule with HasDCacheParameters{
  val io = IO(new DCacheIO)

  io := DontCare
  val FakeRam = Module(new RAM_Helper(256 * 1024 * 1024 / 64))
  val RamEn   = WireInit(VecInit(Seq.fill(LoadPipelineWidth)(false.B)))
  val RamRIdx = WireInit(VecInit(Seq.fill(LoadPipelineWidth)(0.U)))
  val RamRData= WireInit(VecInit(Seq.fill(LoadPipelineWidth)(0.U)))
  // to LoadUnit
  for(i <- 0 until LoadPipelineWidth) {
    RamEn(i)   := io.lsu.load(i).resp.valid && !reset.asBool
    RamRIdx(i) := RegNext((io.lsu.load(i).s1_paddr_dup_dcache - "h80000000".U) >> 3)
    RamRData(i) := RegNext(FakeRam.read(RamEn(i), RamRIdx(i).asUInt))
    io.lsu.load(i).req.ready := true.B
    io.lsu.load(i).resp.valid := RegNext(RegNext(io.lsu.load(i).req.valid) && !io.lsu.load(i).s1_kill)
    io.lsu.load(i).resp.bits.data := RamRData
    io.lsu.load(i).resp.bits.miss := false.B
    io.lsu.load(i).resp.bits.replay := false.B
    io.lsu.load(i).resp.bits.id := DontCare
    io.lsu.load(i).s2_hit := true.B
    io.lsu.load(i).s1_disable_fast_wakeup := false.B
  }
  // to LSQ
  io.lsu.lsq.valid := false.B
  io.lsu.lsq.bits := DontCare
  // to Store Buffer
  io.lsu.store.req.ready := true.B
  io.lsu.store.main_pipe_hit_resp := DontCare
  io.lsu.store.refill_hit_resp := DontCare
  io.lsu.store.replay_resp := DontCare
  io.lsu.store.main_pipe_hit_resp.valid := RegNext(io.lsu.store.req.valid)
  io.lsu.store.main_pipe_hit_resp.bits.id := RegNext(io.lsu.store.req.bits.id)
  // to atomics
  val amo_enable = io.lsu.atomics.req.valid && !reset.asBool
  val amo_cmd    = io.lsu.atomics.req.bits.cmd
  val amo_addr   = io.lsu.atomics.req.bits.addr
  val amo_wdata  = io.lsu.atomics.req.bits.data
  val amo_mask   = io.lsu.atomics.req.bits.mask
  io.lsu.atomics.req.ready := true.B
  io.lsu.atomics.resp.valid := RegNext(io.lsu.atomics.req.valid)
  assert(!io.lsu.atomics.resp.valid || io.lsu.atomics.resp.ready)
  io.lsu.atomics.resp.bits.data := FakeRam.amo_helper(amo_enable, amo_cmd, amo_addr, amo_wdata, amo_mask)
  io.lsu.atomics.resp.bits.replay := false.B
  io.lsu.atomics.resp.bits.id := 1.U
}

class FakeDCache()(implicit p: Parameters) extends XSModule with HasDCacheParameters {
  val io = IO(new DCacheIO)

  io := DontCare
  // to LoadUnit
  for (i <- 0 until LoadPipelineWidth) {
    val fakeRAM = Module(new RAMHelper(64L * 1024 * 1024 * 1024))
    fakeRAM.clk   := clock
    fakeRAM.en    := io.lsu.load(i).resp.valid && !reset.asBool
    fakeRAM.rIdx  := RegNext((io.lsu.load(i).s1_paddr_dup_dcache - "h80000000".U) >> 3)
    fakeRAM.wIdx  := 0.U
    fakeRAM.wdata := 0.U
    fakeRAM.wmask := 0.U
    fakeRAM.wen   := false.B
    io.lsu.load(i).req.ready := true.B
    io.lsu.load(i).resp.valid := RegNext(RegNext(io.lsu.load(i).req.valid) && !io.lsu.load(i).s1_kill)
    io.lsu.load(i).resp.bits.data := fakeRAM.rdata
    io.lsu.load(i).resp.bits.miss := false.B
    io.lsu.load(i).resp.bits.replay := false.B
    io.lsu.load(i).resp.bits.id := DontCare
    io.lsu.load(i).s2_hit := true.B
    io.lsu.load(i).s1_disable_fast_wakeup := false.B
  }
  // to LSQ
  io.lsu.lsq.valid := false.B
  io.lsu.lsq.bits := DontCare
  // to Store Buffer
  io.lsu.store.req.ready := true.B
  io.lsu.store.main_pipe_hit_resp := DontCare
  io.lsu.store.refill_hit_resp := DontCare
  io.lsu.store.replay_resp := DontCare
  io.lsu.store.main_pipe_hit_resp.valid := RegNext(io.lsu.store.req.valid)
  io.lsu.store.main_pipe_hit_resp.bits.id := RegNext(io.lsu.store.req.bits.id)
  // to atomics
  val amoHelper = Module(new AMOHelper)
  amoHelper.clock := clock
  amoHelper.enable := io.lsu.atomics.req.valid && !reset.asBool
  amoHelper.cmd := io.lsu.atomics.req.bits.cmd
  amoHelper.addr := io.lsu.atomics.req.bits.addr
  amoHelper.wdata := io.lsu.atomics.req.bits.data
  amoHelper.mask := io.lsu.atomics.req.bits.mask
  io.lsu.atomics.req.ready := true.B
  io.lsu.atomics.resp.valid := RegNext(io.lsu.atomics.req.valid)
  assert(!io.lsu.atomics.resp.valid || io.lsu.atomics.resp.ready)
  io.lsu.atomics.resp.bits.data := amoHelper.rdata
  io.lsu.atomics.resp.bits.replay := false.B
  io.lsu.atomics.resp.bits.id := 1.U
}
// We don't need to fullfill the interface. We only need to meet the function.