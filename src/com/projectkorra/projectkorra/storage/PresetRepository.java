package com.projectkorra.projectkorra.storage;

import java.util.List;
import java.util.UUID;

public interface PresetRepository {

	List<PresetRecord> load(UUID uuid);

	void save(PresetRecord record);

	void delete(UUID uuid, String name);
}

