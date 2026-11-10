package dev.bartershop.storage;

import dev.bartershop.TestData;
import dev.bartershop.core.model.Shop;
import dev.bartershop.storage.StorageRecords.LoadedShops;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies upgrades from the Phase 1 on-disk schema without losing shops or offers. */
class MigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void unversionedLegacyDatabaseIsUpgradedInPlace() throws Exception {
        UUID shopId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("unversioned.db"))) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE shops (
                            id TEXT PRIMARY KEY, owner_uuid TEXT NOT NULL, owner_name TEXT NOT NULL,
                            name TEXT NOT NULL, chest_world TEXT NOT NULL, chest_x INTEGER NOT NULL,
                            chest_y INTEGER NOT NULL, chest_z INTEGER NOT NULL,
                            chest2_world TEXT, chest2_x INTEGER, chest2_y INTEGER, chest2_z INTEGER,
                            sign_world TEXT, sign_x INTEGER, sign_y INTEGER, sign_z INTEGER,
                            support_world TEXT, support_x INTEGER, support_y INTEGER, support_z INTEGER,
                            capacity_slots INTEGER NOT NULL, enabled INTEGER NOT NULL,
                            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)
                        """);
                statement.execute("""
                        CREATE TABLE offers (
                            id TEXT PRIMARY KEY, shop_id TEXT NOT NULL, sort_order INTEGER NOT NULL,
                            mode TEXT NOT NULL, pay_material TEXT NOT NULL, pay_signature TEXT NOT NULL,
                            pay_serialized TEXT NOT NULL, pay_max_stack INTEGER NOT NULL,
                            pay_amount INTEGER NOT NULL, receive_material TEXT NOT NULL,
                            receive_signature TEXT NOT NULL, receive_serialized TEXT NOT NULL,
                            receive_max_stack INTEGER NOT NULL, receive_amount INTEGER NOT NULL,
                            exact_pay INTEGER NOT NULL)
                        """);
                statement.execute("""
                        INSERT INTO shops VALUES
                        ('%s','%s','Owner','Legacy','world',10,64,10,'world',11,64,10,
                         'world',10,65,10,'world',10,64,9,54,1,1000,1000)
                        """.formatted(shopId, TestData.OWNER));
                statement.execute("""
                        INSERT INTO offers VALUES
                        ('%s','%s',0,'SELL','EMERALD','sig-EMERALD-default','AA==',64,16,
                         'DIAMOND','sig-DIAMOND-default','AA==',64,1,1)
                        """.formatted(UUID.randomUUID(), shopId));
            }
        }

        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("unversioned.db"))) {
            ShopRepository repository = new ShopRepository(connection);
            repository.migrate();
            LoadedShops loaded = repository.loadAll();
            assertTrue(loaded.warnings().isEmpty(), loaded.warnings().toString());
            assertEquals(1, loaded.shops().size());
            Shop shop = loaded.shops().get(0);
            assertEquals("Legacy", shop.name());
            assertEquals(1, shop.offers().size());
            assertEquals(16, shop.offers().get(0).payAmount());
            assertEquals(0, shop.offers().get(0).expiresAt());
            try (Statement statement = connection.createStatement();
                 var rs = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(rs.next());
                assertEquals(ShopRepository.SCHEMA_VERSION, rs.getInt(1));
            }
        }
    }

    @Test
    void version1DatabaseIsUpgradedInPlace() throws Exception {
        UUID shopId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("legacy.db"))) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE shops (
                            id TEXT PRIMARY KEY, owner_uuid TEXT NOT NULL, owner_name TEXT NOT NULL,
                            name TEXT NOT NULL, chest_world TEXT NOT NULL, chest_x INTEGER NOT NULL,
                            chest_y INTEGER NOT NULL, chest_z INTEGER NOT NULL,
                            chest2_world TEXT, chest2_x INTEGER, chest2_y INTEGER, chest2_z INTEGER,
                            sign_world TEXT, sign_x INTEGER, sign_y INTEGER, sign_z INTEGER,
                            support_world TEXT, support_x INTEGER, support_y INTEGER, support_z INTEGER,
                            capacity_slots INTEGER NOT NULL, enabled INTEGER NOT NULL,
                            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)
                        """);
                statement.execute("""
                        CREATE TABLE offers (
                            id TEXT PRIMARY KEY, shop_id TEXT NOT NULL, sort_order INTEGER NOT NULL,
                            mode TEXT NOT NULL, pay_material TEXT NOT NULL, pay_signature TEXT NOT NULL,
                            pay_serialized TEXT NOT NULL, pay_max_stack INTEGER NOT NULL,
                            pay_amount INTEGER NOT NULL, receive_material TEXT NOT NULL,
                            receive_signature TEXT NOT NULL, receive_serialized TEXT NOT NULL,
                            receive_max_stack INTEGER NOT NULL, receive_amount INTEGER NOT NULL,
                            exact_pay INTEGER NOT NULL)
                        """);
                statement.execute("""
                        CREATE TABLE stock (
                            shop_id TEXT NOT NULL, slot INTEGER NOT NULL, material TEXT NOT NULL,
                            signature TEXT NOT NULL, serialized TEXT NOT NULL,
                            max_stack INTEGER NOT NULL, amount INTEGER NOT NULL,
                            PRIMARY KEY (shop_id, slot))
                        """);
                statement.execute("""
                        CREATE TABLE pending_proceeds (
                            id INTEGER PRIMARY KEY AUTOINCREMENT, shop_id TEXT,
                            owner_uuid TEXT NOT NULL, material TEXT NOT NULL,
                            signature TEXT NOT NULL, serialized TEXT NOT NULL,
                            max_stack INTEGER NOT NULL, amount INTEGER NOT NULL, created_at INTEGER NOT NULL)
                        """);
                statement.execute("""
                        INSERT INTO shops VALUES
                        ('%s','%s','Owner','Legacy','world',10,64,10,'world',11,64,10,
                         'world',10,65,10,'world',10,64,9,54,1,1000,1000)
                        """.formatted(shopId, TestData.OWNER));
                statement.execute("""
                        INSERT INTO offers VALUES
                        ('%s','%s',0,'SELL','EMERALD','sig-EMERALD-default','AA==',64,16,
                         'DIAMOND','sig-DIAMOND-default','AA==',64,1,1)
                        """.formatted(UUID.randomUUID(), shopId));
                statement.execute("PRAGMA user_version=1");
            }
        }

        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("legacy.db"))) {
            ShopRepository repository = new ShopRepository(connection);
            repository.migrate();
            LoadedShops loaded = repository.loadAll();
            assertTrue(loaded.warnings().isEmpty(), loaded.warnings().toString());
            assertEquals(1, loaded.shops().size());
            Shop shop = loaded.shops().get(0);
            assertEquals("Legacy", shop.name());
            assertEquals(1, shop.offers().size());
            assertEquals(16, shop.offers().get(0).payAmount());
            assertEquals(0, shop.offers().get(0).expiresAt());
            assertEquals(1, shop.chestUnitCount());
            assertEquals(2, shop.chestBlocks().size());
            assertEquals(54, shop.capacitySlots());
            assertEquals(0, shop.stats().totalTrades());
        }
    }
}
