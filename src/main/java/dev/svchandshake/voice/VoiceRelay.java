package dev.svchandshake.voice;

import com.zenith.network.server.ServerSession;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static dev.svchandshake.VoiceChatHandshakePlugin.LOG;

/**
 * Forwards Simple Voice Chat UDP between the players on the proxy and the backend server.
 * <p>
 * The server only knows the account, so it sees one voice client: this relay. Every player and
 * spectator gets their own secret instead of the real one, which lets the relay tell them apart:
 * <ul>
 *   <li>server -> players: decrypted once, re-encrypted for everyone, so all of them hear</li>
 *   <li>players -> server: re-encrypted with the real secret, but mic audio only from the speaker
 *   (the controlling player, otherwise the spectator who connected first; clients with voice chat
 *   disabled are skipped)</li>
 * </ul>
 * Upstream always goes out from the same socket, so the server keeps seeing one stable address.
 */
public final class VoiceRelay {
    private static final byte MAGIC = (byte) 0xFF;
    private static final byte MIC_PACKET = 0x1;
    private static final byte AUTHENTICATE_PACKET = 0x5;
    // AuthenticatePacket: type(1) playerUUID(16) secret(16)
    private static final int AUTH_SECRET_OFFSET = 1 + 16;
    private static final int IV_SIZE = 12;
    private static final int TAG_BITS = 128;
    // keepalives arrive every second, a client that stayed quiet this long has left voice
    private static final long ACTIVE_MS = 5000;

    private final SecureRandom random = new SecureRandom();
    private final Supplier<ServerSession> controller;
    private final Predicate<ServerSession> disabled;
    private final BooleanSupplier nobodyTalks;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private DatagramSocket listen;
    private DatagramSocket upstream;
    private String boundTo;
    private volatile InetSocketAddress target;
    private volatile byte[] realSecret;
    private volatile SecretKeySpec realKey;
    private volatile boolean serverAnswered;

    private static final class Client {
        final ServerSession session;
        final byte[] secret;
        final SecretKeySpec key;
        volatile InetSocketAddress address;
        volatile long lastSeen;

        Client(ServerSession session, byte[] secret) {
            this.session = session;
            this.secret = secret;
            this.key = new SecretKeySpec(secret, "AES");
        }

        boolean active(long now) {
            return address != null && now - lastSeen < ACTIVE_MS;
        }
    }

    /**
     * @param disabled    clients skipped when picking who talks
     * @param nobodyTalks true while all mic audio should be dropped
     */
    public VoiceRelay(Supplier<ServerSession> controller, Predicate<ServerSession> disabled, BooleanSupplier nobodyTalks) {
        this.controller = controller;
        this.disabled = disabled;
        this.nobodyTalks = nobodyTalks;
    }

    public synchronized void start(String bindAddress, int port) throws IOException {
        String key = bindAddress + ":" + port;
        if (listen != null && key.equals(boundTo)) return;
        stop();
        var bind = bindAddress.isEmpty() ? new InetSocketAddress(port) : new InetSocketAddress(bindAddress, port);
        listen = new DatagramSocket(bind);
        upstream = new DatagramSocket();
        boundTo = key;
        Thread.ofVirtual().name("svc-relay-in").start(() -> pump(listen, true));
        Thread.ofVirtual().name("svc-relay-out").start(() -> pump(upstream, false));
        LOG.info("Voice chat relay listening on UDP {}", listen.getLocalSocketAddress());
    }

    public synchronized void stop() {
        if (listen != null) listen.close();
        if (upstream != null) upstream.close();
        listen = upstream = null;
        boundTo = null;
        for (Client c : clients) c.address = null;
    }

    public synchronized boolean isRunning() {
        return listen != null;
    }

    public void setTarget(InetSocketAddress target) {
        this.target = target;
    }

    /** The secret the server handed out, used for everything sent upstream. */
    public void setRealSecret(byte[] secret) {
        if (Arrays.equals(secret, realSecret)) return;
        realSecret = secret;
        realKey = new SecretKeySpec(secret, "AES");
        serverAnswered = false;
    }

    /** The secret this session's client should use, created on first use. */
    public byte[] secretFor(ServerSession session) {
        for (Client c : clients) {
            if (c.session == session) return c.secret;
        }
        byte[] secret = new byte[SecretPacketData.SECRET_SIZE];
        random.nextBytes(secret);
        clients.add(new Client(session, secret));
        return secret;
    }

    public void remove(ServerSession session) {
        clients.removeIf(c -> c.session == session);
    }

