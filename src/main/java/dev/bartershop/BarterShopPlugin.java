package dev.bartershop;

import dev.bartershop.command.ShopCommand;
import dev.bartershop.config.Language;
import dev.bartershop.config.Messages;
import dev.bartershop.config.Settings;
import dev.bartershop.core.model.Shop;
import dev.bartershop.gui.MenuListener;
import dev.bartershop.listener.PlayerListener;
import dev.bartershop.listener.ProtectionListener;
import dev.bartershop.listener.SignDisplayListener;
import dev.bartershop.listener.SignListener;
import dev.bartershop.service.LanguageService;
import dev.bartershop.service.NotificationService;
import dev.bartershop.service.ProceedsService;
import dev.bartershop.service.ShopManager;
import dev.bartershop.service.SignService;
import dev.bartershop.service.TradeService;
import dev.bartershop.storage.Database;
import dev.bartershop.storage.ShopRepository;
import dev.bartershop.storage.StorageRecords.AuditEntry;
import dev.bartershop.storage.StorageRecords.JournalRecord;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BarterShop: GUI-first, item-for-item chest shops.
 *
 * <p>All item-moving operations run on the server thread with a per-shop lock;
 * JDBC work is serialised on a dedicated database thread. A recovery journal is
 * written before inventory mutation and applied on the next startup if the
 * process stops mid-transaction.</p>
 */
public final class BarterShopPlugin extends JavaPlugin {

    private Settings settings;
    private Messages messages;
    private Database database;
    private ShopManager shopManager;
    private TradeService tradeService;
    private ProceedsService proceedsService;
    private NotificationService notificationService;
    private SignService signService;
    private LanguageService languageService;
    private MenuListener menuListener;
    private NamespacedKey shopKey;
    private final AtomicBoolean maintenance = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(getConfig());
        if (settings.signMarker().length() > dev.bartershop.core.sign.SignLayout.LINE_LENGTH) {
            getLogger().warning("shop.sign-marker is longer than "
                    + dev.bartershop.core.sign.SignLayout.LINE_LENGTH
                    + " characters and will not fit on a sign: " + settings.signMarker());
        }
        maintenance.set(settings.maintenance());
        messages = new Messages(this, settings.defaultLanguage());
        shopKey = new NamespacedKey(this, "shop_id");

        try {
            Path file = getDataFolder().toPath().resolve(settings.databaseFile());
            database = new Database(file);
        } catch (SQLException | RuntimeException e) {
            getLogger().severe("Could not open the BarterShop database: " + e.getMessage());
            getLogger().severe("The plugin will disable itself to avoid unsafe item handling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        recoverJournals();

        languageService = new LanguageService(this, database, settings.defaultLanguage());
        languageService.load();
        messages.setLanguageResolver(this::languageFor);

        shopManager = new ShopManager(this, database, settings, messages);
        try {
            shopManager.load();
        } catch (RuntimeException e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Could not load shops", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        tradeService = new TradeService(this, database, settings, messages);
        proceedsService = new ProceedsService(this, database, messages);
        notificationService = new NotificationService(this, database, messages, settings);
        signService = new SignService(this, shopManager, settings, messages);
        shopManager.setSignService(signService);

        menuListener = new MenuListener(this);
        getServer().getPluginManager().registerEvents(menuListener, this);
        getServer().getPluginManager().registerEvents(new ProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new SignListener(this), this);
        getServer().getPluginManager().registerEvents(new SignDisplayListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        PluginCommand shopCommand = getCommand("shop");
        if (shopCommand != null) {
            ShopCommand executor = new ShopCommand(this);
            shopCommand.setExecutor(executor);
            shopCommand.setTabCompleter(executor);
        }
        PluginCommand adminCommand = getCommand("shopadmin");
        if (adminCommand != null) {
            ShopCommand executor = new ShopCommand(this);
            adminCommand.setExecutor(executor);
            adminCommand.setTabCompleter(executor);
        }

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (signService != null) {
                signService.cleanupExpired();
            }
            scanAnchorsAndNotify();
            expireTransferRequests();
            if (signService != null) {
                signService.refreshAll();
            }
        }, 20L * 60L, 20L * 60L * 5L);
        Bukkit.getScheduler().runTask(this, () -> {
            scanAnchorsAndNotify();
            if (signService != null) {
                signService.refreshAll();
            }
        });

        getLogger().info("BarterShop enabled with " + shopManager.all().size()
                + " shops. Database: " + settings.databaseFile());
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.close();
            database = null;
        }
        getLogger().info("BarterShop disabled.");
    }

