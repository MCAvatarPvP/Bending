package com.projectkorra.projectkorra.waterbending.passive;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.ability.PassiveAbility;
import com.projectkorra.projectkorra.ability.WaterAbility;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.TempBlock;

public class HydroSink extends WaterAbility implements PassiveAbility {

    private double decayMinimum;
    private long minimumSurgeWaveTime;
    private double maxSurgeStamina;

    public HydroSink(final Player player) {
        super(player);

        this.decayMinimum = getConfig().getDouble("Abilities.Water.Surge.DecayMinimum");
        this.minimumSurgeWaveTime = getConfig().getLong("Abilities.Water.Surge.MinimumSurgeWaveTime");
        this.minimumSurgeWaveTime = getConfig().getLong("Abilities.Water.Surge.MinimumSurgeWaveTime");
        this.maxSurgeStamina = getConfig().getDouble("Abilities.Water.Surge.MaxSurgeStamina", 1.2);
    }

    public static double getRegenFactor(BendingPlayer bPlayer) {
        return ConfigManager.getConfig(bPlayer).getDouble("Abilities.Water.Passive.RegenFactor");
    }

    public static boolean applyNoFall(final Player player) {
        if (Commands.isToggledForAll && ConfigManager.defaultConfig.get().getBoolean("Properties.TogglePassivesWithAllBending")) {
            return false;
        }

        final Block block = player.getLocation().getBlock();
        final Block fallBlock = block.getRelative(BlockFace.DOWN);
        if (TempBlock.isTempBlock(fallBlock) && WaterAbility.isIce(fallBlock)) {
            return true;
        } else if (TempBlock.isTempBlock(block) && (block.getType().equals(Material.SNOW))) {
            return true;
        } else if (WaterAbility.isWaterbendable(player, null, block) && !ElementalAbility.isPlant(block)) {
            return true;
        } else if (ElementalAbility.isAir(fallBlock.getType())) {
            return true;
        } else if ((WaterAbility.isWaterbendable(player, null, fallBlock) && !ElementalAbility.isPlant(fallBlock)) || fallBlock.getType() == Material.SNOW_BLOCK) {
            return true;
        }

        return false;
    }

    @Override
    public void progress() {
        if (System.currentTimeMillis() - bPlayer.getLastSurgeWaveTime() > minimumSurgeWaveTime) {
            bPlayer.resetSurgeWaveDecay(maxSurgeStamina);
        }

        final String boundAbility = bPlayer.getBoundAbilityName();
        if ("AirBlast".equalsIgnoreCase(boundAbility) || "AirSuction".equalsIgnoreCase(boundAbility)) {
            return;
        }

        float decay = (float) bPlayer.getSurgeWaveDecay();
        float normalized = (decay - 0.4f) / (1.0f - (float) decayMinimum);
        player.setExp(Math.max(0f, Math.min(1f, normalized)));
    }

    @Override
    public boolean isSneakAbility() {
        return false;
    }

    @Override
    public boolean isHarmlessAbility() {
        return true;
    }

    @Override
    public long getCooldown() {
        return 0;
    }

    @Override
    public String getName() {
        return "HydroSink";
    }

    @Override
    public Location getLocation() {
        return this.player.getLocation();
    }

    @Override
    public boolean isInstantiable() {
        return true;
    }

    @Override
    public boolean isProgressable() {
        return true;
    }
}
