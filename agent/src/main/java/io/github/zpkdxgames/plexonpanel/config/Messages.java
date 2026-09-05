package io.github.zpkdxgames.plexonpanel.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;

public final class Messages {
    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private volatile YamlConfiguration configuration;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        configuration = YamlConfiguration.loadConfiguration(file);
    }

    public Component render(String key) {
        return render(key, Map.of());
    }

    public Component render(String key, Map<String, String> placeholders) {
        String prefix = configuration.getString("prefix", "");
        String template = configuration.getString(key, "<red>Missing message: " + key + "</red>");
        TagResolver.Builder resolver = TagResolver.builder();
        placeholders.forEach((name, value) -> resolver.resolver(Placeholder.unparsed(name, value)));
        return miniMessage.deserialize(prefix + template, resolver.build());
    }

    public void send(CommandSender sender, String key) {
        sender.sendMessage(render(key));
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(render(key, placeholders));
    }
}
