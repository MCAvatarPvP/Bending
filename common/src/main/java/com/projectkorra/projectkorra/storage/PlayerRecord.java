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
    private final String waterCosmetic;
    private final String earthCosmetic;
    private final boolean sprinkle;
    private final boolean permaRemoved;
    private final boolean sourceHoles;
    private final String viewDistance;
    private final boolean detailedActionBar;
    private final boolean oldScooter;
    private final Map<Integer, String> slots;

    public PlayerRecord(final UUID uuid, final String playerName, final String elements, final String subelements, final String style,
                        final String fireColor, final String airColor, final String waterCosmetic, final String earthCosmetic, final boolean sprinkle,
                        final boolean permaRemoved, final boolean sourceHoles, final String viewDistance, final boolean detailedActionBar,
                        final boolean oldScooter,
                        final Map<Integer, String> slots) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.elements = elements;
        this.subelements = subelements;
        this.style = style;
        this.fireColor = fireColor;
        this.airColor = airColor;
        this.waterCosmetic = waterCosmetic;
        this.earthCosmetic = earthCosmetic;
        this.sprinkle = sprinkle;
        this.permaRemoved = permaRemoved;
        this.sourceHoles = sourceHoles;
        this.viewDistance = viewDistance;
        this.detailedActionBar = detailedActionBar;
        this.oldScooter = oldScooter;
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

    public String getWaterCosmetic() {
        return this.waterCosmetic;
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

    public boolean isDetailedActionBarEnabled() {
        return this.detailedActionBar;
    }

    public boolean isOldScooterEnabled() {
        return this.oldScooter;
    }

    public Map<Integer, String> getSlots() {
        return this.slots;
    }
}

