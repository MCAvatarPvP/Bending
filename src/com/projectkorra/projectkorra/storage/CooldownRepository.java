package com.projectkorra.projectkorra.storage;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface CooldownRepository {

	Map<String, Long> load(UUID uuid);

	Optional<Long> load(UUID uuid, String name);

	void upsert(UUID uuid, String name, long value);

	void deleteAll(UUID uuid);
}

