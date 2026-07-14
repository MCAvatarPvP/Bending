package hackathonpack;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.ClickType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class UtilityMethods {
    private UtilityMethods() {
    }

    public static String getVersion() {
        return "HackathonPack v1.0";
    }

    public static Vector rotateVectorAroundY(final Vector vector, final double degrees) {
        final double rad = Math.toRadians(degrees);
        final double x = vector.getX();
        final double z = vector.getZ();
        return new Vector(Math.cos(rad) * x - Math.sin(rad) * z, vector.getY(), Math.sin(rad) * x + Math.cos(rad) * z);
    }

    public static Vector rotate(final Vector vector, final Location yawPitchLocation) {
        final double yaw = Math.toRadians(-yawPitchLocation.getYaw());
        final double pitch = Math.toRadians(yawPitchLocation.getPitch());
        double oldX = vector.getX();
        double oldY = vector.getY();
        double oldZ = vector.getZ();
        vector.setY(oldY * Math.cos(pitch) - oldZ * Math.sin(pitch));
        vector.setZ(oldY * Math.sin(pitch) + oldZ * Math.cos(pitch));
        oldY = vector.getY();
        oldZ = vector.getZ();
        vector.setX(oldX * Math.cos(yaw) + oldZ * Math.sin(yaw));
        vector.setZ(-oldX * Math.sin(yaw) + oldZ * Math.cos(yaw));
        return vector;
    }

    public static boolean isInLineOfSight(final Player player, final Location target) {
        final Vector dir = player.getLocation().getDirection();
        final Vector other = target.clone().toVector().subtract(player.getLocation().toVector());
        final double denom = dir.length() * other.length();
        return denom > 0 && Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dir.dot(other) / denom)))) < 60;
    }

    public static boolean checkBlocks(final LivingEntity entity) {
        final Block block = entity.getLocation().getBlock();
        final Block[] blocks = {
                block.getRelative(BlockFace.UP).getRelative(BlockFace.EAST),
                block.getRelative(BlockFace.UP).getRelative(BlockFace.WEST),
                block.getRelative(BlockFace.UP).getRelative(BlockFace.NORTH),
                block.getRelative(BlockFace.UP).getRelative(BlockFace.SOUTH),
                block.getRelative(BlockFace.UP).getRelative(BlockFace.UP).getRelative(BlockFace.EAST),
                block.getRelative(BlockFace.UP).getRelative(BlockFace.UP).getRelative(BlockFace.WEST),
                block.getRelative(BlockFace.UP).getRelative(BlockFace.UP).getRelative(BlockFace.NORTH),
                block.getRelative(BlockFace.UP).getRelative(BlockFace.UP).getRelative(BlockFace.SOUTH)
        };
        for (final Block candidate : blocks) {
            if (GeneralMethods.isSolid(candidate)) {
                return true;
            }
        }
        return false;
    }

    public static ArrayList<AbilityInformation> getConfiguredCombination(final String enabledPath, final String combinationPath,
                                                                         final ArrayList<AbilityInformation> fallback) {
        if (!ConfigManager.getConfig().getBoolean(enabledPath)) {
            return null;
        }
        final ArrayList<AbilityInformation> result = new ArrayList<>();
        final List<?> configured = ConfigManager.getConfig().getList(combinationPath);
        if (configured != null) {
            for (final Object step : configured) {
                final AbilityInformation parsed = parseAbilityInformation(step);
                if (parsed != null) {
                    result.add(parsed);
                }
            }
        }
        return result.isEmpty() ? new ArrayList<>(fallback) : result;
    }

    public static void logConfiguredCombination(final String abilityName, final String enabledPath, final String combinationPath,
                                                final ArrayList<AbilityInformation> fallback) {
        final ArrayList<AbilityInformation> combination = getConfiguredCombination(enabledPath, combinationPath, fallback);
        ProjectKorra.log.info(abilityName + " combo loaded as " + (combination == null ? "<disabled>" : combination));
    }

    private static AbilityInformation parseAbilityInformation(final Object configuredStep) {
        if (configuredStep == null) {
            return null;
        }
        if (configuredStep instanceof Map<?, ?> map && map.size() == 1) {
            final Map.Entry<?, ?> entry = map.entrySet().iterator().next();
            return createAbilityInformation(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        final String normalized = String.valueOf(configuredStep).trim().replace("->", ":");
        final String[] parts = normalized.contains(":") ? normalized.split("\\s*:\\s*", 2)
                : normalized.contains(",") ? normalized.split("\\s*,\\s*", 2) : normalized.split("\\s+", 2);
        return parts.length == 2 ? createAbilityInformation(parts[0], parts[1]) : null;
    }

    private static AbilityInformation createAbilityInformation(final String abilityName, final String clickName) {
        try {
            return new AbilityInformation(abilityName.trim(), ClickType.valueOf(clickName.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_')));
        } catch (final IllegalArgumentException ignored) {
            return null;
        }
    }
}
