package com.projectkorra.projectkorra.storage;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.configuration.ConfigManager;

import java.util.logging.Level;

public class DBConnection {

	private static StorageAdapter adapter;
	private static boolean isOpen = false;

	private DBConnection() {}

	public static void init() {
		shutdown();

		final StorageType type = StorageType.fromConfig(ConfigManager.getConfig().getString("Storage.engine"));
		try {
			switch (type) {
				case MYSQL:
					adapter = buildMySqlAdapter();
					break;
				default:
					adapter = buildSqliteAdapter();
					break;
			}

			if (adapter == null) {
				ProjectKorra.log.severe("Failed to create storage adapter for " + type);
				isOpen = false;
				return;
			}
			adapter.initialize();
			isOpen = true;
			ProjectKorra.log.info("Storage connection established using " + adapter.getType().name());
		} catch (final StorageException ex) {
			ProjectKorra.log.log(Level.SEVERE, "Disabling due to storage error", ex);
			adapter = null;
			isOpen = false;
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
