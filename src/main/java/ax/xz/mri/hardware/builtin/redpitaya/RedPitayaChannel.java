package ax.xz.mri.hardware.builtin.redpitaya;

import ax.xz.mri.hardware.builtin.redpitaya.proto.PinId;
import ax.xz.mri.model.sequence.SequenceChannel;

import java.util.List;

/**
 * The full Red Pitaya output-channel surface. Each constant is one capability
 * the plugin advertises to the studio; a track binds to it via
 * {@link SequenceChannel} on the {@code (sourceName, subIndex)} pair.
 *
 * <p>{@link #TX_I} and {@link #TX_Q} carry the baseband I/Q stream that the
 * server upconverts to the configured carrier and pushes to the chosen DAC
 * output. The 16 GPIO entries map 1:1 to librp's {@code rp_dpin_t} DIO_*
 * pins on the E1 connector; each is a 0/1 GATE and translates into
 * {@code GpioEvent} commands on level transitions.
 */
public enum RedPitayaChannel {
    // Each TX channel is its own (sourceName, subIndex=0) pair so the
    // timeline UI shows them as "rp.tx.i" / "rp.tx.q" instead of the
    // less readable "rp.tx" / "rp.tx[1]". Mirrors the rp.rx.i / rp.rx.q
    // probe naming on the receive side.
    TX_I("rp.tx.i", 0, Kind.TX_I, null),
    TX_Q("rp.tx.q", 0, Kind.TX_Q, null),

    DIO0_P("rp.dio.0_p", 0, Kind.GPIO, PinId.DIO0_P),
    DIO1_P("rp.dio.1_p", 0, Kind.GPIO, PinId.DIO1_P),
    DIO2_P("rp.dio.2_p", 0, Kind.GPIO, PinId.DIO2_P),
    DIO3_P("rp.dio.3_p", 0, Kind.GPIO, PinId.DIO3_P),
    DIO4_P("rp.dio.4_p", 0, Kind.GPIO, PinId.DIO4_P),
    DIO5_P("rp.dio.5_p", 0, Kind.GPIO, PinId.DIO5_P),
    DIO6_P("rp.dio.6_p", 0, Kind.GPIO, PinId.DIO6_P),
    DIO7_P("rp.dio.7_p", 0, Kind.GPIO, PinId.DIO7_P),
    DIO0_N("rp.dio.0_n", 0, Kind.GPIO, PinId.DIO0_N),
    DIO1_N("rp.dio.1_n", 0, Kind.GPIO, PinId.DIO1_N),
    DIO2_N("rp.dio.2_n", 0, Kind.GPIO, PinId.DIO2_N),
    DIO3_N("rp.dio.3_n", 0, Kind.GPIO, PinId.DIO3_N),
    DIO4_N("rp.dio.4_n", 0, Kind.GPIO, PinId.DIO4_N),
    DIO5_N("rp.dio.5_n", 0, Kind.GPIO, PinId.DIO5_N),
    DIO6_N("rp.dio.6_n", 0, Kind.GPIO, PinId.DIO6_N),
    DIO7_N("rp.dio.7_n", 0, Kind.GPIO, PinId.DIO7_N);

    public enum Kind { TX_I, TX_Q, GPIO }

    private final String sourceName;
    private final int subIndex;
    private final Kind kind;
    private final PinId pinId;

    RedPitayaChannel(String sourceName, int subIndex, Kind kind, PinId pinId) {
        this.sourceName = sourceName;
        this.subIndex = subIndex;
        this.kind = kind;
        this.pinId = pinId;
    }

    public SequenceChannel sequenceChannel() {
        return SequenceChannel.of(sourceName, subIndex);
    }

    public Kind kind() {
        return kind;
    }

    /** Proto pin id for GPIO channels; {@code null} for {@link #TX_I} / {@link #TX_Q}. */
    public PinId pinId() {
        return pinId;
    }

    public String defaultLabel() {
        return switch (kind) {
            case TX_I -> "TX baseband I";
            case TX_Q -> "TX baseband Q";
            case GPIO -> name();
        };
    }

    public static List<SequenceChannel> capabilityChannels() {
        return java.util.Arrays.stream(values()).map(RedPitayaChannel::sequenceChannel).toList();
    }

    public static List<RedPitayaChannel> gpioPins() {
        return java.util.Arrays.stream(values()).filter(c -> c.kind == Kind.GPIO).toList();
    }
}
