package com.projectkorra.projectkorra.storage;

import java.util.HashMap;
import java.util.Map;

public enum PlayerColumn {
    NAME("player"),
    ELEMENT("element"),
    SUBELEMENT("subelement"),
    STYLE("style"),
    FIRE_COLOR("firecolor"),
    AIR_COLOR("aircolor"),
    WATER_COSMETIC("watercosmetic"),
    EARTH_COSMETIC("earthcosmetic"),
    SPRINKLE("sprinkle"),
    PERMA_REMOVED("permaremoved"),
    SOURCE_HOLES("sourceholes"),
    VIEW_DISTANCE("viewdistance"),
    DETAILED_ACTION_BAR("detailedactionbar"),
    OLD_SCOOTER("oldscooter"),
    SLOT_1("slot1"),
    SLOT_2("slot2"),
    SLOT_3("slot3"),
    SLOT_4("slot4"),
    SLOT_5("slot5"),
    SLOT_6("slot6"),
    SLOT_7("slot7"),
    SLOT_8("slot8"),
    SLOT_9("slot9");

    private static final Map<Integer, PlayerColumn> SLOT_LOOKUP = new HashMap<>();

    static {
        SLOT_LOOKUP.put(1, SLOT_1);
        SLOT_LOOKUP.put(2, SLOT_2);
        SLOT_LOOKUP.put(3, SLOT_3);
        SLOT_LOOKUP.put(4, SLOT_4);
        SLOT_LOOKUP.put(5, SLOT_5);
        SLOT_LOOKUP.put(6, SLOT_6);
        SLOT_LOOKUP.put(7, SLOT_7);
        SLOT_LOOKUP.put(8, SLOT_8);
        SLOT_LOOKUP.put(9, SLOT_9);
    }

    private final String columnName;

    PlayerColumn(final String columnName) {
        this.columnName = columnName;
    }

    public static PlayerColumn slotColumn(final int slot) {
        return SLOT_LOOKUP.get(slot);
    }

    public String getColumnName() {
        return this.columnName;
    }
}