    /**
     * Marks shops whose anchors were removed by something outside the plugin
     * (WorldEdit, console, another plugin) and tells an online owner. Registered
     * anchors are protected by events, so this is the safety net for changes no
     * Bukkit event can see.
     */
    private void scanAnchorsAndNotify() {
        if (shopManager == null) {
            return;
        }
        java.util.Map<UUID, Boolean> before = new java.util.HashMap<>();
        for (Shop shop : shopManager.all()) {
            before.put(shop.id(), shop.orphaned());
        }
        shopManager.scanOrphans();
        for (Shop shop : shopManager.all()) {
            if (!shop.orphaned() || Boolean.TRUE.equals(before.get(shop.id()))) {
                continue;
            }
            Player owner = Bukkit.getPlayer(shop.owner());
            if (owner != null) {
                messages.send(owner, "shop.orphaned-notify",
                        "shop", shop.name(),
                        "chest", shop.primaryChest().asString());
            }
            audit(null, "SYSTEM", "SHOP_ORPHANED",
                    shop.id() + " owner=" + shop.owner() + " chest=" + shop.primaryChest().asString());
        }
    }

    private void recoverJournals() {
        try {
            List<JournalRecord> journals = database.call(ShopRepository::loadJournals);
            if (journals.isEmpty()) {
                return;
            }
            getLogger().warning("Found " + journals.size()
                    + " interrupted transaction journal(s); recovering before shops load.");
            for (JournalRecord journal : journals) {
                try {
                    String note = database.call(repository -> repository.recoverJournal(journal));
                    getLogger().warning("Recovery: " + note);
                    audit(null, "SYSTEM", "RECOVERY", note);
                } catch (RuntimeException e) {
                    getLogger().severe("Could not recover journal " + journal.id() + ": " + e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            getLogger().severe("Could not read recovery journals: " + e.getMessage());
        }
    }

    public void audit(UUID actor, String actorName, String action, String details) {
        getLogger().info("[AUDIT] " + actorName + " " + action + ": " + details);
        if (database == null) {
            return;
        }
        database.callAsync(repository -> {
            repository.addAudit(AuditEntry.create(actor, actorName, action, details));
            return null;
        }).exceptionally(throwable -> {
            getLogger().warning("Could not write audit entry: " + throwable.getMessage());
            return null;
        });
    }

    public boolean isMainThread() {
        return Bukkit.isPrimaryThread();
    }

    public void reloadMessagesAndConfig() {
        reloadConfig();
        messages.reload();
        if (shopManager != null) {
            // Sign text is rendered from message files, so it must be rebuilt too.
            shopManager.refreshSigns();
        }
    }

    /** Global trading switch. Collection and shop management stay available. */
    public boolean maintenanceMode() {
        return maintenance.get();
    }

    public void setMaintenanceMode(boolean enabled, String actorName) {
        maintenance.set(enabled);
        getConfig().set("maintenance", enabled);
        saveConfig();
        if (shopManager != null) {
            shopManager.refreshSigns();
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            messages.send(online, enabled ? "maintenance.enabled" : "maintenance.disabled");
        }
        audit(null, actorName == null ? "SYSTEM" : actorName,
                enabled ? "MAINTENANCE_ON" : "MAINTENANCE_OFF", "global trading switch changed");
    }

    public Language languageFor(UUID player) {
        return languageService == null ? settings.defaultLanguage() : languageService.language(player);
    }

    public LanguageService languages() {
        return languageService;
    }

    private void expireTransferRequests() {
        if (database == null || shopManager == null) {
            return;
        }
        database.callAsync(repository -> repository.deleteExpiredTransferRequests(System.currentTimeMillis()))
                .exceptionally(throwable -> {
                    getLogger().warning("Could not prune expired transfer requests: " + throwable.getMessage());
                    return null;
                });
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public Database database() {
        return database;
    }

    public ShopManager shopManager() {
        return shopManager;
    }

    public TradeService tradeService() {
        return tradeService;
    }

    public ProceedsService proceedsService() {
        return proceedsService;
    }

    public NotificationService notificationService() {
        return notificationService;
    }

    public SignService signService() {
        return signService;
    }

    public MenuListener menuListener() {
        return menuListener;
    }

    public NamespacedKey shopKey() {
        return shopKey;
    }
}
