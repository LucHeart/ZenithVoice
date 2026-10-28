package lucheart.zenithvoice;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import lucheart.zenithvoice.command.ZenithVoiceCommand;
import lucheart.zenithvoice.module.ZenithVoiceModule;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Simple Voice Chat through ZenithProxy: passes the force_voice_chat check and relays voice to players and spectators",
    authors = {"luca"},
    mcVersions = {BuildConstants.MC_VERSION}
)
public class ZenithVoicePlugin implements ZenithProxyPlugin {
    public static ZenithVoiceConfig PLUGIN_CONFIG;
    public static ComponentLogger LOG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        PLUGIN_CONFIG = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, ZenithVoiceConfig.class);
        pluginAPI.registerModule(new ZenithVoiceModule());
        pluginAPI.registerCommand(new ZenithVoiceCommand());
        LOG.info("ZenithVoice loaded");
    }
}
