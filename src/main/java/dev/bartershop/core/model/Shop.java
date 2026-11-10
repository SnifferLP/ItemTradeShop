package dev.bartershop.core.model;

import dev.bartershop.core.proceeds.ProceedsStorage;
import dev.bartershop.core.stock.StockStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Aggregate root for a shop: identity and ownership, the physical storage chest
 * units and sign anchors, trade offers, item stock, pending proceeds and
 * activity statistics.
 *
 * <p>Shop records are mutated on the server thread. Stock, proceeds, stats and
 * the chest layout are replaced wholesale by validated operations, so a
 * half-applied transaction cannot be observed by another thread.</p>
 */
public final class Shop {

    public static final int MAX_CHEST_UNITS = 8;
    public static final int MAX_CAPACITY_SLOTS = MAX_CHEST_UNITS * 54;

    private final UUID id;
    private volatile UUID owner;
    private volatile String ownerName;
    private volatile String name;
    /** Ordered chest units. A double chest is one unit holding both block halves. */
    private final List<List<LocationKey>> chestUnits;
    private volatile LocationKey sign;
    private volatile LocationKey signSupport;
    private volatile int capacitySlots;
    private volatile boolean enabled;
    private final long createdAt;
    private final long updatedAtSeed;
    private final List<TradeOffer> offers;
    private volatile StockStorage stock;
    private volatile ProceedsStorage proceeds;
    private volatile ShopStats stats;
    private volatile int warningThreshold;
    private volatile boolean orphaned;

    private final ReentrantLock lock = new ReentrantLock();

    /** Backwards-compatible constructor treating the supplied blocks as one chest unit. */
    public Shop(UUID id,
                UUID owner,
                String ownerName,
                String name,
                List<LocationKey> chestBlocks,
                LocationKey sign,
                LocationKey signSupport,
                int capacitySlots,
                boolean enabled,
                List<TradeOffer> offers,
                StockStorage stock,
                ProceedsStorage proceeds,
                long createdAt) {
        this(id, owner, ownerName, name,
                List.of(List.copyOf(Objects.requireNonNull(chestBlocks, "chestBlocks"))),
                sign, signSupport, capacitySlots, enabled, offers, stock, proceeds, createdAt,
                ShopStats.EMPTY, 0);
    }

    private Shop(UUID id,
                 UUID owner,
                 String ownerName,
                 String name,
                 List<List<LocationKey>> chestUnits,
                 LocationKey sign,
                 LocationKey signSupport,
                 int capacitySlots,
                 boolean enabled,
                 List<TradeOffer> offers,
                 StockStorage stock,
                 ProceedsStorage proceeds,
                 long createdAt,
                 ShopStats stats,
                 int warningThreshold) {
        this.id = Objects.requireNonNull(id, "id");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.ownerName = ownerName == null ? "" : ownerName;
        this.name = name == null || name.isBlank() ? "Chest Shop" : name;
        if (chestUnits == null || chestUnits.isEmpty()) {
            throw new IllegalArgumentException("shop requires at least one chest unit");
        }
        List<List<LocationKey>> units = new ArrayList<>();
        for (List<LocationKey> unit : chestUnits) {
            if (unit == null || unit.isEmpty()) {
                throw new IllegalArgumentException("chest unit must not be empty");
            }
            units.add(List.copyOf(unit));
        }
        if (units.size() > MAX_CHEST_UNITS) {
            throw new IllegalArgumentException("too many chest units: " + units.size());
        }
        this.chestUnits = new CopyOnWriteArrayList<>(units);
        this.sign = sign;
        this.signSupport = signSupport;
        validateCapacity(capacitySlots);
        this.capacitySlots = capacitySlots;
        this.enabled = enabled;
        this.offers = new CopyOnWriteArrayList<>(offers == null ? List.of() : offers);
        this.stock = Objects.requireNonNull(stock, "stock");
        this.proceeds = Objects.requireNonNull(proceeds, "proceeds");
        this.stats = stats == null ? ShopStats.EMPTY : stats;
        this.warningThreshold = Math.max(0, warningThreshold);
        this.createdAt = createdAt;
        this.updatedAtSeed = System.currentTimeMillis();
    }

    /** Restores a shop with an explicit multi-chest layout and statistics. */
    public static Shop withChestUnits(UUID id,
                                      UUID owner,
                                      String ownerName,
                                      String name,
                                      List<List<LocationKey>> chestUnits,
                                      LocationKey sign,
                                      LocationKey signSupport,
                                      int capacitySlots,
                                      boolean enabled,
                                      List<TradeOffer> offers,
                                      StockStorage stock,
                                      ProceedsStorage proceeds,
                                      long createdAt,
                                      ShopStats stats,
                                      int warningThreshold) {
        return new Shop(id, owner, ownerName, name, chestUnits, sign, signSupport,
                capacitySlots, enabled, offers, stock, proceeds, createdAt, stats, warningThreshold);
    }

    private static void validateCapacity(int capacitySlots) {
        if (capacitySlots < 1 || capacitySlots > MAX_CAPACITY_SLOTS) {
            throw new IllegalArgumentException("invalid shop capacity: " + capacitySlots);
        }
    }

