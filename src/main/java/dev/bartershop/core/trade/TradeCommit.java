package dev.bartershop.core.trade;

/**
 * Side-effect boundary for a trade. The engine owns validity and in-memory
 * state; the plugin layer implements this to write a durable journal, apply
 * the diff to the real player inventory, and commit the database transaction.
 *
 * <p>Implementations may throw; the engine then rolls back every applied
 * stage. {@code beforeCommit} must be durable before it returns, because it
 * is the crash-recovery record for the trade.</p>
 */
public interface TradeCommit {

    void beforeCommit(TradePlan plan) throws Exception;

    void applyInventory(TradePlan plan) throws Exception;

    void commit(TradePlan plan) throws Exception;

    void rollbackInventory(TradePlan plan);

    void abortJournal(TradePlan plan);

    /** No-op implementation for unit tests and simulated trades. */
    TradeCommit NOOP = new TradeCommit() {
        @Override
        public void beforeCommit(TradePlan plan) {
        }

        @Override
        public void applyInventory(TradePlan plan) {
        }

        @Override
        public void commit(TradePlan plan) {
        }

        @Override
        public void rollbackInventory(TradePlan plan) {
        }

        @Override
        public void abortJournal(TradePlan plan) {
        }
    };
}
