package com.projectkorra.projectkorra.platform;

import java.util.Collection;

/**
 * Block/item tag facade.
 */
public interface PKTags {
    <T> Collection<T> values(String registry, String key, Class<T> type);
}
