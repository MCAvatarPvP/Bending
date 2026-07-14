package com.jedk1.jedcore.ability.earthbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.MetalAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.Item;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.projectkorra.projectkorra.platform.mc.Material.*;

public class MagnetShield extends MetalAbility implements AddonAbility {

    private final static Material[] METAL = {RAW_IRON, IRON_INGOT, IRON_HELMET, IRON_CHESTPLATE, IRON_LEGGINGS, IRON_BOOTS,
            IRON_BLOCK, RAW_IRON_BLOCK, IRON_AXE, IRON_PICKAXE, IRON_SWORD, IRON_HOE, IRON_SHOVEL, IRON_DOOR, IRON_NUGGET, IRON_BARS,
            IRON_HORSE_ARMOR, IRON_TRAPDOOR, HEAVY_WEIGHTED_PRESSURE_PLATE, RAW_GOLD, GOLD_INGOT, GOLDEN_HELMET, GOLDEN_CHESTPLATE,
            GOLDEN_LEGGINGS, GOLDEN_BOOTS, GOLD_BLOCK, GOLD_NUGGET, RAW_GOLD_BLOCK, GOLDEN_AXE, GOLDEN_PICKAXE, GOLDEN_SHOVEL, GOLDEN_SWORD,
            GOLDEN_HOE, GOLDEN_HORSE_ARMOR, LIGHT_WEIGHTED_PRESSURE_PLATE, CLOCK, COMPASS};

    private static final List<Material> METAL_LIST = new ArrayList<>(Arrays.asList(METAL));

    public MagnetShield(Player player) {
        super(player);

        if (!bPlayer.canBendIgnoreCooldowns(this) || !bPlayer.canMetalbend()) {
            return;
        }

        if (hasAbility(player, MagnetShield.class)) {
            getAbility(player, MagnetShield.class).remove();
            return;
        }

        start();
    }

    public static List<Material> getMetal() {
        return METAL_LIST;
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }
        if (!bPlayer.canBendIgnoreBindsCooldowns(this)) {
            remove();
            return;
        }
        if (!player.isSneaking()) {
            remove();
            return;
        }

        for (Entity e : GeneralMethods.getEntitiesAroundPoint(player.getLocation(), 4)) {
            if (e instanceof Item) {
                Item i = (Item) e;

                if (METAL_LIST.contains(i.getItemStack().getType())) {
                    Vector direction = GeneralMethods.getDirection(player.getLocation(), i.getLocation()).multiply(0.1);
                    i.setVelocity(direction);
                }
            } else if (e instanceof FallingBlock) {
                FallingBlock fb = (FallingBlock) e;

                if (METAL_LIST.contains(fb.getBlockData().getMaterial())) {
                    Vector direction = GeneralMethods.getDirection(player.getLocation(), fb.getLocation()).multiply(0.1);
                    fb.setVelocity(direction);
                    fb.setDropItem(false);
                }
            }
        }
    }

    @Override
    public long getCooldown() {
        return 0;
    }

    @Override
    public Location getLocation() {
        return player.getLocation();
    }

    @Override
    public String getName() {
        return "MagnetShield";
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public boolean isSneakAbility() {
        return true;
    }

    @Override
    public String getAuthor() {
        return JedCore.dev;
    }

    @Override
    public String getVersion() {
        return JedCore.version;
    }

    @Override
    public String getDescription() {
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Earth.MagnetShield.Description");
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Earth.MagnetShield.Enabled");
    }
}
