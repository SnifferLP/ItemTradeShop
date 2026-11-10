package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.service.SignService;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Creates a shop from a [shop] sign plus an empty chest, entirely via GUI. */
public final class SignSetupMenu extends Menu {

    private final SignService signService;
    private final SignService.SetupSession session;

    public SignSetupMenu(BarterShopPlugin plugin, Player player, SignService signService,
                         SignService.SetupSession session) {
        super(plugin, player, null);
        this.signService = signService;
        this.session = session;
    }

    @Override
    protected String titleKey() {
        return "gui.setup.title";
    }

    @Override
    protected int size() {
        return 27;
    }

    @Override
    protected void render() {
        clear();
        List<String> instructions = new ArrayList<>();
        instructions.add(plugin.messages().raw("gui.setup.info1"));
        instructions.add(plugin.messages().raw("gui.setup.info2"));
        instructions.add(plugin.messages().raw("gui.setup.info3"));
        instructions.add(plugin.messages().raw("gui.setup.info4"));
        set(4, Icons.of(Material.BOOK, plugin.messages().raw("gui.setup.info"), instructions));

        set(11, Icons.of(Material.OAK_SIGN, plugin.messages().raw("gui.setup.sign"),
                List.of(plugin.messages().raw("gui.setup.sign-lore",
                        "sign", GuiUtil.safe(session.sign().asString())))));

        List<String> chestLore = new ArrayList<>();
        if (session.chest() == null) {
            chestLore.add(plugin.messages().raw("gui.setup.chest-none"));
        } else {
            chestLore.add(plugin.messages().raw("gui.setup.chest-linked",
                    "chest", GuiUtil.safe(session.chest().asString())));
            chestLore.add(plugin.messages().raw("gui.setup.chest-capacity"));
        }
        chestLore.add(plugin.messages().raw("gui.setup.chest-click"));
        set(13, Icons.of(session.chest() != null ? Material.CHEST : Material.GRAY_DYE,
                plugin.messages().raw("gui.setup.chest"), chestLore));
        bind(13, getInventory().getItem(13), () -> signService.beginChestSelection(player));

        boolean ready = session.chest() != null;
        set(15, Icons.of(ready ? Material.LIME_DYE : Material.GRAY_DYE,
                plugin.messages().raw("gui.setup.confirm"),
                List.of(plugin.messages().raw(ready ? "gui.setup.confirm-lore"
                        : "gui.setup.confirm-no-chest"))));
        bind(15, getInventory().getItem(15), () -> {
            if (!ready) {
                plugin.messages().send(player, "setup.no-chest");
                return;
            }
            signService.confirm(player);
        });

        set(22, Icons.button(plugin, Material.BARRIER, "gui.setup.cancel",
                List.of("gui.setup.cancel-lore")));
        bind(22, getInventory().getItem(22), () -> {
            signService.cancel(player);
            player.closeInventory();
        });

        set(18, Icons.close(plugin));
        bind(18, getInventory().getItem(18), player::closeInventory);
    }
}
