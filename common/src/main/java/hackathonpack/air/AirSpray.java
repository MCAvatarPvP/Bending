package hackathonpack.air;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.inventory.MainHand;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.ClickType;
import hackathonpack.UtilityMethods;

import java.util.ArrayList;
import java.util.Arrays;

public class AirSpray extends AirAbility implements AddonAbility, ComboAbility {
    private long cooldown;
    private int tick;
    private int maximumTick;
    private ArrayList<Boid> boids;
    private int amount;
    private double damage;
    private double range;
    private double alignmentPower;
    private double seperationPower;
    private double cohesionPower;
    private double playerDirectionPower;
    private double knockbackPower;

    public AirSpray(final Player player) {
        super(player);
        if (!this.bPlayer.canBendIgnoreBinds(this) || !ConfigManager.getConfig().getBoolean(path("Enable")) || hasAbility(player, AirSpray.class) || player.getLocation().getBlock().isLiquid()) {
            return;
        }
        setField();
        start();
    }

    private static String path(final String node) {
        return "ExtraAbilities.Hiro3.HackathonPack.Air.AirSpray." + node;
    }

    public void setField() {
        this.cooldown = ConfigManager.getConfig().getLong(path("Cooldown"));
        this.tick = 1;
        this.boids = new ArrayList<>();
        this.maximumTick = (int) (ConfigManager.getConfig().getLong(path("Duration")) / 50);
        this.range = ConfigManager.getConfig().getDouble(path("Range"));
        this.damage = ConfigManager.getConfig().getDouble(path("Damage"));
        this.amount = ConfigManager.getConfig().getInt(path("ParticlePerTick"));
        this.alignmentPower = ConfigManager.getConfig().getDouble(path("AlignmentPower"));
        this.seperationPower = ConfigManager.getConfig().getDouble(path("SeperationPower"));
        this.cohesionPower = ConfigManager.getConfig().getDouble(path("CohesionPower"));
        this.playerDirectionPower = ConfigManager.getConfig().getDouble(path("PlayerDirectionPower"));
        this.knockbackPower = ConfigManager.getConfig().getDouble(path("KnockbackPower"));
        this.player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, this.maximumTick, 100));
        addBoids();
    }

    @Override
    public void progress() {
        if ((this.bPlayer.getBoundAbilityName() == null || !this.bPlayer.getBoundAbilityName().equalsIgnoreCase("AirBurst"))
                && this.bPlayer.canBendIgnoreBinds(CoreAbility.getAbility("AirSpray"))) {
            this.tick = this.maximumTick;
        }
        if (this.boids.isEmpty()) {
            remove();
            if (this.tick <= this.maximumTick) {
                this.bPlayer.addCooldown(this);
                this.player.removePotionEffect(PotionEffectType.MINING_FATIGUE);
            }
            return;
        }
        if (this.tick <= this.maximumTick) {
            if (this.tick == this.maximumTick) {
                this.bPlayer.addCooldown(this);
                this.player.removePotionEffect(PotionEffectType.MINING_FATIGUE);
            }
            if (!this.player.getLocation().getBlock().isLiquid()) {
                if (this.tick % 5 == 0) playAirbendingSound(this.player.getLocation());
                addBoids();
            }
        }
        for (final Boid boid : new ArrayList<>(this.boids)) {
            boid.update();
            boid.show();
        }
        this.boids.removeIf(boid -> boid.getTicksLived() > this.maximumTick);
        this.tick++;
    }

    private void addBoids() {
        for (int i = 0; i < this.amount; i++) {
            Location hand = this.player.getMainHand() == MainHand.RIGHT
                    ? getRightSide(this.player.getLocation().clone().add(0, 1, 0), 0.2)
                    : getLeftSide(this.player.getLocation().clone().add(0, 1, 0), 0.2);
            hand = hand.add(Math.random() * 0.5 - 0.25, Math.random() * 0.5 - 0.25, Math.random() * 0.5 - 0.25);
            this.boids.add(new Boid(this.player, this, hand, this.player.getLocation().getDirection(), this.damage, this.range));
        }
    }

    public Location getRightSide(final Location location, final double distance) {
        final Vector dir = this.player.getLocation().getDirection();
        return location.clone().add(new Vector(-dir.getZ(), 0, dir.getX()).normalize().multiply(distance));
    }

    public Location getLeftSide(final Location location, final double distance) {
        final Vector dir = this.player.getLocation().getDirection();
        return location.clone().add(new Vector(dir.getZ(), 0, -dir.getX()).normalize().multiply(distance));
    }

    public ArrayList<Boid> getBoids() {
        return this.boids;
    }

    public int getMaximumTick() {
        return this.maximumTick;
    }

    public int getTick() {
        return this.tick;
    }

    public double getAlignmentPower() {
        return this.alignmentPower;
    }

    public double getSeperationPower() {
        return this.seperationPower;
    }

    public double getCohesionPower() {
        return this.cohesionPower;
    }

    public double getPlayerDirectionPower() {
        return this.playerDirectionPower;
    }

    public double getKnockbackPower() {
        return this.knockbackPower;
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
        return "AirSpray";
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
        return new AirSpray(player);
    }

    @Override
    public ArrayList<AbilityInformation> getCombination() {
        return UtilityMethods.getConfiguredCombination(path("Enable"), path("Combination"), new ArrayList<>(Arrays.asList(new AbilityInformation("AirBlast", ClickType.SHIFT_DOWN), new AbilityInformation("AirBurst", ClickType.LEFT_CLICK))));
    }

    @Override
    public String getDescription() {
        return "Release a spray of air that can bounce from terrain.";
    }

    @Override
    public String getInstructions() {
        return "AirBlast (Hold Sneak) -> AirBurst (Left Click)";
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
        ConfigManager.getConfig().addDefault(path("Duration"), 3000);
        ConfigManager.getConfig().addDefault(path("Range"), 18);
        ConfigManager.getConfig().addDefault(path("Damage"), 1);
        ConfigManager.getConfig().addDefault(path("ParticlePerTick"), 2);
        ConfigManager.getConfig().addDefault(path("AlignmentPower"), 0.2);
        ConfigManager.getConfig().addDefault(path("SeperationPower"), 1);
        ConfigManager.getConfig().addDefault(path("CohesionPower"), 0.1);
        ConfigManager.getConfig().addDefault(path("PlayerDirectionPower"), 0.1);
        ConfigManager.getConfig().addDefault(path("KnockbackPower"), 0.05);
        ConfigManager.getConfig().addDefault(path("Combination"), Arrays.asList("AirBlast: SHIFT_DOWN", "AirBurst: LEFT_CLICK"));
        ConfigManager.defaultConfig.save();
        ProjectKorra.log.info("Succesfully enabled " + getName() + " by " + getAuthor());
    }

    @Override
    public void stop() {
        ProjectKorra.log.info("Successfully disabled " + getName() + " by " + getAuthor());
        super.remove();
    }
}
