package dev.svchandshake.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import dev.svchandshake.HandshakeConfig.VoiceMode;
import dev.svchandshake.module.VoiceChatHandshakeModule;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static dev.svchandshake.VoiceChatHandshakePlugin.PLUGIN_CONFIG;

public class VoiceChatHandshakeCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("voiceChatHandshake")
            .category(CommandCategory.MODULE)
            .description("""
                Answers Simple Voice Chat's login check so servers with force_voice_chat don't kick the proxy.
                Version 18 = SVC 2.5.x, 20 = SVC 2.6.x. With autoProbe on, the working version is detected and saved.
                
                voice: how a player connected to the proxy gets voice chat.
                relay = UDP goes through the proxy on relayPort (open that UDP port). Everyone hears,
                the controlling player talks (or the first spectator in voice if there is none).
                direct = the client connects straight to the server's voice port. Only the controlling
                player, or a spectator alone on the proxy, gets voice.
                off = untouched, voice usually won't connect.
                
                keepListening (relay): spectators keep hearing while the controlling player has voice
                chat disabled. Off = the controlling player disabling voice cuts it for everyone.
                """)
            .usageLines(
                "on/off",
                "version <number>",
                "autoProbe on/off",
                "timeout <ms>",
                "voice off/direct/relay",
                "relayPort <port>",
                "relayHost <host>",
                "relayHost clear",
                "spectatorVoice on/off",
                "keepListening on/off"
            )
            .aliases("svc")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("voiceChatHandshake")
            .then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.enabled = getToggle(c, "toggle");
                MODULE.get(VoiceChatHandshakeModule.class).syncEnabledFromConfig();
                c.getSource().getEmbed().title("Voice Chat Handshake " + toggleStrCaps(PLUGIN_CONFIG.enabled));
            }))
            .then(literal("version").then(argument("version", integer(1)).executes(c -> {
                PLUGIN_CONFIG.compatibilityVersion = getInteger(c, "version");
                c.getSource().getEmbed().title("Compatibility Version Set");
            })))
            .then(literal("autoProbe").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.autoProbe = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Auto Probe " + toggleStrCaps(PLUGIN_CONFIG.autoProbe));
            })))
            .then(literal("timeout").then(argument("ms", integer(250, 5000)).executes(c -> {
                PLUGIN_CONFIG.probeTimeoutMs = getInteger(c, "ms");
                c.getSource().getEmbed().title("Probe Timeout Set");
            })))
            .then(literal("voice")
                .then(literal("off").executes(c -> { setVoiceMode(c, VoiceMode.OFF); }))
                .then(literal("direct").executes(c -> { setVoiceMode(c, VoiceMode.DIRECT); }))
                .then(literal("relay").executes(c -> { setVoiceMode(c, VoiceMode.RELAY); })))
            .then(literal("relayPort").then(argument("port", integer(1, 65535)).executes(c -> {
                PLUGIN_CONFIG.relayPort = getInteger(c, "port");
                MODULE.get(VoiceChatHandshakeModule.class).applyVoiceMode();
                c.getSource().getEmbed().title("Relay Port Set")
                    .description("Players need to rejoin to pick up the new port");
            })))
            .then(literal("relayHost")
                .then(literal("clear").executes(c -> {
                    PLUGIN_CONFIG.relayPublicHost = "";
                    c.getSource().getEmbed().title("Relay Host Cleared");
                }))
                .then(argument("host", string()).executes(c -> {
                    PLUGIN_CONFIG.relayPublicHost = getString(c, "host");
                    c.getSource().getEmbed().title("Relay Host Set");
                })))
            .then(literal("spectatorVoice").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.spectatorVoice = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Spectator Voice " + toggleStrCaps(PLUGIN_CONFIG.spectatorVoice));
            })))
            .then(literal("keepListening").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.keepListening = getToggle(c, "toggle");
                MODULE.get(VoiceChatHandshakeModule.class).refreshDisabledState();
                c.getSource().getEmbed().title("Keep Listening " + toggleStrCaps(PLUGIN_CONFIG.keepListening));
            })));
    }

    private void setVoiceMode(com.mojang.brigadier.context.CommandContext<CommandContext> c, VoiceMode mode) {
        PLUGIN_CONFIG.voiceMode = mode;
        MODULE.get(VoiceChatHandshakeModule.class).applyVoiceMode();
        c.getSource().getEmbed().title("Voice Mode Set");
    }

    @Override
    public void defaultEmbed(Embed embed) {
        embed
            .primaryColor()
            .addField("Enabled", toggleStr(PLUGIN_CONFIG.enabled))
            .addField("Compatibility Version", PLUGIN_CONFIG.compatibilityVersion)
            .addField("Auto Probe", toggleStr(PLUGIN_CONFIG.autoProbe))
            .addField("Probe Timeout", PLUGIN_CONFIG.probeTimeoutMs + " ms")
            .addField("Voice Mode", PLUGIN_CONFIG.voiceMode.name().toLowerCase())
            .addField("Relay", PLUGIN_CONFIG.relayPort + " (" + (MODULE.get(VoiceChatHandshakeModule.class).isRelayRunning() ? "running" : "stopped") + ")")
            .addField("Relay Host", PLUGIN_CONFIG.relayPublicHost.isEmpty() ? "proxy address" : PLUGIN_CONFIG.relayPublicHost)
            .addField("Spectator Voice", toggleStr(PLUGIN_CONFIG.spectatorVoice))
            .addField("Keep Listening", toggleStr(PLUGIN_CONFIG.keepListening));
    }
}
