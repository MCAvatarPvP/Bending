package com.jedk1.jedcore.ability.earthbending.combo;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.util.RegenTempBlock;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.ability.util.ComboUtil;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.TempBlock;

import java.util.*;

public class Crevice extends EarthAbility implements AddonAbility, ComboAbility {

    private final List<List<TempBlock>> columns = new ArrayList<>();
    private final Random rand = new Random();
    @Attribute(Attribute.RANGE)
    private double range;
    private long regenDelay;
    @Attribute("Depth")
    private int randomDepth;
    private int avatarDepth;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private boolean controllable;
    private boolean verticalRevert;
    private long perBlockDelay;
    private Location origin;
    private Location location;
    private Vector direction;
    private double travelled;
    private boolean skip;

    public Crevice(Player player) {
        super(player);
        if (!bPlayer.canBendIgnoreBinds(this)) {
            return;
        }

        setFields();
        createInstance();
    }

    public static void closeCrevice(Player player) {
        Block target = player.getTargetBlock(null, 10);
        for (Block near : GeneralMethods.getBlocksAroundPoint(target.getLocation(), 2)) {
            for (Crevice c : getAbilities(Crevice.class)) {
                for (List<TempBlock> tbs : c.columns) {
                    for (TempBlock tb : tbs) {
                        if (near.getLocation().equals(tb.getLocation())) {
                            if (c.verticalRevert) c.prepareRevertVertical();
                            else c.prepareRevert();
                            c.remove();
                            return;
                        }
                    }
                }
            }
        }
    }

    public void setFields() {
        range = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Earth.EarthCombo.Crevice.Range");
        regenDelay = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Earth.EarthCombo.Crevice.RevertDelay");
        randomDepth = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Earth.EarthCombo.Crevice.Depth");
        avatarDepth = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Earth.EarthCombo.Crevice.AvatarStateDepth");
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Earth.EarthCombo.Crevice.Cooldown");
        controllable = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Earth.EarthCombo.Crevice.Controllable");
        verticalRevert = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Earth.EarthCombo.Crevice.VerticalRevert");
        perBlockDelay = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Earth.EarthCombo.Crevice.RevertPerBlockDelay");
    }

