package dev.bartershop.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Owns the single SQLite connection and a dedicated database thread. JDBC work
 * never runs on the server thread; critical item-moving operations wait for the
 * result so they can roll back cleanly. Reads used by GUIs are asynchronous.
 */
public final class Database implements AutoCloseable {

    private final Connection connection;
    private final ExecutorService executor;
    private final ShopRepository repository;

    public Database(Path file) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not available", e);
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (Exception e) {
                throw new SQLException("Could not create database directory " + parent, e);
            }
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        repository = new ShopRepository(connection);
        repository.migrate();
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BarterShop-Database");
            thread.setDaemon(true);
            return thread;
        });
    }

    @FunctionalInterface
    public interface DbTask<T> {
        T apply(ShopRepository repository) throws Exception;
    }

    @FunctionalInterface
    public interface DbAction {
        void accept(ShopRepository repository) throws Exception;
    }

    public <T> T call(DbTask<T> work) {
        try {
            return executor.submit(() -> work.apply(repository)).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DatabaseException("Database call interrupted", e);
        } catch (ExecutionException e) {
            throw new DatabaseException("Database operation failed", e.getCause());
        } catch (TimeoutException e) {
            throw new DatabaseException("Database operation timed out", e);
        } catch (RuntimeException e) {
            throw new DatabaseException("Database operation failed", e);
        }
    }

    public <T> CompletableFuture<T> callAsync(DbTask<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.apply(repository);
            } catch (Exception e) {
                throw new DatabaseException("Database operation failed", e);
            }
        }, executor);
    }

    public void run(DbAction work) {
        call(repository -> {
            work.accept(repository);
            return null;
        });
    }

    @Override
    public void close() {
        try {
            executor.submit(() -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                } catch (SQLException ignored) {
                    // Best effort on shutdown.
                }
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // Best effort on shutdown.
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Shutdown is best effort; SQLite recovers from WAL on next start.
        } finally {
            executor.shutdownNow();
        }
    }

    public static final class DatabaseException extends RuntimeException {
        public DatabaseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
