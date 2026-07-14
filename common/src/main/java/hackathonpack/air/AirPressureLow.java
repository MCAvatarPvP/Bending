package hackathonpack.air;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.ClickType;
import hackathonpack.UtilityMethods;

import java.util.ArrayList;
import java.util.Arrays;

public class AirPressureLow extends AirAbility implements AddonAbility, ComboAbility {
    private final long duration = 1000;
    private long cooldown;
    private int particlePerTick;
    private Location spawnLocation;
    private ArrayList<Location> particles;
    private ArrayList<Vector> directions;
    private ArrayList<Vector> velocities;
    private ArrayList<Entity> affectedEntities;

    public AirPressureLow(final Player player) {
        super(player);
        if (!this.bPlayer.canBendIgnoreBinds(this) || CoreAbility.hasAbility(player, AirPressureLow.class) || !ConfigManager.getConfig().getBoolean(path("Enable")))
            return;
        setField();
        start();
    }

    private static String path(final String node) {
        return "ExtraAbilities.Hiro3.HackathonPack.Air.AirPressure.Low." + node;
    }

    private void setField() {
        this.particles = new ArrayList<>();
        this.directions = new ArrayList<>();
        this.velocities = new ArrayList<>();
        this.affectedEntities = new ArrayList<>();
        this.cooldown = ConfigManager.getConfig().getLong(path("Cooldown"));
        this.particlePerTick = ConfigManager.getConfig().getInt(path("ParticlePerTick"));
        this.spawnLocation = this.player.getLocation();
    }

    @Override
    public void progress() {
        if (System.currentTimeMillis() > getStartTime() + this.duration) {
            this.bPlayer.addCooldown(this);
            remove();
            return;
        }
        for (int i = 0; i < this.particlePerTick; i++) {
            final double angle = Math.toRadians(Math.random() * 360);
            final double size = Math.random() * 8 + 0.001;
            final Location loc = this.spawnLocation.clone().add(Math.cos(angle) * size, 0, Math.sin(angle) * size);
            this.particles.add(loc);
            this.directions.add(this.spawnLocation.toVector().subtract(loc.toVector()).setY(0).multiply(0.1));
            this.velocities.add(new Vector(0, 0.2, 0));
        }
        this.affectedEntities.clear();
        for (int i = this.particles.size() - 1; i >= 0; i--) {
            final Location loc = this.particles.get(i);
            this.player.getWorld().spawnParticle(Particle.CLOUD, loc, 0, 0, 0, 0);
            for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(loc, 2)) {
                if (!this.affectedEntities.contains(entity) && !entity.equals(this.player)) {
                    GeneralMethods.setVelocity(entity, this.velocities.get(i).clone().add(this.directions.get(i)).multiply(3));
                    this.affectedEntities.add(entity);
                }
            }
            loc.add(this.velocities.get(i)).add(this.directions.get(i));
            this.directions.get(i).multiply(0.88);
            if (GeneralMethods.isSolid(loc.getBlock()) || loc.getY() - this.spawnLocation.getY() > 7) {
                this.particles.remove(i);
                this.directions.remove(i);
                this.velocities.remove(i);
            }
        }
        if (Math.random() < 0.1) playAirbendingSound(this.spawnLocation);
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
        return "AirPressureLow";
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
    public Object createNewComboInstance(final Player player) {
        return new AirPressureLow(player);
    }

    @Override
    public ArrayList<AbilityInformation> getCombination() {
        return UtilityMethods.getConfiguredCombination(path("Enable"), path("Combination"), new ArrayList<>(Arrays.asList(new AbilityInformation("AirSpout", ClickType.SHIFT_DOWN), new AbilityInformation("AirShield", ClickType.LEFT_CLICK))));
    }

    @Override
    public String getDescription() {
        return "Pull entities towards you and push them up by creating low pressure air field around you.";
    }

    @Override
    public String getInstructions() {
        return "AirSpout (Hold Sneak) -> AirShield (Left Click)";
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
    public void load() {
        ConfigManager.getConfig().addDefault(path("Enable"), true);
        ConfigManager.getConfig().addDefault(path("Cooldown"), 5000);
        ConfigManager.getConfig().addDefault(path("ParticlePerTick"), 2);
        ConfigManager.getConfig().addDefault(path("Combination"), Arrays.asList("AirSpout: SHIFT_DOWN", "AirShield: LEFT_CLICK"));
        ConfigManager.defaultConfig.save();
        ProjectKorra.log.info("Succesfully enabled " + getName() + " by " + getAuthor());
    }

    @Override
    public void stop() {
        ProjectKorra.log.info("Successfully disabled " + getName() + " by " + getAuthor());
        super.remove();
    }
}
