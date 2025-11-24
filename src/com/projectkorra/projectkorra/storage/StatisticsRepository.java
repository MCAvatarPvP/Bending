package com.projectkorra.projectkorra.storage;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface StatisticsRepository {

	void ensureSchema();

	void ensureKey(String statName);

	Map<String, Integer> loadKeys();

	int getOrCreateKey(String statName);

	Map<Integer, Long> load(UUID uuid);

	void applyDeltas(UUID uuid, Map<Integer, Long> deltas);

	long getStat(UUID uuid, int statId);

	Set<UUID> getTrackedPlayers();
}