    private void pump(DatagramSocket in, boolean fromPlayer) {
        var packet = new DatagramPacket(new byte[65535], 65535);
        while (!in.isClosed()) {
            try {
                packet.setLength(65535);
                in.receive(packet);
                var buf = ByteBuffer.wrap(packet.getData(), packet.getOffset(), packet.getLength());
                if (fromPlayer) fromPlayer(buf, (InetSocketAddress) packet.getSocketAddress());
                else fromServer(buf);
            } catch (SocketException e) {
                if (!in.isClosed()) LOG.warn("Voice chat relay socket error", e);
            } catch (Exception e) {
                LOG.debug("Voice chat relay dropped a packet", e);
            }
        }
    }

    // player frame: magic, playerUUID(16), byteArray(iv + ciphertext)
    private void fromPlayer(ByteBuffer buf, InetSocketAddress from) throws IOException {
        SecretKeySpec real = realKey;
        InetSocketAddress dest = target;
        if (real == null || dest == null || buf.get() != MAGIC) return;
        byte[] uuid = new byte[16];
        buf.get(uuid);
        byte[] payload = readByteArray(buf);

        // the client sending from this address is the likely match, otherwise try everyone's secret
        Client sender = null;
        byte[] plain = null;
        for (Client c : clients) {
            if (from.equals(c.address) && (plain = decrypt(c.key, payload)) != null) {
                sender = c;
                break;
            }
        }
        if (sender == null) {
            for (Client c : clients) {
                if ((plain = decrypt(c.key, payload)) != null) {
                    sender = c;
                    break;
                }
            }
        }
        if (sender == null || plain.length == 0) return;

        long now = System.currentTimeMillis();
        if (!from.equals(sender.address)) {
            LOG.info("Voice chat relay: {} connected from {}, forwarding to {}", sender.session.getName(), from, dest);
        }
        sender.address = from;
        sender.lastSeen = now;

        if (plain[0] == MIC_PACKET && sender != speaker(now)) return;
        if (plain[0] == AUTHENTICATE_PACKET && plain.length >= AUTH_SECRET_OFFSET + realSecret.length) {
            System.arraycopy(realSecret, 0, plain, AUTH_SECRET_OFFSET, realSecret.length);
        }

        var out = new ByteArrayOutputStream(plain.length + 64);
        out.write(MAGIC);
        out.writeBytes(uuid);
        writeByteArray(out, encrypt(real, plain));
        byte[] frame = out.toByteArray();
        upstream.send(new DatagramPacket(frame, frame.length, dest));
    }

    // server frame: magic, byteArray(iv + ciphertext)
    private void fromServer(ByteBuffer buf) throws IOException {
        SecretKeySpec real = realKey;
        if (real == null || buf.get() != MAGIC) return;
        byte[] plain = decrypt(real, readByteArray(buf));
        if (plain == null) return;
        if (!serverAnswered) {
            serverAnswered = true;
            LOG.info("Voice chat relay: server {} answered", target);
        }
        for (Client c : clients) {
            InetSocketAddress addr = c.address;
            if (addr == null) continue;
            var out = new ByteArrayOutputStream(plain.length + 64);
            out.write(MAGIC);
            writeByteArray(out, encrypt(c.key, plain));
            byte[] frame = out.toByteArray();
            listen.send(new DatagramPacket(frame, frame.length, addr));
        }
    }

    /** The controlling player if they're in voice, otherwise whoever joined voice first. */
    private Client speaker(long now) {
        if (nobodyTalks.getAsBoolean()) return null;
        ServerSession ctrl = controller.get();
        Client first = null;
        for (Client c : clients) {
            if (!c.active(now) || disabled.test(c.session)) continue;
            if (c.session == ctrl) return c;
            if (first == null) first = c;
        }
        return first;
    }

    private static byte[] readByteArray(ByteBuffer buf) {
        int len = SecretPacketData.readVarInt(buf);
        if (len < IV_SIZE || len > buf.remaining()) throw new IllegalArgumentException("Bad payload length " + len);
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return bytes;
    }

    private static void writeByteArray(ByteArrayOutputStream out, byte[] bytes) {
        SecretPacketData.writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    // same scheme as SVC's Secret: AES-GCM, 12 byte IV prepended
    private static byte[] decrypt(SecretKeySpec key, byte[] payload) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload, 0, IV_SIZE));
            return cipher.doFinal(payload, IV_SIZE, payload.length - IV_SIZE);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    private byte[] encrypt(SecretKeySpec key, byte[] plain) {
        try {
            byte[] iv = new byte[IV_SIZE];
            random.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] enc = cipher.doFinal(plain);
            byte[] payload = new byte[IV_SIZE + enc.length];
            System.arraycopy(iv, 0, payload, 0, IV_SIZE);
            System.arraycopy(enc, 0, payload, IV_SIZE, enc.length);
            return payload;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
