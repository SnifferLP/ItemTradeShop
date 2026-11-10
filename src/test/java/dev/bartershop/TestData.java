package dev.bartershop;

import dev.bartershop.core.inv.InventoryModel;
import dev.bartershop.core.item.ItemData;
import dev.bartershop.core.model.LocationKey;
import dev.bartershop.core.model.Shop;
import dev.bartershop.core.model.TradeOffer;
import dev.bartershop.core.proceeds.ProceedsStorage;
import dev.bartershop.core.stock.StockStorage;

import java.util.List;
import java.util.UUID;

public final class TestData {

    public static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private TestData() {
    }

    public static ItemData item(String material, String variant, int maxStack) {
        String pretty = material.toLowerCase().replace('_', ' ');
        return ItemData.test(material, "sig-" + material + "-" + variant, maxStack, pretty);
    }

    public static ItemData item(String material) {
        return item(material, "default", 64);
    }

    public static Shop shop(int capacity, StockStorage stock, ProceedsStorage proceeds, TradeOffer... offers) {
        return shop(capacity, stock, proceeds, List.of(offers));
    }

    public static Shop shop(int capacity, StockStorage stock, ProceedsStorage proceeds, List<TradeOffer> offers) {
        return new Shop(
                UUID.randomUUID(),
                OWNER,
                "Owner",
                "Test Shop",
                List.of(new LocationKey("world", 10, 64, 10)),
                new LocationKey("world", 10, 65, 10),
                new LocationKey("world", 10, 64, 9),
                capacity,
                true,
                offers,
                stock,
                proceeds,
                System.currentTimeMillis());
    }

    public static InventoryModel inventory(int size, ItemData fill, int stacks) {
        InventoryModel model = InventoryModel.empty(size);
        for (int i = 0; i < stacks && i < size; i++) {
            model.set(i, new InventoryModel.HeldItem(fill, Math.min(fill.maxStackSize(), 64)));
        }
        return model;
    }

    public static InventoryModel fullOf(ItemData fill) {
        return inventory(36, fill, 36);
    }
}
