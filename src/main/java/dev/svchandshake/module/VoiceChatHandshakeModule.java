package dev.svchandshake.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.event.client.ClientConfigurationEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientOnlineEvent;
import com.zenith.module.api.Module;
import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.PacketHandlerCodec;
import com.zenith.network.codec.PacketHandlerStateCodec;
import com.zenith.network.server.ServerSession;
import dev.svchandshake.HandshakeConfig.VoiceMode;
import dev.svchandshake.voice.SecretPacketData;
import dev.svchandshake.voice.VoiceRelay;
import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.EXECUTOR;
import static com.zenith.Globals.saveConfigAsync;
import static dev.svchandshake.VoiceChatHandshakePlugin.PLUGIN_CONFIG;

/**
 * Simple Voice Chat's force_voice_chat check only looks at whether the client sent
 * voicechat:request_secret with the server's compatibility version within login_timeout.
 * This module sends that packet and listens for voicechat:secret to confirm it was accepted.
 * <p>
 * The secret also tells the client where to send voice UDP. With an empty voice host that is the
 * address the client connected to, i.e. the proxy, so the packet is rewritten on its way to the
 * player to point at the relay (or directly at the server).
 * <p>
 * SVC only hands out one secret per login, and the proxy usually asks before a player joins, so the
 * player's own request would be ignored by the server. The secret is kept and answered locally instead.
 */
public class VoiceChatHandshakeModule extends Module {
    private static final Key REGISTER = Key.key("minecraft", "register");
    private static final Key REQUEST_SECRET = Key.key("voicechat", "request_secret");
    private static final Key SECRET = Key.key("voicechat", "secret");

