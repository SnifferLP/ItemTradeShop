package dev.bartershop.service;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.config.Language;
import dev.bartershop.storage.Database;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves and persists per-player interface languages. The in-memory cache is
 * authoritative for the running server and is written through to SQLite so a
 * player keeps their choice across restarts.
 */
public final class LanguageService {

    private final BarterShopPlugin plugin;
    private final Database database;
    private final Language fallback;
    private final Map<UUID, Language> cache = new ConcurrentHashMap<>();

    public LanguageService(BarterShopPlugin plugin, Database database, Language fallback) {
        this.plugin = plugin;
        this.database = database;
        this.fallback = fallback;
    }

    public void load() {
        try {
            Map<UUID, String> stored = database.call(repository -> repository.loadPlayerLanguages());
            for (Map.Entry<UUID, String> entry : stored.entrySet()) {
                cache.put(entry.getKey(), Language.parse(entry.getValue(), fallback));
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not load player language preferences: " + e.getMessage());
        }
    }

    public Language language(UUID player) {
        if (player == null) {
            return fallback;
        }
        return cache.getOrDefault(player, fallback);
    }

    public Language fallback() {
        return fallback;
    }

    public boolean setLanguage(UUID player, Language language) {
        Language previous = cache.put(player, language);
        try {
            database.run(repository -> repository.savePlayerLanguage(player, language.name()));
            return true;
        } catch (RuntimeException e) {
            if (previous == null) {
                cache.remove(player);
            } else {
                cache.put(player, previous);
            }
            plugin.getLogger().warning("Could not save language for " + player + ": " + e.getMessage());
            return false;
        }
    }
}
