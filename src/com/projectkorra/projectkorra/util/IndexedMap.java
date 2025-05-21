package com.projectkorra.projectkorra.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IndexedMap<K,V> {
    private final Map<K,V> map  = new HashMap<>();
    private final List<K> keys = new ArrayList<>();

    public V put(K key, V value) {
        if (!map.containsKey(key)) {
            keys.add(key);
        }
        return map.put(key, value);
    }

    public V get(K key) {
        return map.get(key);
    }

    public K get(int index) {
        if (index < 0 || index >= keys.size()) {
            throw new IndexOutOfBoundsException("Index: "+index+", Size: "+keys.size());
        }

        return keys.get(index);
    }

    public int size() {
        return keys.size();
    }

    public V remove(K key) {
        if (map.containsKey(key)) {
            keys.remove(key);
            return map.remove(key);
        }
        return null;
    }
}
