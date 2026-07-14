package hackathonpack.earth;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.earthbending.RaiseEarth;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.ClickType;
import hackathonpack.UtilityMethods;

import java.util.ArrayList;
import java.util.Arrays;

public class EarthRift extends EarthAbility implements AddonAbility, ComboAbility {
    private long cooldown;
    private int amount;
    private int heightRepeat;
    private double range;
    private int currentHeight;
    private Block headBlock;
    private Vector blockDirection;
    private int amountCount;

    public EarthRift(final Player player) {
        super(player);
        if (this.bPlayer.isOnCooldown(this) || !this.bPlayer.canBendIgnoreBinds(this) || !ConfigManager.getConfig().getBoolean(path("Enable")))
            return;
        if (hasAbility(player, RaiseEarth.class)) {
            getAbility(player, RaiseEarth.class).getBlock().setType(Material.AIR);
            getAbility(player, RaiseEarth.class).remove();
        }
        setField();
        if (findFirstBlock()) {
            this.bPlayer.addCooldown(this);
            start();
        }
    }

    private static String path(final String node) {
        return "ExtraAbilities.Hiro3.HackathonPack.Earth.EarthRift." + node;
    }

    private void setField() {
        this.cooldown = ConfigManager.getConfig().getLong(path("Cooldown"));
        this.amount = ConfigManager.getConfig().getInt(path("Amount"));
        this.heightRepeat = Math.max(1, ConfigManager.getConfig().getInt(path("HeightRepeat")));
        this.currentHeight = 1;
        this.range = ConfigManager.getConfig().getDouble(path("SelectRange"));
        this.amountCount = 0;
        this.blockDirection = new Vector();
    }

    @Override
    public void progress() {
        if (GeneralMethods.isRegionProtectedFromBuild(this, this.player.getLocation()) || !this.player.isSneaking() || this.amountCount >= this.amount) {
            remove();
            return;
        }
        this.currentHeight = this.amountCount / this.heightRepeat + 1;
        final Block targetBlock = getTargetEarthBlock(this.player, this.range);
        if ((targetBlock != null && (targetBlock.getX() != this.headBlock.getX() || targetBlock.getZ() != this.headBlock.getZ())) || targetBlock == null) {
            this.blockDirection = targetBlock != null
                    ? targetBlock.getLocation().toVector().subtract(this.headBlock.getLocation().toVector()).normalize()
                    : getTargetLocation(this.player, 25).subtract(this.headBlock.getLocation()).toVector().normalize();
            this.blockDirection.setY(0);
            if (this.blockDirection.length() < 0.1) this.blockDirection.setX(1);
            Location tmpLoc = this.headBlock.getLocation().clone();
            do {
                tmpLoc.add(this.blockDirection);
            } while (tmpLoc.getBlock().equals(this.headBlock));
            int steps = 0;
            final Location safeLoc = tmpLoc.clone();
            while (!GeneralMethods.isSolid(tmpLoc.getBlock()) && steps < 10) {
                tmpLoc.setY(tmpLoc.getY() - 1);
                steps++;
            }
            if (steps >= 10) tmpLoc = safeLoc.clone();
            new RaiseEarth(this.player, tmpLoc.getBlock().getLocation(), this.currentHeight);
            this.amountCount++;
            this.headBlock = tmpLoc.getBlock();
        }
    }

    private boolean findFirstBlock() {
        this.headBlock = getTargetEarthBlock(this.player, this.range);
        if (this.headBlock == null) return false;
        new RaiseEarth(this.player, this.headBlock.getLocation(), this.currentHeight);
        this.amountCount++;
        return true;
    }

    private Block getTargetEarthBlock(final Player player, final double range) {
        final Vector direction = player.getLocation().getDirection().clone().multiply(0.1);
        final Location loc = player.getEyeLocation().clone();
        final Location startLoc = loc.clone();
        do {
            loc.add(direction);
        } while (startLoc.distance(loc) < range && !GeneralMethods.isSolid(loc.getBlock()));
        return startLoc.distance(loc) < range && isEarthbendable(loc.getBlock()) ? loc.getBlock() : null;
    }

    private Location getTargetLocation(final Player player, final double range) {
        final Vector direction = player.getLocation().getDirection().clone().multiply(0.1);
        final Location loc = player.getEyeLocation().clone();
        final Location startLoc = loc.clone();
        do {
            loc.add(direction);
        } while (startLoc.distance(loc) < range && !GeneralMethods.isSolid(loc.getBlock()));
        return loc;
    }

    @Override
    public long getCooldown() {
        return this.cooldown;
    }

    @Override
    public Location getLocation() {
        return null;
    }

    @Override
    public String getName() {
        return "EarthRift";
    }

    @Override
    public String getDescription() {
        return "Build a wall.";
    }

    @Override
    public String getInstructions() {
        return "Shockwave (Tap Sneak) -> Shockwave (Hold Sneak) -> RaiseEarth (Left Click)";
    }

    @Override
    public String getAuthor() {
        return "Hiro3";
    }

    @Override
    public String getVersion() {
        return UtilityMethods.getVersion();
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
    public Object createNewComboInstance(final Player player) {
        return new EarthRift(player);
    }

    @Override
    public ArrayList<AbilityInformation> getCombination() {
        return UtilityMethods.getConfiguredCombination(path("Enable"), path("Combination"), new ArrayList<>(Arrays.asList(new AbilityInformation("Shockwave", ClickType.SHIFT_DOWN), new AbilityInformation("Shockwave", ClickType.SHIFT_UP), new AbilityInformation("Shockwave", ClickType.SHIFT_DOWN), new AbilityInformation("RaiseEarth", ClickType.LEFT_CLICK))));
    }

    @Override
    public void load() {
        ConfigManager.getConfig().addDefault(path("Enable"), true);
        ConfigManager.getConfig().addDefault(path("Cooldown"), 5000);
        ConfigManager.getConfig().addDefault(path("Amount"), 15);
        ConfigManager.getConfig().addDefault(path("HeightRepeat"), 2);
        ConfigManager.getConfig().addDefault(path("SelectRange"), 50);
        ConfigManager.getConfig().addDefault(path("Combination"), Arrays.asList("Shockwave: SHIFT_DOWN", "Shockwave: SHIFT_UP", "Shockwave: SHIFT_DOWN", "RaiseEarth: LEFT_CLICK"));
        ConfigManager.defaultConfig.save();
        ProjectKorra.log.info("Succesfully enabled " + getName() + " by " + getAuthor());
    }

    @Override
    public void stop() {
        ProjectKorra.log.info("Successfully disabled " + getName() + " by " + getAuthor());
        super.remove();
    }
}
