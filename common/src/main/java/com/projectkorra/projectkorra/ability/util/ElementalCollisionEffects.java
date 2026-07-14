package com.projectkorra.projectkorra.ability.util;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.Element.SubElement;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.object.EarthCosmetic;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.*;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.BlockDisplay;
import com.projectkorra.projectkorra.platform.mc.entity.Display;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.platform.mc.util.Transformation;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.ParticleEffect;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Random;

public final class ElementalCollisionEffects {

    private static final Random RANDOM = new Random();
    private static final Particle.DustOptions AIR_DUST = new Particle.DustOptions(Color.fromRGB(210, 235, 255), 1.25F);
    private static final Particle.DustOptions WATER_DUST = new Particle.DustOptions(Color.fromRGB(70, 165, 255), 1.35F);
    private static final Particle.DustOptions EARTH_DUST = new Particle.DustOptions(Color.fromRGB(125, 88, 48), 1.25F);
    private static final Particle.DustOptions FIRE_DUST = new Particle.DustOptions(Color.fromRGB(255, 105, 25), 1.4F);
    private static final Particle.DustOptions STEAM_DUST = new Particle.DustOptions(Color.fromRGB(225, 235, 235), 1.6F);

    private ElementalCollisionEffects() {
    }

    public static void play(final Collision collision) {
        if (collision == null || collision.getAbilityFirst() == null || collision.getAbilitySecond() == null) {
            return;
        }

        final Element first = parentElement(collision.getAbilityFirst());
        final Element second = parentElement(collision.getAbilitySecond());
        final CollisionCosmetics cosmetics = CollisionCosmetics.from(collision);
        play(center(collision.getLocationFirst(), collision.getLocationSecond()), first, second, cosmetics, direction(collision.getLocationFirst(), collision.getLocationSecond()));
    }

    public static void play(final Location location, final Element firstElement, final Element secondElement) {
        play(location, firstElement, secondElement, null);
    }

    public static void play(final Location location, final Element firstElement, final Element secondElement, final BendingPlayer airBender) {
        play(location, firstElement, secondElement, CollisionCosmetics.from(airBender), null);
    }

    public static void play(final Location location, final Element firstElement, final Element secondElement, final BendingPlayer airBender, final Vector direction) {
        play(location, firstElement, secondElement, CollisionCosmetics.from(airBender), direction);
    }

    private static void play(final Location location, final Element firstElement, final Element secondElement, final CollisionCosmetics cosmetics, final Vector direction) {
        final Element first = parentElement(firstElement);
        final Element second = parentElement(secondElement);
        if (!isBendingElement(first) || !isBendingElement(second)) {
            return;
        }

        if (location == null || location.getWorld() == null) {
            return;
        }

        if (matches(first, second, Element.WATER, Element.FIRE)) {
            playSteam(location);
            return;
        }
        if (matches(first, second, Element.AIR, Element.FIRE)) {
            playFireGust(location, cosmetics, direction);
            return;
        }
        if (matches(first, second, Element.EARTH, Element.WATER)) {
            playMudImpact(location, cosmetics);
            return;
        }
        if (matches(first, second, Element.AIR, Element.WATER)) {
            playMistBurst(location, cosmetics);
            return;
        }
        if (matches(first, second, Element.EARTH, Element.FIRE)) {
            playScorchedRock(location, cosmetics);
            return;
        }
        if (matches(first, second, Element.AIR, Element.EARTH)) {
            playDustGust(location, cosmetics);
            return;
        }
        if (first == Element.EARTH && second == Element.EARTH) {
            playEarthImpact(location, cosmetics);
            return;
        }
        if (first == Element.FIRE && second == Element.FIRE) {
            playFireImpact(location, cosmetics, direction);
            return;
        }

        playGenericImpact(location, first, cosmetics);
        if (first != second) {
            playGenericImpact(location, second, cosmetics);
        }
    }

