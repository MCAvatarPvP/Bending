package com.projectkorra.projectkorra.platform.bukkit.model;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.platform.model.PKBlock;
import com.projectkorra.projectkorra.platform.model.PKLocation;
import com.projectkorra.projectkorra.platform.model.PKWorld;
import com.projectkorra.projectkorra.prediction.PaperPredictionServer;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Locale;

public final class BukkitWorld implements PKWorld {
    private final World world;

    public BukkitWorld(final World world) {
        this.world = world;
    }

    private static Particle particle(final String raw) {
        if (raw == null || raw.isBlank()) return Particle.CLOUD;
        final String key = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return Particle.valueOf(switch (key) {
                case "BLOCK_CRACK", "BLOCK_DUST" -> "BLOCK";
                case "CRIT_MAGIC", "MAGIC_CRIT" -> "ENCHANTED_HIT";
                case "DRIP_LAVA" -> "DRIPPING_LAVA";
                case "DRIP_WATER" -> "DRIPPING_WATER";
                case "ENCHANTMENT_TABLE" -> "ENCHANT";
                case "EXPLOSION_HUGE", "HUGE_EXPLOSION" -> "EXPLOSION_EMITTER";
                case "EXPLOSION_LARGE", "LARGE_EXPLODE" -> "EXPLOSION";
                case "EXPLOSION_NORMAL", "EXPLODE" -> "POOF";
                case "FIREWORKS_SPARK" -> "FIREWORK";
                case "REDSTONE", "RED_DUST" -> "DUST";
                case "SLIME" -> "ITEM_SLIME";
                case "SMOKE_NORMAL", "SMOKE" -> "SMOKE";
                case "SMOKE_LARGE", "LARGE_SMOKE" -> "LARGE_SMOKE";
                case "SNOW_SHOVEL" -> "BLOCK";
                case "SNOWBALL", "SNOWBALL_PROOF" -> "ITEM_SNOWBALL";
                case "SPELL" -> "EFFECT";
                case "SPELL_INSTANT", "INSTANT_SPELL" -> "INSTANT_EFFECT";
                case "SPELL_MOB", "MOB_SPELL", "SPELL_MOB_AMBIENT", "MOB_SPELL_AMBIENT" -> "ENTITY_EFFECT";
                case "SPELL_WITCH", "WITCH_SPELL" -> "WITCH";
                case "TOTEM" -> "TOTEM_OF_UNDYING";
                case "VILLAGER_ANGRY", "ANGRY_VILLAGER" -> "ANGRY_VILLAGER";
                case "VILLAGER_HAPPY", "HAPPY_VILLAGER" -> "HAPPY_VILLAGER";
                case "WATER_BUBBLE", "BUBBLE" -> "BUBBLE";
                case "WATER_DROP" -> "RAIN";
                case "WATER_SPLASH", "SPLASH" -> "SPLASH";
                case "WATER_WAKE", "WAKE" -> "FISHING";
                case "ITEM_CRACK" -> "ITEM";
                default -> key;
            });
        } catch (IllegalArgumentException ignored) {
            return Particle.CLOUD;
        }
    }

    @Override
    public Object handle() {
        return this.world;
    }

    @Override
    public String name() {
        return this.world.getName();
    }

    @Override
    public PKBlock blockAt(final int x, final int y, final int z) {
        return new BukkitBlock(this.world.getBlockAt(x, y, z));
    }

    @Override
    public void playSound(final PKLocation location, final String sound, final float volume, final float pitch) {
        final String selected = GeneralMethods.firstLegacyConfigAlternative(sound);
        if (selected == null || selected.isBlank()) return;
        final Location nativeLocation = location.handle(Location.class);
        final Player excluded = PaperPredictionServer.predictedEffectOwner();
        if (selected.contains(":") || selected.contains(".")) {
            if (excluded == null || excluded.getWorld() != this.world) {
                this.world.playSound(nativeLocation, selected, volume, pitch);
                return;
            }
            this.world.getPlayers().stream().filter(player -> player != excluded)
                    .forEach(player -> player.playSound(nativeLocation, selected, volume, pitch));
            return;
        }
        final Sound nativeSound = Sound.valueOf(selected);
        if (excluded == null || excluded.getWorld() != this.world) {
            this.world.playSound(nativeLocation, nativeSound, volume, pitch);
            return;
        }
        this.world.getPlayers().stream().filter(player -> player != excluded)
                .forEach(player -> player.playSound(nativeLocation, nativeSound, volume, pitch));
    }

    @Override
    public void spawnParticle(final String particle, final PKLocation location, final int count, final double offsetX, final double offsetY, final double offsetZ, final double extra) {
        final Particle nativeParticle = particle(particle);
        final Location nativeLocation = location.handle(Location.class);
        final Player excluded = PaperPredictionServer.predictedEffectOwner();
        if (excluded == null || excluded.getWorld() != this.world)
            this.world.spawnParticle(nativeParticle, nativeLocation, count, offsetX, offsetY, offsetZ, extra);
        else
            this.world.getPlayers().stream().filter(player -> player != excluded).forEach(player -> player.spawnParticle(nativeParticle, nativeLocation, count, offsetX, offsetY, offsetZ, extra));
    }
}
