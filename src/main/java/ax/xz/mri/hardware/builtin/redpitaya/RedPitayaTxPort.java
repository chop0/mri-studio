package ax.xz.mri.hardware.builtin.redpitaya;

import ax.xz.mri.hardware.builtin.redpitaya.proto.TxOutput;

public enum RedPitayaTxPort {
    OUT1(TxOutput.TX_OUT1),
    OUT2(TxOutput.TX_OUT2);

    private final TxOutput proto;

    RedPitayaTxPort(TxOutput proto) {
        this.proto = proto;
    }

    public TxOutput proto() {
        return proto;
    }

    public static RedPitayaTxPort fromProto(TxOutput value) {
        return switch (value) {
            case TX_OUT1 -> OUT1;
            case TX_OUT2 -> OUT2;
            case TX_OUTPUT_UNSPECIFIED, UNRECOGNIZED -> OUT1;
        };
    }
}
