package dev.bartershop.service;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.config.Messages;
import dev.bartershop.config.Settings;
import dev.bartershop.storage.Database;
import dev.bartershop.storage.StorageRecords.PendingSummary;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells owners about queued earnings when they join. Notifications are explicit
 * that items are stored and not yet delivered, and are throttled so reconnects
 * do not spam the same summary.
 */
public final class NotificationService {

    private static final int MAX_SHOPS_IN_MESSAGE = 5;
    private static final long COOLDOWN_MILLIS = 60_000L;

    private final BarterShopPlugin plugin;
    private final Database database;
    private final Messages messages;
    private final Settings settings;
    private final Map<UUID, Notice> lastNotice = new ConcurrentHashMap<>();

    public NotificationService(BarterShopPlugin plugin, Database database, Messages messages, Settings settings) {
        this.plugin = plugin;
        this.database = database;
        this.messages = messages;
        this.settings = settings;
    }

    /** Drops throttle state when a player leaves; it is re-created on the next join. */
    public void forget(UUID player) {
        lastNotice.remove(player);
    }

    public void onJoin(Player player) {
        notifyTransferRequests(player);
        if (!settings.notifyOnJoin()) {
            return;
        }
        UUID owner = player.getUniqueId();
        database.callAsync(repository -> repository.findPendingSummaries(owner))
                .thenAccept(summaries -> {
                    if (summaries.isEmpty()) {
                        return;
                    }
                    int total = summaries.stream().mapToInt(PendingSummary::totalItems).sum();
                    Bukkit.getScheduler().runTask(plugin, () -> send(player, owner, summaries, total));
                })
                .exceptionally(throwable -> {
                    plugin.getLogger().warning("Could not load pending proceeds for " + player.getName()
                            + ": " + throwable.getMessage());
                    return null;
                });
    }

    private void notifyTransferRequests(Player player) {
        UUID playerId = player.getUniqueId();
        database.callAsync(repository -> repository.loadTransferRequestsFor(playerId))
                .thenAccept(requests -> {
                    long pending = requests.stream()
                            .filter(request -> request.toUuid().equals(playerId))
                            .filter(request -> !request.expired(System.currentTimeMillis()))
                            .count();
                    if (pending == 0) {
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            messages.send(player, "transfer.pending-join",
                                    "count", String.valueOf(pending));
                        }
                    });
                })
                .exceptionally(throwable -> {
                    plugin.getLogger().warning("Could not load transfer requests for "
                            + player.getName() + ": " + throwable.getMessage());
                    return null;
                });
    }

    private void send(Player player, UUID owner, List<PendingSummary> summaries, int total) {
        if (!player.isOnline()) {
            return;
        }
        Notice previous = lastNotice.get(owner);
        long now = System.currentTimeMillis();
        if (previous != null && previous.total == total && now - previous.at < COOLDOWN_MILLIS) {
            return;
        }
        lastNotice.put(owner, new Notice(now, total));

        try (Messages.Context ignored = messages.contextFor(player)) {
            messages.send(player, "notify.header",
                    "shops", String.valueOf(summaries.size()),
                    "total", String.valueOf(total));
            int shown = 0;
            for (PendingSummary summary : summaries) {
                if (shown >= MAX_SHOPS_IN_MESSAGE) {
                    messages.send(player, "notify.more", "count", String.valueOf(summaries.size() - shown));
                    break;
                }
                player.sendMessage(messages.component("notify.entry",
                        "shop", label(summary),
                        "count", String.valueOf(summary.totalItems()),
                        "items", ProceedsService.itemsSummary(summary.items(), settings.notificationItemTypes(),
                                remaining -> messages.raw("notify.items-more",
                                        "count", String.valueOf(remaining)))));
                shown++;
            }
            Component footer = messages.component("notify.footer");
            player.sendMessage(footer);
        }
    }

    /** Shop names are user data; recovered or deleted entries use a translated label. */
    private String label(PendingSummary summary) {
        if (summary.shopId() == null) {
            return messages.raw("gui.proceeds-overview.mailbox-label");
        }
        return summary.label().isBlank()
                ? messages.raw("gui.proceeds-overview.deleted-label") : summary.label();
    }

    private record Notice(long at, int total) {
    }
}
