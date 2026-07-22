package com.projectkorra.projectkorra.fabric.client.prediction.state;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks when a locally predicted cooldown has been observed by Paper.
 * Authoritative state predating the input cannot clear a newer local cooldown;
 * a later state may clear it after Paper first confirms that generation.
 */
public final class PredictionCooldownAuthority {
    private final Map<String, Long> predictedExpiries = new HashMap<>();
    private final Set<String> serverConfirmed = new HashSet<>();

    public void onLocalAdded(final String ability, final long expiresAtMillis) {
        if (ability == null || ability.isBlank()) return;
        predictedExpiries.put(ability, expiresAtMillis);
        serverConfirmed.remove(ability);
    }

    public void onLocalRemoved(final String ability) {
        if (ability == null) return;
        predictedExpiries.remove(ability);
        serverConfirmed.remove(ability);
    }

    public boolean isLocallyPredicted(final String ability) {
        return ability != null && predictedExpiries.containsKey(ability);
    }

    public void retainLocallyActive(final Set<String> locallyActive) {
        predictedExpiries.keySet().removeIf(ability -> !locallyActive.contains(ability));
        serverConfirmed.retainAll(predictedExpiries.keySet());
    }

    public Set<String> reconcile(final Set<String> authoritativeActive,
                                 final Set<String> locallyActive) {
        retainLocallyActive(locallyActive);
        final Set<String> released = new LinkedHashSet<>();
        for (String ability : Set.copyOf(predictedExpiries.keySet())) {
            if (authoritativeActive.contains(ability)) {
                serverConfirmed.add(ability);
            } else if (serverConfirmed.contains(ability)) {
                released.add(ability);
            }
        }
        return released;
    }

    public void clear() {
        predictedExpiries.clear();
        serverConfirmed.clear();
    }
}
