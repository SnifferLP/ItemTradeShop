package dev.bartershop.config;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalizationTest {

    @Test
    void bothLanguagesContainTheSameCompleteKeySet() {
        Set<String> english = keys(load("/messages_en.yml"));
        Set<String> german = keys(load("/messages_de.yml"));
        assertTrue(english.size() > 300, "English messages should cover the full interface");
        assertEquals(english, german, "German must provide every English message key");
    }

    @Test
    void everyMessageIsValidMiniMessage() {
        MiniMessage mini = MiniMessage.miniMessage();
        for (String file : new String[]{"/messages_en.yml", "/messages_de.yml"}) {
            YamlConfiguration config = load(file);
            for (String key : keys(config)) {
                String value = config.getString(key);
                assertNotNull(value, key);
                try {
                    mini.deserialize(value);
                } catch (RuntimeException e) {
                    throw new AssertionError("Invalid MiniMessage at " + file + ":" + key, e);
                }
            }
        }
    }

    @Test
    void bothLanguagesUseTheSamePlaceholders() {
        YamlConfiguration english = load("/messages_en.yml");
        YamlConfiguration german = load("/messages_de.yml");
        for (String key : keys(english)) {
            assertEquals(placeholders(english.getString(key)), placeholders(german.getString(key)),
                    "placeholder mismatch at " + key);
        }
    }

    @Test
    void transferLimitAndMailboxLabelsAreLocalised() {
        for (String file : new String[]{"/messages_en.yml", "/messages_de.yml"}) {
            YamlConfiguration config = load(file);
            for (String key : new String[]{
                    "transfer.limit",
                    "notify.items-more",
                    "gui.proceeds-overview.mailbox-label",
                    "gui.proceeds-overview.deleted-label",
                    "gui.offer-editor.matching-lore3"}) {
                assertNotNull(config.getString(key), file + " is missing " + key);
            }
        }
    }

    @Test
    void shopSignTextIsLocalisedInBothLanguages() {
        for (String file : new String[]{"/messages_en.yml", "/messages_de.yml"}) {
            YamlConfiguration config = load(file);
            for (String key : new String[]{
                    "sign.status.disabled",
                    "sign.status.maintenance",
                    "sign.status.unavailable",
                    "sign.status.no-offers",
                    "sign.status.expired",
                    "sign.status.out-of-stock",
                    "sign.line.offers",
                    "sign.line.items",
                    "sign.hint.sell",
                    "sign.hint.buy",
                    "sign.hint.browse",
                    "sign.hint.info"}) {
                assertNotNull(config.getString(key), file + " is missing " + key);
            }
        }
    }

    @Test
    void localizationSupportsEnglishAndGermanMetadata() {
        assertEquals("en", Language.EN.code());
        assertEquals("de", Language.DE.code());
        assertEquals(Language.DE, Language.parse("de_DE", Language.EN));
        assertEquals(Language.DE, Language.parse("German", Language.EN));
        assertEquals(Language.EN, Language.parse("fr", Language.EN));
    }

    private static YamlConfiguration load(String resource) {
        try (InputStream stream = LocalizationTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "missing resource " + resource);
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("Could not load " + resource, e);
        }
    }

    /** Placeholder names used by the plugin; every other tag is MiniMessage styling. */
    private static final Set<String> PLACEHOLDERS = Set.of(
            "amount", "remaining", "entries", "shop", "chest", "player", "count", "time",
            "limit", "owner", "world", "x", "y", "z", "used", "capacity", "items", "total",
            "shops", "language", "mode", "item", "stock", "needed", "hours", "duration",
            "stacks", "sign", "actor", "number", "slots", "max", "state", "offers",
            "threshold", "pay", "receive", "page", "trades");

    private static Set<String> placeholders(String value) {
        Set<String> names = new LinkedHashSet<>();
        if (value == null) {
            return names;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("<([a-zA-Z0-9_-]+)>").matcher(value);
        while (matcher.find()) {
            if (PLACEHOLDERS.contains(matcher.group(1))) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    private static Set<String> keys(YamlConfiguration config) {
        Set<String> keys = new LinkedHashSet<>();
        collect(config.getKeys(true), keys, config);
        return keys;
    }

    private static void collect(Iterable<String> candidates, Set<String> keys, YamlConfiguration config) {
        for (String key : candidates) {
            if (!config.isConfigurationSection(key)) {
                keys.add(key);
            }
        }
    }
}
