package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.config.Messages;
import dev.bartershop.core.model.Shop;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Owner settings: availability, storage, warnings, transfer and deletion. */
public final class SettingsMenu extends Menu {

    private static final int[] WARNING_STEPS = {0, 4, 8, 16, 32, 64, 128, 256};

    private final Shop shop;
    private final boolean admin;

    public SettingsMenu(BarterShopPlugin plugin, Player player, Shop shop, boolean admin, Menu parent) {
        super(plugin, player, parent);
        this.shop = shop;
        this.admin = admin;
    }

    @Override
    protected String titleKey() {
        return "gui.settings.title";
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        clear();
        set(4, Icons.button(plugin, Material.COMPARATOR, "gui.settings.info",
                List.of("gui.settings.info-lore"),
                "owner", GuiUtil.safe(shop.ownerName())));

        set(20, Icons.of(shop.enabled() ? Material.LEVER : Material.REDSTONE_TORCH,
                plugin.messages().raw("gui.settings.toggle",
                        "state", Messages.rich(plugin.messages().raw(shop.enabled()
                                ? "gui.settings.enabled" : "gui.settings.disabled"))),
                List.of(plugin.messages().raw("gui.settings.toggle-lore"))));
        bind(20, getInventory().getItem(20), () -> {
            plugin.shopManager().setEnabled(player, shop, !shop.enabled());
            Feedback.confirm(player);
            refresh();
        });

        set(22, Icons.button(plugin, Material.CHEST_MINECART, "gui.settings.chests",
                List.of("gui.settings.chests-lore"),
                "count", String.valueOf(shop.chestUnitCount()),
                "max", String.valueOf(plugin.settings().maxChestsPerShop())));
        bind(22, getInventory().getItem(22), () -> new ChestsMenu(plugin, player, shop, this).open());

        set(24, Icons.button(plugin, Material.OAK_SIGN, "gui.settings.relink-sign",
                List.of("gui.settings.relink-sign-lore1", "gui.settings.relink-sign-lore2")));
        bind(24, getInventory().getItem(24), () -> plugin.signService().beginSignRelink(player, shop));

        set(30, Icons.of(shop.warningThreshold() > 0
                        ? Material.BELL : Material.GRAY_DYE,
                plugin.messages().raw("gui.settings.warning",
                        "count", String.valueOf(shop.warningThreshold())),
                List.of(plugin.messages().raw("gui.settings.warning-lore"),
                        plugin.messages().raw("gui.settings.warning-click"))));
        bind(30, getInventory().getItem(30), () -> {
            plugin.shopManager().setWarningThreshold(player, shop, nextWarning(shop.warningThreshold()));
            Feedback.confirm(player);
            refresh();
        });

        set(32, Icons.button(plugin, Material.PLAYER_HEAD, "gui.settings.transfer",
                List.of("gui.settings.transfer-lore", "gui.settings.transfer-lore2")));
        bind(32, getInventory().getItem(32), () ->
                new PlayerPickerMenu(plugin, player, this, target -> {
                    if (target.getUniqueId().equals(shop.owner())) {
                        plugin.messages().send(player, "transfer.self");
                        Feedback.error(player);
                        return;
                    }
                    new ConfirmMenu(plugin, player, this,
                            plugin.messages().raw("gui.settings.transfer-confirm",
                                    "player", GuiUtil.safe(target.getName())),
                            () -> {
                                var request = plugin.shopManager().requestTransfer(
                                        player, shop, target.getUniqueId(), target.getName());
                                if (request.isPresent()) {
                                    Feedback.transfer(player);
                                } else {
                                    Feedback.error(player);
                                }
                                player.closeInventory();
                            }).open();
                }, 0).open());

        set(40, Icons.button(plugin, Material.LAVA_BUCKET, "gui.settings.delete",
                List.of("gui.settings.delete-lore1", "gui.settings.delete-lore2",
                        admin ? "gui.settings.delete-admin" : "gui.settings.delete-owner")));
        bind(40, getInventory().getItem(40), () ->
                new ConfirmMenu(plugin, player, this,
                        plugin.messages().raw("gui.settings.delete-confirm"),
                        () -> {
                            if (plugin.shopManager().deleteShop(player, shop, admin)) {
                                player.closeInventory();
                            }
                        }).open());

        set(45, Icons.back(plugin));
        bind(45, getInventory().getItem(45), this::back);
        set(53, Icons.close(plugin));
        bind(53, getInventory().getItem(53), player::closeInventory);
    }

    private static int nextWarning(int current) {
        for (int step : WARNING_STEPS) {
            if (step > current) {
                return step;
            }
        }
        return 0;
    }
}
