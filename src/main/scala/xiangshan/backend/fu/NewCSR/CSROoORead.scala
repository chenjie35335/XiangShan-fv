package xiangshan.backend.fu.NewCSR

import freechips.rocketchip.rocket.CSRs

object CSROoORead {
  /**
   * "Read only" CSRs that can be fully pipelined when read in CSRR instruction.
   * Only read by csr instructions.
   */
  val notRoCsrrAddr = List(
    CSRs.fflags,
    CSRs.fcsr,
    CSRs.vstart,
    CSRs.vxsat,
    CSRs.vcsr,
    CSRs.sstatus,
    CSRs.vsstatus,
    CSRs.mstatus,
    CSRs.hstatus,
    CSRs.mnstatus,
    CSRs.dcsr,
  )
}
