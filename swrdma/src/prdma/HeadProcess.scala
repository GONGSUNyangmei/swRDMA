package swrdma

import common.storage._
import common.axi._
import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum
import common.Collector
import common.connection._

class HeadProcess() extends Module{
	val io = IO(new Bundle{
		val rx_data_in          = Flipped(Decoupled(new AXIS(512)))
		val meta_out	        = (Decoupled(new Pkg_meta()))
		val reth_data_out	    = (Decoupled(new AXIS(512)))
        val aeth_data_out	    = (Decoupled(new AXIS(512)))
        val raw_data_out	    = (Decoupled(new AXIS(512)))

	})

	val pack_head_exceed = Wire(Bool())
	val fixed_len = CONFIG.IP_HEADER_LEN + CONFIG.UDP_HEADER_LEN + CONFIG.IBH_HEADER_LEN + CONFIG.SWRDMA_HEADER_LEN 
	val reth_exceed = Wire(Bool())
	val aeth_exceed = Wire(Bool())
	reth_exceed := (fixed_len.asUInt() + CONFIG.RETH_HEADER_LEN.U) > 512.U
	aeth_exceed := (fixed_len.asUInt() + CONFIG.AETH_HEADER_LEN.U) > 512.U
	val IP_HEADER_HIGH = CONFIG.IP_HEADER_LEN -1
	val UDP_HEADER_HIGH = CONFIG.IP_HEADER_LEN + CONFIG.UDP_HEADER_LEN -1
	val IBH_HEADER_HIGH = CONFIG.IP_HEADER_LEN + CONFIG.UDP_HEADER_LEN + CONFIG.IBH_HEADER_LEN -1
    val RETH_HEADER_HIGH = CONFIG.IP_HEADER_LEN + CONFIG.UDP_HEADER_LEN + CONFIG.IBH_HEADER_LEN + CONFIG.RETH_HEADER_LEN -1
	val AETH_HEADER_HIGH = CONFIG.IP_HEADER_LEN + CONFIG.UDP_HEADER_LEN + CONFIG.IBH_HEADER_LEN + CONFIG.AETH_HEADER_LEN -1
	
	val RETH_LEFT_LEN = 512 - fixed_len - CONFIG.RETH_HEADER_LEN
	val AETH_LEFT_LEN = 512 - fixed_len - CONFIG.AETH_HEADER_LEN
	var SWRDMA_HEADER_HIGH_RETH : Int =511
	if(reth_exceed == false.B){
		SWRDMA_HEADER_HIGH_RETH = CONFIG.IP_HEADER_LEN + CONFIG.UDP_HEADER_LEN + CONFIG.IBH_HEADER_LEN + CONFIG.RETH_HEADER_LEN + CONFIG.SWRDMA_HEADER_LEN -1
	}
	var SWRDMA_HEADER_HIGH_AETH : Int =511
	if(aeth_exceed == false.B){
		SWRDMA_HEADER_HIGH_AETH = CONFIG.IP_HEADER_LEN + CONFIG.UDP_HEADER_LEN + CONFIG.IBH_HEADER_LEN + CONFIG.AETH_HEADER_LEN + CONFIG.SWRDMA_HEADER_LEN -1
	}

	val IP_HEADER_LOW = 0
	val UDP_HEADER_LOW = CONFIG.IP_HEADER_LEN
	val IBH_HEADER_LOW = CONFIG.IP_HEADER_LEN + CONFIG.UDP_HEADER_LEN
	val RETH_HEADER_LOW = CONFIG.IP_HEADER_LEN + CONFIG.UDP_HEADER_LEN + CONFIG.IBH_HEADER_LEN
	val AETH_HEADER_LOW = CONFIG.IP_HEADER_LEN + CONFIG.UDP_HEADER_LEN + CONFIG.IBH_HEADER_LEN 
	var SWRDMA_HEADER_LOW_RETH = CONFIG.IP_HEADER_LEN + CONFIG.UDP_HEADER_LEN + CONFIG.IBH_HEADER_LEN + CONFIG.RETH_HEADER_LEN 
	val SWRDMA_HEADER_LOW_AETH = CONFIG.IP_HEADER_LEN + CONFIG.UDP_HEADER_LEN + CONFIG.IBH_HEADER_LEN + CONFIG.AETH_HEADER_LEN 

	val ip_header = Wire(new IP_HEADER())
	val udp_header = Wire(new UDP_HEADER())
	val ibh_header = Wire(new IBH_HEADER())
	val reth_header = Wire(new RETH_HEADER())
	val aeth_header = Wire(new AETH_HEADER())
	val swrdma_header = Wire(new SWRDMA_HEADER())

	val rx_data_fifo    = XQueue(new AXIS(512),64)
	rx_data_fifo.io.in <> io.rx_data_in

	val rx_data_out          = Decoupled(new AXIS(512))
	val rx_data_out_router  = SerialRouter(new AXIS(512),3)
	rx_data_out_router.io.in <> rx_data_out
	rx_data_out_router.io.out(0) <> io.reth_data_out
	rx_data_out_router.io.out(1) <> io.aeth_data_out
	rx_data_out_router.io.out(2) <> io.raw_data_out
	
	val meta_wire    = Wire(new Pkg_meta())
	val meta_exceed    = Wire(new Pkg_meta())
	val meta_reg  = Reg(new Pkg_meta())
	val remain_part = Wire(UInt(336.W))
	val remain_len = Wire(UInt(336.W))
	val s_head :: s_exceed :: s_payload :: Nil = Enum(3)

	val state = RegInit(s_head)

