package dev.bartershop.service;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.config.Messages;
import dev.bartershop.config.Settings;
import dev.bartershop.core.model.LocationKey;
import dev.bartershop.core.model.PaymentRequirement;
import dev.bartershop.core.model.Shop;
import dev.bartershop.core.model.TradeMode;
import dev.bartershop.core.model.TradeOffer;
import dev.bartershop.core.sign.SignLayout;
import dev.bartershop.core.stock.StockStorage;
import dev.bartershop.gui.ChestsMenu;
import dev.bartershop.gui.CustomerShopMenu;
import dev.bartershop.gui.Feedback;
import dev.bartershop.gui.SettingsMenu;
import dev.bartershop.gui.ShopHubMenu;
import dev.bartershop.gui.SignSetupMenu;
import dev.bartershop.service.ShopManager.CreationResult;
import dev.bartershop.util.LocationUtil;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.block.SignChangeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign entry point. A sign whose first line matches the configured marker is a
 * claim ticket for a GUI setup session; it is not a shop-creation item and no
 * item is consumed. All configuration happens in menus.
 */
public final class SignService {

    public static final long SESSION_LIFETIME_MILLIS = 30 * 60 * 1000L;

    public static final class SetupSession {
        private final UUID player;
        private final LocationKey sign;
        private LocationKey chest;
        private final long createdAt = System.currentTimeMillis();

        private SetupSession(UUID player, LocationKey sign, LocationKey chest) {
            this.player = player;
            this.sign = sign;
            this.chest = chest;
        }

        public UUID player() {
            return player;
        }

        public LocationKey sign() {
            return sign;
        }

        public LocationKey chest() {
            return chest;
        }

        public void setChest(LocationKey chest) {
            this.chest = chest;
        }

        public boolean expired() {
            return System.currentTimeMillis() - createdAt > SESSION_LIFETIME_MILLIS;
        }
    }

    private record RelinkSession(UUID shopId, long createdAt) {
    }

    private final BarterShopPlugin plugin;
    private final ShopManager shops;
    private final Settings settings;
    private final Messages messages;
    private final Map<UUID, SetupSession> setups = new ConcurrentHashMap<>();
    private final Map<UUID, RelinkSession> chestRelinks = new ConcurrentHashMap<>();
    private final Map<UUID, RelinkSession> chestAdds = new ConcurrentHashMap<>();
    private final Map<UUID, RelinkSession> signRelinks = new ConcurrentHashMap<>();
    /** Shop id → expiry timestamp already scheduled for a sign refresh. */
    private final Map<UUID, Long> scheduledExpiryRefresh = new ConcurrentHashMap<>();

    public SignService(BarterShopPlugin plugin, ShopManager shops, Settings settings, Messages messages) {
        this.plugin = plugin;
        this.shops = shops;
        this.settings = settings;
        this.messages = messages;
    }

    // -------------------------------------------------------------- sign edit

    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        LocationKey key = LocationUtil.key(block);
        Shop registered = shops.shopAtSign(key);
        if (registered == null) {
            UUID tagged = LocationUtil.shopIdAt(plugin, block);
            if (tagged != null) {
                registered = shops.byId(tagged).orElse(null);
            }
        }
        if (registered != null) {
            event.setCancelled(true);
            messages.send(player, "sign.protected");
            return;
        }
        if (event.getSide() != Side.FRONT) {
            return;
        }
        String firstLine = plain(event.line(0)).trim();
        boolean marker = isMarker(firstLine);

        RelinkSession signRelink = signRelinks.get(player.getUniqueId());
        if (marker && signRelink != null) {
            Optional<Shop> shop = shops.byId(signRelink.shopId());
            if (shop.isEmpty()) {
                signRelinks.remove(player.getUniqueId());
                messages.send(player, "error.shop-missing");
                return;
            }
            if (!shops.canManage(player, shop.get())) {
                signRelinks.remove(player.getUniqueId());
                messages.send(player, "general.no-permission");
                return;
            }
            var claim = shops.claim(key);
            if (claim.isPresent() && !claim.get().playerUuid().equals(player.getUniqueId())
                    && !player.hasPermission("bartershop.admin")) {
                messages.send(player, "sign.claimed");
                return;
            }
            if (shops.relinkSign(player, shop.get(), block)) {
                signRelinks.remove(player.getUniqueId());
                shops.deleteClaim(key);
                messages.send(player, "sign.relinked");
                Bukkit.getScheduler().runTask(plugin,
                        () -> new ShopHubMenu(plugin, player, shop.get(), false, null).open());
            } else {
                messages.send(player, "sign.relink-failed");
            }
            return;
        }

