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
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.DamageHandler;
import hackathonpack.UtilityMethods;

import java.util.ArrayList;
import java.util.Arrays;

public class AirPressureHigh extends AirAbility implements AddonAbility, ComboAbility {
    private final long duration = 2000;
    private long cooldown;
    private int particlePerTick;
    private Location spawnLocation;
    private ArrayList<Location> particles;
    private ArrayList<Vector> directions;
    private ArrayList<Vector> velocities;
    private ArrayList<Entity> affectedEntities;

    public AirPressureHigh(final Player player) {
        super(player);
        if (!this.bPlayer.canBendIgnoreBinds(this) || CoreAbility.hasAbility(player, AirPressureHigh.class) || !ConfigManager.getConfig().getBoolean(path("Enable")))
            return;
        setField();
        start();
    }

    private static String path(final String node) {
        return "ExtraAbilities.Hiro3.HackathonPack.Air.AirPressure.High." + node;
    }

    private void setField() {
        this.particles = new ArrayList<>();
        this.directions = new ArrayList<>();
        this.velocities = new ArrayList<>();
        this.affectedEntities = new ArrayList<>();
        this.cooldown = ConfigManager.getConfig().getLong(path("Cooldown"));
        this.particlePerTick = ConfigManager.getConfig().getInt(path("ParticlePerTick"));
        this.spawnLocation = this.player.getLocation().add(0, 7, 0);
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
            final double size = Math.random() * 1.5 + 0.001;
            final Location loc = this.spawnLocation.clone().add(Math.cos(angle) * size, Math.random() * 2 - 1, Math.sin(angle) * size);
            this.particles.add(loc);
            this.directions.add(loc.toVector().subtract(this.spawnLocation.toVector()).setY(0).normalize().multiply(0.1));
            this.velocities.add(new Vector(0, -0.2, 0));
        }
        this.affectedEntities.clear();
        for (int i = this.particles.size() - 1; i >= 0; i--) {
            final Location loc = this.particles.get(i);
            this.player.getWorld().spawnParticle(Particle.CLOUD, loc, 0, 0, 0, 0);
            if (this.spawnLocation.getY() - loc.getY() > 5) this.velocities.get(i).add(this.directions.get(i));
            for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(loc, 2)) {
                if (!this.affectedEntities.contains(entity) && !entity.equals(this.player)) {
                    final Vector velocity = entity.getLocation().toVector().subtract(this.spawnLocation.toVector()).add(new Vector(0.0001, 0, 0)).setY(0).normalize();
                    GeneralMethods.setVelocity(entity, velocity);
                    if (entity instanceof LivingEntity living && UtilityMethods.checkBlocks(living))
                        DamageHandler.damageEntity(entity, 1, this);
                    this.affectedEntities.add(entity);
                }
            }
            loc.add(this.velocities.get(i));
            if (GeneralMethods.isSolid(loc.getBlock()) || loc.distance(this.spawnLocation) > 15) {
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
        return "AirPressureHigh";
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
        return new AirPressureHigh(player);
    }

    @Override
    public ArrayList<AbilityInformation> getCombination() {
        return UtilityMethods.getConfiguredCombination(path("Enable"), path("Combination"), new ArrayList<>(Arrays.asList(new AbilityInformation("AirSpout", ClickType.SHIFT_DOWN), new AbilityInformation("AirSpout", ClickType.SHIFT_UP), new AbilityInformation("AirShield", ClickType.LEFT_CLICK))));
    }

    @Override
    public String getDescription() {
        return "Push entities away by increasing the air pressure around you.";
    }

    @Override
    public String getInstructions() {
        return "AirSpout (Tap Sneak) -> AirShield (Left Click)";
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
        ConfigManager.getConfig().addDefault(path("Combination"), Arrays.asList("AirSpout: SHIFT_DOWN", "AirSpout: SHIFT_UP", "AirShield: LEFT_CLICK"));
        ConfigManager.defaultConfig.save();
        ProjectKorra.log.info("Succesfully enabled " + getName() + " by " + getAuthor());
    }

    @Override
    public void stop() {
        ProjectKorra.log.info("Successfully disabled " + getName() + " by " + getAuthor());
        super.remove();
    }
}
