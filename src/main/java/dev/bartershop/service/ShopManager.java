package dev.bartershop.service;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.config.Messages;
import dev.bartershop.config.Settings;
import dev.bartershop.core.inv.InventoryModel;
import dev.bartershop.core.item.ItemData;
import dev.bartershop.core.model.LocationKey;
import dev.bartershop.core.model.Shop;
import dev.bartershop.core.model.ShopStats;
import dev.bartershop.core.model.StockStack;
import dev.bartershop.core.model.TradeOffer;
import dev.bartershop.core.proceeds.ProceedsStorage;
import dev.bartershop.core.stock.StockStorage;
import dev.bartershop.storage.Database;
import dev.bartershop.storage.ShopRepository;
import dev.bartershop.storage.StorageRecords.HistoryEntry;
import dev.bartershop.storage.StorageRecords.LoadedShops;
import dev.bartershop.storage.StorageRecords.SignClaim;
import dev.bartershop.storage.StorageRecords.TransferRequest;
import dev.bartershop.util.ItemCodec;
import dev.bartershop.util.LocationUtil;
import dev.bartershop.util.PlayerInventoryAdapter;
import dev.bartershop.util.RateLimiter;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns the live shop registry and all shop lifecycle operations. Every public
 * mutation re-checks ownership on the server side; GUI visibility is never
 * treated as authorisation.
 */
public final class ShopManager {

    private final BarterShopPlugin plugin;
    private final Database database;
    private final Settings settings;
    private final Messages messages;

    private final Map<UUID, Shop> byId = new ConcurrentHashMap<>();
    private final Map<LocationKey, Shop> byChest = new ConcurrentHashMap<>();
    private final Map<LocationKey, Shop> bySign = new ConcurrentHashMap<>();
    private final Map<LocationKey, Shop> bySignSupport = new ConcurrentHashMap<>();
    private final Map<LocationKey, SignClaim> claims = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> lowStockWarned = new ConcurrentHashMap<>();
    private final RateLimiter limiter = new RateLimiter();
    private volatile SignService signService;

    public ShopManager(BarterShopPlugin plugin, Database database, Settings settings, Messages messages) {
        this.plugin = plugin;
        this.database = database;
        this.settings = settings;
        this.messages = messages;
    }

    public void setSignService(SignService signService) {
        this.signService = signService;
    }

    // ------------------------------------------------------------- lifecycle

    public void load() {
        LoadedShops loaded = database.call(ShopRepository::loadAll);
        for (String warning : loaded.warnings()) {
            plugin.getLogger().warning(warning);
        }
        for (Shop shop : loaded.shops()) {
            register(shop);
        }
        List<SignClaim> loadedClaims = database.call(ShopRepository::loadClaims);
        for (SignClaim claim : loadedClaims) {
            claims.put(claim.sign(), claim);
        }
        long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(settings.signClaimExpiryMinutes());
        database.run(repository -> repository.deleteStaleClaims(cutoff));
        plugin.getLogger().info("Loaded " + byId.size() + " shops.");
    }

    public void register(Shop shop) {
        byId.put(shop.id(), shop);
        for (LocationKey key : shop.chestBlocks()) {
            Shop previous = byChest.putIfAbsent(key, shop);
            if (previous != null && !previous.id().equals(shop.id())) {
                plugin.getLogger().warning("Chest " + key.asString() + " is claimed by two shops; "
                        + shop.id() + " will not be reachable through that chest.");
            }
        }
        if (shop.sign() != null) {
            Shop previous = bySign.putIfAbsent(shop.sign(), shop);
            if (previous != null && !previous.id().equals(shop.id())) {
                plugin.getLogger().warning("Sign " + shop.sign().asString() + " is claimed by two shops.");
            }
        }
        if (shop.signSupport() != null) {
            bySignSupport.put(shop.signSupport(), shop);
        }
    }

    public void unregister(Shop shop) {
        byId.remove(shop.id(), shop);
        for (LocationKey key : shop.chestBlocks()) {
            byChest.remove(key, shop);
        }
        if (shop.sign() != null) {
            bySign.remove(shop.sign(), shop);
        }
        if (shop.signSupport() != null) {
            bySignSupport.remove(shop.signSupport(), shop);
        }
    }

    public Collection<Shop> all() {
        return List.copyOf(byId.values());
    }

