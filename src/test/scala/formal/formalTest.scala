package formal

import chisel3._
import chisel3._
import chiseltest._
import chiseltest.formal._
import org.scalatest.flatspec.AnyFlatSpec
import chipsalliance.rocketchip.config.{Config, Parameters}
import chisel3.stage.ChiselGeneratorAnnotation
import chisel3._
import device.{AXI4RAMWrapper, SimJTAG}
import freechips.rocketchip.diplomacy.{DisableMonitors, LazyModule, LazyModuleImp}
import utils.GTimer
import top.XSTop
import xiangshan.{DebugOptions, DebugOptionsKey, XSCore}
import chipsalliance.rocketchip.config._
import freechips.rocketchip.devices.debug._
import difftest._
import freechips.rocketchip.util.ElaborationArtefacts
import top.MinimalConfig

class XiangshanFormalSpec extends AnyFlatSpec with Formal with ChiselScalatestTester{
  behavior of "XiangshanFormal"
  it should "pass" in {
    println("Begin Xiangshan Formal Verification")
    val config = new MinimalConfig(1)
    verify(DisableMonitors(p => LazyModule(new XSTop()(p)))(config).core_with_l2.head.core.module, Seq(BoundedCheck(22), BtormcEngineAnnotation))
  }
}