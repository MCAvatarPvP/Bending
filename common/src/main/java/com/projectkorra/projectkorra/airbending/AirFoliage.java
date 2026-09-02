package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.platform.mc.Color;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.Tag;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.Bisected;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

/** Shared material-matched foliage destruction for fast air attacks. */
public final class AirFoliage {

    private AirFoliage() { }

    public static boolean clear(final AirAbility ability, final Block block, final Vector direction) {
        if (ability == null || block == null || !isFoliage(block)
                || GeneralMethods.isRegionProtectedFromBuild(ability, block.getLocation())) return false;

        final BlockData originalData = block.getBlockData().clone();
        final Block pairedHalf = originalData instanceof Bisected ? pairedHalf(block) : null;
        final Location center = block.getLocation().clone().add(0.5, 0.5, 0.5);
        final boolean leaves = isLeafBlock(block.getType());
        final Color color = foliageColor(block.getType());
        final int particleCount = leaves ? 5 : 3;
        final Vector forward = normalizedOrDefault(direction, new Vector(0, 0, 1));
        final Vector reference = Math.abs(forward.getY()) < 0.92
                ? new Vector(0, 1, 0) : new Vector(1, 0, 0);
        final Vector right = forward.clone().crossProduct(reference).normalize();
        for (int index = 0; index < particleCount; index++) {
            final double progress = (index + 0.5) / particleCount;
            final double phase = progress * Math.PI * 2.0;
            final Location particleOrigin = center.clone()
                    .add(right.clone().multiply(Math.sin(phase) * (leaves ? 0.22 : 0.12)))
                    .add(0, (progress - 0.5) * (leaves ? 0.34 : 0.18), 0);
            final Vector velocity = particleVelocity(direction, index, particleCount);
            // A zero-count particle uses the offsets as one particle's exact velocity.
            block.getWorld().spawnParticle(Particle.TINTED_LEAVES, particleOrigin, 0,
                    velocity.getX(), velocity.getY(), velocity.getZ(), 1.0, color);
        }
        block.setType(Material.AIR, false);
        if (pairedHalf != null) clear(ability, pairedHalf, direction);
        return true;
    }

    private static Block pairedHalf(final Block block) {
        final Block above = block.getRelative(BlockFace.UP);
        if (above.getType() == block.getType() && above.getBlockData() instanceof Bisected) return above;
        final Block below = block.getRelative(BlockFace.DOWN);
        return below.getType() == block.getType() && below.getBlockData() instanceof Bisected ? below : null;
    }

    private static boolean isFoliage(final Block block) {
        return isLeafBlock(block.getType())
                || (ElementalAbility.isPlant(block.getType()) && GeneralMethods.isPassable(block));
    }

    private static boolean isLeafBlock(final Material material) {
        return material != null && (Tag.LEAVES.isTagged(material) || material.toString().endsWith("_LEAVES"));
    }

    static Color foliageColor(final Material material) {
        final String name = material == null ? "" : material.toString();
        if (name.contains("CHERRY") || name.contains("PINK") || name.equals("PEONY")) {
            return Color.fromRGB(0xE7A8B7);
        }
        if (name.contains("PALE_OAK")) return Color.fromRGB(0xA7BFA0);
        if (name.contains("SPRUCE")) return Color.fromRGB(0x619961);
        if (name.contains("BIRCH")) return Color.fromRGB(0x80A755);
        if (name.contains("MANGROVE")) return Color.fromRGB(0x92C648);
        if (name.contains("WARPED") || name.contains("TWISTING") || name.equals("NETHER_SPROUTS")) {
            return Color.fromRGB(0x3A8E8C);
        }
        if (name.contains("CRIMSON") || name.equals("NETHER_WART") || name.equals("NETHER_WART_BLOCK")) {
            return Color.fromRGB(0xA6536F);
        }
        if (name.equals("DEAD_BUSH") || name.contains("BROWN_MUSHROOM")) return Color.fromRGB(0x8A6846);
        if (name.contains("RED_MUSHROOM") || name.equals("POPPY") || name.equals("ROSE_BUSH")
                || name.equals("RED_TULIP")) return Color.fromRGB(0xD4473F);
        if (name.equals("DANDELION") || name.equals("SUNFLOWER") || name.contains("YELLOW")) {
            return Color.fromRGB(0xF2C94C);
        }
        if (name.equals("BLUE_ORCHID") || name.equals("CORNFLOWER")) return Color.fromRGB(0x5596E6);
        if (name.equals("ALLIUM") || name.equals("LILAC") || name.contains("PURPLE")) {
            return Color.fromRGB(0xA66CC5);
        }
        if (name.equals("ORANGE_TULIP") || name.contains("EYEBLOSSOM") || name.equals("TORCHFLOWER")) {
            return Color.fromRGB(0xE88935);
        }
        if (name.equals("AZURE_BLUET") || name.equals("OXEYE_DAISY") || name.equals("WHITE_TULIP")
                || name.equals("LILY_OF_THE_VALLEY")) return Color.fromRGB(0xE8E5D5);
        return Color.fromRGB(0x48B518);
    }

    static Vector particleVelocity(final Vector attackVelocity, final int index, final int particleCount) {
        final double attackSpeed = attackVelocity == null ? 0.0 : attackVelocity.length();
        final Vector forward = normalizedOrDefault(attackVelocity, new Vector(0, 0, 1));
        final Vector reference = Math.abs(forward.getY()) < 0.92
                ? new Vector(0, 1, 0) : new Vector(1, 0, 0);
        final Vector right = forward.clone().crossProduct(reference).normalize();
        final double progress = (index + 0.5) / Math.max(1, particleCount);
        final double phase = progress * Math.PI * 2.0;
        final double inheritedSpeed = clamp(attackSpeed * 0.18, 0.08, 0.32);
        final double flutter = Math.sin(phase) * (0.025 + inheritedSpeed * 0.08);
        final double lift = 0.035 + (index % 3) * 0.012;
        return forward.multiply(inheritedSpeed * (0.84 + progress * 0.22))
                .add(right.multiply(flutter))
                .add(new Vector(0, lift, 0));
    }

    private static Vector normalizedOrDefault(final Vector vector, final Vector fallback) {
        return vector == null || vector.lengthSquared() <= 1.0E-9
                ? fallback.clone() : vector.clone().normalize();
    }

    private static double clamp(final double value, final double minimum, final double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
