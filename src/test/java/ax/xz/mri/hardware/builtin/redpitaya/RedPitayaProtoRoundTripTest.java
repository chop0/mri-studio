package ax.xz.mri.hardware.builtin.redpitaya;

import ax.xz.mri.hardware.builtin.redpitaya.proto.Abort;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Ack;
import ax.xz.mri.hardware.builtin.redpitaya.proto.BeginRun;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Command;
import ax.xz.mri.hardware.builtin.redpitaya.proto.DeviceFact;
import ax.xz.mri.hardware.builtin.redpitaya.proto.DiagReply;
import ax.xz.mri.hardware.builtin.redpitaya.proto.EndRun;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Error;
import ax.xz.mri.hardware.builtin.redpitaya.proto.ErrorCode;
import ax.xz.mri.hardware.builtin.redpitaya.proto.GetDiag;
import ax.xz.mri.hardware.builtin.redpitaya.proto.GpioEvent;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Hello;
import ax.xz.mri.hardware.builtin.redpitaya.proto.HelloAck;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Metric;
import ax.xz.mri.hardware.builtin.redpitaya.proto.ParamValue;
import ax.xz.mri.hardware.builtin.redpitaya.proto.PinId;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Reply;
import ax.xz.mri.hardware.builtin.redpitaya.proto.RunResult;
import ax.xz.mri.hardware.builtin.redpitaya.proto.RunSetup;
import ax.xz.mri.hardware.builtin.redpitaya.proto.RxTrace;
import ax.xz.mri.hardware.builtin.redpitaya.proto.TxBlock;
import ax.xz.mri.hardware.builtin.redpitaya.proto.TxOutput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Each {@link Command} and {@link Reply} oneof variant survives a
 * serialise / parse round-trip with structural equality. This is the v1
 * wire-format guarantee — the schema is the contract; bytes are an
 * implementation detail.
 */
class RedPitayaProtoRoundTripTest {

    @Test
    void helloRoundTrip() throws Exception {
        var c = Command.newBuilder().setHello(Hello.newBuilder()
            .setClientProtoMajor(1).setClientProtoMinor(0).build()).build();
        assertEquals(c, Command.parseFrom(c.toByteArray()));
    }

    @Test
    void beginRunRoundTrip() throws Exception {
        var setup = RunSetup.newBuilder()
            .setTxCarrierHz(21.3e6).setRxCarrierHz(21.3e6)
            .setDecimation(8).setTxOutput(TxOutput.TX_OUT1).setTxGain(0.5f)
            .setRxGatePin(PinId.DIO0_P).build();
        var c = Command.newBuilder().setBeginRun(BeginRun.newBuilder()
            .setSetup(setup).setTotalSamples(1024).setDtSeconds(64e-9)
            .addChannelSlotIds("rp.tx[0]").addChannelSlotIds("rp.tx[1]")
            .build()).build();
        assertEquals(c, Command.parseFrom(c.toByteArray()));
    }

    @Test
    void txBlockRoundTrip() throws Exception {
        var block = TxBlock.newBuilder();
        for (int i = 0; i < 4096; i++) {
            block.addI((float) Math.sin(i * 0.01));
            block.addQ((float) Math.cos(i * 0.01));
        }
        var c = Command.newBuilder().setTxBlock(block.build()).build();
        var parsed = Command.parseFrom(c.toByteArray());
        assertEquals(c, parsed);
        assertEquals(4096, parsed.getTxBlock().getICount());
    }

    @Test
    void gpioEventRoundTrip() throws Exception {
        var c = Command.newBuilder().setGpioEvent(GpioEvent.newBuilder()
            .setSampleIndex(12345).setPin(PinId.DIO3_N).setState(true).build()).build();
        assertEquals(c, Command.parseFrom(c.toByteArray()));
    }

    @Test
    void endRunAbortGetDiagRoundTrip() throws Exception {
        var end   = Command.newBuilder().setEndRun(EndRun.getDefaultInstance()).build();
        var abort = Command.newBuilder().setAbort(Abort.getDefaultInstance()).build();
        var diag  = Command.newBuilder().setGetDiag(GetDiag.getDefaultInstance()).build();
        assertEquals(end,   Command.parseFrom(end.toByteArray()));
        assertEquals(abort, Command.parseFrom(abort.toByteArray()));
        assertEquals(diag,  Command.parseFrom(diag.toByteArray()));
    }

    @Test
    void helloAckReplyRoundTrip() throws Exception {
        var r = Reply.newBuilder().setHelloAck(HelloAck.newBuilder()
            .setServerProtoMajor(1).setServerProtoMinor(0).setGitSha("deadbeef").setBuildUnixTime(1714000000L)
            .build()).build();
        assertEquals(r, Reply.parseFrom(r.toByteArray()));
    }

    @Test
    void ackErrorReplyRoundTrip() throws Exception {
        var ack = Reply.newBuilder().setAck(Ack.newBuilder().setNote("queued").build()).build();
        var err = Reply.newBuilder().setError(Error.newBuilder()
            .setCode(ErrorCode.ERR_VERSION_MISMATCH).setMessage("client=2 server=1").build()).build();
        assertEquals(ack, Reply.parseFrom(ack.toByteArray()));
        assertEquals(err, Reply.parseFrom(err.toByteArray()));
    }

    @Test
    void runResultReplyRoundTrip() throws Exception {
        var rx = RxTrace.newBuilder().setFirstSampleUs(200).setSamplePeriodUs(0.064);
        for (int i = 0; i < 1024; i++) {
            rx.addI(0.01f * i);
            rx.addQ(-0.01f * i);
        }
        var res = RunResult.newBuilder().setRx(rx.build())
            .addMetrics(Metric.newBuilder().setKey("tx_samples_sent").setValue(ParamValue.newBuilder().setI(4096).build()).build())
            .addMetrics(Metric.newBuilder().setKey("dac_underruns").setValue(ParamValue.newBuilder().setI(0).build()).build())
            .build();
        var r = Reply.newBuilder().setRunResult(res).build();
        assertEquals(r, Reply.parseFrom(r.toByteArray()));
    }

    @Test
    void diagReplyRoundTrip() throws Exception {
        var d = DiagReply.newBuilder()
            .addFacts(DeviceFact.newBuilder().setKey("model").setValue(ParamValue.newBuilder().setS("STEMlab 125-14").build()).build())
            .addFacts(DeviceFact.newBuilder().setKey("cpu_temp_c").setValue(ParamValue.newBuilder().setD(48.5).build()).build())
            .addFacts(DeviceFact.newBuilder().setKey("uptime_s").setValue(ParamValue.newBuilder().setI(3600).build()).build())
            .build();
        var r = Reply.newBuilder().setDiagReply(d).build();
        assertEquals(r, Reply.parseFrom(r.toByteArray()));
    }
}
