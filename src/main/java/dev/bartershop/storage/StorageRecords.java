package dev.bartershop.storage;

import dev.bartershop.core.model.LocationKey;
import dev.bartershop.core.model.StockStack;

import java.util.List;
import java.util.UUID;

/** Small immutable values shared between the repository and services. */
public final class StorageRecords {

    private StorageRecords() {
    }

    public enum JournalType {
        TRADE,
        COLLECTION
    }

    public record HistoryEntry(
            long id,
            UUID shopId,
            UUID ownerUuid,
            UUID actorUuid,
            String actorName,
            String type,
            String description,
            long createdAt
    ) {
        public static HistoryEntry create(UUID shopId, UUID ownerUuid, UUID actorUuid,
                                          String actorName, String type, String description) {
            return new HistoryEntry(0, shopId, ownerUuid, actorUuid, actorName, type, description,
                    System.currentTimeMillis());
        }
    }

    public record AuditEntry(
            long id,
            UUID actorUuid,
            String actorName,
            String action,
            String details,
            long createdAt
    ) {
        public static AuditEntry create(UUID actorUuid, String actorName, String action, String details) {
            return new AuditEntry(0, actorUuid, actorName, action, details, System.currentTimeMillis());
        }
    }

    public record SignClaim(
            LocationKey sign,
            UUID playerUuid,
            String playerName,
            long createdAt
    ) {
    }

    public record JournalRecord(
            String id,
            JournalType type,
            UUID shopId,
            UUID playerUuid,
            String payload,
            long createdAt
    ) {
    }

    public record PendingSummary(
            UUID shopId,
            String label,
            List<StockStack> items
    ) {
        public int totalItems() {
            int total = 0;
            for (StockStack stack : items) {
                total += stack.amount();
            }
            return total;
        }
    }

    public record LoadedShops(List<dev.bartershop.core.model.Shop> shops, List<String> warnings) {
    }

    /** A pending, recipient-confirmed ownership transfer. */
    public record TransferRequest(
            UUID id,
            UUID shopId,
            String shopName,
            UUID fromUuid,
            String fromName,
            UUID toUuid,
            String toName,
            long createdAt,
            long expiresAt
    ) {
        public boolean expired(long now) {
            return now >= expiresAt;
        }
    }
}
