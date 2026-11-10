package dev.bartershop.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Message lookup with embedded defaults for every supported language.
 *
 * <p>Server owners can override English keys in {@code messages.yml} and German
 * keys in {@code messages_de.yml}. Keys added by an update keep working through
 * the embedded defaults. Placeholder values are escaped before insertion so a
 * player name or custom item name can never inject MiniMessage tags.</p>
 */
public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final ThreadLocal<Language> REQUEST_LANGUAGE = new ThreadLocal<>();
    /** Marks an already resolved message that may contribute formatting inside another message. */
    private static final char MARKUP_PREFIX = '\u0001';

    private final JavaPlugin plugin;
    private final Map<Language, YamlConfiguration> configs = new EnumMap<>(Language.class);
    private final Map<Language, YamlConfiguration> defaults = new EnumMap<>(Language.class);
    private final Language fallback;
    private Function<UUID, Language> resolver;

    public Messages(JavaPlugin plugin, Language fallback) {
        this.plugin = plugin;
        this.fallback = fallback;
        this.resolver = player -> fallback;
        reload();
    }

    public void setLanguageResolver(Function<UUID, Language> resolver) {
        this.resolver = resolver == null ? player -> fallback : resolver;
    }

    public void reload() {
        configs.clear();
        defaults.clear();
        for (Language language : Language.values()) {
            YamlConfiguration embedded = loadResource(defaultResourceName(language));
            defaults.put(language, embedded);
            String fileName = overrideFileName(language);
            File file = new File(plugin.getDataFolder(), fileName);
            if (!file.exists()) {
                plugin.saveResource(fileName, false);
            }
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            config.setDefaults(embedded);
            configs.put(language, config);
        }
    }

    private static String defaultResourceName(Language language) {
        return "messages_" + language.code() + ".yml";
    }

    private static String overrideFileName(Language language) {
        return language == Language.EN ? "messages.yml" : "messages_de.yml";
    }

    private YamlConfiguration loadResource(String name) {
        try (var stream = plugin.getResource(name)) {
            if (stream == null) {
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read embedded " + name + ": " + e.getMessage());
            return new YamlConfiguration();
        }
    }

    public Language language() {
        return activeLanguage();
    }

    /** Locale of the current GUI render, or the default language. */
    public Language activeLanguage() {
        Language context = REQUEST_LANGUAGE.get();
        return context == null ? fallback : context;
    }

    public Language languageFor(CommandSender sender) {
        if (sender instanceof Player player) {
            return resolver.apply(player.getUniqueId());
        }
        return fallback;
    }

    public Context contextFor(Player player) {
        return context(resolver.apply(player.getUniqueId()));
    }

    public Context context(Language language) {
        Language previous = REQUEST_LANGUAGE.get();
        REQUEST_LANGUAGE.set(language == null ? fallback : language);
        return new Context(previous);
    }

    public String raw(String key, String... placeholders) {
        return raw(activeLanguage(), key, placeholders);
    }

    public String raw(Language language, String key, String... placeholders) {
        YamlConfiguration config = configs.getOrDefault(language, configs.get(Language.EN));
        String value = config == null ? null : config.getString(key);
        if (value == null) {
            // Try the other language, then report the missing key clearly in logs.
            for (YamlConfiguration fallbackConfig : configs.values()) {
                value = fallbackConfig.getString(key);
                if (value != null) {
                    break;
                }
            }
        }
        if (value == null) {
            plugin.getLogger().warning("Missing message key: " + key);
            return "Missing message: " + key;
        }
        return substitute(value, placeholders);
    }

    /** Plain sign-safe text with formatting tags removed and placeholders inserted. */
    public String plain(String key, String... placeholders) {
        return MINI.stripTags(raw(key, placeholders));
    }

    /** Plain text in an explicit language, independent of any render context. */
    public String plain(Language language, String key, String... placeholders) {
        return MINI.stripTags(raw(language, key, placeholders));
    }

    public Component component(String key, String... placeholders) {
        String raw = raw(key, placeholders);
        try {
            return MINI.deserialize(raw);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Invalid MiniMessage in messages.yml key '" + key
                    + "': " + e.getMessage());
            return Component.text(raw.replaceAll("<[^>]+>", ""));
        }
    }

    public Component component(Language language, String key, String... placeholders) {
        try (Context ignored = context(language)) {
            return component(key, placeholders);
        }
    }

    public void send(CommandSender sender, String key, String... placeholders) {
        Language language = languageFor(sender);
        Component prefix = component(language, "prefix");
        sender.sendMessage(prefix.append(component(language, key, placeholders)));
    }

    public String prefixPlain() {
        return raw("prefix");
    }

    /**
     * Marks a value that was itself resolved from a message file, so its
     * formatting survives insertion into a parent message. Only code paths may
     * call this; user supplied names must always go through plain placeholders,
     * which are escaped.
     */
    public static String rich(String value) {
        return value == null || value.isEmpty() ? "" : MARKUP_PREFIX + value;
    }

    private static String substitute(String value, String... placeholders) {
        if (placeholders == null || placeholders.length == 0) {
            return value;
        }
        String result = value;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String key = placeholders[i];
            String source = placeholders[i + 1] == null ? "" : placeholders[i + 1];
            String replacement = !source.isEmpty() && source.charAt(0) == MARKUP_PREFIX
                    ? source.substring(1)
                    : MINI.escapeTags(source);
            result = result.replace("<" + key + ">", replacement);
        }
        return result;
    }

    /** Scoped render language used by menu titles, icons and lore. */
    public static final class Context implements AutoCloseable {
        private final Language previous;

        private Context(Language previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                REQUEST_LANGUAGE.remove();
            } else {
                REQUEST_LANGUAGE.set(previous);
            }
        }
    }
}
