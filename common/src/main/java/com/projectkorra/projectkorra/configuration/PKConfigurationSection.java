package com.projectkorra.projectkorra.configuration;

import com.projectkorra.projectkorra.platform.mc.Color;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Platform-neutral subset of the configuration API used by ProjectKorra common code.
 */
public interface PKConfigurationSection {
    boolean contains(String path);

    Object get(String path);

    Object get(String path, Object def);

    String getString(String path);

    String getString(String path, String def);

    boolean getBoolean(String path);

    boolean getBoolean(String path, boolean def);

    int getInt(String path);

    int getInt(String path, int def);

    long getLong(String path);

    long getLong(String path, long def);

    double getDouble(String path);

    double getDouble(String path, double def);

    List<String> getStringList(String path);

    default List<?> getList(String path) {
        Object value = get(path);
        return value instanceof List<?> list ? list : List.of();
    }

    default List<?> getList(String path, List<?> def) {
        Object value = get(path);
        return value instanceof List<?> list ? list : def;
    }

    default List<Integer> getIntegerList(String path) {
        return getList(path).stream().filter(Number.class::isInstance).map(Number.class::cast).map(Number::intValue).toList();
    }

    default List<Boolean> getBooleanList(String path) {
        return getList(path).stream().filter(Boolean.class::isInstance).map(Boolean.class::cast).toList();
    }

    default List<Double> getDoubleList(String path) {
        return getList(path).stream().filter(Number.class::isInstance).map(Number.class::cast).map(Number::doubleValue).toList();
    }

    default List<Float> getFloatList(String path) {
        return getList(path).stream().filter(Number.class::isInstance).map(Number.class::cast).map(Number::floatValue).toList();
    }

    default List<Long> getLongList(String path) {
        return getList(path).stream().filter(Number.class::isInstance).map(Number.class::cast).map(Number::longValue).toList();
    }

    default List<Map<?, ?>> getMapList(String path) {
        return getList(path).stream().filter(Map.class::isInstance).map(m -> (Map<?, ?>) m).collect(Collectors.toList());
    }

    default ItemStack getItemStack(String path) {
        Object value = get(path);
        return value instanceof ItemStack item ? item : null;
    }

    default ItemStack getItemStack(String path, ItemStack def) {
        ItemStack value = getItemStack(path);
        return value == null ? def : value;
    }

    default Color getColor(String path) {
        Object value = get(path);
        return value instanceof Color color ? color : null;
    }

    default Color getColor(String path, Color def) {
        Color value = getColor(path);
        return value == null ? def : value;
    }

    default Vector getVector(String path) {
        Object value = get(path);
        return value instanceof Vector vector ? vector : null;
    }

    default Vector getVector(String path, Vector def) {
        Vector value = getVector(path);
        return value == null ? def : value;
    }

    default Location getLocation(String path) {
        Object value = get(path);
        return value instanceof Location location ? location : null;
    }

    default Location getLocation(String path, Location def) {
        Location value = getLocation(path);
        return value == null ? def : value;
    }

    PKConfigurationSection getConfigurationSection(String path);

    Set<String> getKeys(boolean deep);

    default Map<String, Object> getValues(boolean deep) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : getKeys(deep)) values.put(key, get(key));
        return values;
    }

    default boolean isBoolean(String path) {
        return get(path) instanceof Boolean;
    }

    default boolean isInt(String path) {
        return get(path) instanceof Number number && Math.floor(number.doubleValue()) == number.doubleValue();
    }

    default boolean isDouble(String path) {
        return get(path) instanceof Number;
    }

    default boolean isLong(String path) {
        return get(path) instanceof Number;
    }

    default boolean isSet(String path) {
        return contains(path);
    }
}
