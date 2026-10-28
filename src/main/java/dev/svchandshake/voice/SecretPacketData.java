package dev.svchandshake.voice;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Payload of Simple Voice Chat's voicechat:secret packet. Only the fields that decide where the
 * client sends voice UDP are parsed; everything else is copied through untouched.
 * <pre>
 * secret(16) serverPort(int) playerUUID(16) codec(byte) mtu(int) distance(double) keepAlive(int)
 * groupsEnabled(bool) voiceHost(utf) allowRecording(bool) ...
 * </pre>
 */
public final class SecretPacketData {
    public static final int SECRET_SIZE = 16;
    private static final int PORT_OFFSET = 16;
    private static final int HOST_OFFSET = 16 + 4 + 16 + 1 + 4 + 8 + 4 + 1;

    private final byte[] data;
    private final int hostEnd;
    public final int serverPort;
    public final String voiceHost;

    public byte[] secret() {
        return Arrays.copyOf(data, SECRET_SIZE);
    }

    private SecretPacketData(byte[] data, int hostEnd, int serverPort, String voiceHost) {
        this.data = data;
        this.hostEnd = hostEnd;
        this.serverPort = serverPort;
        this.voiceHost = voiceHost;
    }

    public static SecretPacketData parse(byte[] data) {
        var buf = ByteBuffer.wrap(data);
        int port = buf.getInt(PORT_OFFSET);
        buf.position(HOST_OFFSET);
        int len = readVarInt(buf);
        if (len < 0 || len > buf.remaining()) throw new IllegalArgumentException("Bad voiceHost length " + len);
        String host = new String(data, buf.position(), len, StandardCharsets.UTF_8);
        return new SecretPacketData(data, buf.position() + len, port, host);
    }

    /** Copy of the packet with serverPort and voiceHost replaced. */
    public byte[] withAddress(int port, String host) {
        return withAddress(port, host, null);
    }

    /** Copy of the packet with serverPort, voiceHost and, if not null, the secret replaced. */
    public byte[] withAddress(int port, String host, byte[] secret) {
        byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
        var out = new ByteArrayOutputStream(data.length + hostBytes.length);
        out.write(data, 0, HOST_OFFSET);
        writeVarInt(out, hostBytes.length);
        out.writeBytes(hostBytes);
        out.write(data, hostEnd, data.length - hostEnd);
        byte[] result = out.toByteArray();
        ByteBuffer.wrap(result).putInt(PORT_OFFSET, port);
        if (secret != null) System.arraycopy(secret, 0, result, 0, SECRET_SIZE);
        return result;
    }

    /** Where an SVC client would send voice to, same rules as SVC's InitializationData.parseAddress. */
    public HostPort voiceAddress(String serverHost) {
        if (voiceHost.isEmpty()) return new HostPort(serverHost, serverPort);
        try {
            int p = Integer.parseInt(voiceHost);
            return new HostPort(serverHost, p > 0 && p <= 65535 ? p : serverPort);
        } catch (NumberFormatException ignored) {
        }
        try {
            URI uri = new URI("voicechat://" + voiceHost);
            String host = uri.getHost() != null ? uri.getHost() : serverHost;
            return new HostPort(host, uri.getPort() > 0 ? uri.getPort() : serverPort);
        } catch (Exception e) {
            return new HostPort(serverHost, serverPort);
        }
    }

    public record HostPort(String host, int port) {
        /** voiceHost string form, IPv6 literals bracketed. */
        @Override
        public String toString() {
            String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
            return h + ":" + port;
        }
    }

    static int readVarInt(ByteBuffer buf) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            byte b = buf.get();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new IllegalArgumentException("VarInt too big");
    }

    static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