    private static Element parentElement(final CoreAbility ability) {
        return parentElement(ability.getElement());
    }

    private static Element parentElement(final Element element) {
        if (element instanceof SubElement) {
            return ((SubElement) element).getParentElement();
        }
        return element;
    }

    private static boolean isBendingElement(final Element element) {
        return element == Element.AIR || element == Element.WATER || element == Element.EARTH || element == Element.FIRE;
    }

    private static boolean matches(final Element first, final Element second, final Element expectedFirst, final Element expectedSecond) {
        return first == expectedFirst && second == expectedSecond || first == expectedSecond && second == expectedFirst;
    }

    private static Location center(final Location first, final Location second) {
        if (first == null) {
            return second == null ? null : second.clone();
        }
        if (second == null || first.getWorld() != second.getWorld()) {
            return first.clone();
        }
        return first.clone().add(second).multiply(0.5);
    }

    private static Vector direction(final Location first, final Location second) {
        if (first == null || second == null || first.getWorld() != second.getWorld()) {
            return null;
        }
        final Vector direction = first.toVector().subtract(second.toVector());
        if (direction.lengthSquared() < 1.0E-6D) {
            return null;
        }
        return direction.normalize();
    }

    private static void playSteam(final Location location) {
        final World world = location.getWorld();
        world.spawnParticle(Particle.CLOUD, location, 18, 0.38D, 0.28D, 0.38D, 0.03D);
        world.spawnParticle(Particle.WHITE_SMOKE, location, 12, 0.32D, 0.25D, 0.32D, 0.015D);
        world.spawnParticle(Particle.SPLASH, location, 4, 0.18D, 0.12D, 0.18D, 0.01D);
        world.spawnParticle(Particle.DUST, location, 6, 0.24D, 0.2D, 0.24D, 0.0D, STEAM_DUST);
        spawnRing(location, Particle.CLOUD, 0.65D, 8, 0.035D);
        spawnBlockBurst(location, Material.WHITE_STAINED_GLASS, 4, 0.34D, 0.14F);
        world.playSound(location, Sound.BLOCK_FIRE_EXTINGUISH, 0.9F, 1.1F);
        world.playSound(location, Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 0.45F, 1.55F);
    }

    private static void playFireGust(final Location location, final CollisionCosmetics cosmetics, final Vector direction) {
        playFireParticles(cosmetics.fireBender, location, 16, 0.32D, 0.22D, 0.32D, 0.03D);
        playAirParticles(cosmetics.airBender, location, 7, 0.28D, 0.2D, 0.28D, 0.025D);
        spawnBlockBurst(location, Material.FIRE, 3, 0.32D, 0.11F);
    }

    private static void playMudImpact(final Location location, final CollisionCosmetics cosmetics) {
        final World world = location.getWorld();
        final Material earthMaterial = cosmetics.earthMaterial(Material.MUD);
        world.spawnParticle(Particle.BLOCK, location, 12, 0.28D, 0.18D, 0.28D, 0.06D, earthMaterial.createBlockData());
        world.spawnParticle(Particle.SPLASH, location, 6, 0.22D, 0.12D, 0.22D, 0.015D);
        world.spawnParticle(Particle.DUST, location, 5, 0.22D, 0.16D, 0.22D, 0.0D, EARTH_DUST);
        spawnBlockBurst(location, earthMaterial, 4, 0.3D, 0.13F);
        world.playSound(location, Sound.BLOCK_MUD_BREAK, 0.85F, 0.95F);
        world.playSound(location, Sound.ENTITY_GENERIC_SPLASH, 0.45F, 1.15F);
    }

