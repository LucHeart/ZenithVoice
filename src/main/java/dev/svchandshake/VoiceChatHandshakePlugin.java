package dev.svchandshake;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import dev.svchandshake.command.VoiceChatHandshakeCommand;
import dev.svchandshake.module.VoiceChatHandshakeModule;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Answers Simple Voice Chat's handshake so force_voice_chat servers don't kick the proxy",
    authors = {"luca"},
    mcVersions = {BuildConstants.MC_VERSION}
)
public class VoiceChatHandshakePlugin implements ZenithProxyPlugin {
    public static HandshakeConfig PLUGIN_CONFIG;
    public static ComponentLogger LOG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        PLUGIN_CONFIG = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, HandshakeConfig.class);
        pluginAPI.registerModule(new VoiceChatHandshakeModule());
        pluginAPI.registerCommand(new VoiceChatHandshakeCommand());
        LOG.info("VoiceChatHandshake loaded");
    }
}
