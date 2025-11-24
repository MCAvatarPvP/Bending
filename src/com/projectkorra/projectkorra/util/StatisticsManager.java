package com.projectkorra.projectkorra.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Player;

import com.projectkorra.projectkorra.Manager;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.storage.DBConnection;
import com.projectkorra.projectkorra.storage.StatisticsRepository;
import com.projectkorra.projectkorra.storage.StorageException;

public class StatisticsManager extends Manager implements Runnable {

	/**
	 * HashMap which contains all current statistic values (Map<player,
	 * Map<statId, statValue>>)
	 */
	private final Map<UUID, Map<Integer, Long>> STATISTICS = new HashMap<>();
	/**
	 * HashMap which contains all statistic delta values (Map<player,
	 * Map<statId, statValue>>)
	 */
	private final Map<UUID, Map<Integer, Long>> DELTA = new HashMap<>();
	/**
	 * HashMap which contains all statistic names by ID.
	 */
	private final Map<String, Integer> KEYS_BY_NAME = new HashMap<>();
	/**
	 * HashMap which contains all statistic IDs by name.
	 */
	private final Map<Integer, String> KEYS_BY_ID = new HashMap<>();
	/**
	 * HashMap which contains all UUIDs of players who have recently logged out
	 * to have their stats saved.
	 */
	private final Set<UUID> STORAGE = new HashSet<>();
	private final int INTERVAL = 5;

	private StatisticsManager() {}

	@Override
	public void onActivate() {
		if (!ProjectKorra.isStatisticsEnabled()) {
			ProjectKorra.plugin.getServer().getScheduler().scheduleSyncRepeatingTask(ProjectKorra.plugin, this, 20 * this.INTERVAL, 20 * this.INTERVAL);
		}
		this.setupStatistics();
	}

	public void setupStatistics() {
		final StatisticsRepository repository = DBConnection.getAdapter().statistics();
		repository.ensureSchema();
		for (final CoreAbility ability : CoreAbility.getAbilitiesByName()) {
			if (ability.isHarmlessAbility()) {
				continue;
			}
			for (final Statistic statistic : Statistic.values()) {
				repository.ensureKey(statistic.getStatisticName(ability));
			}
		}
		final Map<String, Integer> keys = repository.loadKeys();
		this.KEYS_BY_NAME.clear();
		this.KEYS_BY_NAME.putAll(keys);
		this.KEYS_BY_ID.clear();
		for (final Map.Entry<String, Integer> entry : keys.entrySet()) {
			this.KEYS_BY_ID.put(entry.getValue(), entry.getKey());
		}
	}

	public void load(final UUID uuid) {
		this.STATISTICS.put(uuid, new HashMap<>());
		this.DELTA.put(uuid, new HashMap<>());
		try {
			final Map<Integer, Long> stats = DBConnection.getAdapter().statistics().load(uuid);
			this.STATISTICS.get(uuid).putAll(stats);
			for (final Integer statId : stats.keySet()) {
				this.DELTA.get(uuid).put(statId, 0L);
			}
		} catch (final StorageException ex) {
			ex.printStackTrace();
		}
	}

	public void save(final UUID uuid, final boolean async) {
		if (!this.DELTA.containsKey(uuid)) {
			return;
		}
		final Map<Integer, Long> stats = new HashMap<>(this.DELTA.get(uuid));
		final Runnable task = () -> DBConnection.getAdapter().statistics().applyDeltas(uuid, stats);
		if (async) {
			ProjectKorra.plugin.getServer().getScheduler().runTaskAsynchronously(ProjectKorra.plugin, task);
		} else {
			task.run();
		}
	}

	public long getStatisticDelta(final UUID uuid, final int statId) {
		// If the player is offline, pull value from database.
		if (!this.DELTA.containsKey(uuid)) {
			return 0;
		} else if (!this.DELTA.get(uuid).containsKey(statId)) {
			return 0;
		}
		return this.DELTA.get(uuid).get(statId);
	}

	public long getStatisticCurrent(final UUID uuid, final int statId) {
		// If the player is offline, pull value from database.
		if (!this.STATISTICS.containsKey(uuid)) {
			return DBConnection.getAdapter().statistics().getStat(uuid, statId);
		} else if (!this.STATISTICS.get(uuid).containsKey(statId)) {
			return 0;
		}
		return this.STATISTICS.get(uuid).get(statId);
	}

	public void addStatistic(final UUID uuid, final int statId, final long statDelta) {
		if (!this.STATISTICS.containsKey(uuid) || !this.DELTA.containsKey(uuid)) {
			return;
		}
		this.STATISTICS.get(uuid).put(statId, this.getStatisticCurrent(uuid, statId) + statDelta);
		this.DELTA.get(uuid).put(statId, this.getStatisticDelta(uuid, statId) + statDelta);

	}

	public Map<Integer, Long> getStatisticsMap(final UUID uuid) {
		final Map<Integer, Long> map = new HashMap<>();
		// If the player is offline, create a new temporary Map from the database.
		if (!this.STATISTICS.containsKey(uuid)) {
			return DBConnection.getAdapter().statistics().load(uuid);
		}
		return this.STATISTICS.get(uuid);
	}

	public void store(final UUID uuid) {
		this.STORAGE.add(uuid);
	}

	@Override
	public void run() {
		for (final UUID uuid : this.STORAGE) {
			// Confirm that the player is offline.
			final Player player = ProjectKorra.plugin.getServer().getPlayer(uuid);
			if (player == null) {
				this.save(uuid, true);
			}
		}
		this.STORAGE.clear();
	}

	public Map<String, Integer> getKeysByName() {
		return this.KEYS_BY_NAME;
	}

	public Map<Integer, String> getKeysById() {
		return this.KEYS_BY_ID;
	}

}
