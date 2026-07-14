package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.configuration.PKConfiguration;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.metadata.FixedMetadataValue;
import com.projectkorra.projectkorra.platform.mc.metadata.MetadataValue;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FallHandler {

    private static final Map<String, VelocityMode> abilityFallDamageBlockers = new HashMap<>();

    public static void stopFall(Player player) {
        stopFall(player, true);
    }

    public static void stopFall(Player player, boolean waitForMovement) {
        player.setMetadata(
                "fallhandler:step",
                new FixedMetadataValue(ProjectKorra.plugin, waitForMovement ? Step.WAITING_FOR_MOVEMENT : Step.MOVED)
        );
    }

    public static int getStep(Player player) {
        List<MetadataValue> values = player.getMetadata("fallhandler:step");
        if (values.isEmpty()) return -1;
        return values.getFirst().value() instanceof Integer i ? i : -1;
    }

    public static void removePlayer(Player player) {
        player.removeMetadata("fallhandler:step", ProjectKorra.plugin);
    }

    public static boolean contains(Player player) {
        return player.hasMetadata("fallhandler:step");
    }

    public static void move(Player player) {
        int step = getStep(player);
        if (step < 0) return;
        boolean isOnGround = GeneralMethods.isOnGround(player);

        if (step == Step.WAITING_FOR_MOVEMENT && !isOnGround) {
            player.setMetadata("fallhandler:step", new FixedMetadataValue(ProjectKorra.plugin, Step.MOVED));
        } else if (step == Step.MOVED && isOnGround) {
            player.setMetadata("fallhandler:step", new FixedMetadataValue(ProjectKorra.plugin, Step.REMOVE));
        } else if (step >= Step.REMOVE) {
            removePlayer(player);
        }
    }

    public static boolean shouldStopFallFromVelocity(Player player, CoreAbility ability) {
        if (player == null || ability == null) return false;
        boolean isUser = ability.getPlayer() != null
                && player.getUniqueId().equals(ability.getPlayer().getUniqueId());
        VelocityMode mode = abilityFallDamageBlockers.get(normalize(ability.getName()));
        if (mode == null)
            return false;
        return mode == VelocityMode.BOTH ||
                (isUser && mode == VelocityMode.USER) ||
                (!isUser && mode == VelocityMode.OTHERS);
    }

    public static void loadNoFallDamageAbilities() {
        abilityFallDamageBlockers.clear();
        PKConfiguration config = ConfigManager.fallDamageConfig.get();

        for (String s : config.getStringList("OnVelocity")) {
            String[] args = s.split("\\s*,\\s*");
            if (args.length < 2) continue;
            if (args[0].equalsIgnoreCase("ability")) continue;

            String configuredName = args[0].trim();
            CoreAbility ability = CoreAbility.getAbility(configuredName);
            if (ability == null) ability = CoreAbility.getAbilityByClassName(configuredName);
            if (ability == null) {
                for (CoreAbility candidate : CoreAbility.getAbilities()) {
                    if (candidate.getName().equalsIgnoreCase(configuredName)
                            || candidate.getClass().getSimpleName().equalsIgnoreCase(configuredName)) {
                        ability = candidate;
                        break;
                    }
                }
            }
            VelocityMode mode = VelocityMode.getMode(args[1].trim());

            if (ability == null) {
                ProjectKorra.log.warning("Couldn't find ability \"" + args[0] + "\" in fallDamageConfig.yml");
                continue;
            }
            if (mode == null) {
                ProjectKorra.log.warning("Couldn't find mode \"" + args[1] + "\" in fallDamageConfig.yml");
                continue;
            }

            String abilityName = normalize(ability.getName());
            VelocityMode existingMode = abilityFallDamageBlockers.get(abilityName);
            if (existingMode == VelocityMode.BOTH) {
                continue;
            } else if ((existingMode == VelocityMode.USER && mode == VelocityMode.OTHERS) ||
                    (existingMode == VelocityMode.OTHERS && mode == VelocityMode.USER)) {
                abilityFallDamageBlockers.put(abilityName, VelocityMode.BOTH);
                continue;
            }

            abilityFallDamageBlockers.put(abilityName, mode);
        }
    }

    private static String normalize(String ability) {
        return ability == null ? "" : ability.trim().toLowerCase(Locale.ROOT);
    }

    public enum VelocityMode {
        USER, OTHERS, BOTH;

        public static VelocityMode getMode(String name) {
            try {
                return valueOf(name.toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    public static class Step {
        public static final int WAITING_FOR_MOVEMENT = 0;
        public static final int MOVED = 1;
        public static final int REMOVE = 2;
    }
}