    private static void playMistBurst(final Location location, final CollisionCosmetics cosmetics) {
        final World world = location.getWorld();
        playAirParticles(cosmetics.airBender, location, 12, 0.32D, 0.22D, 0.32D, 0.025D);
        world.spawnParticle(Particle.SPLASH, location, 8, 0.28D, 0.12D, 0.28D, 0.012D);
        world.spawnParticle(Particle.DUST, location, 7, 0.3D, 0.2D, 0.3D, 0.0D, WATER_DUST);
        spawnAirRing(cosmetics.airBender, location, 0.68D, 8, 0.03D);
        spawnBlockBurst(location, Material.LIGHT_BLUE_STAINED_GLASS, 3, 0.31D, 0.12F);
        world.playSound(location, Sound.ENTITY_BOAT_PADDLE_WATER, 0.55F, 2F);
    }

    private static void playScorchedRock(final Location location, final CollisionCosmetics cosmetics) {
        final World world = location.getWorld();
        final Material earthMaterial = cosmetics.earthMaterial(Material.DEEPSLATE);
        world.spawnParticle(Particle.LAVA, location, 3, 0.18D, 0.12D, 0.18D, 0.005D);
        playFireParticles(cosmetics.fireBender, location, 10, 0.24D, 0.16D, 0.24D, 0.015D);
        world.spawnParticle(Particle.BLOCK, location, 12, 0.28D, 0.2D, 0.28D, 0.07D, earthMaterial.createBlockData());
        spawnBlockBurst(location, earthMaterial, 4, 0.29D, 0.12F);
        world.playSound(location, Sound.BLOCK_STONE_BREAK, 0.85F, 0.85F);
        world.playSound(location, Sound.BLOCK_FIRE_AMBIENT, 0.55F, 1.3F);
    }

    private static void playDustGust(final Location location, final CollisionCosmetics cosmetics) {
        final World world = location.getWorld();
        final Material earthMaterial = cosmetics.earthMaterial(Material.SAND);
        playAirParticles(cosmetics.airBender, location, 8, 0.24D, 0.16D, 0.24D, 0.025D);
        world.spawnParticle(Particle.BLOCK, location, 14, 0.3D, 0.18D, 0.3D, 0.06D, earthMaterial.createBlockData());
        world.spawnParticle(Particle.DUST, location, 5, 0.26D, 0.16D, 0.26D, 0.0D, AIR_DUST);
        spawnAirRing(cosmetics.airBender, location, 0.62D, 8, 0.025D);
        spawnBlockBurst(location, earthMaterial, 4, 0.28D, 0.12F);
        world.playSound(location, Sound.BLOCK_SAND_BREAK, 0.75F, 1.05F);
        world.playSound(location, Sound.ENTITY_BREEZE_SHOOT, 0.45F, 1.25F);
    }

    private static void playEarthImpact(final Location location, final CollisionCosmetics cosmetics) {
        final World world = location.getWorld();
        final Material earthMaterial = cosmetics.earthMaterial(Material.ROOTED_DIRT);
        world.spawnParticle(Particle.BLOCK, location, 14, 0.24D, 0.16D, 0.24D, 0.07D, earthMaterial.createBlockData());
        world.spawnParticle(Particle.DUST, location, 5, 0.2D, 0.14D, 0.2D, 0.0D, EARTH_DUST);
        spawnBlockBurst(location, earthMaterial, 4, 0.24D, 0.12F);
        world.playSound(location, Sound.BLOCK_ROOTED_DIRT_BREAK, 0.85F, 0.75F);
    }

    private static void playFireImpact(final Location location, final CollisionCosmetics cosmetics, final Vector direction) {
        final World world = location.getWorld();
        world.spawnParticle(Particle.LAVA, location, 3, 0.16D, 0.08D, 0.16D, 0.0D);
        world.spawnParticle(Particle.DUST, location, 6, 0.18D, 0.12D, 0.18D, 0.0D, FIRE_DUST);
        spawnBlockBurst(location, Material.ORANGE_STAINED_GLASS, 4, 0.26D, 0.11F);
        world.playSound(location, Sound.ENTITY_GENERIC_EXTINGUISH_FIRE, 0.85F, 1.5f);
    }