    private void createInstance() {
        origin = player.getTargetBlock(null, 6).getLocation();
        if (isEarthbendable(origin.getBlock())) {
            Location tempLoc = player.getLocation().clone();
            tempLoc.setPitch(0);
            direction = tempLoc.getDirection().clone();
            origin.setDirection(tempLoc.getDirection());
            location = origin.clone();
            if (bPlayer.isAvatarState()) {
                randomDepth = avatarDepth;
            }

            start();
            if (!isRemoved()) {
                bPlayer.addCooldown(this);
            }
        }
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            if (verticalRevert) prepareRevertVertical();
            else prepareRevert();
            remove();
            return;
        }
        if (travelled >= range || skip) {
            if (System.currentTimeMillis() > getStartTime() + regenDelay) {
                if (verticalRevert) prepareRevertVertical();
                else prepareRevert();
                remove();
                return;
            }
            return;
        }
        advanceCrevice();
    }

    @Override
    public void remove() {
        if (verticalRevert) prepareRevertVertical();
        else prepareRevert();
        super.remove();
    }

    private void advanceCrevice() {
        if (!controllable) {
            switch (rand.nextInt(2)) {
                case 0:
                    if (location.getYaw() <= origin.getYaw()) {
                        location.setYaw(location.getYaw() + 40);
                        direction = location.getDirection().clone();
                    }
                    break;
                case 1:
                    if (location.getYaw() >= origin.getYaw()) {
                        location.setYaw(location.getYaw() - 40);
                        direction = location.getDirection().clone();
                    }
                    break;
                default:
                    direction = location.getDirection().clone();
                    break;
            }
        } else {
            location.setDirection(player.getLocation().getDirection());
            location.setPitch(0);
            direction = location.getDirection().clone();
        }

        Location tempLoc = location.clone();
        location = location.add(direction.multiply(1));
        playEarthbendingSound(tempLoc);
        location.getWorld().playSound(location, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, (float) 0.5, (float) 0.5);
        if (skip) {
            return;
        }

        travelled++;
        if (travelled >= range)
            return;

        if (RegionProtection.isRegionProtected(player, location, "RaiseEarth")) {
            return;
        }

        if (!isTransparent(location.getBlock().getRelative(BlockFace.UP))) {
            location.add(0, 1, 0);
            if (!isTransparent(location.getBlock().getRelative(BlockFace.UP)) || !isEarthbendable(location.getBlock())) {
                skip = true;
                return;
            }
        } else if (isTransparent(location.getBlock()) || !isEarthbendable(location.getBlock())) {
            location.subtract(0, 1, 0);
            if (isTransparent(location.getBlock()) || !isEarthbendable(location.getBlock())) {
                skip = true;
                return;
            }
        }

        removePillar(tempLoc, randInt(randomDepth + 1 - 2, randomDepth + 1 + 2));
        removePillar(GeneralMethods.getRightSide(tempLoc, 1), randInt(randomDepth - 1, randomDepth + 1));
        removePillar(GeneralMethods.getLeftSide(tempLoc, 1), randInt(randomDepth - 1, randomDepth + 1));
    }

    private int randInt(int min, int max) {
        return rand.nextInt(max - min) + min;
    }

    private void removePillar(Location location, int depth) {
        List<TempBlock> blocks = new ArrayList<>();
        Location tempLoc = location.clone().getBlock().getLocation();
        tempLoc.add(0, 1, 0);
        for (int i = 0; i < depth + 1; i++) {
            if (tempLoc.getY() < Objects.requireNonNull(tempLoc.getWorld()).getMinHeight() || tempLoc.getY() > tempLoc.getWorld().getMaxHeight()) {
                break;
            }
            if (RegionProtection.isRegionProtected(player, tempLoc, this)) {
                continue;
            }
            if (i == 0 && !isTransparent(tempLoc.getBlock())) {
                continue;
            }
            if (i > 0 && !isEarthbendable(tempLoc.getBlock())) {
                continue;
            }

            for (Entity entity : GeneralMethods.getEntitiesAroundPoint(tempLoc, 1)) {
                entity.setVelocity(new Vector(0, -0.75, 0));
            }

            blocks.add(new TempBlock(tempLoc.getBlock(), Material.AIR.createBlockData()));
            tempLoc.subtract(0, 1, 0);
        }

        Collections.reverse(blocks);

        columns.add(blocks);
    }

    private void prepareRevert() {
        for (int i = 0; i < columns.size(); ++i) {
            List<TempBlock> tbs = columns.get(i);
            for (int t = tbs.size() - 1; t > 0; --t) {
                TempBlock tb = tbs.get(t);
                tb.revertBlock();
                for (Entity entity : GeneralMethods.getEntitiesAroundPoint(tb.getLocation(), 1.2)) {
                    entity.setVelocity(new Vector(0, 0.7, 0));
                }
                new RegenTempBlock(tb.getBlock(), Material.AIR, Material.AIR.createBlockData(), i * perBlockDelay);
            }
        }
        columns.clear();
    }

    private void prepareRevertVertical() {
        for (int y = 0; y < randomDepth + 3; y++) {
            for (int i = 0; i < columns.size(); ++i) {
                List<TempBlock> tbs = columns.get(i);
                if (tbs.size() == 0) continue;
                TempBlock tb = tbs.get(0);
                tb.revertBlock();
                for (Entity entity : GeneralMethods.getEntitiesAroundPoint(tb.getLocation(), 1.2)) {
                    entity.setVelocity(new Vector(0, 0.7, 0));
                }
                new RegenTempBlock(tb.getBlock(), Material.AIR, Material.AIR.createBlockData(), y * perBlockDelay);
                tbs.remove(tb);
            }
        }
        columns.clear();
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public Location getLocation() {
        return location;
    }

    @Override
    public String getName() {
        return "Crevice";
    }

    @Override
    public boolean isHiddenAbility() {
        return false;
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public boolean isSneakAbility() {
        return false;
    }

    @Override
    public Object createNewComboInstance(Player player) {
        return new Crevice(player);
    }

    @Override
    public ArrayList<AbilityInformation> getCombination() {
        return ComboUtil.generateCombinationFromList(this, JedCoreConfig.getConfig(bPlayer).getStringList("Abilities.Earth.EarthCombo.Crevice.Combination"));
    }

    @Override
    public String getInstructions() {
        return JedCoreConfig.getConfig(bPlayer).getString("Abilities.Earth.EarthCombo.Crevice.Instructions");
    }

    @Override
    public String getDescription() {
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Earth.EarthCombo.Crevice.Description");
    }

    @Override
    public String getAuthor() {
        return JedCore.dev;
    }

    @Override
    public String getVersion() {
        return JedCore.version;
    }

    public double getRange() {
        return range;
    }

    public void setRange(double range) {
        this.range = range;
    }

    public long getRegenDelay() {
        return regenDelay;
    }

    public void setRegenDelay(long regenDelay) {
        this.regenDelay = regenDelay;
    }

    public int getDepth() {
        return randomDepth;
    }

    public void setDepth(int depth) {
        this.randomDepth = depth;
    }

    public int getAvatarDepth() {
        return avatarDepth;
    }

    public void setAvatarDepth(int avatarDepth) {
        this.avatarDepth = avatarDepth;
    }

    public Location getOrigin() {
        return origin;
    }

    public void setOrigin(Location origin) {
        this.origin = origin;
    }

    public Vector getDirection() {
        return direction;
    }

    public void setDirection(Vector direction) {
        this.direction = direction;
    }

    public double getDistanceTravelled() {
        return travelled;
    }

    public void setDistanceTravelled(double travelled) {
        this.travelled = travelled;
    }

    public List<List<TempBlock>> getColumns() {
        return columns;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Earth.EarthCombo.Crevice.Enabled");
    }
}
