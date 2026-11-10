package dev.bartershop.command;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.config.Language;
import dev.bartershop.config.Messages;
import dev.bartershop.gui.AdminMenu;
import dev.bartershop.gui.CreateShopInfoMenu;
import dev.bartershop.gui.MyShopsMenu;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Commands are only GUI entry points and admin diagnostics. Every ordinary
 * shop action is performed in an inventory GUI.
 */
public final class ShopCommand implements CommandExecutor, TabCompleter {

    private final BarterShopPlugin plugin;

    public ShopCommand(BarterShopPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("shopadmin")) {
            return admin(sender, args);
        }
        if (args.length > 0) {
            switch (args[0].toLowerCase()) {
                case "admin" -> {
                    return admin(sender, java.util.Arrays.copyOfRange(args, 1, args.length));
                }
                case "help" -> {
                    if (sender instanceof Player player) {
                        new CreateShopInfoMenu(plugin, player, null).open();
                    } else {
                        plugin.messages().send(sender, "command.gui-only");
                    }
                    return true;
                }
                case "reload" -> {
                    return reload(sender);
                }
                case "language", "lang" -> {
                    return language(sender, args);
                }
                default -> {
                }
            }
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "command.gui-only");
            return true;
        }
        if (!player.hasPermission("bartershop.use") && !player.hasPermission("bartershop.admin")) {
            plugin.messages().send(player, "general.no-permission");
            return true;
        }
        new MyShopsMenu(plugin, player, null, 0).open();
        return true;
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bartershop.admin")) {
            plugin.messages().send(sender, "general.no-permission");
            return true;
        }
        if (args.length > 0) {
            switch (args[0].toLowerCase()) {
                case "scan" -> {
                    int count = plugin.shopManager().scanOrphans();
                    plugin.messages().send(sender, "admin.scan-done", "count", String.valueOf(count));
                    return true;
                }
                case "reload" -> {
                    return reload(sender);
                }
                case "maintenance" -> {
                    return maintenance(sender, args);
                }
                case "signs" -> {
                    plugin.shopManager().scanOrphans();
                    plugin.shopManager().refreshSigns();
                    plugin.messages().send(sender, "admin.signs-refreshed");
                    return true;
                }
                default -> {
                }
            }
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "command.admin-gui-only");
            return true;
        }
        new AdminMenu(plugin, player, null).open();
        return true;
    }

    private boolean language(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "command.language-players-only");
            return true;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "command.language-usage");
            return true;
        }
        Language language = Language.parse(args[1], null);
        if (language == null) {
            plugin.messages().send(player, "command.language-unknown", "language", args[1]);
            return true;
        }
        if (plugin.languages().setLanguage(player.getUniqueId(), language)) {
            plugin.messages().send(player, "command.language-set",
                    "language", language.nativeName());
        } else {
            plugin.messages().send(player, "error.database");
        }
        return true;
    }

    private boolean maintenance(CommandSender sender, String[] args) {
        boolean enabled = plugin.maintenanceMode();
        if (args.length > 1) {
            String value = args[1].toLowerCase();
            if (value.equals("on") || value.equals("true")) {
                enabled = true;
            } else if (value.equals("off") || value.equals("false")) {
                enabled = false;
            } else if (value.equals("toggle")) {
                enabled = !enabled;
            } else {
                plugin.messages().send(sender, "admin.maintenance-usage");
                return true;
            }
        } else {
            enabled = !enabled;
        }
        plugin.setMaintenanceMode(enabled, sender.getName());
        plugin.messages().send(sender, "admin.maintenance-set",
                "state", Messages.rich(plugin.messages().raw(enabled
                        ? "admin.maintenance-state-on" : "admin.maintenance-state-off")));
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("bartershop.admin")) {
            plugin.messages().send(sender, "general.no-permission");
            return true;
        }
        plugin.reloadMessagesAndConfig();
        plugin.messages().send(sender, "admin.reloaded");
        plugin.getLogger().info("Configuration and messages reloaded by " + sender.getName()
                + " (restart required for settings that are read at startup)");
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            if (command.getName().equalsIgnoreCase("shopadmin")) {
                return sender.hasPermission("bartershop.admin")
                        ? List.of("scan", "reload", "maintenance", "signs") : List.of();
            }
            return sender.hasPermission("bartershop.admin")
                    ? List.of("admin", "help", "reload", "language") : List.of("help", "language");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("language")) {
            return List.of("en", "de");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("maintenance")) {
            return List.of("on", "off", "toggle");
        }
        return List.of();
    }
}
