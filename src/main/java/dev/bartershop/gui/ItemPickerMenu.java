package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.config.Messages;
import dev.bartershop.core.item.ItemData;
import dev.bartershop.util.ItemCodec;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Item selection that never requires holding a special item. Owners can click
 * an item in their own inventory or browse the full material catalog.
 */
public final class ItemPickerMenu extends Menu {

    private enum Mode {
        INVENTORY,
        CATALOG
    }

    private static final int PAGE_SIZE = 45;
    private static final List<Material> CATALOG = buildCatalog();

    private final BiConsumer<ItemData, Integer> onSelect;
    private Mode mode = Mode.INVENTORY;
    private int page;

    public ItemPickerMenu(BarterShopPlugin plugin, Player player, Menu parent,
                          BiConsumer<ItemData, Integer> onSelect) {
        super(plugin, player, parent);
        this.onSelect = onSelect;
    }

    @Override
    protected String titleKey() {
        return "gui.picker.title";
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        clear();
        if (mode == Mode.INVENTORY) {
            List<String> instructions = new ArrayList<>();
            instructions.add(plugin.messages().raw("gui.picker.inventory-lore1"));
            instructions.add(plugin.messages().raw("gui.picker.inventory-lore2"));
            instructions.add(plugin.messages().raw("gui.picker.inventory-lore3"));
            set(22, Icons.of(Material.CHEST, plugin.messages().raw("gui.picker.inventory"), instructions));
        } else {
            int pages = Math.max(1, (CATALOG.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            int current = Math.min(page, pages - 1);
            int start = current * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, CATALOG.size());
            for (int i = start; i < end; i++) {
                Material material = CATALOG.get(i);
                int slot = i - start;
                ItemStack icon = Icons.of(material, "<white>" + GuiUtil.safe(prettify(material)),
                        List.of(plugin.messages().raw("gui.picker.catalog-click")));
                bind(slot, icon, () -> select(material));
            }
            if (current > 0) {
                set(45, Icons.previous(plugin, current));
                bind(45, getInventory().getItem(45), () -> {
                    page = current - 1;
                    refresh();
                });
            }
            if (current + 1 < pages) {
                set(53, Icons.next(plugin, current + 2));
                bind(53, getInventory().getItem(53), () -> {
                    page = current + 1;
                    refresh();
                });
            }
        }
        set(49, Icons.button(plugin, mode == Mode.INVENTORY ? Material.BOOK : Material.CHEST,
                "gui.picker.toggle", List.of("gui.picker.toggle-lore"),
                "mode", Messages.rich(plugin.messages().raw(mode == Mode.INVENTORY
                        ? "gui.picker.mode-catalog" : "gui.picker.mode-inventory"))));
        bind(49, getInventory().getItem(49), () -> {
            mode = mode == Mode.INVENTORY ? Mode.CATALOG : Mode.INVENTORY;
            page = 0;
            refresh();
        });
        set(48, Icons.back(plugin));
        bind(48, getInventory().getItem(48), this::back);
        set(50, Icons.close(plugin));
        bind(50, getInventory().getItem(50), player::closeInventory);
    }

    @Override
    public void onPlayerInventoryClick(int playerSlot, ItemStack clicked) {
        if (mode != Mode.INVENTORY || clicked == null || clicked.getType().isAir()) {
            return;
        }
        try {
            ItemData data = ItemCodec.of(clicked);
            onSelect.accept(data, clicked.getAmount());
            parentMenu().open();
        } catch (RuntimeException e) {
            plugin.messages().send(player, "offer.invalid-item");
        }
    }

    private void select(Material material) {
        if (material == null || material.isAir() || !material.isItem()) {
            return;
        }
        try {
            ItemData data = ItemCodec.of(new ItemStack(material));
            onSelect.accept(data, 1);
            parentMenu().open();
        } catch (RuntimeException e) {
            plugin.messages().send(player, "offer.invalid-item");
        }
    }

    private static List<Material> buildCatalog() {
        List<Material> materials = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.isItem() && !material.isAir() && !material.isLegacy()) {
                materials.add(material);
            }
        }
        materials.sort(Comparator.comparing(Enum::name));
        return List.copyOf(materials);
    }

    private static String prettify(Material material) {
        String lower = material.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
