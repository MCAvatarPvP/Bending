package me.hiro;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.earthbending.RaiseEarth;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.ClickType;

import java.util.ArrayList;
import java.util.Arrays;

public class Toss extends EarthAbility implements AddonAbility, ComboAbility {
    private long cooldown;
    private long radius;
    private int range;
    private boolean limited;
    private int maxEntity;
    private ArrayList<LivingEntity> targets;
    private Location targetLoc;

    public Toss(final Player player) {
        super(player);
        if (this.bPlayer.isOnCooldown(this) || !this.bPlayer.canBendIgnoreBinds(this)) return;
        setField();
        findTargets(this.radius);
        if (this.targets.isEmpty()) {
            player.sendMessage(ChatColor.GREEN + "No entities found!");
            return;
        }
        findTargetLocation();
        this.bPlayer.addCooldown(this);
        start();
    }

    private static String path(final String node) {
        return "ExtraAbilities.Hiro3.Earth.Toss." + node;
    }

    private void setField() {
        this.cooldown = ConfigManager.getConfig().getLong(path("Cooldown"));
        this.radius = ConfigManager.getConfig().getLong(path("EntitySelectRadius"));
        this.range = ConfigManager.getConfig().getInt(path("BlockSelectRange"));
        this.limited = ConfigManager.getConfig().getBoolean(path("LimitedAmountOfEntities.isEnabled"));
        this.maxEntity = ConfigManager.getConfig().getInt(path("LimitedAmountOfEntities.MaxEntityNumber"));
        this.targets = new ArrayList<>();
    }

    @Override
    public void progress() {
        if (GeneralMethods.isRegionProtectedFromBuild(this, this.player.getLocation())) {
            remove();
            return;
        }
        launch();
        remove();
    }

    private void launch() {
        for (final LivingEntity target : this.targets) {
            final Vector vector = this.targetLoc.clone().toVector().subtract(target.getLocation().toVector());
            GeneralMethods.setVelocity(this, target, new Vector(vector.getX(), vector.getY() < 4 ? 4 : vector.getY() + 1.5, vector.getZ()).multiply(0.175));
            if (this.bPlayer.areSourceHolesOn()) shape(target, target.getVelocity());
        }
    }

    private void shape(final LivingEntity entity, final Vector velocity) {
        if (!this.bPlayer.areSourceHolesOn() || velocity.lengthSquared() == 0) return;
        final Vector direction = velocity.clone().setY(0).normalize().multiply(-1);
        new RaiseEarth(this.player, entity.getLocation().add(0, -1, 0).add(direction).getBlock().getLocation(), 2);
        new RaiseEarth(this.player, entity.getLocation().add(0, -1, 0).add(direction).add(direction).getBlock().getLocation(), 1);
    }

    private void findTargets(final double radius) {
        for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(this.player.getLocation(), radius)) {
            if (entity instanceof LivingEntity living && !entity.equals(this.player) && isEarthbendable(living.getLocation().add(0, -1, 0).getBlock())) {
                if (!this.limited || this.targets.size() < this.maxEntity) this.targets.add(living);
            }
        }
    }

    private void findTargetLocation() {
        final Block selected = getTargetEarthBlock(this.player, this.range);
        Block target = selected;
        int i = 0;
        while (!GeneralMethods.isSolid(target) && i < this.range) {
            target = target.getRelative(BlockFace.DOWN, 1);
            i++;
        }
        if (i < 5) target = selected;
        this.targetLoc = target.getLocation().clone().add(0.5, 0, 0.5);
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
        return "Toss";
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
    public Object createNewComboInstance(final Player player) {
        return new Toss(player);
    }

    @Override
    public ArrayList<AbilityInformation> getCombination() {
        return new ArrayList<>(Arrays.asList(new AbilityInformation("EarthBlast", ClickType.LEFT_CLICK), new AbilityInformation("EarthBlast", ClickType.SHIFT_DOWN), new AbilityInformation("Catapult", ClickType.SHIFT_UP)));
    }

    @Override
    public String getDescription() {
        return "Throw creatures to the block you're looking at.";
    }

    @Override
    public String getInstructions() {
        return "EarthBlast (Left Click) -> EarthBlast (Hold Sneak) -> Catapult (Release Sneak)";
    }

    @Override
    public String getAuthor() {
        return "Hiro3";
    }

    @Override
    public String getVersion() {
        return "1.1";
    }

    @Override
    public void load() {
        ConfigManager.getConfig().addDefault(path("Cooldown"), 5000);
        ConfigManager.getConfig().addDefault(path("BlockSelectRange"), 15);
        ConfigManager.getConfig().addDefault(path("EntitySelectRadius"), 10);
        ConfigManager.getConfig().addDefault(path("LimitedAmountOfEntities.isEnabled"), false);
        ConfigManager.getConfig().addDefault(path("LimitedAmountOfEntities.MaxEntityNumber"), 10);
        ConfigManager.defaultConfig.save();
        ProjectKorra.log.info("Succesfully enabled " + getName() + " by " + getAuthor());
    }

    @Override
    public void stop() {
        ProjectKorra.log.info("Successfully disabled " + getName() + " by " + getAuthor());
        super.remove();
    }
}
