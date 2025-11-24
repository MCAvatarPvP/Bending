package com.projectkorra.projectkorra.storage;

import java.util.Set;
import java.util.UUID;

public interface BoardRepository {

	Set<UUID> loadDisabled();

	boolean isDisabled(UUID uuid);

	void setBoardState(UUID uuid, boolean enabled);
}

