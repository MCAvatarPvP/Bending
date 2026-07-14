package com.projectkorra.projectkorra.fabric.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks when a locally predicted cooldown has been observed by the server.
 * An authoritative state which predates the input must not clear a new local
 * cooldown, but a later state may clear it after the server first confirmed it.
 */
final class PredictionCooldownAuthority {
    private final Map<String, Long> predictedExpiries = new HashMap<>();
    private final Set<String> serverConfirmed = new HashSet<>();

    void onLocalAdded(final String ability, final long expiresAtMillis) {
        if (ability == null || ability.isBlank()) return;
        predictedExpiries.put(ability, expiresAtMillis);
        serverConfirmed.remove(ability);
    }

    void onLocalRemoved(final String ability) {
        if (ability == null) return;
        predictedExpiries.remove(ability);
        serverConfirmed.remove(ability);
    }

    void retainLocallyActive(final Set<String> locallyActive) {
        predictedExpiries.keySet().removeIf(ability -> !locallyActive.contains(ability));
        serverConfirmed.retainAll(predictedExpiries.keySet());
    }

    Set<String> reconcile(final Set<String> authoritativeActive, final Set<String> locallyActive) {
        retainLocallyActive(locallyActive);
        final Set<String> released = new LinkedHashSet<>();
        for (final String ability : Set.copyOf(predictedExpiries.keySet())) {
            if (authoritativeActive.contains(ability)) {
                serverConfirmed.add(ability);
            } else if (serverConfirmed.contains(ability)) {
                released.add(ability);
            }
        }
        return released;
    }

    void clear() {
        predictedExpiries.clear();
        serverConfirmed.clear();
    }
}