        if (!marker) {
            var claim = shops.claim(key);
            if (claim.isPresent() && claim.get().playerUuid().equals(player.getUniqueId())) {
                shops.deleteClaim(key);
                SetupSession session = setups.get(player.getUniqueId());
                if (session != null && session.sign().equals(key)) {
                    setups.remove(player.getUniqueId());
                }
            }
            return;
        }

        if (!player.hasPermission("bartershop.create") && !player.hasPermission("bartershop.admin")) {
            event.setCancelled(true);
            messages.send(player, "general.no-permission");
            return;
        }
        var existingClaim = shops.claim(key);
        if (existingClaim.isPresent()
                && !existingClaim.get().playerUuid().equals(player.getUniqueId())
                && !player.hasPermission("bartershop.admin")) {
            event.setCancelled(true);
            messages.send(player, "sign.claimed");
            return;
        }
        try {
            shops.saveClaim(new dev.bartershop.storage.StorageRecords.SignClaim(
                    key, player.getUniqueId(), player.getName(), System.currentTimeMillis()));
        } catch (RuntimeException e) {
            event.setCancelled(true);
            plugin.messages().send(player, "error.database");
            return;
        }

        Block attached = LocationUtil.attachedChest(block);
        LocationKey chestKey = null;
        if (attached != null && shops.shopAtChest(LocationUtil.key(attached)) == null) {
            chestKey = LocationUtil.key(attached);
        }
        SetupSession session = new SetupSession(player.getUniqueId(), key, chestKey);
        setups.put(player.getUniqueId(), session);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                new SignSetupMenu(plugin, player, this, session).open();
            }
        });
    }

    // ------------------------------------------------------------- sign clicks

    /** Returns true when the click was consumed by BarterShop. */
    public boolean handleSignClick(Player player, Block signBlock) {
        if (!LocationUtil.isSign(signBlock)) {
            return false;
        }
        LocationKey key = LocationUtil.key(signBlock);
        Shop shop = shops.shopAtSign(key);
        if (shop == null) {
            UUID tagged = LocationUtil.shopIdAt(plugin, signBlock);
            if (tagged != null) {
                shop = shops.byId(tagged).orElse(null);
            }
        }
        if (shop != null) {
            if (shops.canManage(player, shop)) {
                Shop finalShop = shop;
                Bukkit.getScheduler().runTask(plugin, () ->
                        new ShopHubMenu(plugin, player, finalShop,
                                player.hasPermission("bartershop.admin"), null).open());
            } else if (shop.orphaned()) {
                messages.send(player, "shop.unavailable");
            } else if (!shop.enabled()) {
                messages.send(player, "shop.disabled");
            } else if (!player.hasPermission("bartershop.use")) {
                messages.send(player, "general.no-permission");
            } else {
                Shop finalShop = shop;
                Bukkit.getScheduler().runTask(plugin, () ->
                        new CustomerShopMenu(plugin, player, finalShop, null).open());
            }
            return true;
        }

        String firstLine = LocationUtil.signLine(signBlock, 0);
        if (!isMarker(firstLine)) {
            return false;
        }
        if (!player.hasPermission("bartershop.create") && !player.hasPermission("bartershop.admin")) {
            messages.send(player, "general.no-permission");
            return true;
        }
        var claim = shops.claim(key);
        if (claim.isPresent()
                && !claim.get().playerUuid().equals(player.getUniqueId())
                && !player.hasPermission("bartershop.admin")) {
            messages.send(player, "sign.claimed");
            return true;
        }
        if (claim.isEmpty()) {
            try {
                shops.saveClaim(new dev.bartershop.storage.StorageRecords.SignClaim(
                        key, player.getUniqueId(), player.getName(), System.currentTimeMillis()));
            } catch (RuntimeException e) {
                messages.send(player, "error.database");
                return true;
            }
        }
        Block attached = LocationUtil.attachedChest(signBlock);
        LocationKey chestKey = null;
        if (attached != null && shops.shopAtChest(LocationUtil.key(attached)) == null) {
            chestKey = LocationUtil.key(attached);
        }
        SetupSession session = new SetupSession(player.getUniqueId(), key, chestKey);
        setups.put(player.getUniqueId(), session);
        new SignSetupMenu(plugin, player, this, session).open();
        return true;
    }

    // ------------------------------------------------------------ chest select

    /** Returns true when a pending chest selection consumed the click. */
    public boolean handleChestClick(Player player, Block chestBlock) {
        RelinkSession add = chestAdds.get(player.getUniqueId());
        if (add != null && sessionExpired(add)) {
            chestAdds.remove(player.getUniqueId());
            add = null;
            messages.send(player, "setup.expired");
        }
        if (add != null) {
            Shop shop = shops.byId(add.shopId()).orElse(null);
            if (shop == null) {
                chestAdds.remove(player.getUniqueId());
                messages.send(player, "error.shop-missing");
                return true;
            }
            ShopManager.RelinkFailure failure = shops.addChest(player, shop, chestBlock);
            chestAdds.remove(player.getUniqueId());
            if (failure == null) {
                messages.send(player, "shop.chest-added");
                Feedback.success(player);
            } else {
                messages.send(player, "shop.chest-add-failed");
                Feedback.error(player);
            }
            new ChestsMenu(plugin, player, shop, null).open();
            return true;
        }
        RelinkSession relink = chestRelinks.get(player.getUniqueId());
        if (relink != null && sessionExpired(relink)) {
            chestRelinks.remove(player.getUniqueId());
            relink = null;
            messages.send(player, "setup.expired");
        }
        if (relink != null) {
            Shop shop = shops.byId(relink.shopId()).orElse(null);
            if (shop == null) {
                chestRelinks.remove(player.getUniqueId());
                messages.send(player, "error.shop-missing");
                return true;
            }
            ShopManager.RelinkFailure failure = shops.relinkChest(player, shop, chestBlock);
            chestRelinks.remove(player.getUniqueId());
            if (failure == null) {
                messages.send(player, "shop.chest-relinked");
                Feedback.success(player);
            } else {
                messages.send(player, "shop.relink-failed");
                Feedback.error(player);
            }
            new ChestsMenu(plugin, player, shop, null).open();
            return true;
        }
        SetupSession session = setups.get(player.getUniqueId());
        if (session == null) {
            return false;
        }
        if (!LocationUtil.isChest(chestBlock)) {
            messages.send(player, "shop.create.invalid");
            return true;
        }
        if (shops.shopAtChest(LocationUtil.key(chestBlock)) != null) {
            messages.send(player, "shop.create.chest-occupied");
            return true;
        }
        if (!LocationUtil.chestIsEmpty(chestBlock)) {
            messages.send(player, "shop.create.chest-not-empty");
            return true;
        }
        session.setChest(LocationUtil.key(chestBlock));
        messages.send(player, "shop.chest-selected");
        new SignSetupMenu(plugin, player, this, session).open();
        return true;
    }

    public boolean cancelWithSneak(Player player) {
        boolean had = setups.remove(player.getUniqueId()) != null
                | chestRelinks.remove(player.getUniqueId()) != null
                | chestAdds.remove(player.getUniqueId()) != null
                | signRelinks.remove(player.getUniqueId()) != null;
        if (had) {
            messages.send(player, "setup.cancelled");
        }
        return had;
    }

    // ------------------------------------------------------------------ setup

    public Optional<SetupSession> session(Player player) {
        SetupSession session = setups.get(player.getUniqueId());
        if (session == null) {
            return Optional.empty();
        }
        if (session.expired()) {
            setups.remove(player.getUniqueId());
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public void beginChestSelection(Player player) {
        if (session(player).isEmpty()) {
            return;
        }
        player.closeInventory();
        messages.send(player, "setup.select-chest");
    }

    public void beginChestRelink(Player player, Shop shop) {
        if (!shops.canManage(player, shop)) {
            return;
        }
        chestRelinks.put(player.getUniqueId(), new RelinkSession(shop.id(), System.currentTimeMillis()));
        player.closeInventory();
        messages.send(player, "shop.relink-select");
    }

    public void beginChestAdd(Player player, Shop shop) {
        if (!shops.canManage(player, shop)) {
            return;
        }
        chestAdds.put(player.getUniqueId(), new RelinkSession(shop.id(), System.currentTimeMillis()));
        player.closeInventory();
        messages.send(player, "shop.chest-add-select");
    }

    public void beginSignRelink(Player player, Shop shop) {
        if (!shops.canManage(player, shop)) {
            return;
        }
        signRelinks.put(player.getUniqueId(), new RelinkSession(shop.id(), System.currentTimeMillis()));
        player.closeInventory();
        messages.send(player, "sign.relink-select");
    }

    public void cancel(Player player) {
        setups.remove(player.getUniqueId());
        messages.send(player, "setup.cancelled");
    }

    public void confirm(Player player) {
        SetupSession session = session(player).orElse(null);
        if (session == null) {
            messages.send(player, "setup.expired");
            return;
        }
        if (session.chest() == null) {
            messages.send(player, "setup.no-chest");
            return;
        }
        Block sign = LocationUtil.block(session.sign());
        Block chest = LocationUtil.block(session.chest());
        if (sign == null || chest == null) {
            messages.send(player, "setup.expired");
            return;
        }
        CreationResult result = shops.createShop(player, sign, chest);
        if (!result.success()) {
            messages.send(player, creationFailureKey(result.failure()));
            new SignSetupMenu(plugin, player, this, session).open();
            return;
        }
        setups.remove(player.getUniqueId());
        messages.send(player, "shop.created");
        new ShopHubMenu(plugin, player, result.shop(), false, null).open();
    }

    private static String creationFailureKey(ShopManager.CreationFailure failure) {
        return switch (failure) {
            case NO_PERMISSION -> "general.no-permission";
            case LIMIT_REACHED -> "shop.create.limit";
            case INVALID_SIGN, INVALID_CHEST -> "shop.create.invalid";
            case SIGN_OCCUPIED -> "shop.create.sign-occupied";
            case CHEST_OCCUPIED -> "shop.create.chest-occupied";
            case CHEST_NOT_EMPTY -> "shop.create.chest-not-empty";
            case CLAIMED_BY_OTHER -> "shop.create.claimed";
            case CAPACITY_TOO_SMALL -> "shop.create.capacity";
            case DATABASE_ERROR -> "error.database";
            case WORLD_NOT_ALLOWED -> "shop.create.world";
            case TOO_FAR -> "shop.create.too-far";
        };
    }

    // ------------------------------------------------------------ sign display

    /**
     * Writes the offer preview to the physical sign. Sign text is display only;
     * authorization always uses the tagged block and the shop registry.
     *
     * <p>Unloaded chunks are skipped rather than left with text that is newer
     * than the data behind it; {@link #refreshChunk} rewrites the sign as soon
     * as the chunk loads again, and a pending expiry schedules its own refresh.</p>
     */
    public void refresh(Shop shop) {
        if (shop == null || shop.sign() == null || !LocationUtil.isLoaded(shop.sign())) {
            return;
        }
        Block block = LocationUtil.block(shop.sign());
        if (block == null || !LocationUtil.isSign(block)) {
            return;
        }
        SignLayout.Status status;
        if (plugin.maintenanceMode()) {
            status = SignLayout.Status.MAINTENANCE;
        } else if (shop.orphaned()) {
            status = SignLayout.Status.UNAVAILABLE;
        } else if (!shop.enabled()) {
            status = SignLayout.Status.DISABLED;
        } else {
            status = SignLayout.Status.OPEN;
        }
        long now = System.currentTimeMillis();
        String[] lines = SignLayout.build(settings.signMarker(), shop.name(), status,
                offerViews(shop, now), this::label);
        LocationUtil.writeSignLines(block, lines);
        scheduleExpiryRefresh(shop);
    }

    /** Re-renders every shop sign inside a chunk that just became visible again. */
    public void refreshChunk(Chunk chunk) {
        if (chunk == null) {
            return;
        }
        String world = chunk.getWorld().getName();
        for (Shop shop : shops.all()) {
            LocationKey sign = shop.sign();
            if (sign == null || !sign.world().equalsIgnoreCase(world)) {
                continue;
            }
            if ((sign.x() >> 4) != chunk.getX() || (sign.z() >> 4) != chunk.getZ()) {
                continue;
            }
            refresh(shop);
        }
    }

    /**
     * Sign text always uses the configured default language, not the language of
     * the player whose action triggered the refresh, so every visitor reads the
     * same words.
     */
    private String label(String key, String... placeholders) {
        return messages.plain(settings.defaultLanguage(), key, placeholders);
    }

    private List<SignLayout.OfferView> offerViews(Shop shop, long now) {
        List<SignLayout.OfferView> views = new ArrayList<>();
        for (TradeOffer offer : shop.offers()) {
            views.add(new SignLayout.OfferView(
                    offer.mode() == TradeMode.SELL,
                    offer.payments().size(),
                    offer.payAmount(),
                    offer.payItem().prettyName(),
                    offer.receiveAmount(),
                    offer.receiveName(),
                    inStock(shop, offer),
                    offer.isExpired(now)));
        }
        return views;
    }

    /**
     * Mirrors the trade engine's availability rules without mutating shop state,
     * so a sign never advertises a trade that would fail on stock.
     */
    private static boolean inStock(Shop shop, TradeOffer offer) {
        if (shop.stock().count(offer.receiveItem(), true) < offer.receiveAmount()) {
            return false;
        }
        if (offer.mode() == TradeMode.BUY) {
            StockStorage stock = shop.stock().copy();
            if (!stock.remove(offer.receiveItem(), offer.receiveAmount(), true)) {
                return false;
            }
            for (PaymentRequirement payment : offer.payments()) {
                if (stock.add(payment.item(), payment.amount()) > 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Refreshes a sign the moment its last active offer expires, so an expired
     * offer is never presented as tradable until the next periodic scan.
     */
    private void scheduleExpiryRefresh(Shop shop) {
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        for (TradeOffer offer : shop.offers()) {
            if (offer.hasExpiry() && offer.expiresAt() > now) {
                next = Math.min(next, offer.expiresAt());
            }
        }
        if (next == Long.MAX_VALUE) {
            scheduledExpiryRefresh.remove(shop.id());
            return;
        }
        Long pending = scheduledExpiryRefresh.get(shop.id());
        if (pending != null && pending == next) {
            return;
        }
        scheduledExpiryRefresh.put(shop.id(), next);
        long delayMillis = Math.max(1_000L, next - now + 1_000L);
        long ticks = Math.max(20L, Math.min(Integer.MAX_VALUE, delayMillis / 50L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            scheduledExpiryRefresh.remove(shop.id());
            if (shops.byId(shop.id()).isPresent()) {
                refresh(shop);
            }
        }, ticks);
    }

    public void refreshAll() {
        for (Shop shop : shops.all()) {
            refresh(shop);
        }
    }

    // -------------------------------------------------------------- lifecycle

    public void onQuit(Player player) {
        setups.remove(player.getUniqueId());
        chestRelinks.remove(player.getUniqueId());
        chestAdds.remove(player.getUniqueId());
        signRelinks.remove(player.getUniqueId());
    }

    public void onSignBroken(Player player, Block signBlock) {
        if (LocationUtil.isSign(signBlock)) {
            LocationKey key = LocationUtil.key(signBlock);
            var claim = shops.claim(key);
            if (claim.isPresent() && (claim.get().playerUuid().equals(player.getUniqueId())
                    || player.hasPermission("bartershop.admin"))) {
                shops.deleteClaim(key);
                setups.remove(player.getUniqueId());
            }
        }
    }

    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        setups.entrySet().removeIf(entry -> entry.getValue().expired());
        chestRelinks.entrySet().removeIf(entry -> now - entry.getValue().createdAt() > SESSION_LIFETIME_MILLIS);
        chestAdds.entrySet().removeIf(entry -> now - entry.getValue().createdAt() > SESSION_LIFETIME_MILLIS);
        signRelinks.entrySet().removeIf(entry -> now - entry.getValue().createdAt() > SESSION_LIFETIME_MILLIS);
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(component);
    }

    private static boolean sessionExpired(RelinkSession session) {
        return System.currentTimeMillis() - session.createdAt() > SESSION_LIFETIME_MILLIS;
    }

    public boolean isMarker(String line) {
        if (line == null) {
            return false;
        }
        String cleaned = line.replaceAll("§.", "").trim();
        return cleaned.equalsIgnoreCase(settings.signMarker());
    }

    public boolean hasSignRelink(Player player) {
        return signRelinks.containsKey(player.getUniqueId());
    }

}
