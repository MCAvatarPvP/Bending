package com.projectkorra.projectkorra.storage;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.Platform;

import java.util.logging.Level;

public class DBConnection {

    private static StorageAdapter adapter;
    private static boolean isOpen = false;

    private DBConnection() {
    }

    public static void init() {
        shutdown();

        final StorageType configuredType = StorageType.fromConfig(ConfigManager.getConfig().getString("Storage.engine"));
        if (open(configuredType, true)) {
            return;
        }

        if (!isFabric()) {
            return;
        }

        if (configuredType == StorageType.MYSQL) {
            ProjectKorra.log.warning("Fabric was configured for MySQL but could not open it; falling back to SQLite for local/client prediction runtime.");
            if (open(StorageType.SQLITE, false)) {
                return;
            }
        }

        ProjectKorra.log.warning("Falling back to in-memory ProjectKorra storage. Player data, presets, cooldowns, and statistics will not persist after restart.");
        adapter = new MemoryStorageAdapter();
        adapter.initialize();
        isOpen = true;
        ProjectKorra.log.info("Storage connection established using " + adapter.getType().name());
    }

    private static boolean open(final StorageType type, final boolean severeOnFailure) {
        try {
            switch (type) {
                case MYSQL:
                    adapter = buildMySqlAdapter();
                    break;
                case MEMORY:
                    adapter = new MemoryStorageAdapter();
                    break;
                default:
                    adapter = buildSqliteAdapter();
                    break;
            }

            if (adapter == null) {
                ProjectKorra.log.severe("Failed to create storage adapter for " + type);
                isOpen = false;
                return false;
            }
            adapter.initialize();
            isOpen = true;
            ProjectKorra.log.info("Storage connection established using " + adapter.getType().name());
            return true;
        } catch (final StorageException ex) {
            if (severeOnFailure && !isFabric()) {
                ProjectKorra.log.log(Level.SEVERE, "Disabling due to storage error", ex);
            } else {
                ProjectKorra.log.warning("Unable to open " + type.name() + " storage: " + ex.getMessage());
            }
            adapter = null;
            isOpen = false;
            return false;
        }
    }

    private static boolean isFabric() {
        try {
            return Platform.isInstalled() && "fabric".equalsIgnoreCase(Platform.current().id());
        } catch (final RuntimeException ignored) {
            return false;
        }
    }

    private static StorageAdapter buildMySqlAdapter() {
        final String host = ConfigManager.getConfig().getString("Storage.MySQL.host");
        final int port = ConfigManager.getConfig().getInt("Storage.MySQL.port");
        final String pass = ConfigManager.getConfig().getString("Storage.MySQL.pass");
        final String db = ConfigManager.getConfig().getString("Storage.MySQL.db");
        final String user = ConfigManager.getConfig().getString("Storage.MySQL.user");
        final String properties = ConfigManager.getConfig().getString("Storage.MySQL.properties");
        final Database database = new MySQL(ProjectKorra.log, host, port, user, pass, db, properties);
        return new SqlStorageAdapter(database, StorageType.MYSQL);
    }

    private static StorageAdapter buildSqliteAdapter() {
        final Database database = new SQLite(ProjectKorra.log, "projectkorra.db", ProjectKorra.plugin.getDataFolder().getAbsolutePath());
        return new SqlStorageAdapter(database, StorageType.SQLITE);
    }

    public static boolean isOpen() {
        return isOpen && adapter != null;
    }

    public static StorageAdapter getAdapter() {
        return adapter;
    }

    public static void shutdown() {
        if (adapter != null) {
            adapter.shutdown();
            adapter = null;
        }
        isOpen = false;
    }
}
