package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.core.model.Shop;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Entry point: own shops, activity, transfer requests and recovered items. */
public final class MyShopsMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private final int page;

    public MyShopsMenu(BarterShopPlugin plugin, Player player, Menu parent, int page) {
        super(plugin, player, parent);
        this.page = Math.max(0, page);
    }

    @Override
    protected String titleKey() {
        return "gui.shops.title";
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        clear();
        List<Shop> shops = plugin.shopManager().ownedBy(player.getUniqueId());
        int pages = Math.max(1, (shops.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(page, pages - 1);
        int start = current * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, shops.size());
        for (int i = start; i < end; i++) {
            Shop shop = shops.get(i);
            bind(9 + (i - start), shopIcon(shop),
                    () -> new ShopHubMenu(plugin, player, shop, false, this).open());
        }

        bind(0, Icons.button(plugin, Material.WRITABLE_BOOK, "gui.shops.create",
                        List.of("gui.shops.create-lore")),
                () -> new CreateShopInfoMenu(plugin, player, this).open());

        bind(2, Icons.button(plugin, Material.BOOK, "gui.shops.language",
                        List.of("gui.shops.language-lore"),
                        "language", plugin.languageFor(player.getUniqueId()).nativeName()),
                () -> new PreferencesMenu(plugin, player, this).open());

        if (player.hasPermission("bartershop.admin")) {
            bind(4, Icons.button(plugin, Material.COMMAND_BLOCK, "gui.shops.admin",
                            List.of("gui.shops.admin-lore")),
                    () -> new AdminMenu(plugin, player, this).open());
        } else {
            set(4, Icons.button(plugin, Material.CHEST, "gui.shops.info",
                    List.of("gui.shops.info-lore")));
        }

        bind(6, Icons.button(plugin, Material.PAPER, "gui.shops.transfers",
                        List.of("gui.shops.transfers-lore")),
                () -> TransferRequestsMenu.open(plugin, player, this));

        bind(8, Icons.button(plugin, Material.ENDER_CHEST, "gui.shops.recovered",
                        List.of("gui.shops.recovered-lore")),
                this::openMailbox);

        bind(49, Icons.button(plugin, Material.EMERALD, "gui.shops.proceeds",
                        List.of("gui.shops.proceeds-lore")),
                () -> ProceedsOverviewMenu.open(plugin, player, this));

        if (current > 0) {
            set(48, Icons.previous(plugin, current));
            bind(48, getInventory().getItem(48),
                    () -> new MyShopsMenu(plugin, player, parent, current - 1).open());
        }
        if (current + 1 < pages) {
            set(50, Icons.next(plugin, current + 2));
            bind(50, getInventory().getItem(50),
                    () -> new MyShopsMenu(plugin, player, parent, current + 1).open());
        }
        set(53, Icons.close(plugin));
        bind(53, getInventory().getItem(53), player::closeInventory);

        if (shops.isEmpty()) {
            set(22, Icons.button(plugin, Material.BARRIER, "gui.shops.empty",
                    List.of("gui.shops.empty-lore")));
        }
    }

    private ItemStack shopIcon(Shop shop) {
        List<String> lore = new ArrayList<>();
        lore.add(plugin.messages().raw("gui.shops.owner", "owner", GuiUtil.safe(shop.ownerName())));
        lore.add(plugin.messages().raw("gui.shops.location",
                "world", GuiUtil.safe(shop.primaryChest().world()),
                "x", String.valueOf(shop.primaryChest().x()),
                "y", String.valueOf(shop.primaryChest().y()),
                "z", String.valueOf(shop.primaryChest().z())));
        lore.add(plugin.messages().raw("gui.shops.offers",
                "count", String.valueOf(shop.offers().size())));
        lore.add(plugin.messages().raw("gui.shops.stock",
                "used", String.valueOf(shop.stock().slotsUsed()),
                "capacity", String.valueOf(shop.capacitySlots())));
        lore.add(plugin.messages().raw("gui.shops.pending",
                "count", String.valueOf(shop.proceeds().totalItems())));
        lore.add(shop.enabled()
                ? plugin.messages().raw("gui.shops.enabled")
                : plugin.messages().raw("gui.shops.disabled"));
        if (shop.stats().totalTrades() > 0) {
            lore.add(plugin.messages().raw("gui.shops.trades",
                    "count", String.valueOf(shop.stats().totalTrades())));
        }
        if (shop.orphaned()) {
            lore.add(plugin.messages().raw("gui.shops.orphaned"));
        }
        lore.add(plugin.messages().raw("gui.shops.click"));
        return Icons.of(shop.enabled() ? Material.CHEST : Material.TRAPPED_CHEST,
                GuiUtil.safe(shop.name()), lore);
    }

    private void openMailbox() {
        if (!player.isOnline()) {
            return;
        }
        player.closeInventory();
        plugin.database().callAsync(repository -> repository.loadMailbox(player.getUniqueId()))
                .thenAccept(mailbox -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (mailbox.isEmpty()) {
                        plugin.messages().send(player, "mailbox.empty");
                    } else {
                        new MailboxMenu(plugin, player, this, mailbox, 0).open();
                    }
                }))
                .exceptionally(throwable -> {
                    plugin.getLogger().warning("Could not load mailbox: " + throwable.getMessage());
                    Bukkit.getScheduler().runTask(plugin,
                            () -> plugin.messages().send(player, "error.database"));
                    return null;
                });
    }
}