    // bumped on every new attempt / disconnect so stale scheduled probes do nothing
    private final AtomicInteger generation = new AtomicInteger();
    private volatile boolean accepted = false;
    private volatile int lastSentVersion = -1;
    private final VoiceRelay relay = new VoiceRelay();
    // raw secret payload from the server for the current login, replayed to players who join later
    private volatile byte[] lastSecret;
    private volatile String lastServerHost;

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientOnlineEvent.class, e -> startHandshake(PLUGIN_CONFIG.autoProbe)),
            // backend switch on a Velocity network -> new backend wants its own handshake.
            // Only resend the known-good version here: probing wrong versions on a server that
            // already accepted us would overwrite our stored compatibility there.
            of(ClientConfigurationEvent.Exited.class, e -> startHandshake(false)),
            of(ClientDisconnectEvent.class, e -> {
                reset();
                forgetSecret();
            })
        );
    }

    @Override
    public PacketHandlerCodec registerClientPacketHandlerCodec() {
        return PacketHandlerCodec.clientBuilder()
            .setId("voicechat-handshake")
            .setPriority(1000)
            .state(ProtocolState.GAME, PacketHandlerStateCodec.clientBuilder()
                .inbound(ClientboundCustomPayloadPacket.class, this::onCustomPayload)
                .build())
            .build();
    }

    @Override
    public PacketHandlerCodec registerServerPacketHandlerCodec() {
        return PacketHandlerCodec.serverBuilder()
            .setId("voicechat-handshake")
            .setPriority(1000)
            .state(ProtocolState.GAME, PacketHandlerStateCodec.serverBuilder()
                .inbound(ServerboundCustomPayloadPacket.class, this::onPlayerCustomPayload)
                .outbound(ClientboundCustomPayloadPacket.class, this::onSendToPlayer)
                .build())
            .build();
    }

    @Override
    public void onEnable() {
        applyVoiceMode();
    }

    @Override
    public void onDisable() {
        reset();
        forgetSecret();
        relay.stop();
    }

    /** Starts or stops the relay to match the config. Call after changing voice settings. */
    public void applyVoiceMode() {
        if (!isEnabled() || PLUGIN_CONFIG.voiceMode != VoiceMode.RELAY) {
            relay.stop();
            return;
        }
        try {
            relay.start(PLUGIN_CONFIG.relayBindAddress, PLUGIN_CONFIG.relayPort);
        } catch (Exception e) {
            error("Failed to start voice chat relay on UDP port {}", PLUGIN_CONFIG.relayPort, e);
        }
    }

    public boolean isRelayRunning() {
        return relay.isRunning();
    }

    private ClientboundCustomPayloadPacket onCustomPayload(ClientboundCustomPayloadPacket packet, ClientSession session) {
        if (!SECRET.equals(packet.getChannel())) return packet;
        if (!accepted) {
            accepted = true;
            generation.incrementAndGet(); // stop probing
            int v = lastSentVersion;
            info("Server accepted voice chat handshake (compatibility version {})", v);
            if (v > 0 && PLUGIN_CONFIG.compatibilityVersion != v) {
                PLUGIN_CONFIG.compatibilityVersion = v;
                saveConfigAsync();
            }
        }
        lastSecret = packet.getData();
        lastServerHost = session.getHost();
        return new ClientboundCustomPayloadPacket(SECRET, rewriteVoiceAddress(packet.getData(), session.getHost()));
    }

    /**
     * The player's client asking for its secret: answer from the stored one, the server would ignore a second request.
     * Spectator packets never reach the server, so for them the stored secret is the only way to get voice.
     */
    private ServerboundCustomPayloadPacket onPlayerCustomPayload(ServerboundCustomPayloadPacket packet, ServerSession session) {
        if (!REQUEST_SECRET.equals(packet.getChannel())) return packet;
        boolean spectator = session.isSpectator();
        if (spectator && !PLUGIN_CONFIG.spectatorVoice) return null;
        byte[] secret = lastSecret;
        if (secret == null) {
            debug("Player requested voice chat secret before the proxy had one, forwarding to server");
            return packet;
        }
        int clientVersion = packet.getData().length >= 4 ? ByteBuffer.wrap(packet.getData()).getInt() : -1;
        if (lastSentVersion > 0 && clientVersion != lastSentVersion) {
            warn("Your Simple Voice Chat uses compatibility version {} but the server was answered with {} - update the mod to match the server", clientVersion, lastSentVersion);
        }
        info("Answering {}'s voice chat secret request", spectator ? "spectator" : "player");
        session.sendAsync(new ClientboundCustomPayloadPacket(SECRET, rewriteVoiceAddress(secret, lastServerHost)));
        return null;
    }

    /** Secrets the server pushes (e.g. after a voice timeout) are forwarded to every session, keep them from spectators if disabled. */
    private ClientboundCustomPayloadPacket onSendToPlayer(ClientboundCustomPayloadPacket packet, ServerSession session) {
        if (SECRET.equals(packet.getChannel()) && session.isSpectator() && !PLUGIN_CONFIG.spectatorVoice) return null;
        return packet;
    }

    private byte[] rewriteVoiceAddress(byte[] data, String serverHost) {
        VoiceMode mode = PLUGIN_CONFIG.voiceMode;
        if (mode == VoiceMode.OFF) return data;
        SecretPacketData secret;
        try {
            secret = SecretPacketData.parse(data);
        } catch (Exception e) {
            warn("Could not parse voicechat:secret, forwarding it unchanged", e);
            return data;
        }
        SecretPacketData.HostPort server = secret.voiceAddress(serverHost);

        if (mode == VoiceMode.DIRECT) {
            info("Voice chat: pointing client directly at {}", server);
            return secret.withAddress(server.port(), server.toString());
        }

        // resolve off the network thread, the client retries authentication until the relay forwards it
        EXECUTOR.execute(() -> {
            try {
                relay.setTarget(new InetSocketAddress(server.host(), server.port()));
            } catch (Exception e) {
                error("Failed to resolve voice chat server {}", server, e);
            }
        });
        if (!relay.isRunning()) applyVoiceMode();
        int port = PLUGIN_CONFIG.relayPort;
        // a bare port keeps the host the client connected to the proxy with
        String host = PLUGIN_CONFIG.relayPublicHost.isEmpty()
            ? String.valueOf(port)
            : new SecretPacketData.HostPort(PLUGIN_CONFIG.relayPublicHost, port).toString();
        info("Voice chat: relaying UDP port {} -> {}", port, server);
        return secret.withAddress(port, host);
    }

    private void reset() {
        generation.incrementAndGet();
        accepted = false;
        lastSentVersion = -1;
    }

    // the secret stays valid for the whole login (a backend switch just sends a new one), so only drop it on disconnect
    private void forgetSecret() {
        lastSecret = null;
        lastServerHost = null;
    }

    private void startHandshake(boolean probe) {
        reset();
        final int gen = generation.get();

        // configured version first, then the remaining probe versions (no duplicates)
        var order = new LinkedHashSet<Integer>();
        order.add(PLUGIN_CONFIG.compatibilityVersion);
        if (probe) order.addAll(PLUGIN_CONFIG.probeVersions);
        final List<Integer> versions = List.copyOf(order);

        EXECUTOR.schedule(() -> attempt(gen, versions, 0),
            Math.max(0, PLUGIN_CONFIG.initialDelayMs), TimeUnit.MILLISECONDS);
    }

    private void attempt(int gen, List<Integer> versions, int index) {
        if (gen != generation.get() || accepted) return;
        if (index >= versions.size()) {
            warn("No voice chat secret received after trying versions {} - server may use a different version or a different check", versions);
            return;
        }
        int version = versions.get(index);
        lastSentVersion = version;

        // tell the server we can receive voicechat:secret (needed on Spigot/Bukkit), then request it
        byte[] register = SECRET.asString().getBytes(StandardCharsets.UTF_8);
        byte[] request = ByteBuffer.allocate(4).putInt(version).array(); // big-endian int, same as FriendlyByteBuf.writeInt
        sendClientPacketsAsync(
            new ServerboundCustomPayloadPacket(REGISTER, register),
            new ServerboundCustomPayloadPacket(REQUEST_SECRET, request)
        );
        debug("Sent voicechat:request_secret with compatibility version {}", version);

        EXECUTOR.schedule(() -> attempt(gen, versions, index + 1),
            Math.max(250, PLUGIN_CONFIG.probeTimeoutMs), TimeUnit.MILLISECONDS);
    }
}
