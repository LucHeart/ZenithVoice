package dev.svchandshake;

import java.util.ArrayList;
import java.util.List;

public class HandshakeConfig {
    public boolean enabled = true;
    /** SVC compatibility version sent first. 18 = SVC 2.5.x, 20 = SVC 2.6.x. Auto-updated when a probe succeeds. */
    public int compatibilityVersion = 20;
    /** If the server doesn't answer, try the other known versions too. */
    public boolean autoProbe = true;
    public List<Integer> probeVersions = new ArrayList<>(List.of(20, 18, 19));
    /** Delay after joining before the first request (ms). */
    public int initialDelayMs = 500;
    /** How long to wait for the server's secret before trying the next version (ms). */
    public int probeTimeoutMs = 2000;

    /** How a player connected to the proxy reaches the server's voice chat. */
    public VoiceMode voiceMode = VoiceMode.RELAY;
    /** UDP port the relay listens on. The player's client must be able to reach it. */
    public int relayPort = 24454;
    /** Local address the relay binds to, empty = all interfaces. */
    public String relayBindAddress = "";
    /** Host players use to reach the relay, empty = the address they connected to the proxy with. */
    public String relayPublicHost = "";
    /** Give spectators the account's voice secret too, so they speak and hear as the account. */
    public boolean spectatorVoice = true;
    /**
     * Relay mode: the server only gets "voice disabled" once every client on the proxy has disabled it,
     * so spectators keep hearing while the controlling player has voice off. Off = the controlling
     * player's setting goes straight to the server, like plain SVC.
     */
    public boolean keepListening = false;

    public enum VoiceMode {
        /** Don't touch the secret packet. */
        OFF,
        /** Point the player's client straight at the server's voice port. */
        DIRECT,
        /** Forward voice UDP through the proxy. */
        RELAY
    }
}
