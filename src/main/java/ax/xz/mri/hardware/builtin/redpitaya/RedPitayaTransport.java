package ax.xz.mri.hardware.builtin.redpitaya;

import ax.xz.mri.hardware.builtin.redpitaya.proto.Command;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Reply;

import java.io.IOException;

/**
 * Send {@link Command}s and receive {@link Reply}s from one Red Pitaya
 * server. The interface exists so {@link RedPitayaDevice} can be unit-tested
 * against an in-memory fake instead of mocking sockets; the production
 * implementation is {@link TcpRedPitayaTransport}.
 *
 * <p>Threading: implementations are <em>not</em> required to be thread-safe.
 * Each transport instance is owned by one caller (typically one
 * {@link RedPitayaDevice} per run, or the diagnostics poller).
 */
public interface RedPitayaTransport extends AutoCloseable {

    /** Encode and write one command. Blocks on TCP backpressure. */
    void send(Command command) throws IOException;

    /** Read the next reply. Blocks until a full frame arrives or the socket closes. */
    Reply receive() throws IOException;

    @Override
    void close();
}