    public UUID id() {
        return id;
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name.length() > 48 ? name.substring(0, 48) : name;
        }
    }

    public void setOwner(UUID newOwner, String newOwnerName) {
        this.owner = Objects.requireNonNull(newOwner, "newOwner");
        this.ownerName = newOwnerName == null ? "" : newOwnerName;
    }

    /** All chest blocks, flattened across every linked chest unit. */
    public List<LocationKey> chestBlocks() {
        List<LocationKey> all = new ArrayList<>();
        for (List<LocationKey> unit : chestUnits) {
            all.addAll(unit);
        }
        return List.copyOf(all);
    }

    /** Ordered chest units; each inner list is one single or double chest. */
    public List<List<LocationKey>> chestUnits() {
        List<List<LocationKey>> copy = new ArrayList<>();
        for (List<LocationKey> unit : chestUnits) {
            copy.add(List.copyOf(unit));
        }
        return List.copyOf(copy);
    }

    public LocationKey primaryChest() {
        return chestUnits.get(0).get(0);
    }

    public boolean isChestBlock(LocationKey location) {
        for (List<LocationKey> unit : chestUnits) {
            if (unit.contains(location)) {
                return true;
            }
        }
        return false;
    }

    public int chestUnitCount() {
        return chestUnits.size();
    }

    /** Replaces the entire layout with one unit; kept for legacy relink flows. */
    public void setChestBlocks(List<LocationKey> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException("shop requires a chest");
        }
        setChestUnits(List.of(blocks));
    }

    public void setChestUnits(List<List<LocationKey>> units) {
        if (units == null || units.isEmpty()) {
            throw new IllegalArgumentException("shop requires a chest");
        }
        if (units.size() > MAX_CHEST_UNITS) {
            throw new IllegalArgumentException("too many chest units");
        }
        List<List<LocationKey>> copy = new ArrayList<>();
        for (List<LocationKey> unit : units) {
            if (unit == null || unit.isEmpty()) {
                throw new IllegalArgumentException("chest unit must not be empty");
            }
            copy.add(List.copyOf(unit));
        }
        chestUnits.clear();
        chestUnits.addAll(copy);
    }

    public void addChestUnit(List<LocationKey> unit) {
        if (chestUnits.size() >= MAX_CHEST_UNITS) {
            throw new IllegalStateException("maximum chest units reached");
        }
        chestUnits.add(List.copyOf(unit));
    }

    public void removeChestUnit(int index) {
        if (chestUnits.size() <= 1) {
            throw new IllegalStateException("a shop needs at least one chest unit");
        }
        chestUnits.remove(index);
    }

    public LocationKey sign() {
        return sign;
    }

    public LocationKey signSupport() {
        return signSupport;
    }

    public void setSign(LocationKey sign, LocationKey support) {
        this.sign = sign;
        this.signSupport = support;
    }

    public int capacitySlots() {
        return capacitySlots;
    }

    public void setCapacitySlots(int capacitySlots) {
        validateCapacity(capacitySlots);
        this.capacitySlots = capacitySlots;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean orphaned() {
        return orphaned;
    }

    public void setOrphaned(boolean orphaned) {
        this.orphaned = orphaned;
    }

    public long createdAt() {
        return createdAt;
    }

    /** Marker so callers can persist a fresh updated_at value without keeping a second clock field. */
    public long updatedAt() {
        return Math.max(updatedAtSeed, System.currentTimeMillis());
    }

    public List<TradeOffer> offers() {
        return List.copyOf(offers);
    }

    public Optional<TradeOffer> findOffer(UUID offerId) {
        for (TradeOffer offer : offers) {
            if (offer.id().equals(offerId)) {
                return Optional.of(offer);
            }
        }
        return Optional.empty();
    }

    public void addOffer(TradeOffer offer) {
        offers.add(Objects.requireNonNull(offer, "offer"));
    }

    public void replaceOffer(TradeOffer offer) {
        Objects.requireNonNull(offer, "offer");
        for (int i = 0; i < offers.size(); i++) {
            if (offers.get(i).id().equals(offer.id())) {
                offers.set(i, offer);
                return;
            }
        }
        throw new IllegalArgumentException("offer not found: " + offer.id());
    }

    public void removeOffer(UUID offerId) {
        offers.removeIf(offer -> offer.id().equals(offerId));
    }

    /** Restores an offer snapshot after a failed persistence attempt. */
    public void replaceOffers(List<TradeOffer> replacement) {
        Objects.requireNonNull(replacement, "replacement");
        offers.clear();
        offers.addAll(replacement);
    }

    public StockStorage stock() {
        return stock;
    }

    public ProceedsStorage proceeds() {
        return proceeds;
    }

    /** Replaces the stock snapshot as part of an applied transaction. */
    public void setStock(StockStorage newStock) {
        this.stock = Objects.requireNonNull(newStock, "newStock");
    }

    /** Replaces the pending-proceeds snapshot as part of an applied transaction. */
    public void setProceeds(ProceedsStorage newProceeds) {
        this.proceeds = Objects.requireNonNull(newProceeds, "newProceeds");
    }

    public ShopStats stats() {
        return stats;
    }

    public void setStats(ShopStats stats) {
        this.stats = Objects.requireNonNull(stats, "stats");
    }

    public int warningThreshold() {
        return warningThreshold;
    }

    public void setWarningThreshold(int threshold) {
        this.warningThreshold = Math.max(0, threshold);
    }

    public ReentrantLock lock() {
        return lock;
    }

    public boolean hasProceeds() {
        return !proceeds.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Shop other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Shop{" + id + ", owner=" + owner + ", chest=" + primaryChest()
                + ", chests=" + chestUnitCount() + ", sign=" + sign + "}";
    }
}
