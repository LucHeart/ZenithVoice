package dev.svchandshake.voice;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;

import static dev.svchandshake.VoiceChatHandshakePlugin.LOG;

/**
 * Forwards Simple Voice Chat UDP between the player and the backend server.
 * SVC packets are encrypted end to end with the per-player secret, so this only shuffles bytes.
 * Upstream always goes out from the same socket, so the server keeps seeing one stable address
 * even when the player reconnects to the proxy.
 */
public final class VoiceRelay {
    private DatagramSocket listen;
    private DatagramSocket upstream;
    private String boundTo;
    private volatile InetSocketAddress target;
    private volatile InetSocketAddress client;
    private volatile boolean serverAnswered;

    public synchronized void start(String bindAddress, int port) throws IOException {
        String key = bindAddress + ":" + port;
        if (listen != null && key.equals(boundTo)) return;
        stop();
        var bind = bindAddress.isEmpty() ? new InetSocketAddress(port) : new InetSocketAddress(bindAddress, port);
        listen = new DatagramSocket(bind);
        upstream = new DatagramSocket();
        boundTo = key;
        pump("svc-relay-in", listen, upstream, true);
        pump("svc-relay-out", upstream, listen, false);
        LOG.info("Voice chat relay listening on UDP {}", listen.getLocalSocketAddress());
    }

    public synchronized void stop() {
        if (listen != null) listen.close();
        if (upstream != null) upstream.close();
        listen = upstream = null;
        boundTo = null;
        client = null;
    }

    public synchronized boolean isRunning() {
        return listen != null;
    }

    public void setTarget(InetSocketAddress target) {
        this.target = target;
    }

    /** fromPlayer: packets arriving on the listen socket go to the server, the rest go back to the player. */
    private void pump(String name, DatagramSocket in, DatagramSocket out, boolean fromPlayer) {
        Thread.ofVirtual().name(name).start(() -> {
            var packet = new DatagramPacket(new byte[65535], 65535);
            while (!in.isClosed()) {
                try {
                    packet.setLength(65535);
                    in.receive(packet);
                    InetSocketAddress dest;
                    if (fromPlayer) {
                        var from = (InetSocketAddress) packet.getSocketAddress();
                        if (!from.equals(client)) {
                            client = from;
                            serverAnswered = false;
                            LOG.info("Voice chat relay: player connected from {}, forwarding to {}", from, target);
                        }
                        dest = target;
                        if (dest == null) LOG.warn("Voice chat relay: got a packet from the player but no server target is known yet");
                    } else {
                        dest = client;
                        if (!serverAnswered) {
                            serverAnswered = true;
                            LOG.info("Voice chat relay: server {} answered", packet.getSocketAddress());
                        }
                    }
                    if (dest == null) continue;
                    out.send(new DatagramPacket(packet.getData(), packet.getOffset(), packet.getLength(), dest));
                } catch (SocketException e) {
                    if (!in.isClosed()) LOG.warn("Voice chat relay socket error", e);
                } catch (IOException e) {
                    LOG.debug("Voice chat relay send failed", e);
                }
            }
        });
    }
}