	val empty_pack :: reth_pack:: aeth_pack :: raw_pack :: Nil = Enum(4)
	val pack_class = RegInit(empty_pack)
	rx_data_out_router.io.idx :=  pack_class  //todo to be checked

	switch(state){
		is(s_head){
			when(rx_data_fifo.io.out.fire() && rx_data_fifo.io.out.bits.last =/= 1.U && pack_head_exceed){
				state := s_exceed
			}.elsewhen(rx_data_fifo.io.out.fire() && rx_data_fifo.io.out.bits.last =/= 1.U && !pack_head_exceed){
				state := s_payload
			}.otherwise{
				state := s_head
			}
		}
		is(s_exceed){
			when(rx_data_fifo.io.out.fire()&& rx_data_fifo.io.out.bits.last =/= 1.U){
				state := s_payload
			}.elsewhen(rx_data_fifo.io.out.fire()&& rx_data_fifo.io.out.bits.last === 1.U){
				state := s_head
			}.otherwise{
				state := s_exceed
			}
		}
		is(s_payload){
			when(rx_data_fifo.io.out.fire()&& rx_data_fifo.io.out.bits.last === 1.U){
				state := s_head
			}.otherwise{
				state := s_payload
			}
		}	
	}
	io.meta_out.bits := Mux(state === s_head,meta_wire,meta_exceed)
	io.meta_out.valid := ((state === s_head && !pack_head_exceed) || (state === s_exceed) )&& rx_data_out.ready && io.meta_out.ready

	rx_data_out.bits := rx_data_fifo.io.out.bits
	rx_data_out.valid :=  rx_data_fifo.io.out.valid&& ((state === s_payload )|| (state === s_head && pack_head_exceed) || (rx_data_out.ready && io.meta_out.ready))
	rx_data_fifo.io.out.ready := rx_data_out.ready && ((state === s_payload )|| (state === s_head && pack_head_exceed) || (rx_data_out.ready && io.meta_out.ready))

	when(state === s_head ){  //todo need to be checked
		when(meta_wire.op_code === IB_OP_CODE.RC_WRITE_FIRST||
			 meta_wire.op_code === IB_OP_CODE.RC_WRITE_ONLY||
			 meta_wire.op_code === IB_OP_CODE.RC_WRITE_ONLY_WIT_IMD||
			 meta_wire.op_code === IB_OP_CODE.RC_READ_REQUEST						
		){  
			pack_class := reth_pack
			pack_head_exceed := reth_exceed
		}.elsewhen(meta_wire.op_code === IB_OP_CODE.RC_READ_RESP_FIRST||
			       meta_wire.op_code === IB_OP_CODE.RC_READ_RESP_LAST||
			       meta_wire.op_code === IB_OP_CODE.RC_READ_RESP_ONLY||
			       meta_wire.op_code === IB_OP_CODE.RC_ACK){
			        // ||meta_wire.op_code === "b00010010".U	
			pack_class := aeth_pack
			pack_head_exceed := aeth_exceed
		}.elsewhen(meta_wire.op_code =/= IB_OP_CODE.reserve &&
					meta_wire.op_code =/= IB_OP_CODE.reserve0){
			pack_class := raw_pack
			pack_head_exceed := false.B
		}.otherwise{
			pack_class := empty_pack
		}
	}
	

	
	ip_header                    := rx_data_fifo.io.out.bits.data(IP_HEADER_HIGH,IP_HEADER_LOW)
	udp_header                   := rx_data_fifo.io.out.bits.data(UDP_HEADER_HIGH,UDP_HEADER_LOW)
	ibh_header                   := rx_data_fifo.io.out.bits.data(IBH_HEADER_HIGH,IBH_HEADER_LOW)
	reth_header                  := rx_data_fifo.io.out.bits.data(RETH_HEADER_HIGH,RETH_HEADER_LOW)
	aeth_header                  := rx_data_fifo.io.out.bits.data(AETH_HEADER_HIGH,AETH_HEADER_LOW)
	
	meta_wire.op_code	  := Util.reverse(ibh_header.op_code)
	meta_wire.qpn         := Util.reverse(ibh_header.qpn)
	meta_wire.psn         := Util.reverse(ibh_header.psn)
	meta_wire.ecn         := Util.reverse(ip_header.ecn === 3.U ) 
	meta_wire.vaddr       := Util.reverse(reth_header.vaddr)
	meta_wire.pkg_length  := Util.reverse(udp_header.length)
	meta_wire.msg_length  := 0.U(32.W)   //todo to be modified	
	meta_wire.user_define :=	Mux(pack_class === raw_pack,0.U,
								Mux(pack_class === reth_pack,rx_data_fifo.io.out.bits.data(SWRDMA_HEADER_HIGH_RETH,SWRDMA_HEADER_LOW_RETH),
															 rx_data_fifo.io.out.bits.data(SWRDMA_HEADER_HIGH_AETH,SWRDMA_HEADER_LOW_AETH)))

	when(state === s_head ){ //todo need to be checked
		meta_reg := meta_wire
	}

	remain_part := Mux(pack_class === reth_pack,
						rx_data_fifo.io.out.bits.data(RETH_LEFT_LEN-1,0),
						rx_data_fifo.io.out.bits.data(AETH_LEFT_LEN-1,0))
	remain_len := Mux(pack_class === reth_pack,
						RETH_LEFT_LEN.asUInt(),
						AETH_LEFT_LEN.asUInt())
	meta_exceed := meta_reg //todo to be modified
	meta_exceed.user_define := meta_reg.user_define + remain_part << (CONFIG.SWRDMA_HEADER_LEN.asUInt()-remain_len)

}