    private static void playGenericImpact(final Location location, final Element element, final CollisionCosmetics cosmetics) {
        final World world = location.getWorld();
        if (element == Element.AIR) {
            playAirParticles(cosmetics.airBender, location, 8, 0.2D, 0.18D, 0.2D, 0.022D);
            world.spawnParticle(Particle.DUST, location, 4, 0.2D, 0.18D, 0.2D, 0.0D, AIR_DUST);
            spawnAirRing(cosmetics.airBender, location, 0.5D, 6, 0.025D);
        } else if (element == Element.WATER) {
            world.spawnParticle(Particle.SPLASH, location, 10, 0.24D, 0.14D, 0.24D, 0.015D);
            world.spawnParticle(Particle.DUST, location, 4, 0.2D, 0.14D, 0.2D, 0.0D, WATER_DUST);
            spawnBlockBurst(location, Material.LIGHT_BLUE_STAINED_GLASS, 2, 0.22D, 0.1F);
        } else if (element == Element.EARTH) {
            final Material earthMaterial = cosmetics.earthMaterial(Material.DIRT);
            world.spawnParticle(Particle.BLOCK, location, 12, 0.24D, 0.18D, 0.24D, 0.07D, earthMaterial.createBlockData());
            spawnBlockBurst(location, earthMaterial, 3, 0.24D, 0.11F);
        } else if (element == Element.FIRE) {
            playFireParticles(cosmetics.fireBender, location, 9, 0.2D, 0.18D, 0.2D, 0.018D);
            world.spawnParticle(Particle.SMOKE, location, 4, 0.16D, 0.14D, 0.16D, 0.01D);
            world.spawnParticle(Particle.DUST, location, 4, 0.18D, 0.16D, 0.18D, 0.0D, FIRE_DUST);
        }
    }

    private static void spawnRing(final Location center, final Particle particle, final double radius, final int points, final double speed) {
        final World world = center.getWorld();
        for (int i = 0; i < points; i++) {
            final double angle = Math.PI * 2.0D * i / points;
            final Location point = center.clone().add(Math.cos(angle) * radius, 0.08D, Math.sin(angle) * radius);
            world.spawnParticle(particle, point, 1, 0.02D, 0.02D, 0.02D, speed);
        }
    }

    private static void spawnAirRing(final BendingPlayer airBender, final Location center, final double radius, final int points, final double speed) {
        for (int i = 0; i < points; i++) {
            final double angle = Math.PI * 2.0D * i / points;
            final Location point = center.clone().add(Math.cos(angle) * radius, 0.08D, Math.sin(angle) * radius);
            playAirParticles(airBender, point, 1, 0.01D, 0.01D, 0.01D, speed);
        }
    }

    private static void playAirParticles(final BendingPlayer airBender, final Location location, final int amount, final double offsetX, final double offsetY, final double offsetZ, final double speed) {
        if (airBender != null) {
            AirAbility.playAirbendingParticles(airBender, location, amount, offsetX, offsetY, offsetZ, speed);
            return;
        }
        AirAbility.getAirbendingParticles().display(location, amount, offsetX, offsetY, offsetZ, speed);
    }

    private static void playFireParticles(final BendingPlayer fireBender, final Location location, final int amount, final double offsetX, final double offsetY, final double offsetZ, final double speed) {
        if (fireBender != null) {
            FireAbility.playFirebendingParticles(fireBender, location, amount, offsetX, offsetY, offsetZ, speed);
            return;
        }
        ParticleEffect.FLAME.display(location, amount, offsetX, offsetY, offsetZ, speed);
    }

