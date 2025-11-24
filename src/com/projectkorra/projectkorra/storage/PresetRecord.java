package com.projectkorra.projectkorra.storage;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PresetRecord {

	private final UUID uuid;
	private final String name;
	private final Map<Integer, String> slots;

	public PresetRecord(final UUID uuid, final String name, final Map<Integer, String> slots) {
		this.uuid = uuid;
		this.name = name;
		this.slots = Collections.unmodifiableMap(new HashMap<>(slots));
	}

	public UUID getUuid() {
		return this.uuid;
	}

	public String getName() {
		return this.name;
	}

	public Map<Integer, String> getSlots() {
		return this.slots;
	}
}

