package dev.bartershop.storage;

import dev.bartershop.core.item.ItemData;
import dev.bartershop.core.model.LocationKey;
import dev.bartershop.core.model.PaymentRequirement;
import dev.bartershop.core.model.Shop;
import dev.bartershop.core.model.ShopStats;
import dev.bartershop.core.model.StockStack;
import dev.bartershop.core.model.TradeMode;
import dev.bartershop.core.model.TradeOffer;
import dev.bartershop.core.proceeds.ProceedsStorage;
import dev.bartershop.core.stock.StockStorage;
import dev.bartershop.core.trade.TradePlan;
import dev.bartershop.storage.StorageRecords.AuditEntry;
import dev.bartershop.storage.StorageRecords.HistoryEntry;
import dev.bartershop.storage.StorageRecords.JournalRecord;
import dev.bartershop.storage.StorageRecords.JournalType;
import dev.bartershop.storage.StorageRecords.LoadedShops;
import dev.bartershop.storage.StorageRecords.PendingSummary;
import dev.bartershop.storage.StorageRecords.SignClaim;
import dev.bartershop.storage.StorageRecords.TransferRequest;
import dev.bartershop.storage.JournalCodec.TradePayload;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * All SQL for BarterShop. The connection is owned and serialised by
 * {@link dev.bartershop.storage.Database}; this class performs no threading
 * itself. Every item-moving operation runs inside a single SQLite transaction.
 */
public final class ShopRepository {

    public static final int SCHEMA_VERSION = 2;

    private final Connection connection;

    public ShopRepository(Connection connection) {
        this.connection = connection;
    }

    // ---------------------------------------------------------------- schema

