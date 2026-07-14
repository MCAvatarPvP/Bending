package me.literka.abilities;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.FallHandler;
import me.literka.ChiRework;
import me.literka.ModernChiAbility;
import me.literka.util.Utils;

import java.util.List;

public class Uppercut extends ModernChiAbility implements AddonAbility {

    private final double initialHeightDifference;
    private final int hitTriggerProgress;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private long blockDuration;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    private double height;
    private double animationSpeed;
    @Attribute("BlockChance")
    private double blockChance;
    private boolean fallDmg;
    private LivingEntity target;
    private Location origin;
    private List<Location> locations;
    private int animProgress;
    private boolean hit;

    public Uppercut(Player player, LivingEntity target) {
        super(player);

        origin = player.getEyeLocation();
        origin.setDirection(target.getEyeLocation().toVector().subtract(origin.toVector()).normalize());

        cooldown = ChiRework.config().getLong("Abilities.Uppercut.Cooldown");
        blockDuration = ChiRework.config().getLong("Abilities.Uppercut.BlockDuration");
        blockChance = ChiRework.config().getDouble("Abilities.Uppercut.BlockChance");
        damage = ChiRework.config().getDouble("Abilities.Uppercut.Damage");
        height = ChiRework.config().getDouble("Abilities.Uppercut.Height");
        animationSpeed = ChiRework.config().getDouble("Abilities.Uppercut.AnimationSpeed");
        fallDmg = ChiRework.config().getBoolean("Abilities.Uppercut.FallDamage");

        this.target = target;
        this.initialHeightDifference = player.getLocation().getY() - target.getLocation().getY();

        Location handLoc = GeneralMethods.getMainHandLocation(player);
        Location targetHead = target.getEyeLocation().add(0, 2, 0);
        Location middleLoc = targetHead.clone();
        middleLoc.setY(Math.min(handLoc.getY(), target.getLocation().getY()));
        locations = Utils.bezier(handLoc, middleLoc, targetHead);

        int stepPerTick = Math.max(1, (int) (locations.size() * animationSpeed));
        animProgress = stepPerTick;
        hitTriggerProgress = calculateHitTriggerProgress(stepPerTick);

        bPlayer.addCooldown(this);
        start();
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }

        if (target.isDead() || ((target instanceof Player t) && !t.isOnline())) {
            remove();
            return;
        }

        if (animProgress > locations.size()) animProgress = locations.size();

        for (int i = 0; i < animProgress; i++) {
            Location currPoint = locations.get(i);
            Utils.spawnParticles(currPoint, 2, 0.125, 0.125, 0.125);
        }

        if (animProgress >= hitTriggerProgress && !hit) {
//				loc.getWorld().playSound(loc, Sound.UI_TOAST_OUT, SoundCategory.MASTER, 4f, 1.7f);
            Utils.damage(target, damage, this);
            Utils.punchSound(target.getLocation(), false);

            double absDiff = Math.abs(initialHeightDifference);

            double playerHeight = calculatePerfectYVel(initialHeightDifference < 0 ? height + absDiff : height);
            double targetHeight = calculatePerfectYVel(initialHeightDifference > 0 ? height + absDiff : height);

            int selfDelayTicks = calculateSelfVelocityDelayTicks();
            if (selfDelayTicks > 0) {
                Platform.scheduler().runLater(() -> {
                    if (!player.isOnline() || player.isDead()) return;
                    GeneralMethods.setVelocity(this, player, new Vector(0, playerHeight, 0));
                }, selfDelayTicks);
            } else {
                GeneralMethods.setVelocity(this, player, new Vector(0, playerHeight, 0));
            }
            GeneralMethods.setVelocity(this, target, new Vector(0, targetHeight, 0));

            if (target instanceof Player p) {
                if (!fallDmg) FallHandler.stopFall(p);
                if (Utils.willChiBlock(p, blockChance)) Utils.blockChi(p, blockDuration);
            }

            hit = true;
        }

        if (animProgress >= locations.size()) {
            remove();
            return;
        }

        animProgress += Math.max(1, (int) (locations.size() * animationSpeed));
    }

    private double calculatePerfectYVel(double height) {
        return Math.sqrt(0.16 * height);
    }

    private int calculateHitTriggerProgress(int stepPerTick) {
        final int defaultTrigger = 13;
        boolean lagCompEnabled = ChiRework.config().getBoolean("Abilities.Uppercut.LagCompensation.Enabled");
        if (!lagCompEnabled) {
            return defaultTrigger;
        }

        int attackerPing = Math.max(0, player.getPing());
        boolean syncForBothParties = ChiRework.config().getBoolean("Abilities.Uppercut.LagCompensation.SyncForBothParties");
        boolean includeTargetPing = ChiRework.config().getBoolean("Abilities.Uppercut.LagCompensation.IncludeTargetPing");
        int targetPing = includeTargetPing && target instanceof Player p ? Math.max(0, p.getPing()) : 0;
        int compensatedPing = syncForBothParties ? attackerPing : attackerPing + targetPing;

        double pingPerTick = ChiRework.config().getDouble("Abilities.Uppercut.LagCompensation.PingPerTick");
        if (pingPerTick <= 0) {
            pingPerTick = 100;
        }

        int maxCompTicks = Math.max(0, ChiRework.config().getInt("Abilities.Uppercut.LagCompensation.MaxCompensationTicks"));
        int compensationTicks = (int) Math.ceil(compensatedPing / pingPerTick);
        compensationTicks = Math.min(maxCompTicks, compensationTicks);

        int compensatedTrigger = defaultTrigger - (compensationTicks * stepPerTick);
        return Math.max(stepPerTick, compensatedTrigger);
    }

    private int calculateSelfVelocityDelayTicks() {
        boolean compensateSelfVelocity = ChiRework.config().getBoolean("Abilities.Uppercut.LagCompensation.CompensateSelfVelocity");
        if (!compensateSelfVelocity || !(target instanceof Player p)) {
            return 0;
        }

        int attackerPing = Math.max(0, player.getPing());
        int targetPing = Math.max(0, p.getPing());
        int pingDiffMs = Math.max(0, targetPing - attackerPing);
        if (pingDiffMs <= 0) return 0;

        int maxCompTicks = Math.max(0, ChiRework.config().getInt("Abilities.Uppercut.LagCompensation.MaxCompensationTicks"));
        int delayTicks = (int) Math.ceil(pingDiffMs / 50.0D);
        return Math.min(maxCompTicks, delayTicks);
    }


    @Override
    public boolean isSneakAbility() {
        return false;
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public boolean isEnabled() {
        return ChiRework.config().getBoolean("Abilities.Uppercut.Enabled");
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "Uppercut";
    }

    @Override
    public String getDescription() {
        return ChiRework.config().getString("Language.Uppercut.Description");
    }

    @Override
    public String getInstructions() {
        return ChiRework.config().getString("Language.Uppercut.Instructions");
    }

    @Override
    public Location getLocation() {
        return null;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public String getAuthor() {
        return ChiRework.authors;
    }

    @Override
    public String getVersion() {
        return ChiRework.name + " " + ChiRework.version;
    }
}
