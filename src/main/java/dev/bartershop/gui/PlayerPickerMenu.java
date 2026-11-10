package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Picks an online player through a GUI (used for ownership transfer). */
public final class PlayerPickerMenu extends Menu {

    private static final int PAGE_SIZE = 45;
    private final Consumer<Player> onPick;
    private final int page;

    public PlayerPickerMenu(BarterShopPlugin plugin, Player player, Menu parent,
                            Consumer<Player> onPick, int page) {
        super(plugin, player, parent);
        this.onPick = onPick;
        this.page = Math.max(0, page);
    }

    @Override
    protected String titleKey() {
        return "gui.players.title";
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        clear();
        List<Player> candidates = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.getUniqueId().equals(player.getUniqueId())) {
                candidates.add(online);
            }
        }
        int pages = Math.max(1, (candidates.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(page, pages - 1);
        int start = current * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, candidates.size());
        for (int i = start; i < end; i++) {
            Player target = candidates.get(i);
            int slot = i - start;
            ItemStack head = Icons.of(Material.PLAYER_HEAD, "<white>" + GuiUtil.safe(target.getName()),
                    List.of(plugin.messages().raw("gui.players.choose")));
            bind(slot, head, () -> onPick.accept(target));
        }
        if (candidates.isEmpty()) {
            set(22, Icons.button(plugin, Material.BARRIER, "gui.players.empty",
                    List.of("gui.players.empty-lore")));
        }
        set(49, Icons.back(plugin));
        bind(49, getInventory().getItem(49), this::back);
        if (current > 0) {
            set(45, Icons.previous(plugin, current));
            bind(45, getInventory().getItem(45),
                    () -> new PlayerPickerMenu(plugin, player, parent, onPick, current - 1).open());
        }
        if (current + 1 < pages) {
            set(53, Icons.next(plugin, current + 2));
            bind(53, getInventory().getItem(53),
                    () -> new PlayerPickerMenu(plugin, player, parent, onPick, current + 1).open());
        }
    }
}
