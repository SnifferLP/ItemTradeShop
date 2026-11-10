package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.storage.StorageRecords.TransferRequest;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Lists inbound and outbound ownership transfer requests. */
public final class TransferRequestsMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private final List<TransferRequest> requests;
    private final int page;

    private TransferRequestsMenu(BarterShopPlugin plugin, Player player, Menu parent,
                                 List<TransferRequest> requests, int page) {
        super(plugin, player, parent);
        this.requests = requests;
        this.page = Math.max(0, page);
    }

    public static void open(BarterShopPlugin plugin, Player player, Menu parent) {
        player.closeInventory();
        plugin.database().callAsync(repository ->
                        repository.loadTransferRequestsFor(player.getUniqueId()))
                .thenAccept(requests -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        new TransferRequestsMenu(plugin, player, parent, requests, 0).open();
                    }
                }))
                .exceptionally(throwable -> {
                    plugin.getLogger().warning("Could not load transfer requests: " + throwable.getMessage());
                    Bukkit.getScheduler().runTask(plugin,
                            () -> plugin.messages().send(player, "error.database"));
                    return null;
                });
    }

    @Override
    protected String titleKey() {
        return "gui.transfers.title";
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        clear();
        int pages = Math.max(1, (requests.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(page, pages - 1);
        int start = current * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, requests.size());
        for (int i = start; i < end; i++) {
            TransferRequest request = requests.get(i);
            int slot = 9 + (i - start);
            boolean incoming = request.toUuid().equals(player.getUniqueId());
            boolean expired = request.expired(System.currentTimeMillis());
            List<String> lore = new ArrayList<>();
            lore.add(plugin.messages().raw("gui.transfers.shop", "shop", GuiUtil.safe(request.shopName())));
            lore.add(plugin.messages().raw(incoming ? "gui.transfers.from" : "gui.transfers.to",
                    "player", GuiUtil.safe(incoming ? request.fromName() : request.toName())));
            lore.add(plugin.messages().raw("gui.transfers.expires",
                    "time", GuiUtil.time(request.expiresAt())));
            if (expired) {
                lore.add(plugin.messages().raw("gui.transfers.expired"));
            } else {
                lore.add(plugin.messages().raw(incoming
                        ? "gui.transfers.accept-hint" : "gui.transfers.cancel-hint"));
            }
            ItemStack icon = Icons.of(expired ? Material.GRAY_DYE
                            : incoming ? Material.LIME_DYE : Material.PAPER,
                    plugin.messages().raw(incoming
                            ? "gui.transfers.incoming" : "gui.transfers.outgoing"), lore);
            bind(slot, icon, () -> {
                if (expired) {
                    plugin.messages().send(player, "transfer.expired");
                    return;
                }
                if (incoming) {
                    new ConfirmMenu(plugin, player, this,
                            plugin.messages().raw("gui.transfers.accept-confirm",
                                    "shop", GuiUtil.safe(request.shopName()),
                                    "player", GuiUtil.safe(request.fromName())),
                            () -> {
                                boolean accepted = plugin.shopManager().acceptTransfer(player, request.id());
                                if (accepted) {
                                    Feedback.transfer(player);
                                } else {
                                    Feedback.error(player);
                                }
                                open(plugin, player, parent);
                            }).open();
                } else {
                    new ConfirmMenu(plugin, player, this,
                            plugin.messages().raw("gui.transfers.cancel-confirm",
                                    "shop", GuiUtil.safe(request.shopName())),
                            () -> {
                                boolean cancelled = plugin.shopManager().cancelTransfer(player, request);
                                if (cancelled) {
                                    Feedback.confirm(player);
                                } else {
                                    Feedback.error(player);
                                }
                                open(plugin, player, parent);
                            }).open();
                }
            });
        }
        if (requests.isEmpty()) {
            set(22, Icons.button(plugin, Material.PAPER, "gui.transfers.empty",
                    List.of("gui.transfers.empty-lore")));
        }

        set(45, Icons.back(plugin));
        bind(45, getInventory().getItem(45), this::back);
        set(53, Icons.close(plugin));
        bind(53, getInventory().getItem(53), player::closeInventory);
        if (current > 0) {
            set(46, Icons.previous(plugin, current));
            bind(46, getInventory().getItem(46), () ->
                    new TransferRequestsMenu(plugin, player, parent, requests, current - 1).open());
        }
        if (current + 1 < pages) {
            set(52, Icons.next(plugin, current + 2));
            bind(52, getInventory().getItem(52), () ->
                    new TransferRequestsMenu(plugin, player, parent, requests, current + 1).open());
        }
    }
}