    public Optional<Shop> byId(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Shop shopAtChest(LocationKey key) {
        return byChest.get(key);
    }

    public Shop shopAtSign(LocationKey key) {
        return bySign.get(key);
    }

    public Shop shopBySupport(LocationKey key) {
        return bySignSupport.get(key);
    }

    public boolean isProtectedChest(LocationKey key) {
        return byChest.containsKey(key);
    }

    public boolean isProtectedSign(LocationKey key) {
        return bySign.containsKey(key);
    }

    public boolean isProtectedSupport(LocationKey key) {
        return bySignSupport.containsKey(key);
    }

    public List<Shop> ownedBy(UUID owner) {
        List<Shop> result = new ArrayList<>();
        for (Shop shop : byId.values()) {
            if (shop.owner().equals(owner)) {
                result.add(shop);
            }
        }
        result.sort((a, b) -> a.createdAt() == b.createdAt()
                ? a.id().compareTo(b.id())
                : Long.compare(a.createdAt(), b.createdAt()));
        return result;
    }

    public int countOwned(UUID owner) {
        int count = 0;
        for (Shop shop : byId.values()) {
            if (shop.owner().equals(owner)) {
                count++;
            }
        }
        return count;
    }

    // -------------------------------------------------------------- creation

    public enum CreationFailure {
        NO_PERMISSION,
        LIMIT_REACHED,
        INVALID_SIGN,
        INVALID_CHEST,
        SIGN_OCCUPIED,
        CHEST_OCCUPIED,
        CHEST_NOT_EMPTY,
        CAPACITY_TOO_SMALL,
        CLAIMED_BY_OTHER,
        DATABASE_ERROR,
        WORLD_NOT_ALLOWED,
        TOO_FAR
    }

    public record CreationResult(Shop shop, CreationFailure failure) {
        public static CreationResult success(Shop shop) {
            return new CreationResult(shop, null);
        }

        public static CreationResult failure(CreationFailure failure) {
            return new CreationResult(null, failure);
        }

        public boolean success() {
            return shop != null;
        }
    }

    public CreationResult createShop(Player player, Block signBlock, Block chestBlock) {
        if (!plugin.isMainThread()) {
            throw new IllegalStateException("shop creation must run on the server thread");
        }
        if (!player.hasPermission("bartershop.create") && !player.hasPermission("bartershop.admin")) {
            return CreationResult.failure(CreationFailure.NO_PERMISSION);
        }
        if (signBlock == null || !LocationUtil.isSign(signBlock)) {
            return CreationResult.failure(CreationFailure.INVALID_SIGN);
        }
        if (chestBlock == null || !LocationUtil.isChest(chestBlock)) {
            return CreationResult.failure(CreationFailure.INVALID_CHEST);
        }
        if (chestBlock.getWorld() != signBlock.getWorld()) {
            return CreationResult.failure(CreationFailure.INVALID_CHEST);
        }
        if (!settings.allowedWorlds().isEmpty()
                && !settings.allowedWorlds().contains(signBlock.getWorld().getName().toLowerCase(java.util.Locale.ROOT))) {
            return CreationResult.failure(CreationFailure.WORLD_NOT_ALLOWED);
        }
        if (player.getWorld() != signBlock.getWorld()
                || player.getLocation().distanceSquared(signBlock.getLocation().add(0.5, 0.5, 0.5)) > 64
                || player.getLocation().distanceSquared(chestBlock.getLocation().add(0.5, 0.5, 0.5)) > 64) {
            return CreationResult.failure(CreationFailure.TOO_FAR);
        }
        LocationKey signKey = LocationUtil.key(signBlock);
        if (bySign.containsKey(signKey) || LocationUtil.shopIdAt(plugin, signBlock) != null) {
            return CreationResult.failure(CreationFailure.SIGN_OCCUPIED);
        }
        SignClaim claim = claims.get(signKey);
        if (claim != null && !claim.playerUuid().equals(player.getUniqueId())
                && !player.hasPermission("bartershop.admin")) {
            return CreationResult.failure(CreationFailure.CLAIMED_BY_OTHER);
        }
        List<LocationKey> chestBlocks = LocationUtil.chestBlocks(chestBlock);
        if (chestBlocks.isEmpty()) {
            return CreationResult.failure(CreationFailure.INVALID_CHEST);
        }
        for (LocationKey key : chestBlocks) {
            if (byChest.containsKey(key)) {
                return CreationResult.failure(CreationFailure.CHEST_OCCUPIED);
            }
        }
        if (!LocationUtil.chestIsEmpty(chestBlock)) {
            return CreationResult.failure(CreationFailure.CHEST_NOT_EMPTY);
        }
        int capacity = chestBlocks.size() > 1 ? 54 : 27;
        int actualCapacity = LocationUtil.chestCapacity(chestBlock);
        if (actualCapacity >= 27) {
            capacity = Math.min(54, actualCapacity);
        }
        if (!player.hasPermission("bartershop.shops.unlimited")
                && countOwned(player.getUniqueId()) >= settings.maxShopsPerPlayer()) {
            return CreationResult.failure(CreationFailure.LIMIT_REACHED);
        }

        LocationKey support = LocationUtil.signSupport(signBlock);
        if ((byChest.containsKey(support) && !chestBlocks.contains(support))
                || bySignSupport.containsKey(support)) {
            return CreationResult.failure(CreationFailure.CHEST_OCCUPIED);
        }
        Shop shop = Shop.withChestUnits(
                UUID.randomUUID(),
                player.getUniqueId(),
                player.getName(),
                player.getName() + "'s Shop",
                List.of(chestBlocks),
                signKey,
                support,
                capacity,
                true,
                List.of(),
                new StockStorage(capacity),
                new ProceedsStorage(),
                System.currentTimeMillis(),
                ShopStats.EMPTY,
                settings.defaultLowStockThreshold());
        try {
            database.run(repository -> {
                repository.insertShop(shop);
                repository.deleteClaim(signKey);
            });
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Could not create shop for " + player.getName() + ": " + e.getMessage());
            return CreationResult.failure(CreationFailure.DATABASE_ERROR);
        }
        claims.remove(signKey);
        register(shop);
        LocationUtil.tagShopBlock(plugin, signBlock, shop.id());
        for (LocationKey key : chestBlocks) {
            Block block = LocationUtil.block(key);
            if (block != null) {
                LocationUtil.tagShopBlock(plugin, block, shop.id());
            }
        }
        plugin.audit(player.getUniqueId(), player.getName(), "SHOP_CREATE",
                shop.id() + " chest=" + shop.primaryChest().asString() + " sign=" + signKey.asString());
        plugin.getLogger().info("Created shop " + shop.id() + " for " + player.getName()
                + " at " + shop.primaryChest().asString());
        refreshSign(shop);
        return CreationResult.success(shop);
    }

    // --------------------------------------------------------------- deletion

    public boolean deleteShop(Player actor, Shop shop, boolean admin) {
        if (admin && !actor.hasPermission("bartershop.admin")) {
            return false;
        }
        if (!admin && !shop.owner().equals(actor.getUniqueId())) {
            return false;
        }
        ReentrantLock lock = shop.lock();
        if (!tryLock(lock)) {
            return false;
        }
        try {
            database.run(repository -> repository.deleteShopToMailbox(shop,
                    (admin ? "Administrative deletion" : "Owner deletion")
                            + " by " + actor.getName()
                            + "; stock and proceeds moved to the owner's recovery mailbox"));
            LocationKey signKey = shop.sign();
            unregister(shop);
            lowStockWarned.remove(shop.id());
            for (LocationKey key : shop.chestBlocks()) {
                Block chest = LocationUtil.block(key);
                LocationUtil.untagShopBlock(plugin, chest);
            }
            Block sign = signKey == null ? null : LocationUtil.block(signKey);
            if (sign != null) {
                LocationUtil.untagShopBlock(plugin, sign);
                sign.setType(Material.AIR, false);
            }
            plugin.audit(actor.getUniqueId(), actor.getName(),
                    admin ? "ADMIN_SHOP_DELETE" : "SHOP_DELETE",
                    shop.id() + " owner=" + shop.owner() + " chest=" + shop.primaryChest().asString()
                            + " stock=" + shop.stock().totalItems() + " proceeds=" + shop.proceeds().totalItems());
            messages.send(actor, "shop.deleted");
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Could not delete shop " + shop.id() + ": " + e.getMessage());
            messages.send(actor, "error.database");
            return false;
        } finally {
            lock.unlock();
        }
    }

    // -------------------------------------------------------------- ownership

    /**
     * Sends a transfer request. The current owner (or an administrator) starts
     * it; the recipient must explicitly accept it before ownership changes.
     */
    public Optional<TransferRequest> requestTransfer(Player actor, Shop shop, UUID targetId, String targetName) {
        boolean admin = actor.hasPermission("bartershop.admin");
        if (!admin && !shop.owner().equals(actor.getUniqueId())) {
            messages.send(actor, "general.no-permission");
            return Optional.empty();
        }
        if (targetId.equals(shop.owner())) {
            messages.send(actor, "transfer.self");
            return Optional.empty();
        }
        if (!limiter.allow(actor.getUniqueId(), "transfer",
                settings.maxTransferRequestsPerHour(), 3_600_000L)) {
            messages.send(actor, "error.rate-limit");
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        TransferRequest request = new TransferRequest(
                UUID.randomUUID(), shop.id(), shop.name(),
                shop.owner(), shop.ownerName(), targetId, targetName,
                now, now + settings.transferExpiryMillis());
        try {
            database.run(repository -> repository.replaceTransferRequest(request));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not create transfer request for " + shop.id()
                    + ": " + e.getMessage());
            messages.send(actor, "error.database");
            return Optional.empty();
        }
        messages.send(actor, "transfer.request-sent", "player", targetName);
        Player online = Bukkit.getPlayer(targetId);
        if (online != null && online.isOnline()) {
            messages.send(online, "transfer.requested", "shop", shop.name(),
                    "player", actor.getName());
        }
        plugin.audit(actor.getUniqueId(), actor.getName(), "SHOP_TRANSFER_REQUEST",
                shop.id() + " -> " + targetId + " (" + targetName + ")");
        return Optional.of(request);
    }

    public Optional<TransferRequest> pendingTransfer(Shop shop) {
        try {
            return database.call(repository -> repository.loadTransferRequestForShop(shop.id()));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not load transfer request for " + shop.id()
                    + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Accepts a transfer as its intended recipient, revalidating everything live. */
    public boolean acceptTransfer(Player player, UUID requestId) {
        TransferRequest request;
        try {
            request = database.call(repository -> repository.loadTransferRequest(requestId)).orElse(null);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not load transfer request " + requestId + ": " + e.getMessage());
            messages.send(player, "error.database");
            return false;
        }
        if (request == null) {
            messages.send(player, "transfer.missing");
            return false;
        }
        if (!request.toUuid().equals(player.getUniqueId())) {
            messages.send(player, "general.no-permission");
            return false;
        }
        if (request.expired(System.currentTimeMillis())) {
            try {
                database.run(repository -> repository.deleteTransferRequest(requestId));
            } catch (RuntimeException ignored) {
                // Expiry cleanup is best effort; the request remains unusable.
            }
            messages.send(player, "transfer.expired");
            return false;
        }
        Shop shop = byId.get(request.shopId());
        if (shop == null) {
            messages.send(player, "error.shop-missing");
            return false;
        }
        ReentrantLock lock = shop.lock();
        if (!tryLock(lock)) {
            messages.send(player, "trade.failed.busy");
            return false;
        }
        try {
            if (!shop.owner().equals(request.fromUuid())) {
                try {
                    database.run(repository -> repository.deleteTransferRequest(requestId));
                } catch (RuntimeException ignored) {
                    // The stale request is rejected regardless; cleanup is best effort.
                }
                messages.send(player, "transfer.stale");
                return false;
            }
            // Ownership counts against the recipient's limit; otherwise an owner
            // could exceed the shop cap by rotating shops through an alt account.
            if (!player.hasPermission("bartershop.shops.unlimited")
                    && countOwned(player.getUniqueId()) >= settings.maxShopsPerPlayer()) {
                messages.send(player, "transfer.limit",
                        "limit", String.valueOf(settings.maxShopsPerPlayer()));
                return false;
            }
            database.run(repository -> repository.transferShop(
                    shop, player.getUniqueId(), player.getName(), player.getName()));
            UUID previousOwner = shop.owner();
            shop.setOwner(player.getUniqueId(), player.getName());
            shop.setProceeds(new ProceedsStorage());
            plugin.audit(player.getUniqueId(), player.getName(), "SHOP_TRANSFER_ACCEPT",
                    shop.id() + " from " + previousOwner + " to " + player.getUniqueId());
            messages.send(player, "transfer.accepted", "shop", shop.name());
            Player previous = Bukkit.getPlayer(previousOwner);
            if (previous != null && previous.isOnline()) {
                messages.send(previous, "transfer.completed", "shop", shop.name(),
                        "player", player.getName());
            }
            refreshSign(shop);
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Could not complete transfer for " + shop.id()
                    + ": " + e.getMessage());
            messages.send(player, "error.database");
            return false;
        } finally {
            lock.unlock();
        }
    }

    public boolean cancelTransfer(Player actor, TransferRequest request) {
        Shop shop = byId.get(request.shopId());
        boolean allowed = actor.hasPermission("bartershop.admin")
                || request.fromUuid().equals(actor.getUniqueId())
                || (shop != null && shop.owner().equals(actor.getUniqueId()));
        if (!allowed) {
            messages.send(actor, "general.no-permission");
            return false;
        }
        try {
            database.run(repository -> repository.deleteTransferRequest(request.id()));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not cancel transfer " + request.id() + ": " + e.getMessage());
            messages.send(actor, "error.database");
            return false;
        }
        messages.send(actor, "transfer.cancelled");
        Player target = Bukkit.getPlayer(request.toUuid());
        if (target != null && target.isOnline() && !target.getUniqueId().equals(actor.getUniqueId())) {
            messages.send(target, "transfer.cancelled-other", "shop", request.shopName());
        }
        plugin.audit(actor.getUniqueId(), actor.getName(), "SHOP_TRANSFER_CANCEL",
                request.shopId() + " -> " + request.toUuid());
        return true;
    }

    public void setEnabled(Player actor, Shop shop, boolean enabled) {
        if (!shop.owner().equals(actor.getUniqueId()) && !actor.hasPermission("bartershop.admin")) {
            return;
        }
        boolean previous = shop.enabled();
        shop.setEnabled(enabled);
        try {
            database.run(repository -> repository.saveEnabled(shop));
        } catch (RuntimeException e) {
            shop.setEnabled(previous);
            plugin.getLogger().warning("Could not save enabled state for " + shop.id()
                    + ": " + e.getMessage());
            messages.send(actor, "error.database");
            return;
        }
        if (actor.hasPermission("bartershop.admin") && !shop.owner().equals(actor.getUniqueId())) {
            plugin.audit(actor.getUniqueId(), actor.getName(), "ADMIN_SHOP_ENABLED",
                    shop.id() + " enabled=" + enabled);
        }
        refreshSign(shop);
    }

    public void setWarningThreshold(Player actor, Shop shop, int threshold) {
        if (!canManage(actor, shop)) {
            messages.send(actor, "general.no-permission");
            return;
        }
        int previous = shop.warningThreshold();
        int clamped = Math.max(0, Math.min(Shop.MAX_CAPACITY_SLOTS, threshold));
        shop.setWarningThreshold(clamped);
        try {
            database.run(repository -> repository.saveWarningThreshold(shop));
        } catch (RuntimeException e) {
            shop.setWarningThreshold(previous);
            messages.send(actor, "error.database");
            return;
        }
        lowStockWarned.remove(shop.id());
        messages.send(actor, "stock.warning-threshold", "count", String.valueOf(clamped));
        checkStockWarnings(shop);
    }

    public void rename(Player actor, Shop shop, String name) {
        if (!shop.owner().equals(actor.getUniqueId()) && !actor.hasPermission("bartershop.admin")) {
            return;
        }
        String previous = shop.name();
        shop.setName(name);
        try {
            database.run(repository -> repository.renameShop(shop.id(), shop.name()));
            refreshSign(shop);
        } catch (RuntimeException e) {
            shop.setName(previous);
            plugin.getLogger().warning("Could not rename shop " + shop.id() + ": " + e.getMessage());
            messages.send(actor, "error.database");
        }
    }

    // ----------------------------------------------------------------- offers

    public boolean addOffer(Player actor, Shop shop, TradeOffer offer) {
        if (!shop.owner().equals(actor.getUniqueId()) && !actor.hasPermission("bartershop.admin")) {
            return false;
        }
        if (!limiter.allow(actor.getUniqueId(), "offers",
                settings.maxOfferChangesPerMinute(), 60_000L)) {
            messages.send(actor, "error.rate-limit");
            return false;
        }
        if (shop.offers().size() >= settings.maxOffersPerShop()) {
            messages.send(actor, "offer.limit", "limit", String.valueOf(settings.maxOffersPerShop()));
            return false;
        }
        if (offer.payAmount() > settings.maxOfferAmount() || offer.receiveAmount() > settings.maxOfferAmount()) {
            messages.send(actor, "offer.amount-limit",
                    "limit", String.valueOf(settings.maxOfferAmount()));
            return false;
        }
        List<TradeOffer> before = shop.offers();
        shop.addOffer(offer);
        if (!persistOffers(actor, shop, "Offer added: " + offer.describe())) {
            shop.replaceOffers(before);
            return false;
        }
        checkStockWarnings(shop);
        return true;
    }

    public boolean updateOffer(Player actor, Shop shop, TradeOffer offer) {
        if (!shop.owner().equals(actor.getUniqueId()) && !actor.hasPermission("bartershop.admin")) {
            return false;
        }
        if (!limiter.allow(actor.getUniqueId(), "offers",
                settings.maxOfferChangesPerMinute(), 60_000L)) {
            messages.send(actor, "error.rate-limit");
            return false;
        }
        if (offer.payAmount() > settings.maxOfferAmount() || offer.receiveAmount() > settings.maxOfferAmount()) {
            messages.send(actor, "offer.amount-limit",
                    "limit", String.valueOf(settings.maxOfferAmount()));
            return false;
        }
        List<TradeOffer> before = shop.offers();
        try {
            shop.replaceOffer(offer);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!persistOffers(actor, shop, "Offer updated: " + offer.describe())) {
            shop.replaceOffers(before);
            return false;
        }
        checkStockWarnings(shop);
        return true;
    }

    public boolean removeOffer(Player actor, Shop shop, UUID offerId) {
        if (!shop.owner().equals(actor.getUniqueId()) && !actor.hasPermission("bartershop.admin")) {
            return false;
        }
        Optional<TradeOffer> offer = shop.findOffer(offerId);
        if (offer.isEmpty()) {
            return false;
        }
        List<TradeOffer> before = shop.offers();
        shop.removeOffer(offerId);
        if (!persistOffers(actor, shop, "Offer removed: " + offer.get().describe())) {
            shop.replaceOffers(before);
            return false;
        }
        checkStockWarnings(shop);
        return true;
    }

    private boolean persistOffers(Player actor, Shop shop, String history) {
        try {
            database.run(repository -> {
                repository.saveOffers(shop);
                repository.addHistory(HistoryEntry.create(shop.id(), shop.owner(), actor.getUniqueId(),
                        actor.getName(), "CONFIG", history));
            });
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not save offers for " + shop.id() + ": " + e.getMessage());
            messages.send(actor, "error.database");
            return false;
        }
        if (actor.hasPermission("bartershop.admin") && !shop.owner().equals(actor.getUniqueId())) {
            plugin.audit(actor.getUniqueId(), actor.getName(), "ADMIN_OFFER_CHANGE",
                    shop.id() + " " + history);
        }
        refreshSign(shop);
        return true;
    }

    // ------------------------------------------------------------------ stock

    public enum StockOutcome {
        SUCCESS,
        NOT_OWNER,
        STORAGE_FULL,
        INVENTORY_FULL,
        EMPTY_HAND,
        INVALID_ITEM,
        CURSOR_BUSY,
        CHANGED,
        DATABASE_ERROR,
        LOCKED
    }

    public record StockResult(StockOutcome outcome, int amount, String itemName) {
        public static StockResult failure(StockOutcome outcome) {
            return new StockResult(outcome, 0, "");
        }
    }

    public StockResult deposit(Player actor, Shop shop, int playerSlot) {
        if (!shop.owner().equals(actor.getUniqueId())) {
            return StockResult.failure(StockOutcome.NOT_OWNER);
        }
        if (playerSlot < 0 || playerSlot >= PlayerInventoryAdapter.STORAGE_SLOTS) {
            return StockResult.failure(StockOutcome.EMPTY_HAND);
        }
        if (!PlayerInventoryAdapter.cursorEmpty(actor)) {
            return StockResult.failure(StockOutcome.CURSOR_BUSY);
        }
        ItemStack current = actor.getInventory().getItem(playerSlot);
        if (current == null || current.getType().isAir()) {
            return StockResult.failure(StockOutcome.EMPTY_HAND);
        }
        ItemData data;
        try {
            data = ItemCodec.of(current);
        } catch (RuntimeException e) {
            return StockResult.failure(StockOutcome.INVALID_ITEM);
        }
        int wanted = current.getAmount();
        ReentrantLock lock = shop.lock();
        if (!tryLock(lock)) {
            return StockResult.failure(StockOutcome.LOCKED);
        }
        StockStorage before = shop.stock().copy();
        try {
            StockStorage after = shop.stock().copy();
            int leftover = after.add(data, wanted);
            int accepted = wanted - leftover;
            if (accepted <= 0) {
                return StockResult.failure(StockOutcome.STORAGE_FULL);
            }
            shop.setStock(after);
            try {
                database.run(repository -> repository.saveStock(shop));
            } catch (RuntimeException e) {
                shop.setStock(before);
                plugin.getLogger().warning("Deposit failed: " + e.getMessage());
                return StockResult.failure(StockOutcome.DATABASE_ERROR);
            }
            ItemStack live = actor.getInventory().getItem(playerSlot);
            if (!PlayerInventoryAdapter.same(live, new InventoryModel.HeldItem(data, wanted))) {
                shop.setStock(before);
                try {
                    database.run(repository -> repository.saveStock(shop));
                } catch (RuntimeException ignored) {
                    // Recovery journal not needed: the original stock is still in memory/DB.
                }
                return StockResult.failure(StockOutcome.CHANGED);
            }
            int remaining = wanted - accepted;
            actor.getInventory().setItem(playerSlot,
                    remaining <= 0 ? null : live.asQuantity(remaining));
            actor.updateInventory();
            database.callAsync(repository -> {
                repository.addHistory(HistoryEntry.create(shop.id(), shop.owner(), actor.getUniqueId(),
                        actor.getName(), "STOCK", "Deposited " + accepted + "x " + data.prettyName()));
                return null;
            }).exceptionally(throwable -> {
                plugin.getLogger().warning("Could not write deposit history: " + throwable.getMessage());
                return null;
            });
            checkStockWarnings(shop);
            refreshSign(shop);
            return new StockResult(StockOutcome.SUCCESS, accepted, data.prettyName());
        } finally {
            lock.unlock();
        }
    }

    public StockResult withdraw(Player actor, Shop shop, int stockIndex, int requested) {
        if (!shop.owner().equals(actor.getUniqueId())) {
            return StockResult.failure(StockOutcome.NOT_OWNER);
        }
        if (!PlayerInventoryAdapter.cursorEmpty(actor)) {
            return StockResult.failure(StockOutcome.CURSOR_BUSY);
        }
        int amount = Math.max(1, requested);
        ReentrantLock lock = shop.lock();
        if (!tryLock(lock)) {
            return StockResult.failure(StockOutcome.LOCKED);
        }
        StockStorage before = shop.stock().copy();
        try {
            if (stockIndex < 0 || stockIndex >= shop.stock().slotsUsed()) {
                return StockResult.failure(StockOutcome.INVALID_ITEM);
            }
            StockStack stack = shop.stock().at(stockIndex);
            InventoryModel model = PlayerInventoryAdapter.modelOf(actor);
            InventoryModel afterModel = model.copy();
            int leftover = afterModel.add(stack.item(), Math.min(amount, stack.amount()));
            int accepted = Math.min(amount, stack.amount()) - leftover;
            if (accepted <= 0) {
                return StockResult.failure(StockOutcome.INVENTORY_FULL);
            }
            StockStorage after = shop.stock().copy();
            after.removeAt(stockIndex, accepted);
            shop.setStock(after);
            try {
                database.run(repository -> repository.saveStock(shop));
            } catch (RuntimeException e) {
                shop.setStock(before);
                plugin.getLogger().warning("Withdraw failed: " + e.getMessage());
                return StockResult.failure(StockOutcome.DATABASE_ERROR);
            }
            try {
                PlayerInventoryAdapter.apply(actor, model, afterModel);
            } catch (RuntimeException e) {
                shop.setStock(before);
                try {
                    database.run(repository -> repository.saveStock(shop));
                } catch (RuntimeException ignored) {
                    // The stock snapshot is in memory; DB recovery is attempted on next save.
                }
                PlayerInventoryAdapter.restore(actor, model, afterModel);
                return StockResult.failure(StockOutcome.CHANGED);
            }
            database.callAsync(repository -> {
                repository.addHistory(HistoryEntry.create(shop.id(), shop.owner(), actor.getUniqueId(),
                        actor.getName(), "STOCK", "Withdrew " + accepted + "x " + stack.item().prettyName()));
                return null;
            }).exceptionally(throwable -> {
                plugin.getLogger().warning("Could not write withdrawal history: " + throwable.getMessage());
                return null;
            });
            checkStockWarnings(shop);
            refreshSign(shop);
            return new StockResult(StockOutcome.SUCCESS, accepted, stack.item().prettyName());
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------- relink/scan

    public enum RelinkFailure {
        NO_PERMISSION,
        INVALID_CHEST,
        CHEST_OCCUPIED,
        CHEST_NOT_EMPTY,
        CAPACITY_TOO_SMALL,
        LIMIT_REACHED,
        ALREADY_LINKED,
        LAST_CHEST,
        DATABASE_ERROR,
        LOCKED
    }

    /** Replaces the first chest unit, keeping any additional linked chests. */
    public RelinkFailure relinkChest(Player actor, Shop shop, Block chestBlock) {
        boolean admin = actor.hasPermission("bartershop.admin");
        if (!shop.owner().equals(actor.getUniqueId()) && !admin) {
            return RelinkFailure.NO_PERMISSION;
        }
        if (!LocationUtil.isChest(chestBlock)) {
            return RelinkFailure.INVALID_CHEST;
        }
        ReentrantLock lock = shop.lock();
        if (!tryLock(lock)) {
            return RelinkFailure.LOCKED;
        }
        try {
            List<LocationKey> keys = LocationUtil.chestBlocks(chestBlock);
            if (keys.isEmpty()) {
                return RelinkFailure.INVALID_CHEST;
            }
            RelinkFailure conflict = validateChestKeys(shop, keys);
            if (conflict != null) {
                return conflict;
            }
            if (!LocationUtil.chestIsEmpty(chestBlock)) {
                return RelinkFailure.CHEST_NOT_EMPTY;
            }
            List<List<LocationKey>> oldUnits = shop.chestUnits();
            int oldUnitCapacity = unitCapacity(oldUnits.get(0));
            int newUnitCapacity = Math.max(27, LocationUtil.chestCapacity(chestBlock));
            int newCapacity = shop.capacitySlots() - oldUnitCapacity + newUnitCapacity;
            if (newCapacity < shop.stock().slotsUsed()) {
                return RelinkFailure.CAPACITY_TOO_SMALL;
            }
            List<LocationKey> old = oldUnits.get(0);
            for (LocationKey key : old) {
                byChest.remove(key, shop);
                LocationUtil.untagShopBlock(plugin, LocationUtil.block(key));
            }
            List<List<LocationKey>> replacement = new ArrayList<>(oldUnits);
            replacement.set(0, keys);
            shop.setChestUnits(replacement);
            shop.setCapacitySlots(newCapacity);
            StockStorage oldStock = shop.stock();
            shop.setStock(oldStock.withCapacity(newCapacity));
            for (LocationKey key : keys) {
                byChest.put(key, shop);
                LocationUtil.tagShopBlock(plugin, LocationUtil.block(key), shop.id());
            }
            try {
                database.run(repository -> repository.saveLocations(shop));
            } catch (RuntimeException e) {
                for (LocationKey key : keys) {
                    byChest.remove(key, shop);
                    LocationUtil.untagShopBlock(plugin, LocationUtil.block(key));
                }
                shop.setChestUnits(oldUnits);
                shop.setCapacitySlots(oldUnitCapacity + newCapacity - newUnitCapacity);
                shop.setStock(oldStock);
                for (LocationKey key : old) {
                    byChest.put(key, shop);
                    LocationUtil.tagShopBlock(plugin, LocationUtil.block(key), shop.id());
                }
                throw e;
            }
            plugin.audit(actor.getUniqueId(), actor.getName(), admin ? "ADMIN_SHOP_RELINK" : "SHOP_RELINK",
                    shop.id() + " chest=" + shop.primaryChest().asString());
            refreshSign(shop);
            return null;
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Could not relink shop " + shop.id() + ": " + e.getMessage());
            return RelinkFailure.DATABASE_ERROR;
        } finally {
            lock.unlock();
        }
    }

    /** Adds one empty, unclaimed chest unit and increases the storage capacity. */
    public RelinkFailure addChest(Player actor, Shop shop, Block chestBlock) {
        boolean admin = actor.hasPermission("bartershop.admin");
        if (!shop.owner().equals(actor.getUniqueId()) && !admin) {
            return RelinkFailure.NO_PERMISSION;
        }
        if (!LocationUtil.isChest(chestBlock)) {
            return RelinkFailure.INVALID_CHEST;
        }
        ReentrantLock lock = shop.lock();
        if (!tryLock(lock)) {
            return RelinkFailure.LOCKED;
        }
        try {
            if (shop.chestUnitCount() >= settings.maxChestsPerShop()) {
                return RelinkFailure.LIMIT_REACHED;
            }
            List<LocationKey> keys = LocationUtil.chestBlocks(chestBlock);
            if (keys.isEmpty()) {
                return RelinkFailure.INVALID_CHEST;
            }
            for (LocationKey key : keys) {
                if (shop.isChestBlock(key)) {
                    return RelinkFailure.ALREADY_LINKED;
                }
            }
            RelinkFailure conflict = validateChestKeys(shop, keys);
            if (conflict != null) {
                return conflict;
            }
            if (!LocationUtil.chestIsEmpty(chestBlock)) {
                return RelinkFailure.CHEST_NOT_EMPTY;
            }
            int added = Math.max(27, LocationUtil.chestCapacity(chestBlock));
            int newCapacity = shop.capacitySlots() + added;
            if (newCapacity > Shop.MAX_CAPACITY_SLOTS) {
                return RelinkFailure.CAPACITY_TOO_SMALL;
            }
            List<List<LocationKey>> oldUnits = shop.chestUnits();
            StockStorage oldStock = shop.stock();
            shop.addChestUnit(keys);
            shop.setCapacitySlots(newCapacity);
            shop.setStock(oldStock.withCapacity(newCapacity));
            for (LocationKey key : keys) {
                byChest.put(key, shop);
                LocationUtil.tagShopBlock(plugin, LocationUtil.block(key), shop.id());
            }
            try {
                database.run(repository -> repository.saveLocations(shop));
            } catch (RuntimeException e) {
                for (LocationKey key : keys) {
                    byChest.remove(key, shop);
                    LocationUtil.untagShopBlock(plugin, LocationUtil.block(key));
                }
                shop.setChestUnits(oldUnits);
                shop.setCapacitySlots(newCapacity - added);
                shop.setStock(oldStock);
                throw e;
            }
            plugin.audit(actor.getUniqueId(), actor.getName(), admin ? "ADMIN_SHOP_CHEST_ADD" : "SHOP_CHEST_ADD",
                    shop.id() + " chest=" + keys.get(0).asString());
            refreshSign(shop);
            return null;
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Could not link chest for shop " + shop.id() + ": " + e.getMessage());
            return RelinkFailure.DATABASE_ERROR;
        } finally {
            lock.unlock();
        }
    }

    /** Removes one chest unit when the remaining capacity can still hold all stock. */
    public RelinkFailure removeChest(Player actor, Shop shop, int unitIndex) {
        boolean admin = actor.hasPermission("bartershop.admin");
        if (!shop.owner().equals(actor.getUniqueId()) && !admin) {
            return RelinkFailure.NO_PERMISSION;
        }
        ReentrantLock lock = shop.lock();
        if (!tryLock(lock)) {
            return RelinkFailure.LOCKED;
        }
        try {
            List<List<LocationKey>> units = shop.chestUnits();
            if (unitIndex < 0 || unitIndex >= units.size()) {
                return RelinkFailure.INVALID_CHEST;
            }
            if (units.size() <= 1) {
                return RelinkFailure.LAST_CHEST;
            }
            if (unitIndex == 0) {
                // The primary unit is the shop identity anchor. Replacing it
                // is a dedicated relink operation, never an unlink.
                return RelinkFailure.INVALID_CHEST;
            }
            int removed = unitCapacity(units.get(unitIndex));
            int newCapacity = shop.capacitySlots() - removed;
            if (newCapacity < shop.stock().slotsUsed()) {
                return RelinkFailure.CAPACITY_TOO_SMALL;
            }
            List<LocationKey> removedBlocks = units.get(unitIndex);
            StockStorage oldStock = shop.stock();
            shop.removeChestUnit(unitIndex);
            shop.setCapacitySlots(newCapacity);
            shop.setStock(oldStock.withCapacity(newCapacity));
            for (LocationKey key : removedBlocks) {
                byChest.remove(key, shop);
                LocationUtil.untagShopBlock(plugin, LocationUtil.block(key));
            }
            try {
                database.run(repository -> repository.saveLocations(shop));
            } catch (RuntimeException e) {
                shop.setChestUnits(units);
                shop.setCapacitySlots(newCapacity + removed);
                shop.setStock(oldStock);
                for (LocationKey key : removedBlocks) {
                    byChest.put(key, shop);
                    LocationUtil.tagShopBlock(plugin, LocationUtil.block(key), shop.id());
                }
                throw e;
            }
            plugin.audit(actor.getUniqueId(), actor.getName(), admin ? "ADMIN_SHOP_CHEST_REMOVE" : "SHOP_CHEST_REMOVE",
                    shop.id() + " chest=" + removedBlocks.get(0).asString());
            refreshSign(shop);
            return null;
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Could not unlink chest for shop " + shop.id() + ": " + e.getMessage());
            return RelinkFailure.DATABASE_ERROR;
        } finally {
            lock.unlock();
        }
    }

    private RelinkFailure validateChestKeys(Shop shop, List<LocationKey> keys) {
        for (LocationKey key : keys) {
            Shop owner = byChest.get(key);
            if (owner != null && !owner.id().equals(shop.id())) {
                return RelinkFailure.CHEST_OCCUPIED;
            }
        }
        return null;
    }

    private static int unitCapacity(List<LocationKey> unit) {
        return unit.size() > 1 ? 54 : 27;
    }

    public boolean relinkSign(Player actor, Shop shop, Block signBlock) {
        boolean admin = actor.hasPermission("bartershop.admin");
        if (!shop.owner().equals(actor.getUniqueId()) && !admin) {
            return false;
        }
        if (!LocationUtil.isSign(signBlock)) {
            return false;
        }
        LocationKey newSign = LocationUtil.key(signBlock);
        Shop existing = bySign.get(newSign);
        if (existing != null && !existing.id().equals(shop.id())) {
            return false;
        }
        SignClaim claim = claims.get(newSign);
        if (claim != null && !claim.playerUuid().equals(actor.getUniqueId()) && !admin) {
            return false;
        }
        ReentrantLock lock = shop.lock();
        if (!tryLock(lock)) {
            return false;
        }
        try {
            LocationKey oldSign = shop.sign();
            LocationKey oldSupport = shop.signSupport();
            LocationKey support = LocationUtil.signSupport(signBlock);
            if (newSign.equals(oldSign)) {
                // Relinking a shop to its own sign must never delete that sign.
                refreshSign(shop);
                return true;
            }
            Shop supportChest = byChest.get(support);
            if (supportChest != null && !supportChest.id().equals(shop.id())) {
                return false;
            }
            Shop supportSign = bySignSupport.get(support);
            if (supportSign != null && !supportSign.id().equals(shop.id())) {
                return false;
            }
            if (oldSign != null) {
                bySign.remove(oldSign, shop);
            }
            if (oldSupport != null) {
                bySignSupport.remove(oldSupport, shop);
            }
            shop.setSign(newSign, support);
            bySign.put(newSign, shop);
            bySignSupport.put(support, shop);
            LocationUtil.tagShopBlock(plugin, signBlock, shop.id());
            try {
                database.run(repository -> repository.saveLocations(shop));
            } catch (RuntimeException e) {
                bySign.remove(newSign, shop);
                bySignSupport.remove(support, shop);
                LocationUtil.untagShopBlock(plugin, signBlock);
                shop.setSign(oldSign, oldSupport);
                if (oldSign != null) {
                    bySign.put(oldSign, shop);
                }
                if (oldSupport != null) {
                    bySignSupport.put(oldSupport, shop);
                }
                throw e;
            }
            if (oldSign != null) {
                Block old = LocationUtil.block(oldSign);
                if (old != null) {
                    LocationUtil.untagShopBlock(plugin, old);
                    old.setType(Material.AIR, false);
                }
            }
            plugin.audit(actor.getUniqueId(), actor.getName(), admin ? "ADMIN_SHOP_RELINK_SIGN" : "SHOP_RELINK_SIGN",
                    shop.id() + " sign=" + newSign.asString());
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Could not relink sign for shop " + shop.id() + ": " + e.getMessage());
            return false;
        } finally {
            lock.unlock();
        }
    }

    public int scanOrphans() {
        int orphaned = 0;
        for (Shop shop : byId.values()) {
            boolean broken = false;
            for (LocationKey key : shop.chestBlocks()) {
                if (!LocationUtil.isLoaded(key)) {
                    // Unloaded chunks are not evidence of a missing anchor; keep the shop usable.
                    continue;
                }
                Block block = LocationUtil.block(key);
                if (block == null || !LocationUtil.isChest(block)) {
                    broken = true;
                }
            }
            if (shop.sign() != null && LocationUtil.isLoaded(shop.sign())) {
                Block sign = LocationUtil.block(shop.sign());
                if (sign == null || !LocationUtil.isSign(sign)) {
                    broken = true;
                }
            }
            shop.setOrphaned(broken);
            if (broken) {
                orphaned++;
            }
        }
        return orphaned;
    }

    public void refreshSigns() {
        for (Shop shop : byId.values()) {
            refreshSign(shop);
        }
    }

    public void refreshSign(Shop shop) {
        if (signService != null && shop != null) {
            signService.refresh(shop);
        }
    }

    /**
     * Sends one low-stock warning per offer and condition. A warning is not
     * repeated until the relevant stock has recovered above the threshold.
     */
    public void checkStockWarnings(Shop shop) {
        int threshold = shop.warningThreshold();
        if (threshold <= 0 || shop.offers().isEmpty()) {
            lowStockWarned.remove(shop.id());
            return;
        }
        Set<UUID> warned = lowStockWarned.computeIfAbsent(shop.id(), key -> ConcurrentHashMap.newKeySet());
        Set<UUID> current = ConcurrentHashMap.newKeySet();
        Player owner = Bukkit.getPlayer(shop.owner());
        for (TradeOffer offer : shop.offers()) {
            // Every offer delivers receiveItem from shop stock, so that is the
            // only item whose stock level matters for availability warnings.
            int available = shop.stock().count(offer.receiveItem(), true);
            int needed = Math.min(threshold, offer.receiveAmount());
            boolean low = available < needed;
            String item = offer.receiveItem().prettyName();
            if (low) {
                current.add(offer.id());
                if (owner != null && owner.isOnline() && warned.add(offer.id())) {
                    messages.send(owner, "stock.warning",
                            "shop", shop.name(),
                            "item", item,
                            "stock", String.valueOf(available),
                            "threshold", String.valueOf(needed));
                }
            } else {
                warned.remove(offer.id());
            }
        }
        warned.retainAll(current);
        if (warned.isEmpty()) {
            lowStockWarned.remove(shop.id(), warned);
        }
    }

    public Set<UUID> lowStockOfferIds(Shop shop) {
        Set<UUID> warned = lowStockWarned.get(shop.id());
        return warned == null ? Set.of() : Set.copyOf(warned);
    }

    // ----------------------------------------------------------------- claims

    public Optional<SignClaim> claim(LocationKey sign) {
        return Optional.ofNullable(claims.get(sign));
    }

    public void saveClaim(SignClaim claim) {
        claims.put(claim.sign(), claim);
        database.run(repository -> repository.saveClaim(claim));
    }

    public void deleteClaim(LocationKey sign) {
        claims.remove(sign);
        database.callAsync(repository -> {
            repository.deleteClaim(sign);
            return null;
        }).exceptionally(throwable -> {
            plugin.getLogger().warning("Could not delete sign claim: " + throwable.getMessage());
            return null;
        });
    }

    private boolean tryLock(ReentrantLock lock) {
        try {
            return lock.tryLock(settings.lockTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public BarterShopPlugin plugin() {
        return plugin;
    }

    public List<String> ownerNames() {
        List<String> names = new ArrayList<>();
        for (Shop shop : byId.values()) {
            if (!shop.ownerName().isBlank() && !names.contains(shop.ownerName())) {
                names.add(shop.ownerName());
            }
        }
        return names;
    }

    /** True when the actor may administer this shop (server-side check, never GUI-only). */
    public boolean canManage(Player player, Shop shop) {
        return shop.owner().equals(player.getUniqueId()) || player.hasPermission("bartershop.admin");
    }

    public boolean isOnline(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null && player.isOnline();
    }

    public static boolean canTradeInGameMode(GameMode mode, Settings settings) {
        if (mode == GameMode.CREATIVE && !settings.allowCreativeTrades()) {
            return false;
        }
        return mode != GameMode.SPECTATOR;
    }

    public static String itemLabel(ItemData data) {
        return data == null ? "?" : data.prettyName();
    }

    public static boolean isAir(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR;
    }

    public static String locationLabel(LocationKey key) {
        return Objects.requireNonNullElse(LocationUtil.describe(key), "unknown");
    }
}
