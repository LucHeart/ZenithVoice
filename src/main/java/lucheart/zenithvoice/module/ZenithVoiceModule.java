package lucheart.zenithvoice.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.event.client.ClientConfigurationEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientOnlineEvent;
import com.zenith.event.player.PlayerConnectedEvent;
import com.zenith.event.player.PlayerDisconnectedEvent;
import com.zenith.event.player.SpectatorConnectedEvent;
import com.zenith.event.player.SpectatorDisconnectedEvent;
import com.zenith.module.api.Module;
import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.PacketHandlerCodec;
import com.zenith.network.codec.PacketHandlerStateCodec;
import com.zenith.network.server.ServerSession;
import lucheart.zenithvoice.ZenithVoiceConfig.VoiceMode;
import lucheart.zenithvoice.voice.SecretPacketData;
import lucheart.zenithvoice.voice.VoiceRelay;
import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.EXECUTOR;
import static com.zenith.Globals.saveConfigAsync;
import static lucheart.zenithvoice.ZenithVoicePlugin.PLUGIN_CONFIG;

/**
 * Simple Voice Chat's force_voice_chat check only looks at whether the client sent
 * voicechat:request_secret with the server's compatibility version within login_timeout.
 * This module sends that packet and listens for voicechat:secret to confirm it was accepted.
 * <p>
 * The secret also tells the client where to send voice UDP. With an empty voice host that is the
 * address the client connected to, i.e. the proxy, so the packet is rewritten per player on its way
 * out: in relay mode to the relay with a secret of their own, in direct mode to the server.
 * <p>
 * SVC only hands out one secret per login, and the proxy usually asks before a player joins, so the
 * player's own request would be ignored by the server. The secret is kept and answered locally instead.
 */
public class ZenithVoiceModule extends Module {
    private static final Key REGISTER = Key.key("minecraft", "register");
    private static final Key REQUEST_SECRET = Key.key("voicechat", "request_secret");
    private static final Key SECRET = Key.key("voicechat", "secret");
    private static final Key UPDATE_STATE = Key.key("voicechat", "update_state");
    // let the proxy's connection lists settle before deciding who holds voice
    private static final long CONNECTION_SETTLE_MS = 500;