    private static void spawnBlockBurst(final Location center, final Material material, final int count, final double radius, final float scale) {
        if (ProjectKorra.plugin == null || !Platform.scheduler().isPrimaryThread()) {
            return;
        }

        final BlockData blockData = material.createBlockData();
        for (int i = 0; i < count; i++) {
            final double angle = Math.PI * 2.0D * (i + RANDOM.nextDouble() * 0.35D) / count;
            final double y = 0.06D + RANDOM.nextDouble() * 0.18D;
            final Location start = center.clone().add(Math.cos(angle) * 0.12D, y, Math.sin(angle) * 0.12D);
            final Vector velocity = new Vector(Math.cos(angle) * radius * 0.34D, 0.12D + RANDOM.nextDouble() * 0.08D, Math.sin(angle) * radius * 0.34D);
            final BlockDisplay display = center.getWorld().spawn(start, BlockDisplay.class);
            display.setBlock(blockData);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setShadowRadius(0.0F);
            display.setShadowStrength(0.0F);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(1);
            display.setTeleportDuration(1);
            display.setTransformation(new Transformation(
                    new Vector3f(-scale / 2.0F, -scale / 2.0F, -scale / 2.0F),
                    new AxisAngle4f(randomRotation(), randomAxis(), randomAxis(), randomAxis()),
                    new Vector3f(scale, scale, scale),
                    new AxisAngle4f()));
            new BukkitRunnable() {
                private final Location current = start.clone();
                private final Vector motion = velocity.clone();
                private int ticks;

                @Override
                public void run() {
                    if (!display.isValid()) {
                        this.cancel();
                        return;
                    }
                    this.current.add(this.motion);
                    this.motion.setY(this.motion.getY() - 0.045D);
                    display.teleport(this.current);
                    if (++this.ticks >= 9) {
                        display.remove();
                        this.cancel();
                    }
                }
            }.runTaskTimer(ProjectKorra.plugin, 1L, 1L);
        }
    }

    private static float randomRotation() {
        return (float) (RANDOM.nextDouble() * Math.PI * 2.0D);
    }

    private static float randomAxis() {
        return RANDOM.nextBoolean() ? 1.0F : -1.0F;
    }

    private static final class CollisionCosmetics {
        private final BendingPlayer airBender;
        private final BendingPlayer fireBender;
        private final BendingPlayer earthBender;

        private CollisionCosmetics(final BendingPlayer airBender, final BendingPlayer fireBender, final BendingPlayer earthBender) {
            this.airBender = airBender;
            this.fireBender = fireBender;
            this.earthBender = earthBender;
        }

        private static CollisionCosmetics from(final BendingPlayer bender) {
            return new CollisionCosmetics(bender, bender, bender);
        }

        private static CollisionCosmetics from(final Collision collision) {
            BendingPlayer airBender = null;
            BendingPlayer fireBender = null;
            BendingPlayer earthBender = null;
            final CoreAbility first = collision.getAbilityFirst();
            final CoreAbility second = collision.getAbilitySecond();

            if (parentElement(first) == Element.AIR) {
                airBender = first.getBendingPlayer();
            } else if (parentElement(first) == Element.FIRE) {
                fireBender = first.getBendingPlayer();
            } else if (parentElement(first) == Element.EARTH) {
                earthBender = first.getBendingPlayer();
            }

            if (parentElement(second) == Element.AIR && airBender == null) {
                airBender = second.getBendingPlayer();
            } else if (parentElement(second) == Element.FIRE && fireBender == null) {
                fireBender = second.getBendingPlayer();
            } else if (parentElement(second) == Element.EARTH && earthBender == null) {
                earthBender = second.getBendingPlayer();
            }

            return new CollisionCosmetics(airBender, fireBender, earthBender);
        }

        private Material earthMaterial(final Material fallback) {
            if (this.earthBender == null) {
                return fallback;
            }
            final EarthCosmetic cosmetic = this.earthBender.getEarthCosmetic();
            if (cosmetic == null || cosmetic.getMaterial() == null) {
                return fallback;
            }
            return cosmetic.getMaterial();
        }
    }
}
