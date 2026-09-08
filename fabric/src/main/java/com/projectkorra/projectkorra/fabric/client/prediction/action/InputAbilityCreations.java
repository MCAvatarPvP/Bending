package com.projectkorra.projectkorra.fabric.client.prediction.action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** The constructor outcomes at the input boundary, before any progress ticks. */
public final class InputAbilityCreations<T> {
    private final List<T> created = new ArrayList<>();

    public void add(final T ability) {
        if (ability != null && created.stream().noneMatch(existing -> existing == ability)) {
            created.add(ability);
        }
    }

    public Map<String, Integer> counts(final Function<T, String> name) {
        final Map<String, Integer> counts = new HashMap<>();
        for (T ability : created) counts.merge(normalize(name.apply(ability)), 1, Integer::sum);
        return counts;
    }

    public Set<T> rejected(final List<String> authoritativeNames,
                           final Function<T, String> name,
                           final Predicate<T> established) {
        final Map<String, Integer> remaining = new HashMap<>();
        for (String value : authoritativeNames) remaining.merge(normalize(value), 1, Integer::sum);
        final Set<T> rejected = Collections.newSetFromMap(new IdentityHashMap<>());
        for (T ability : created) {
            final String key = normalize(name.apply(ability));
            final int count = remaining.getOrDefault(key, 0);
            if (count > 0) remaining.put(key, count - 1);
            else if (!established.test(ability)) rejected.add(ability);
        }
        return rejected;
    }

    /** Descendants share their root's verdict, even after the root has finished. */
    public static <T> boolean descendsFrom(final T ability, final Set<T> roots,
                                          final Function<T, T> parent) {
        for (T current = ability; current != null; current = parent.apply(current)) {
            if (roots.contains(current)) return true;
        }
        return false;
    }

    private static String normalize(final String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
