package com.projectkorra.projectkorra.storage;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerRecord {

	private final UUID uuid;
	private final String playerName;
	private final String elements;
	private final String subelements;
	private final String style;
	private final String fireColor;
	private final String airColor;
	private final String earthCosmetic;
	private final boolean sprinkle;
	private final boolean permaRemoved;
	private final boolean sourceHoles;
	private final String viewDistance;
	private final Map<Integer, String> slots;

	public PlayerRecord(final UUID uuid, final String playerName, final String elements, final String subelements, final String style,
						final String fireColor, final String airColor, final String earthCosmetic, final boolean sprinkle,
						final boolean permaRemoved, final boolean sourceHoles, final String viewDistance, final Map<Integer, String> slots) {
		this.uuid = uuid;
		this.playerName = playerName;
		this.elements = elements;
		this.subelements = subelements;
		this.style = style;
		this.fireColor = fireColor;
		this.airColor = airColor;
		this.earthCosmetic = earthCosmetic;
		this.sprinkle = sprinkle;
		this.permaRemoved = permaRemoved;
		this.sourceHoles = sourceHoles;
		this.viewDistance = viewDistance;
		this.slots = Collections.unmodifiableMap(new HashMap<>(slots));
	}

	public UUID getUuid() {
		return this.uuid;
	}

	public String getPlayerName() {
		return this.playerName;
	}

	public String getElements() {
		return this.elements;
	}

	public String getSubelements() {
		return this.subelements;
	}

	public String getStyle() {
		return this.style;
	}

	public String getFireColor() {
		return this.fireColor;
	}

	public String getAirColor() {
		return this.airColor;
	}

	public String getEarthCosmetic() {
		return this.earthCosmetic;
	}

	public boolean isSprinkle() {
		return this.sprinkle;
	}

	public boolean isPermaRemoved() {
		return this.permaRemoved;
	}

	public boolean hasSourceHoles() {
		return this.sourceHoles;
	}

	public String getViewDistance() {
		return this.viewDistance;
	}

	public Map<Integer, String> getSlots() {
		return this.slots;
	}
}