    public void migrate() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        connection.setAutoCommit(true);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
        }
        int version;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
            version = rs.next() ? rs.getInt(1) : 0;
        }
        if (version == 0) {
            createSchema();
            // A legacy database may predate user_version pragmas entirely.
            // Bring any existing tables up to the current shape before the
            // first read, otherwise every shop would be skipped as corrupt.
            inTransaction(c -> {
                ensurePhase2Columns(c);
                createPhase2Tables(c);
                backfillPhase2(c);
                setSchemaVersion(c, SCHEMA_VERSION);
                return null;
            });
        } else if (version > SCHEMA_VERSION) {
            throw new SQLException("database schema version " + version
                    + " is newer than this plugin supports (" + SCHEMA_VERSION + ")");
        } else if (version == 1) {
            migrateVersion1();
        }
    }

    /**
     * Adds Phase 2 tables without rewriting existing rows. Old single-payment
     * offers and two-block chest columns remain readable as a fallback.
     */
    private void migrateVersion1() throws SQLException {
        // DDL and backfill are one transaction so a crash cannot leave a
        // half-migrated schema that refuses to start next time.
        inTransaction(c -> {
            ensurePhase2Columns(c);
            createPhase2Tables(c);
            backfillPhase2(c);
            setSchemaVersion(c, SCHEMA_VERSION);
            return null;
        });
    }

    /** Adds Phase 2 columns to a table created by Phase 1, idempotently. */
    private static void ensurePhase2Columns(Connection c) throws SQLException {
        addColumnIfMissing(c, "shops", "warning_threshold", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "shops", "stats_trades", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "shops", "stats_items_in", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "shops", "stats_items_out", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "shops", "stats_last_trade", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "shops", "stats_unique_customers", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "offers", "expires_at", "INTEGER NOT NULL DEFAULT 0");
    }

    private static void addColumnIfMissing(Connection c, String table, String column, String definition)
            throws SQLException {
        if (!columnExists(c, table, column)) {
            try (Statement statement = c.createStatement()) {
                statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        }
    }

    private static boolean columnExists(Connection c, String table, String column) throws SQLException {
        try (Statement statement = c.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Copies legacy shop/chest/offer columns into the Phase 2 normalised tables. */
    private static void backfillPhase2(Connection c) throws SQLException {
        try (Statement statement = c.createStatement()) {
            statement.execute("""
                    INSERT OR IGNORE INTO shop_chests (shop_id, unit, slot, world, x, y, z)
                    SELECT id, 0, 0, chest_world, chest_x, chest_y, chest_z FROM shops
                    """);
            statement.execute("""
                    INSERT OR IGNORE INTO shop_chests (shop_id, unit, slot, world, x, y, z)
                    SELECT id, 0, 1, chest2_world, chest2_x, chest2_y, chest2_z
                    FROM shops WHERE chest2_world IS NOT NULL
                    """);
            statement.execute("""
                    INSERT OR IGNORE INTO offer_payments
                        (offer_id, idx, material, signature, serialized, max_stack, amount, exact_match)
                    SELECT id, 0, pay_material, pay_signature, pay_serialized, pay_max_stack, pay_amount, exact_pay
                    FROM offers
                    """);
        }
    }

    private static void setSchemaVersion(Connection c, int version) throws SQLException {
        try (Statement statement = c.createStatement()) {
            statement.execute("PRAGMA user_version=" + version);
        }
    }

    private void createPhase2Tables(Connection c) throws SQLException {
        try (Statement statement = c.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS shop_chests (
                        shop_id TEXT NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                        unit INTEGER NOT NULL,
                        slot INTEGER NOT NULL,
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        PRIMARY KEY (shop_id, unit, slot)
                    )
                    """);
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_shop_chests_location ON shop_chests(world, x, y, z)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS offer_payments (
                        offer_id TEXT NOT NULL REFERENCES offers(id) ON DELETE CASCADE,
                        idx INTEGER NOT NULL,
                        material TEXT NOT NULL,
                        signature TEXT NOT NULL,
                        serialized TEXT NOT NULL,
                        max_stack INTEGER NOT NULL,
                        amount INTEGER NOT NULL,
                        exact_match INTEGER NOT NULL,
                        PRIMARY KEY (offer_id, idx)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS shop_customers (
                        shop_id TEXT NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                        customer_uuid TEXT NOT NULL,
                        first_trade INTEGER NOT NULL,
                        PRIMARY KEY (shop_id, customer_uuid)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS transfer_requests (
                        id TEXT PRIMARY KEY,
                        shop_id TEXT NOT NULL,
                        from_uuid TEXT NOT NULL,
                        from_name TEXT NOT NULL,
                        to_uuid TEXT NOT NULL,
                        to_name TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_transfer_shop ON transfer_requests(shop_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS ix_transfer_to ON transfer_requests(to_uuid, created_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS ix_transfer_from ON transfer_requests(from_uuid, created_at)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS player_settings (
                        player_uuid TEXT PRIMARY KEY,
                        language TEXT NOT NULL
                    )
                    """);
        }
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS shops (
                        id TEXT PRIMARY KEY,
                        owner_uuid TEXT NOT NULL,
                        owner_name TEXT NOT NULL,
                        name TEXT NOT NULL,
                        chest_world TEXT NOT NULL,
                        chest_x INTEGER NOT NULL,
                        chest_y INTEGER NOT NULL,
                        chest_z INTEGER NOT NULL,
                        chest2_world TEXT,
                        chest2_x INTEGER,
                        chest2_y INTEGER,
                        chest2_z INTEGER,
                        sign_world TEXT,
                        sign_x INTEGER,
                        sign_y INTEGER,
                        sign_z INTEGER,
                        support_world TEXT,
                        support_x INTEGER,
                        support_y INTEGER,
                        support_z INTEGER,
                        capacity_slots INTEGER NOT NULL,
                        enabled INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        warning_threshold INTEGER NOT NULL DEFAULT 0,
                        stats_trades INTEGER NOT NULL DEFAULT 0,
                        stats_items_in INTEGER NOT NULL DEFAULT 0,
                        stats_items_out INTEGER NOT NULL DEFAULT 0,
                        stats_last_trade INTEGER NOT NULL DEFAULT 0,
                        stats_unique_customers INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_shops_chest ON shops(chest_world, chest_x, chest_y, chest_z)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_shops_chest2 ON shops(chest2_world, chest2_x, chest2_y, chest2_z)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_shops_sign ON shops(sign_world, sign_x, sign_y, sign_z)");
            statement.execute("CREATE INDEX IF NOT EXISTS ix_shops_owner ON shops(owner_uuid)");

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS offers (
                        id TEXT PRIMARY KEY,
                        shop_id TEXT NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                        sort_order INTEGER NOT NULL,
                        mode TEXT NOT NULL,
                        pay_material TEXT NOT NULL,
                        pay_signature TEXT NOT NULL,
                        pay_serialized TEXT NOT NULL,
                        pay_max_stack INTEGER NOT NULL,
                        pay_amount INTEGER NOT NULL,
                        receive_material TEXT NOT NULL,
                        receive_signature TEXT NOT NULL,
                        receive_serialized TEXT NOT NULL,
                        receive_max_stack INTEGER NOT NULL,
                        receive_amount INTEGER NOT NULL,
                        exact_pay INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS ix_offers_shop ON offers(shop_id, sort_order)");

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS stock (
                        shop_id TEXT NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                        slot INTEGER NOT NULL,
                        material TEXT NOT NULL,
                        signature TEXT NOT NULL,
                        serialized TEXT NOT NULL,
                        max_stack INTEGER NOT NULL,
                        amount INTEGER NOT NULL,
                        PRIMARY KEY (shop_id, slot)
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS pending_proceeds (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        shop_id TEXT,
                        owner_uuid TEXT NOT NULL,
                        material TEXT NOT NULL,
                        signature TEXT NOT NULL,
                        serialized TEXT NOT NULL,
                        max_stack INTEGER NOT NULL,
                        amount INTEGER NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS ix_proceeds_owner ON pending_proceeds(owner_uuid)");
            statement.execute("CREATE INDEX IF NOT EXISTS ix_proceeds_shop ON pending_proceeds(shop_id)");

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        shop_id TEXT,
                        owner_uuid TEXT NOT NULL,
                        actor_uuid TEXT,
                        actor_name TEXT,
                        type TEXT NOT NULL,
                        description TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS ix_history_shop ON history(shop_id, id)");
            statement.execute("CREATE INDEX IF NOT EXISTS ix_history_owner ON history(owner_uuid, id)");

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS audit_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        actor_uuid TEXT,
                        actor_name TEXT,
                        action TEXT NOT NULL,
                        details TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sign_claims (
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        PRIMARY KEY (world, x, y, z)
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS journals (
                        id TEXT PRIMARY KEY,
                        type TEXT NOT NULL,
                        shop_id TEXT,
                        player_uuid TEXT,
                        payload TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);
        }
        createPhase2Tables(connection);
    }

    // ----------------------------------------------------------- transactions

    private <T> T inTransaction(SqlWork<T> work) throws SQLException {
        boolean previous = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = work.run(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        } finally {
            connection.setAutoCommit(previous);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    // ------------------------------------------------------------------ load

    public LoadedShops loadAll() throws SQLException {
        List<ShopHeader> headers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM shops")) {
            while (rs.next()) {
                try {
                    headers.add(readHeader(rs));
                } catch (RuntimeException | SQLException e) {
                    warnings.add("Skipped corrupt shop row: " + e.getMessage());
                }
            }
        }
        List<Shop> shops = new ArrayList<>();
        for (ShopHeader header : headers) {
            try {
                shops.add(readShop(header, warnings));
            } catch (RuntimeException | SQLException e) {
                warnings.add("Skipped corrupt shop " + header.id + ": " + e.getMessage());
            }
        }
        return new LoadedShops(shops, warnings);
    }

    private ShopHeader readHeader(ResultSet rs) throws SQLException {
        UUID id = UUID.fromString(rs.getString("id"));
        UUID owner = UUID.fromString(rs.getString("owner_uuid"));
        List<List<LocationKey>> chestUnits = loadChestUnits(id);
        if (chestUnits.isEmpty()) {
            List<LocationKey> legacy = new ArrayList<>();
            LocationKey primary = location(rs, "chest_world", "chest_x", "chest_y", "chest_z");
            if (primary != null) {
                legacy.add(primary);
                LocationKey second = location(rs, "chest2_world", "chest2_x", "chest2_y", "chest2_z");
                if (second != null) {
                    legacy.add(second);
                }
                chestUnits = List.of(legacy);
            }
        }
        if (chestUnits.isEmpty()) {
            throw new IllegalArgumentException("missing primary chest for shop " + id);
        }
        ShopStats stats = new ShopStats(
                rs.getInt("stats_trades"),
                rs.getLong("stats_items_in"),
                rs.getLong("stats_items_out"),
                rs.getLong("stats_last_trade"),
                rs.getInt("stats_unique_customers"));
        return new ShopHeader(
                id,
                owner,
                rs.getString("owner_name"),
                rs.getString("name"),
                chestUnits,
                location(rs, "sign_world", "sign_x", "sign_y", "sign_z"),
                location(rs, "support_world", "support_x", "support_y", "support_z"),
                rs.getInt("capacity_slots"),
                rs.getInt("enabled") != 0,
                rs.getLong("created_at"),
                stats,
                rs.getInt("warning_threshold"));
    }

    private List<List<LocationKey>> loadChestUnits(UUID shopId) throws SQLException {
        Map<Integer, List<LocationKey>> units = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT unit, world, x, y, z FROM shop_chests WHERE shop_id = ? ORDER BY unit, slot")) {
            ps.setString(1, shopId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    units.computeIfAbsent(rs.getInt("unit"), key -> new ArrayList<>())
                            .add(new LocationKey(rs.getString("world"), rs.getInt("x"),
                                    rs.getInt("y"), rs.getInt("z")));
                }
            }
        }
        return new ArrayList<>(units.values());
    }

    private Shop readShop(ShopHeader header, List<String> warnings) throws SQLException {
        if (header.capacity < 1) {
            throw new IllegalArgumentException("invalid capacity " + header.capacity);
        }
        List<TradeOffer> offers = loadOffers(header.id, warnings);
        List<StockStack> stockStacks = loadStock(header.id, warnings);
        int capacity = fitCapacity(header.capacity, stockStacks.size(), warnings);
        StockStorage stock;
        try {
            stock = StockStorage.restore(capacity, stockStacks);
        } catch (IllegalArgumentException e) {
            warnings.add("Shop " + header.id + " stock exceeded the hard capacity limit; truncating: "
                    + e.getMessage());
            stock = StockStorage.restore(capacity,
                    stockStacks.subList(0, Math.min(capacity, stockStacks.size())));
        }
        ProceedsStorage proceeds = ProceedsStorage.restore(loadProceeds(header.id, null, warnings));
        return Shop.withChestUnits(
                header.id,
                header.owner,
                header.ownerName,
                header.name,
                header.chestUnits,
                header.sign,
                header.support,
                capacity,
                header.enabled,
                offers,
                stock,
                proceeds,
                header.createdAt,
                header.stats,
                header.warningThreshold);
    }

    /**
     * Grows the stored capacity when it no longer covers the persisted stacks.
     * Items are never dropped just because a capacity value is stale or was
     * edited by hand; only the hard slot limit can force truncation.
     */
    static int fitCapacity(int storedCapacity, int stacks, List<String> warnings) {
        if (stacks <= storedCapacity) {
            return storedCapacity;
        }
        int grown = Math.min(dev.bartershop.core.model.Shop.MAX_CAPACITY_SLOTS, Math.max(stacks, storedCapacity));
        warnings.add("Capacity " + storedCapacity + " did not cover " + stacks
                + " stock stacks; restored as " + grown + " to preserve items");
        return grown;
    }

    private record ShopHeader(UUID id, UUID owner, String ownerName, String name,
                              List<List<LocationKey>> chestUnits, LocationKey sign, LocationKey support,
                              int capacity, boolean enabled, long createdAt,
                              ShopStats stats, int warningThreshold) {
    }

    private List<TradeOffer> loadOffers(UUID shopId, List<String> warnings) throws SQLException {
        List<TradeOffer> offers = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM offers WHERE shop_id = ? ORDER BY sort_order")) {
            ps.setString(1, shopId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        UUID offerId = UUID.fromString(rs.getString("id"));
                        List<PaymentRequirement> payments = loadPayments(offerId);
                        if (payments.isEmpty()) {
                            payments = List.of(new PaymentRequirement(
                                    item(rs, "pay_"), rs.getInt("pay_amount"),
                                    rs.getInt("exact_pay") != 0));
                        }
                        offers.add(new TradeOffer(
                                offerId,
                                TradeMode.valueOf(rs.getString("mode")),
                                payments,
                                item(rs, "receive_"),
                                rs.getInt("receive_amount"),
                                rs.getInt("exact_pay") != 0,
                                rs.getLong("expires_at")));
                    } catch (RuntimeException | SQLException e) {
                        warnings.add("Shop " + shopId + " has a corrupt offer: " + e.getMessage());
                    }
                }
            }
        }
        return offers;
    }

    private List<PaymentRequirement> loadPayments(UUID offerId) throws SQLException {
        List<PaymentRequirement> payments = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM offer_payments WHERE offer_id = ? ORDER BY idx")) {
            ps.setString(1, offerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    payments.add(new PaymentRequirement(
                            item(rs, ""), rs.getInt("amount"), rs.getInt("exact_match") != 0));
                }
            }
        }
        return payments;
    }

    private List<StockStack> loadStock(UUID shopId, List<String> warnings) throws SQLException {
        List<StockStack> stacks = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM stock WHERE shop_id = ? ORDER BY slot")) {
            ps.setString(1, shopId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        stacks.add(new StockStack(item(rs, ""), rs.getInt("amount")));
                    } catch (RuntimeException e) {
                        warnings.add("Shop " + shopId + " has a corrupt stock entry: " + e.getMessage());
                    }
                }
            }
        }
        return stacks;
    }

    private List<StockStack> loadProceeds(UUID shopId, UUID owner, List<String> warnings) throws SQLException {
        List<StockStack> stacks = new ArrayList<>();
        String sql = shopId != null
                ? "SELECT * FROM pending_proceeds WHERE shop_id = ? ORDER BY id"
                : "SELECT * FROM pending_proceeds WHERE shop_id IS NULL AND owner_uuid = ? ORDER BY id";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, shopId != null ? shopId.toString() : owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        stacks.add(new StockStack(item(rs, ""), rs.getInt("amount")));
                    } catch (RuntimeException e) {
                        warnings.add("Corrupt pending-proceeds entry skipped: " + e.getMessage());
                    }
                }
            }
        }
        return stacks;
    }

    // ------------------------------------------------------------- shop writes

    public void insertShop(Shop shop) throws SQLException {
        inTransaction(c -> {
            insertShopRow(c, shop);
            replaceChests(c, shop);
            replaceOffers(c, shop);
            replaceStock(c, shop.id(), shop.stock());
            replaceProceeds(c, shop.id(), shop.owner(), shop.proceeds());
            return null;
        });
    }

    public void saveShopMeta(Shop shop) throws SQLException {
        inTransaction(c -> {
            updateShopRow(c, shop);
            replaceOffers(c, shop);
            return null;
        });
    }

    public void saveEnabled(Shop shop) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE shops SET enabled = ?, updated_at = ? WHERE id = ?")) {
            ps.setInt(1, shop.enabled() ? 1 : 0);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, shop.id().toString());
            ps.executeUpdate();
        }
    }

    public void saveLocations(Shop shop) throws SQLException {
        inTransaction(c -> {
            updateShopRow(c, shop);
            replaceChests(c, shop);
            return null;
        });
    }

    public void saveWarningThreshold(Shop shop) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE shops SET warning_threshold = ?, updated_at = ? WHERE id = ?")) {
            ps.setInt(1, shop.warningThreshold());
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, shop.id().toString());
            ps.executeUpdate();
        }
    }

    public void saveStock(Shop shop) throws SQLException {
        inTransaction(c -> {
            replaceStock(c, shop.id(), shop.stock());
            return null;
        });
    }

    public void saveProceeds(Shop shop) throws SQLException {
        inTransaction(c -> {
            replaceProceeds(c, shop.id(), shop.owner(), shop.proceeds());
            return null;
        });
    }

    public void saveOffers(Shop shop) throws SQLException {
        inTransaction(c -> {
            replaceOffers(c, shop);
            return null;
        });
    }

    private void insertShopRow(Connection c, Shop shop) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shops (id, owner_uuid, owner_name, name,
                    chest_world, chest_x, chest_y, chest_z,
                    chest2_world, chest2_x, chest2_y, chest2_z,
                    sign_world, sign_x, sign_y, sign_z,
                    support_world, support_x, support_y, support_z,
                    capacity_slots, enabled, created_at, updated_at,
                    warning_threshold, stats_trades, stats_items_in, stats_items_out,
                    stats_last_trade, stats_unique_customers)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            fillShopRow(ps, shop, true);
            ps.executeUpdate();
        }
    }

    private void updateShopRow(Connection c, Shop shop) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE shops SET owner_uuid = ?, owner_name = ?, name = ?,
                    chest_world = ?, chest_x = ?, chest_y = ?, chest_z = ?,
                    chest2_world = ?, chest2_x = ?, chest2_y = ?, chest2_z = ?,
                    sign_world = ?, sign_x = ?, sign_y = ?, sign_z = ?,
                    support_world = ?, support_x = ?, support_y = ?, support_z = ?,
                    capacity_slots = ?, enabled = ?, updated_at = ?,
                    warning_threshold = ?, stats_trades = ?, stats_items_in = ?,
                    stats_items_out = ?, stats_last_trade = ?, stats_unique_customers = ?
                WHERE id = ?
                """)) {
            int i = 1;
            ps.setString(i++, shop.owner().toString());
            ps.setString(i++, shop.ownerName());
            ps.setString(i++, shop.name());
            LocationKey primary = shop.primaryChest();
            ps.setString(i++, primary.world());
            ps.setInt(i++, primary.x());
            ps.setInt(i++, primary.y());
            ps.setInt(i++, primary.z());
            LocationKey second = shop.chestBlocks().size() > 1 ? shop.chestBlocks().get(1) : null;
            setNullableLocation(ps, i, second);
            i += 4;
            setNullableLocation(ps, i, shop.sign());
            i += 4;
            setNullableLocation(ps, i, shop.signSupport());
            i += 4;
            ps.setInt(i++, shop.capacitySlots());
            ps.setInt(i++, shop.enabled() ? 1 : 0);
            ps.setLong(i++, System.currentTimeMillis());
            ps.setInt(i++, shop.warningThreshold());
            setStats(ps, i, shop.stats());
            i += 5;
            ps.setString(i, shop.id().toString());
            ps.executeUpdate();
        }
    }

    private void fillShopRow(PreparedStatement ps, Shop shop, boolean insert) throws SQLException {
        int i = 1;
        ps.setString(i++, shop.id().toString());
        ps.setString(i++, shop.owner().toString());
        ps.setString(i++, shop.ownerName());
        ps.setString(i++, shop.name());
        LocationKey primary = shop.primaryChest();
        ps.setString(i++, primary.world());
        ps.setInt(i++, primary.x());
        ps.setInt(i++, primary.y());
        ps.setInt(i++, primary.z());
        LocationKey second = shop.chestBlocks().size() > 1 ? shop.chestBlocks().get(1) : null;
        setNullableLocation(ps, i, second);
        i += 4;
        setNullableLocation(ps, i, shop.sign());
        i += 4;
        setNullableLocation(ps, i, shop.signSupport());
        i += 4;
        ps.setInt(i++, shop.capacitySlots());
        ps.setInt(i++, shop.enabled() ? 1 : 0);
        ps.setLong(i++, shop.createdAt());
        ps.setLong(i++, System.currentTimeMillis());
        ps.setInt(i++, shop.warningThreshold());
        setStats(ps, i, shop.stats());
    }

    private static void setStats(PreparedStatement ps, int start, ShopStats stats) throws SQLException {
        ps.setInt(start, stats.totalTrades());
        ps.setLong(start + 1, stats.itemsIn());
        ps.setLong(start + 2, stats.itemsOut());
        ps.setLong(start + 3, stats.lastTradeAt());
        ps.setInt(start + 4, stats.uniqueCustomers());
    }

    private void replaceChests(Connection c, Shop shop) throws SQLException {
        try (PreparedStatement delete = c.prepareStatement(
                "DELETE FROM shop_chests WHERE shop_id = ?")) {
            delete.setString(1, shop.id().toString());
            delete.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shop_chests (shop_id, unit, slot, world, x, y, z)
                VALUES (?,?,?,?,?,?,?)
                """)) {
            int unitIndex = 0;
            for (List<LocationKey> unit : shop.chestUnits()) {
                int slot = 0;
                for (LocationKey key : unit) {
                    ps.setString(1, shop.id().toString());
                    ps.setInt(2, unitIndex);
                    ps.setInt(3, slot++);
                    ps.setString(4, key.world());
                    ps.setInt(5, key.x());
                    ps.setInt(6, key.y());
                    ps.setInt(7, key.z());
                    ps.addBatch();
                }
                unitIndex++;
            }
            ps.executeBatch();
        }
    }

    private void replaceOffers(Connection c, Shop shop) throws SQLException {
        try (PreparedStatement delete = c.prepareStatement("DELETE FROM offers WHERE shop_id = ?")) {
            delete.setString(1, shop.id().toString());
            delete.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO offers (id, shop_id, sort_order, mode,
                    pay_material, pay_signature, pay_serialized, pay_max_stack, pay_amount,
                    receive_material, receive_signature, receive_serialized, receive_max_stack,
                    receive_amount, exact_pay, expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            int order = 0;
            for (TradeOffer offer : shop.offers()) {
                int i = 1;
                ps.setString(i++, offer.id().toString());
                ps.setString(i++, shop.id().toString());
                ps.setInt(i++, order++);
                ps.setString(i++, offer.mode().name());
                PaymentRequirement first = offer.payments().get(0);
                setItem(ps, i, first.item());
                i += 4;
                ps.setInt(i++, first.amount());
                setItem(ps, i, offer.receiveItem());
                i += 4;
                ps.setInt(i++, offer.receiveAmount());
                ps.setInt(i++, offer.exactPay() ? 1 : 0);
                ps.setLong(i, offer.expiresAt());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO offer_payments
                    (offer_id, idx, material, signature, serialized, max_stack, amount, exact_match)
                VALUES (?,?,?,?,?,?,?,?)
                """)) {
            for (TradeOffer offer : shop.offers()) {
                int index = 0;
                for (PaymentRequirement payment : offer.payments()) {
                    int i = 1;
                    ps.setString(i++, offer.id().toString());
                    ps.setInt(i++, index++);
                    setItem(ps, i, payment.item());
                    i += 4;
                    ps.setInt(i++, payment.amount());
                    ps.setInt(i, payment.exact() ? 1 : 0);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    private void replaceStock(Connection c, UUID shopId, StockStorage stock) throws SQLException {
        try (PreparedStatement delete = c.prepareStatement("DELETE FROM stock WHERE shop_id = ?")) {
            delete.setString(1, shopId.toString());
            delete.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO stock (shop_id, slot, material, signature, serialized, max_stack, amount)
                VALUES (?,?,?,?,?,?,?)
                """)) {
            int slot = 0;
            for (StockStack stack : stock.stacks()) {
                int i = 1;
                ps.setString(i++, shopId.toString());
                ps.setInt(i++, slot++);
                setItem(ps, i, stack.item());
                i += 4;
                ps.setInt(i, stack.amount());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void replaceProceeds(Connection c, UUID shopId, UUID owner, ProceedsStorage proceeds) throws SQLException {
        try (PreparedStatement delete = c.prepareStatement("DELETE FROM pending_proceeds WHERE shop_id = ?")) {
            delete.setString(1, shopId.toString());
            delete.executeUpdate();
        }
        insertProceeds(c, shopId, owner, proceeds);
    }

    private void insertProceeds(Connection c, UUID shopId, UUID owner, ProceedsStorage proceeds) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO pending_proceeds
                    (shop_id, owner_uuid, material, signature, serialized, max_stack, amount, created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """)) {
            long now = System.currentTimeMillis();
            for (StockStack stack : proceeds.stacks()) {
                int i = 1;
                if (shopId != null) {
                    ps.setString(i++, shopId.toString());
                } else {
                    ps.setString(i++, null);
                }
                ps.setString(i++, owner == null ? "" : owner.toString());
                setItem(ps, i, stack.item());
                i += 4;
                ps.setInt(i++, stack.amount());
                ps.setLong(i, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void replaceMailbox(UUID owner, ProceedsStorage proceeds) throws SQLException {
        inTransaction(c -> {
            deleteMailbox(c, owner);
            insertProceeds(c, null, owner, proceeds);
            return null;
        });
    }

    public void addMailboxItems(UUID owner, List<StockStack> items, String reason) throws SQLException {
        if (items.isEmpty()) {
            return;
        }
        inTransaction(c -> {
            insertProceeds(c, null, owner, ProceedsStorage.restore(items));
            insertHistory(c, HistoryEntry.create(null, owner, null, "SYSTEM", "RECOVERY", reason));
            return null;
        });
    }

    private void deleteMailbox(Connection c, UUID owner) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM pending_proceeds WHERE shop_id IS NULL AND owner_uuid = ?")) {
            ps.setString(1, owner.toString());
            ps.executeUpdate();
        }
    }

    public ProceedsStorage loadMailbox(UUID owner) throws SQLException {
        return ProceedsStorage.restore(loadProceeds(null, owner, new ArrayList<>()));
    }

    public List<PendingSummary> findPendingSummaries(UUID owner) throws SQLException {
        Map<UUID, String> labels = new LinkedHashMap<>();
        Map<UUID, List<StockStack>> items = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT p.*, s.name AS shop_name
                FROM pending_proceeds p
                LEFT JOIN shops s ON s.id = p.shop_id
                WHERE p.owner_uuid = ?
                ORDER BY p.shop_id IS NULL, p.shop_id, p.id
                """)) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String rawShop = rs.getString("shop_id");
                    UUID shopId;
                    try {
                        shopId = rawShop == null ? null : UUID.fromString(rawShop);
                    } catch (IllegalArgumentException e) {
                        // A corrupt shop id must not hide every other pending entry.
                        continue;
                    }
                    String shopName = rs.getString("shop_name");
                    // Labels are resolved by the caller so they can be localised.
                    final String label = shopId == null || shopName == null ? "" : shopName;
                    labels.putIfAbsent(shopId, label);
                    try {
                        items.computeIfAbsent(shopId, key -> new ArrayList<>())
                                .add(new StockStack(item(rs, ""), rs.getInt("amount")));
                    } catch (RuntimeException ignored) {
                        // Corrupt proceeds row: skip but keep the rest visible.
                    }
                }
            }
        }
        List<PendingSummary> summaries = new ArrayList<>();
        for (Map.Entry<UUID, List<StockStack>> entry : items.entrySet()) {
            summaries.add(new PendingSummary(entry.getKey(),
                    labels.getOrDefault(entry.getKey(), ""), List.copyOf(entry.getValue())));
        }
        return summaries;
    }

    /**
     * Deletes a shop and atomically moves its stock and pending proceeds into
     * the owner's recovery mailbox so nothing is lost. The sign and chest are
     * detached by the service afterwards.
     */
    public void deleteShopToMailbox(Shop shop, String reason) throws SQLException {
        inTransaction(c -> {
            insertProceeds(c, null, shop.owner(), ProceedsStorage.restore(shop.stock().stacks()));
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE pending_proceeds SET shop_id = NULL WHERE shop_id = ?")) {
                ps.setString(1, shop.id().toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM journals WHERE shop_id = ?")) {
                ps.setString(1, shop.id().toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM stock WHERE shop_id = ?")) {
                ps.setString(1, shop.id().toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM offers WHERE shop_id = ?")) {
                ps.setString(1, shop.id().toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM transfer_requests WHERE shop_id = ?")) {
                ps.setString(1, shop.id().toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM shops WHERE id = ?")) {
                ps.setString(1, shop.id().toString());
                ps.executeUpdate();
            }
            insertHistory(c, HistoryEntry.create(
                    shop.id(), shop.owner(), null, "SYSTEM", "DELETE", reason));
            return null;
        });
    }

    /**
     * Transfers ownership. Proceeds earned before the transfer are moved to
     * the former owner's mailbox; the stock stays with the shop.
     */
    public void transferShop(Shop shop, UUID newOwner, String newOwnerName, String actorName) throws SQLException {
        inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM transfer_requests WHERE shop_id = ?")) {
                ps.setString(1, shop.id().toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE pending_proceeds SET shop_id = NULL WHERE shop_id = ?")) {
                ps.setString(1, shop.id().toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE shops SET owner_uuid = ?, owner_name = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, newOwner.toString());
                ps.setString(2, newOwnerName);
                ps.setLong(3, System.currentTimeMillis());
                ps.setString(4, shop.id().toString());
                ps.executeUpdate();
            }
            insertHistory(c, HistoryEntry.create(
                    shop.id(), shop.owner(), null, actorName, "TRANSFER",
                    "Ownership transferred to " + newOwnerName));
            return null;
        });
    }

    public void replaceTransferRequest(TransferRequest request) throws SQLException {
        inTransaction(c -> {
            try (PreparedStatement delete = c.prepareStatement(
                    "DELETE FROM transfer_requests WHERE shop_id = ?")) {
                delete.setString(1, request.shopId().toString());
                delete.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO transfer_requests
                        (id, shop_id, from_uuid, from_name, to_uuid, to_name, created_at, expires_at)
                    VALUES (?,?,?,?,?,?,?,?)
                    """)) {
                ps.setString(1, request.id().toString());
                ps.setString(2, request.shopId().toString());
                ps.setString(3, request.fromUuid().toString());
                ps.setString(4, request.fromName());
                ps.setString(5, request.toUuid().toString());
                ps.setString(6, request.toName());
                ps.setLong(7, request.createdAt());
                ps.setLong(8, request.expiresAt());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Optional<TransferRequest> loadTransferRequest(UUID requestId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT r.*, COALESCE(s.name, 'Deleted shop') AS shop_name
                FROM transfer_requests r LEFT JOIN shops s ON s.id = r.shop_id
                WHERE r.id = ?
                """)) {
            ps.setString(1, requestId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readTransfer(rs)) : Optional.empty();
            }
        }
    }

    public List<TransferRequest> loadTransferRequestsFor(UUID player) throws SQLException {
        List<TransferRequest> requests = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT r.*, COALESCE(s.name, 'Deleted shop') AS shop_name
                FROM transfer_requests r LEFT JOIN shops s ON s.id = r.shop_id
                WHERE r.to_uuid = ? OR r.from_uuid = ?
                ORDER BY r.created_at DESC
                """)) {
            ps.setString(1, player.toString());
            ps.setString(2, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    requests.add(readTransfer(rs));
                }
            }
        }
        return requests;
    }

    public Optional<TransferRequest> loadTransferRequestForShop(UUID shopId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT r.*, COALESCE(s.name, 'Deleted shop') AS shop_name
                FROM transfer_requests r LEFT JOIN shops s ON s.id = r.shop_id
                WHERE r.shop_id = ?
                """)) {
            ps.setString(1, shopId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readTransfer(rs)) : Optional.empty();
            }
        }
    }

    public void deleteTransferRequest(UUID requestId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM transfer_requests WHERE id = ?")) {
            ps.setString(1, requestId.toString());
            ps.executeUpdate();
        }
    }

    public void deleteTransferRequestsForShop(UUID shopId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM transfer_requests WHERE shop_id = ?")) {
            ps.setString(1, shopId.toString());
            ps.executeUpdate();
        }
    }

    public int deleteExpiredTransferRequests(long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM transfer_requests WHERE expires_at <= ?")) {
            ps.setLong(1, now);
            return ps.executeUpdate();
        }
    }

    private static TransferRequest readTransfer(ResultSet rs) throws SQLException {
        return new TransferRequest(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("shop_id")),
                rs.getString("shop_name"),
                UUID.fromString(rs.getString("from_uuid")),
                rs.getString("from_name"),
                UUID.fromString(rs.getString("to_uuid")),
                rs.getString("to_name"),
                rs.getLong("created_at"),
                rs.getLong("expires_at"));
    }

    public Map<UUID, String> loadPlayerLanguages() throws SQLException {
        Map<UUID, String> languages = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT player_uuid, language FROM player_settings")) {
            while (rs.next()) {
                try {
                    languages.put(UUID.fromString(rs.getString("player_uuid")), rs.getString("language"));
                } catch (IllegalArgumentException ignored) {
                    // Ignore a malformed UUID row rather than failing plugin startup.
                }
            }
        }
        return languages;
    }

    public void savePlayerLanguage(UUID player, String language) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO player_settings (player_uuid, language) VALUES (?,?)
                ON CONFLICT(player_uuid) DO UPDATE SET language = excluded.language
                """)) {
            ps.setString(1, player.toString());
            ps.setString(2, language);
            ps.executeUpdate();
        }
    }

    public void renameShop(UUID shopId, String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE shops SET name = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, name);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, shopId.toString());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------ transactions

    public void prepareJournal(JournalRecord journal) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO journals (id, type, shop_id, player_uuid, payload, created_at)
                VALUES (?,?,?,?,?,?)
                """)) {
            ps.setString(1, journal.id());
            ps.setString(2, journal.type().name());
            ps.setString(3, journal.shopId() == null ? null : journal.shopId().toString());
            ps.setString(4, journal.playerUuid() == null ? null : journal.playerUuid().toString());
            ps.setString(5, journal.payload());
            ps.setLong(6, journal.createdAt());
            ps.executeUpdate();
        }
    }

    public void abortJournal(String journalId) throws SQLException {
        if (journalId == null) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM journals WHERE id = ?")) {
            ps.setString(1, journalId);
            ps.executeUpdate();
        }
    }

    public void commitTrade(TradePlan plan) throws SQLException {
        inTransaction(c -> {
            if (plan.journalId() != null) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM journals WHERE id = ?")) {
                    ps.setString(1, plan.journalId());
                    ps.executeUpdate();
                }
            }
            replaceStock(c, plan.shop().id(), plan.stockAfter());
            replaceProceeds(c, plan.shop().id(), plan.shop().owner(), plan.proceedsAfter());
            insertHistory(c, HistoryEntry.create(
                    plan.shop().id(),
                    plan.shop().owner(),
                    plan.customerId(),
                    plan.customerName(),
                    "TRADE",
                    plan.historyDescription()));
            boolean newCustomer;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO shop_customers (shop_id, customer_uuid, first_trade) VALUES (?,?,?)")) {
                ps.setString(1, plan.shop().id().toString());
                ps.setString(2, plan.customerId().toString());
                ps.setLong(3, System.currentTimeMillis());
                newCustomer = ps.executeUpdate() > 0;
            }
            plan.setNewCustomer(newCustomer);
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE shops SET
                        stats_trades = stats_trades + 1,
                        stats_items_in = stats_items_in + ?,
                        stats_items_out = stats_items_out + ?,
                        stats_last_trade = ?,
                        stats_unique_customers = stats_unique_customers + ?,
                        updated_at = ?
                    WHERE id = ?
                    """)) {
                ps.setInt(1, plan.paymentItemCount());
                ps.setInt(2, plan.deliveredItemCount());
                ps.setLong(3, System.currentTimeMillis());
                ps.setInt(4, newCustomer ? 1 : 0);
                ps.setLong(5, System.currentTimeMillis());
                ps.setString(6, plan.shop().id().toString());
                ps.executeUpdate();
            }
            return null;
        });
    }


    public void beginCollection(JournalRecord journal, Shop shop, ProceedsStorage after) throws SQLException {
        inTransaction(c -> {
            insertJournal(c, journal);
            replaceProceeds(c, shop.id(), shop.owner(), after);
            return null;
        });
    }

    public void beginMailboxCollection(JournalRecord journal, UUID owner, ProceedsStorage after) throws SQLException {
        inTransaction(c -> {
            insertJournal(c, journal);
            deleteMailbox(c, owner);
            insertProceeds(c, null, owner, after);
            return null;
        });
    }

    public void rollbackCollection(String journalId, UUID shopId, UUID owner, ProceedsStorage before)
            throws SQLException {
        inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM journals WHERE id = ?")) {
                ps.setString(1, journalId);
                ps.executeUpdate();
            }
            if (shopId != null) {
                replaceProceeds(c, shopId, owner, before);
            } else {
                deleteMailbox(c, owner);
                insertProceeds(c, null, owner, before);
            }
            return null;
        });
    }

    public void completeCollection(String journalId, UUID shopId, UUID owner,
                                   ProceedsStorage after, HistoryEntry history) throws SQLException {
        inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM journals WHERE id = ?")) {
                ps.setString(1, journalId);
                ps.executeUpdate();
            }
            if (shopId != null) {
                replaceProceeds(c, shopId, owner, after);
            } else {
                deleteMailbox(c, owner);
                insertProceeds(c, null, owner, after);
            }
            insertHistory(c, history);
            return null;
        });
    }

    private void insertJournal(Connection c, JournalRecord journal) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO journals (id, type, shop_id, player_uuid, payload, created_at)
                VALUES (?,?,?,?,?,?)
                """)) {
            ps.setString(1, journal.id());
            ps.setString(2, journal.type().name());
            ps.setString(3, journal.shopId() == null ? null : journal.shopId().toString());
            ps.setString(4, journal.playerUuid() == null ? null : journal.playerUuid().toString());
            ps.setString(5, journal.payload());
            ps.setLong(6, journal.createdAt());
            ps.executeUpdate();
        }
    }

    // --------------------------------------------------------------- recovery

    public List<JournalRecord> loadJournals() throws SQLException {
        List<JournalRecord> journals = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM journals ORDER BY created_at")) {
            while (rs.next()) {
                String rawShop = rs.getString("shop_id");
                String rawPlayer = rs.getString("player_uuid");
                journals.add(new JournalRecord(
                        rs.getString("id"),
                        JournalType.valueOf(rs.getString("type")),
                        rawShop == null ? null : UUID.fromString(rawShop),
                        rawPlayer == null ? null : UUID.fromString(rawPlayer),
                        rs.getString("payload"),
                        rs.getLong("created_at")));
            }
        }
        return journals;
    }

    /**
     * Replays an interrupted trade by applying the journal's after-state.
     * Returns a human-readable recovery note.
     */
    public String recoverJournal(JournalRecord journal) throws SQLException {
        if (journal.type() == JournalType.TRADE) {
            TradePayload payload = JournalCodec.decodeTrade(journal.payload());
            Optional<OwnerCapacity> shopInfo = shopOwnerAndCapacity(journal.shopId());
            if (shopInfo.isEmpty()) {
                abortJournal(journal.id());
                return "discarded journal " + journal.id() + " for a shop that no longer exists";
            }
            int storedCapacity = shopInfo.get().capacity();
            int capacity = fitCapacity(storedCapacity, payload.stock().size(), new ArrayList<>());
            StockStorage stock;
            try {
                stock = StockStorage.restore(capacity, payload.stock());
            } catch (IllegalArgumentException e) {
                stock = StockStorage.restore(capacity,
                        payload.stock().subList(0, Math.min(capacity, payload.stock().size())));
            }
            ProceedsStorage proceeds = ProceedsStorage.restore(payload.proceeds());
            final StockStorage stockFinal = stock;
            inTransaction(c -> {
                if (capacity != storedCapacity) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE shops SET capacity_slots = ? WHERE id = ?")) {
                        ps.setInt(1, capacity);
                        ps.setString(2, journal.shopId().toString());
                        ps.executeUpdate();
                    }
                }
                replaceStock(c, journal.shopId(), stockFinal);
                replaceProceeds(c, journal.shopId(), shopInfo.get().owner(), proceeds);
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM journals WHERE id = ?")) {
                    ps.setString(1, journal.id());
                    ps.executeUpdate();
                }
                insertHistory(c, HistoryEntry.create(
                        journal.shopId(),
                        shopInfo.get().owner(),
                        journal.playerUuid(),
                        "",
                        "RECOVERY",
                        "Recovered interrupted trade (journal " + journal.id() + ")"));
                touchShop(c, journal.shopId());
                return null;
            });
            return "rolled forward interrupted trade " + journal.id();
        }

        List<StockStack> items = JournalCodec.decodeItems(journal.payload());
        UUID owner = journal.playerUuid();
        if (owner == null) {
            abortJournal(journal.id());
            return "discarded collection journal " + journal.id() + " without an owner";
        }
        inTransaction(c -> {
            if (journal.shopId() != null && shopExists(c, journal.shopId())) {
                insertProceeds(c, journal.shopId(), owner, ProceedsStorage.restore(items));
            } else {
                insertProceeds(c, null, owner, ProceedsStorage.restore(items));
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM journals WHERE id = ?")) {
                ps.setString(1, journal.id());
                ps.executeUpdate();
            }
            insertHistory(c, HistoryEntry.create(
                    journal.shopId(),
                    owner,
                    null,
                    "SYSTEM",
                    "RECOVERY",
                    "Returned an interrupted collection to pending proceeds"));
            return null;
        });
        return "rolled back interrupted collection " + journal.id();
    }

    private Optional<OwnerCapacity> shopOwnerAndCapacity(UUID shopId) throws SQLException {
        if (shopId == null) {
            return Optional.empty();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT owner_uuid, capacity_slots FROM shops WHERE id = ?")) {
            ps.setString(1, shopId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new OwnerCapacity(
                        UUID.fromString(rs.getString("owner_uuid")), rs.getInt("capacity_slots")));
            }
        }
    }

    private boolean shopExists(Connection c, UUID shopId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM shops WHERE id = ?")) {
            ps.setString(1, shopId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private record OwnerCapacity(UUID owner, int capacity) {
    }

    // ---------------------------------------------------------------- history

    public void addHistory(HistoryEntry entry) throws SQLException {
        insertHistory(connection, entry);
    }

    private void insertHistory(Connection c, HistoryEntry entry) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO history (shop_id, owner_uuid, actor_uuid, actor_name, type, description, created_at)
                VALUES (?,?,?,?,?,?,?)
                """)) {
            ps.setString(1, entry.shopId() == null ? null : entry.shopId().toString());
            ps.setString(2, entry.ownerUuid().toString());
            ps.setString(3, entry.actorUuid() == null ? null : entry.actorUuid().toString());
            ps.setString(4, entry.actorName());
            ps.setString(5, entry.type());
            ps.setString(6, entry.description());
            ps.setLong(7, entry.createdAt());
            ps.executeUpdate();
        }
    }

    public List<HistoryEntry> loadHistory(UUID shopId, int limit, int offset) throws SQLException {
        List<HistoryEntry> entries = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM history WHERE shop_id = ? ORDER BY id DESC LIMIT ? OFFSET ?
                """)) {
            ps.setString(1, shopId.toString());
            ps.setInt(2, Math.max(1, limit));
            ps.setInt(3, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(readHistory(rs));
                }
            }
        }
        return entries;
    }

    public List<HistoryEntry> loadHistoryForOwner(UUID owner, int limit, int offset) throws SQLException {
        List<HistoryEntry> entries = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM history WHERE owner_uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?
                """)) {
            ps.setString(1, owner.toString());
            ps.setInt(2, Math.max(1, limit));
            ps.setInt(3, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(readHistory(rs));
                }
            }
        }
        return entries;
    }

    public int countHistory(UUID shopId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM history WHERE shop_id = ?")) {
            ps.setString(1, shopId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public int countHistoryForOwner(UUID owner) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM history WHERE owner_uuid = ?")) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private HistoryEntry readHistory(ResultSet rs) throws SQLException {
        String rawShop = rs.getString("shop_id");
        String rawActor = rs.getString("actor_uuid");
        return new HistoryEntry(
                rs.getLong("id"),
                rawShop == null ? null : UUID.fromString(rawShop),
                UUID.fromString(rs.getString("owner_uuid")),
                rawActor == null ? null : UUID.fromString(rawActor),
                rs.getString("actor_name"),
                rs.getString("type"),
                rs.getString("description"),
                rs.getLong("created_at"));
    }

    public void addAudit(AuditEntry entry) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO audit_log (actor_uuid, actor_name, action, details, created_at)
                VALUES (?,?,?,?,?)
                """)) {
            ps.setString(1, entry.actorUuid() == null ? null : entry.actorUuid().toString());
            ps.setString(2, entry.actorName());
            ps.setString(3, entry.action());
            ps.setString(4, entry.details());
            ps.setLong(5, entry.createdAt());
            ps.executeUpdate();
        }
    }

    public List<AuditEntry> loadAudit(int limit, int offset) throws SQLException {
        List<AuditEntry> entries = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM audit_log ORDER BY id DESC LIMIT ? OFFSET ?")) {
            ps.setInt(1, Math.max(1, limit));
            ps.setInt(2, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String rawActor = rs.getString("actor_uuid");
                    entries.add(new AuditEntry(
                            rs.getLong("id"),
                            rawActor == null ? null : UUID.fromString(rawActor),
                            rs.getString("actor_name"),
                            rs.getString("action"),
                            rs.getString("details"),
                            rs.getLong("created_at")));
                }
            }
        }
        return entries;
    }

    public int countAudit() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM audit_log")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // ------------------------------------------------------------ sign claims

    public void saveClaim(SignClaim claim) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO sign_claims (world, x, y, z, player_uuid, player_name, created_at)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT(world, x, y, z) DO UPDATE SET
                    player_uuid = excluded.player_uuid,
                    player_name = excluded.player_name,
                    created_at = excluded.created_at
                """)) {
            LocationKey sign = claim.sign();
            ps.setString(1, sign.world());
            ps.setInt(2, sign.x());
            ps.setInt(3, sign.y());
            ps.setInt(4, sign.z());
            ps.setString(5, claim.playerUuid().toString());
            ps.setString(6, claim.playerName());
            ps.setLong(7, claim.createdAt());
            ps.executeUpdate();
        }
    }

    public void deleteClaim(LocationKey sign) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM sign_claims WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
            ps.setString(1, sign.world());
            ps.setInt(2, sign.x());
            ps.setInt(3, sign.y());
            ps.setInt(4, sign.z());
            ps.executeUpdate();
        }
    }

    public List<SignClaim> loadClaims() throws SQLException {
        List<SignClaim> claims = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM sign_claims")) {
            while (rs.next()) {
                claims.add(new SignClaim(
                        new LocationKey(rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z")),
                        UUID.fromString(rs.getString("player_uuid")),
                        rs.getString("player_name"),
                        rs.getLong("created_at")));
            }
        }
        return claims;
    }

    public void deleteStaleClaims(long cutoffMillis) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM sign_claims WHERE created_at < ?")) {
            ps.setLong(1, cutoffMillis);
            ps.executeUpdate();
        }
    }

    // ----------------------------------------------------------------- helpers

    private void touchShop(Connection c, UUID shopId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE shops SET updated_at = ? WHERE id = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, shopId.toString());
            ps.executeUpdate();
        }
    }

    private static void setItem(PreparedStatement ps, int start, ItemData item) throws SQLException {
        ps.setString(start, item.material());
        ps.setString(start + 1, item.signature());
        ps.setString(start + 2, item.serialized());
        ps.setInt(start + 3, item.maxStackSize());
    }

    private static ItemData item(ResultSet rs, String prefix) throws SQLException {
        String material = rs.getString(prefix + "material");
        String signature = rs.getString(prefix + "signature");
        String serialized = rs.getString(prefix + "serialized");
        int maxStack = rs.getInt(prefix + "max_stack");
        return new ItemData(material, signature, serialized, maxStack, null);
    }

    private static void setNullableLocation(PreparedStatement ps, int start, LocationKey location)
            throws SQLException {
        if (location == null) {
            ps.setString(start, null);
            ps.setObject(start + 1, null);
            ps.setObject(start + 2, null);
            ps.setObject(start + 3, null);
        } else {
            ps.setString(start, location.world());
            ps.setInt(start + 1, location.x());
            ps.setInt(start + 2, location.y());
            ps.setInt(start + 3, location.z());
        }
    }

    private static LocationKey location(ResultSet rs, String world, String x, String y, String z)
            throws SQLException {
        String worldName = rs.getString(world);
        if (worldName == null) {
            return null;
        }
        int px = rs.getInt(x);
        if (rs.wasNull()) {
            return null;
        }
        return new LocationKey(worldName, px, rs.getInt(y), rs.getInt(z));
    }
}
