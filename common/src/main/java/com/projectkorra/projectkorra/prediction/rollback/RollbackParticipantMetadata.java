package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.metadata.FixedMetadataValue;
import com.projectkorra.projectkorra.platform.mc.metadata.MetadataValue;
import com.projectkorra.projectkorra.util.FallHandler;
import com.projectkorra.projectkorra.util.MovementHandler;
import java.util.*;

/** The backing markers of bending's stun and fall-damage rules, on the confirmed roster only. */
final class RollbackParticipantMetadata {
    private static final List<String> KEYS = List.of(MovementHandler.STOP_METADATA, FallHandler.STEP_METADATA);

    private RollbackParticipantMetadata() { }

    static Map<UUID, Map<String, Object>> capture(Collection<? extends Player> players) {
        var snapshots = new TreeMap<UUID, Map<String, Object>>();
        for (var player : players) {
            var markers = new LinkedHashMap<String, Object>();
            for (String key : KEYS) {
                for (var entry : player.getMetadata(key)) {
                    if (entry.owner() != ProjectKorra.plugin)
                        throw new IllegalStateException("Bending participant marker overlaps another metadata owner: " + key);
                    markers.put(key, entry.value());
                }
            }
            snapshots.put(player.getUniqueId(), markers);
        }
        return snapshots;
    }

    static void validate(Map<UUID, Map<String, Object>> snapshots, Map<UUID, Player> roster) {
        if (!snapshots.isEmpty() && !snapshots.keySet().equals(roster.keySet()))
            throw new IllegalStateException("Bending status metadata belongs to another roster");
        for (var markers : snapshots.values()) {
            if (!KEYS.containsAll(markers.keySet())) throw new IllegalStateException("Unknown bending participant marker");
            var stun = markers.get(MovementHandler.STOP_METADATA);
            if (stun != null && !(stun instanceof CoreAbility)) throw new IllegalStateException("Invalid bending stun owner");
            if (markers.containsKey(FallHandler.STEP_METADATA)
                    && !(markers.get(FallHandler.STEP_METADATA) instanceof Integer step && step >= FallHandler.Step.WAITING_FOR_MOVEMENT
                    && step <= FallHandler.Step.REMOVE)) throw new IllegalStateException("Invalid bending fall step");
        }
    }

    static void apply(Map<UUID, Map<String, Object>> snapshots, Map<UUID, Player> roster) {
        validate(snapshots, roster);
        snapshots.forEach((id, markers) -> {
            Player player = roster.get(id);
            for (String key : KEYS) {
                MetadataValue current = player.getMetadata(key).stream().filter(value -> value.owner() == ProjectKorra.plugin)
                        .findFirst().orElse(null);
                if (markers.containsKey(key)) {
                    var value = markers.get(key);
                    if (current == null || (value instanceof CoreAbility ? current.value() != value : !Objects.equals(current.value(), value)))
                        player.setMetadata(key, new FixedMetadataValue(ProjectKorra.plugin, value));
                } else if (current != null) player.removeMetadata(key, ProjectKorra.plugin);
            }
        });
    }
}