    // bumped on every new attempt / disconnect so stale scheduled probes do nothing
    private final AtomicInteger generation = new AtomicInteger();
    private volatile boolean accepted = false;
    private volatile int lastSentVersion = -1;
    // relay mode: who has voice chat disabled. With keepListening the server only gets "disabled" once all
    // of them are, otherwise one client turning voice off would cut the audio for everyone sharing the account
    private final Map<ServerSession, Boolean> disabledStates = new ConcurrentHashMap<>();
    private volatile Boolean sentDisabled;
    private final VoiceRelay relay = new VoiceRelay(
        () -> Proxy.getInstance().getCurrentPlayer().get(),
        // keepListening: a client with voice off hands the mic to the next one
        session -> PLUGIN_CONFIG.keepListening && disabledStates.getOrDefault(session, false),
        // without it the server stops sending audio when the controlling player disables voice,
        // so spectators shouldn't talk into a conversation they can't hear
        () -> !PLUGIN_CONFIG.keepListening && controllerDisabled());
    private final SecureRandom random = new SecureRandom();
    // raw secret payload from the server for the current login, replayed to players who join later
    private volatile byte[] lastSecret;
    private volatile String lastServerHost;
    // direct mode: the one session currently given the real secret
    private volatile ServerSession directHolder;
    // secrets already prepared for one session, sent past onSendToPlayer untouched
    private final Set<Packet> prepared = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

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
            }),
            of(PlayerConnectedEvent.class, e -> connectionsChanged()),
            of(SpectatorConnectedEvent.class, e -> connectionsChanged()),
            of(PlayerDisconnectedEvent.class, e -> sessionClosed(e.session())),
            of(SpectatorDisconnectedEvent.class, e -> sessionClosed(e.serverSession()))
        );
    }

    @Override
    public PacketHandlerCodec registerClientPacketHandlerCodec() {
        return PacketHandlerCodec.clientBuilder()
            .setId("zenith-voice")
            .setPriority(1000)
            .state(ProtocolState.GAME, PacketHandlerStateCodec.clientBuilder()
                .inbound(ClientboundCustomPayloadPacket.class, this::onCustomPayload)
                .build())
            .build();
    }

    @Override
    public PacketHandlerCodec registerServerPacketHandlerCodec() {
        return PacketHandlerCodec.serverBuilder()
            .setId("zenith-voice")
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
        } else {
            try {
                relay.start(PLUGIN_CONFIG.relayBindAddress, PLUGIN_CONFIG.relayPort);
            } catch (Exception e) {
                error("Failed to start voice chat relay on UDP port {}", PLUGIN_CONFIG.relayPort, e);
            }
        }
        connectionsChanged();
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
        storeSecret(packet.getData(), session.getHost());
        // forwarded as is, onSendToPlayer rewrites it for each player
        return packet;
    }

    /**
     * The player's client asking for its secret: answer from the stored one, the server would ignore a second request.
     * Spectator packets never reach the server, so for them the stored secret is the only way to get voice.
     */
    private ServerboundCustomPayloadPacket onPlayerCustomPayload(ServerboundCustomPayloadPacket packet, ServerSession session) {
        if (UPDATE_STATE.equals(packet.getChannel()) && PLUGIN_CONFIG.voiceMode == VoiceMode.RELAY) {
            disabledStates.put(session, packet.getData().length > 0 && packet.getData()[0] != 0);
            refreshDisabledState();
            return null;
        }
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
        session.sendAsync(new ClientboundCustomPayloadPacket(SECRET, secret));
        return null;
    }

    /** Every secret on its way to a player or spectator, from the server or replayed: rewrite it for that session. */
    private ClientboundCustomPayloadPacket onSendToPlayer(ClientboundCustomPayloadPacket packet, ServerSession session) {
        if (prepared.remove(packet) || !SECRET.equals(packet.getChannel())) return packet;
        VoiceMode mode = PLUGIN_CONFIG.voiceMode;
        if (mode == VoiceMode.OFF) return packet;
        if (session.isSpectator() && !PLUGIN_CONFIG.spectatorVoice) return null;
        SecretPacketData secret;
        try {
            secret = SecretPacketData.parse(packet.getData());
        } catch (Exception e) {
            warn("Could not parse voicechat:secret, forwarding it unchanged", e);
            return packet;
        }
        SecretPacketData.HostPort server = secret.voiceAddress(lastServerHost != null ? lastServerHost : "");

        if (mode == VoiceMode.DIRECT) {
            if (session != voiceHolder()) return null;
            directHolder = session;
            info("Voice chat: pointing {} directly at {}", session.getName(), server);
            return new ClientboundCustomPayloadPacket(SECRET, secret.withAddress(server.port(), server.toString()));
        }

        if (!relay.isRunning()) applyVoiceMode();
        int port = PLUGIN_CONFIG.relayPort;
        // a bare port keeps the host the client connected to the proxy with
        String host = PLUGIN_CONFIG.relayPublicHost.isEmpty()
            ? String.valueOf(port)
            : new SecretPacketData.HostPort(PLUGIN_CONFIG.relayPublicHost, port).toString();
        info("Voice chat: relaying {} over UDP port {} -> {}", session.getName(), port, server);
        return new ClientboundCustomPayloadPacket(SECRET, secret.withAddress(port, host, relay.secretFor(session)));
    }

    private void storeSecret(byte[] data, String serverHost) {
        lastSecret = data;
        lastServerHost = serverHost;
        if (PLUGIN_CONFIG.voiceMode != VoiceMode.RELAY) return;
        try {
            var secret = SecretPacketData.parse(data);
            relay.setRealSecret(secret.secret());
            SecretPacketData.HostPort server = secret.voiceAddress(serverHost);
            // resolve off the network thread, clients retry authentication until the relay forwards it
            EXECUTOR.execute(() -> {
                try {
                    relay.setTarget(new InetSocketAddress(server.host(), server.port()));
                } catch (Exception e) {
                    error("Failed to resolve voice chat server {}", server, e);
                }
            });
        } catch (Exception e) {
            warn("Could not parse voicechat:secret, relay not updated", e);
        }
    }

    /**
     * Direct mode can only have one client per account on the server: the controlling player, or a
     * spectator who is alone on the proxy. Null when nobody should have voice.
     */
    private ServerSession voiceHolder() {
        ServerSession player = Proxy.getInstance().getCurrentPlayer().get();
        if (player != null) return player;
        if (!PLUGIN_CONFIG.spectatorVoice) return null;
        List<ServerSession> spectators = Proxy.getInstance().getSpectatorConnections();
        return spectators.size() == 1 ? spectators.getFirst() : null;
    }

    private boolean controllerDisabled() {
        ServerSession player = Proxy.getInstance().getCurrentPlayer().get();
        return player != null && disabledStates.getOrDefault(player, false);
    }

    /**
     * Send the account's voice disabled state to the server: with keepListening only once every client
     * on the proxy has it disabled, otherwise whatever the controlling player has. Call after changing keepListening.
     */
    public void refreshDisabledState() {
        Boolean disabled;
        if (PLUGIN_CONFIG.keepListening) {
            disabled = disabledStates.isEmpty() ? null : !disabledStates.containsValue(false);
        } else {
            ServerSession player = Proxy.getInstance().getCurrentPlayer().get();
            disabled = player == null ? null : disabledStates.get(player);
        }
        if (disabled == null || Objects.equals(sentDisabled, disabled)) return;
        sentDisabled = disabled;
        debug("Voice chat {} for the account", disabled ? "disabled" : "enabled");
        sendClientPacketsAsync(new ServerboundCustomPayloadPacket(UPDATE_STATE, new byte[]{(byte) (disabled ? 1 : 0)}));
    }

    private void sessionClosed(ServerSession session) {
        disabledStates.remove(session);
        refreshDisabledState();
        relay.remove(session);
        if (directHolder == session) directHolder = null;
        connectionsChanged();
    }

    /** Direct mode: when who holds voice changes, cut off the old holder and hand the secret to the new one. */
    private void connectionsChanged() {
        EXECUTOR.schedule(() -> {
            if (!isEnabled() || PLUGIN_CONFIG.voiceMode != VoiceMode.DIRECT) {
                directHolder = null;
                return;
            }
            ServerSession old = directHolder;
            ServerSession holder = voiceHolder();
            if (holder == old) return;
            directHolder = holder;
            if (old != null && old.isConnected()) {
                info("Voice chat: disconnecting {} from voice", old.getName());
                revokeVoice(old);
            }
            // the server keeps the old client's address until it times out (~10s), then sends a new secret on its own
            byte[] secret = lastSecret;
            if (holder != null && secret != null) holder.sendAsync(new ClientboundCustomPayloadPacket(SECRET, secret));
        }, CONNECTION_SETTLE_MS, TimeUnit.MILLISECONDS);
    }

    /** A secret that can't authenticate, pointing nowhere: the client drops its voice connection and gives up. */
    private void revokeVoice(ServerSession session) {
        byte[] secret = lastSecret;
        if (secret == null) return;
        try {
            byte[] junk = new byte[SecretPacketData.SECRET_SIZE];
            random.nextBytes(junk);
            var packet = new ClientboundCustomPayloadPacket(SECRET, SecretPacketData.parse(secret).withAddress(1, "127.0.0.1:1", junk));
            prepared.add(packet);
            session.sendAsync(packet);
        } catch (Exception e) {
            warn("Could not disconnect {} from voice", session.getName(), e);
        }
    }

    private void reset() {
        generation.incrementAndGet();
        accepted = false;
        lastSentVersion = -1;
    }

    // the secret stays valid for the whole login (a backend switch just sends a new one), so only drop it on disconnect
    private void forgetSecret() {
        sentDisabled = null;
        lastSecret = null;
        lastServerHost = null;
        directHolder = null;
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
