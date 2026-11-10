package dev.bartershop.config;

import dev.bartershop.core.model.Shop;
import dev.bartershop.core.model.TradeOffer;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Validated, immutable-at-runtime settings. */
public final class Settings {

    private final String signMarker;
    private final int maxShopsPerPlayer;
    private final int maxOfferAmount;
    private final int maxOffersPerShop;
    private final int maxChestsPerShop;
    private final boolean allowOwnerTrade;
    private final boolean allowCreativeTrades;
    private final boolean notifyOnJoin;
    private final long lockTimeoutMillis;
    private final long signClaimExpiryMinutes;
    private final int notificationItemTypes;
    private final String databaseFile;
    private final List<String> allowedWorlds;
    private final Language defaultLanguage;
    private final boolean maintenance;
    private final int defaultLowStockThreshold;
    private final long transferExpiryMinutes;
    private final long clickDebounceMillis;
    private final int maxClicksPerSecond;
    private final int maxTradesPerMinute;
    private final int maxOfferChangesPerMinute;
    private final int maxTransferRequestsPerHour;
    private final List<Long> offerExpiryHours;

    private Settings(FileConfiguration config) {
        this.signMarker = config.getString("shop.sign-marker", "[shop]").trim();
        this.maxShopsPerPlayer = clamp(config.getInt("shop.max-shops-per-player", 5), 1, 1000);
        this.maxOfferAmount = clamp(config.getInt("shop.max-offer-amount", TradeOffer.MAX_AMOUNT),
                1, TradeOffer.MAX_AMOUNT);
        this.maxOffersPerShop = clamp(config.getInt("shop.max-offers-per-shop", 24), 1, 45);
        this.maxChestsPerShop = clamp(config.getInt("shop.max-chests-per-shop", 4),
                1, Shop.MAX_CHEST_UNITS);
        this.allowOwnerTrade = config.getBoolean("shop.allow-owner-trade", false);
        this.allowCreativeTrades = config.getBoolean("shop.allow-creative-trades", false);
        this.notifyOnJoin = config.getBoolean("shop.notify-on-join", true);
        this.lockTimeoutMillis = clamp(config.getLong("shop.lock-timeout-ms", 2000L), 50L, 30000L);
        this.signClaimExpiryMinutes = clamp(config.getLong("shop.sign-claim-expiry-minutes", 10080L),
                1L, 525600L);
        this.notificationItemTypes = clamp(config.getInt("shop.notification-item-types", 5), 1, 10);
        this.databaseFile = config.getString("database.file", "shops.db");
        this.allowedWorlds = config.getStringList("shop.allowed-worlds").stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
        this.defaultLanguage = Language.parse(config.getString("language", "en"), Language.EN);
        this.maintenance = config.getBoolean("maintenance", false);
        this.defaultLowStockThreshold = clamp(config.getInt("shop.default-low-stock-threshold", 8),
                0, Shop.MAX_CAPACITY_SLOTS);
        this.transferExpiryMinutes = clamp(config.getLong("shop.transfer-expiry-minutes", 10080L),
                1L, 525600L);
        this.clickDebounceMillis = clamp(config.getLong("limits.click-debounce-ms", 120L), 0L, 2000L);
        this.maxClicksPerSecond = clamp(config.getInt("limits.max-clicks-per-second", 12), 2, 100);
        this.maxTradesPerMinute = clamp(config.getInt("limits.max-trades-per-minute", 30), 1, 600);
        this.maxOfferChangesPerMinute = clamp(config.getInt("limits.max-offer-changes-per-minute", 30),
                1, 600);
        this.maxTransferRequestsPerHour = clamp(config.getInt("limits.max-transfer-requests-per-hour", 10),
                1, 100);
        this.offerExpiryHours = parseExpiryOptions(config.getIntegerList("shop.offer-expiry-hours"));
    }

    private static List<Long> parseExpiryOptions(List<Integer> raw) {
        List<Long> options = new ArrayList<>();
        if (raw != null) {
            for (Integer hours : raw) {
                if (hours != null && hours >= 0 && !options.contains(hours.longValue())) {
                    options.add(hours.longValue());
                }
            }
        }
        if (options.isEmpty()) {
            options.addAll(List.of(0L, 1L, 24L, 168L, 720L));
        }
        return List.copyOf(options);
    }

    public static Settings load(FileConfiguration config) {
        return new Settings(config);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    public String signMarker() {
        return signMarker.isBlank() ? "[shop]" : signMarker;
    }

    public int maxShopsPerPlayer() {
        return maxShopsPerPlayer;
    }

    public int maxOfferAmount() {
        return maxOfferAmount;
    }

    public int maxOffersPerShop() {
        return maxOffersPerShop;
    }

    public int maxChestsPerShop() {
        return maxChestsPerShop;
    }

    public boolean allowOwnerTrade() {
        return allowOwnerTrade;
    }

    public boolean allowCreativeTrades() {
        return allowCreativeTrades;
    }

    public boolean notifyOnJoin() {
        return notifyOnJoin;
    }

    public long lockTimeoutMillis() {
        return lockTimeoutMillis;
    }

    public long signClaimExpiryMinutes() {
        return signClaimExpiryMinutes;
    }

    public int notificationItemTypes() {
        return notificationItemTypes;
    }

    public String databaseFile() {
        return databaseFile;
    }

    /** Empty means every world is allowed. */
    public List<String> allowedWorlds() {
        return allowedWorlds;
    }

    public Language defaultLanguage() {
        return defaultLanguage;
    }

    public boolean maintenance() {
        return maintenance;
    }

    public int defaultLowStockThreshold() {
        return defaultLowStockThreshold;
    }

    public long transferExpiryMillis() {
        return transferExpiryMinutes * 60_000L;
    }

    public long clickDebounceMillis() {
        return clickDebounceMillis;
    }

    public int maxClicksPerSecond() {
        return maxClicksPerSecond;
    }

    public int maxTradesPerMinute() {
        return maxTradesPerMinute;
    }

    public int maxOfferChangesPerMinute() {
        return maxOfferChangesPerMinute;
    }

    public int maxTransferRequestsPerHour() {
        return maxTransferRequestsPerHour;
    }

    /** Expiry presets in hours; 0 means no expiry. */
    public List<Long> offerExpiryHours() {
        return offerExpiryHours;
    }
}
