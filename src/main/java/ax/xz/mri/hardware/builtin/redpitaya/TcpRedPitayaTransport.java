package ax.xz.mri.hardware.builtin.redpitaya;

import ax.xz.mri.hardware.builtin.redpitaya.proto.Command;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Reply;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Production transport: a single TCP connection with 4-byte little-endian
 * length-prefix framing.
 *
 * <p>Wire format:
 * <pre>
 *   [ uint32 LE length ][ Command or Reply protobuf bytes ]
 * </pre>
 *
 * <p>The server enforces single-client semantics; opening two transports to
 * the same host concurrently is undefined.
 */
public final class TcpRedPitayaTransport implements RedPitayaTransport {

    private static final int LENGTH_BYTES = 4;
    /**
     * Hard cap on the size of a single inbound frame. RxTrace replies for a
     * full DDR3 capture can be ~hundreds of MB; cap at 512 MB so a corrupt
     * length doesn't cause an OOM.
     */
    private static final int MAX_FRAME_BYTES = 512 * 1024 * 1024;

    private final Socket socket;
    private final DataOutputStream out;
    private final DataInputStream in;

    public TcpRedPitayaTransport(String host, int port, int connectTimeoutMs) throws IOException {
        this.socket = new Socket();
        this.socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        this.socket.setTcpNoDelay(true);
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
    }

    @Override
    public void send(Command command) throws IOException {
        byte[] payload = command.toByteArray();
        writeLengthLE(out, payload.length);
        out.write(payload);
        out.flush();
    }

    @Override
    public Reply receive() throws IOException {
        int length = readLengthLE(in);
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("Refusing oversized inbound frame: " + length + " bytes");
        }
        byte[] payload = in.readNBytes(length);
        if (payload.length != length) {
            throw new EOFException("Short read: expected " + length + " bytes, got " + payload.length);
        }
        return Reply.parseFrom(payload);
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) {}
    }

    static void writeLengthLE(DataOutputStream out, int length) throws IOException {
        out.write(length & 0xFF);
        out.write((length >>> 8) & 0xFF);
        out.write((length >>> 16) & 0xFF);
        out.write((length >>> 24) & 0xFF);
    }

    static int readLengthLE(DataInputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        if ((b0 | b1 | b2 | b3) < 0) throw new EOFException("Connection closed before length prefix");
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }
}
