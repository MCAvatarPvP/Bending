package com.projectkorra.projectkorra.storage;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface PlayerRepository {

	Optional<PlayerRecord> load(UUID uuid);

	void createDefault(UUID uuid, String playerName);

	void update(UUID uuid, Map<PlayerColumn, String> columns);
}

