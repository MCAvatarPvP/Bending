package com.jedk1.jedcore.ability.waterbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.WaterAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Item;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.BlockSource;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.ParticleEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class WakeFishing extends WaterAbility implements AddonAbility {

    private final static Material[] FISH_TYPES = {
            Material.COD, Material.PUFFERFISH, Material.TROPICAL_FISH, Material.SALMON
    };
    Random rand = new Random();
    private Block focusedBlock;
    private Location location;
    private int point;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.DURATION)
    private long duration;
    @Attribute(Attribute.RANGE)
    private long range;

    public WakeFishing(Player player) {
        super(player);
        if (!bPlayer.canBend(this)) {
            return;
        }

        setFields();

        if (prepare())
            start();
    }

    public void setFields() {
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Water.WakeFishing.Cooldown");
        duration = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Water.WakeFishing.Duration");
        range = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Water.WakeFishing.Range");

        applyModifiers();
    }

    private void applyModifiers() {
        if (isNight(player.getWorld())) {
            cooldown -= ((long) getNightFactor(cooldown) - cooldown);
            range = (long) getNightFactor(range);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean prepare() {
        Block block = BlockSource.getWaterSourceBlock(player, range, ClickType.SHIFT_DOWN, true, false, false);
        if (isWater(block) && block.getData() == 0) {
            focusedBlock = block;
            location = focusedBlock.getLocation();
            return true;
        }
        return false;
    }

    private boolean isFocused() {
        Block block = BlockSource.getWaterSourceBlock(player, range, ClickType.SHIFT_DOWN, true, false, false);
        return block != null && block.equals(focusedBlock);
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline() || !player.isSneaking()) {
            remove();
            return;
        }
        if (!bPlayer.canBendIgnoreCooldowns(this) || !isFocused()) {
            bPlayer.addCooldown(this);
            remove();
            return;
        }
        if (System.currentTimeMillis() > getStartTime() + duration) {
            bPlayer.addCooldown(this);
            remove();
            return;
        }
        displayParticles();
        spawnFishRandom();
    }

    private void displayParticles() {
        point++;
        if (point == 32)
            point = 0;
        for (int i = 0; i < 4; i++) {
            ParticleEffect.WATER_SPLASH.display(getCirclePoints(focusedBlock.getLocation().clone().add(0.5, 0, 0.5), 32, (i * 90), 1).get(point), 3, 0, 0, 0, 0.05);
            ParticleEffect.WATER_WAKE.display(getCirclePoints(focusedBlock.getLocation().clone().add(0.5, -0.6, 0.5), 32, (i * 90), 1).get(point), 1, 0, 0, 0, 0.02);
        }

        ParticleEffect.SMOKE_NORMAL.display(focusedBlock.getLocation().clone().add(.5, .5, .5), 2, 0, 0, 0, 0.001);
    }

    private void spawnFishRandom() {
        if (rand.nextInt(50) == 0) {
            Material fishType = FISH_TYPES[rand.nextInt(FISH_TYPES.length)];
            ItemStack fish = new ItemStack(fishType, 1);

            Item item = player.getWorld().dropItemNaturally(focusedBlock.getLocation().clone().add(.5, 1.5, .5), fish);
            Vector v = player.getEyeLocation().toVector().subtract(focusedBlock.getLocation().clone().add(.5, 1.5, .5).toVector());
            item.setVelocity(v.multiply(.15));
        }
    }

    private List<Location> getCirclePoints(Location location, int points, int startAngle, double size) {
        List<Location> locations = new ArrayList<Location>();
        for (int i = 0; i < 360; i += 360 / points) {
            double angle = (i * Math.PI / 180);
            double x = size * Math.cos(angle + startAngle);
            double z = size * Math.sin(angle + startAngle);
            Location loc = location.clone();
            loc.add(x, 1, z);
            locations.add(loc);
        }
        return locations;
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    public void setCooldown(long cooldown) {
        this.cooldown = cooldown;
    }

    @Override
    public Location getLocation() {
        return location;
    }

    public void setLocation(Location location) {
        this.location = location;
    }

    @Override
    public String getName() {
        return "WakeFishing";
    }

    @Override
    public boolean isHarmlessAbility() {
        return true;
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
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Water.WakeFishing.Description");
    }

    public Block getFocusedBlock() {
        return focusedBlock;
    }

    public void setFocusedBlock(Block focusedBlock) {
        this.focusedBlock = focusedBlock;
    }

    public int getPoint() {
        return point;
    }

    public void setPoint(int point) {
        this.point = point;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public long getRange() {
        return range;
    }

    public void setRange(long range) {
        this.range = range;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Water.WakeFishing.Enabled");
    }
}
