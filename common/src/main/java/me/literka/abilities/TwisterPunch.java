package me.literka.abilities;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.inventory.MainHand;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.FallHandler;
import com.projectkorra.projectkorra.util.TempBlock;
import me.literka.ChiRework;
import me.literka.ModernChiAbility;
import me.literka.util.ParticleEffect;
import me.literka.util.Utils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class TwisterPunch extends ModernChiAbility implements AddonAbility {

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private long blockDuration;
    @Attribute("BlockChance")
    private double blockChance;
    private int increment;
    private int speed;
    private boolean fallDmg;
    private double circleRadius;
    private double endCircleRadius;
    private double circleRadiusSteps;
    private double hitRadiusH;
    private double hitRadiusV;
    private double damage;
    private int requiredDistanceSelf;
    private int requiredDistanceOthers;
    private double explosionDamage;
    private double explosionRadius;
    private double explosionBlastPower;
    private double pushSelf;
    private double pushOthers;

    private List<LivingEntity> hitEntities;
    private List<LivingEntity> explodeEntities;
    private boolean left;
    private int spinProgress;
    private boolean didHitEntity;
    private boolean appliedVel;
    private boolean explode;
    private double yaw;
    private ParticleEffect effect;

    public TwisterPunch(Player player) {
        super(player);

        cooldown = ChiRework.config().getLong("Abilities.TwisterPunch.Cooldown");
        blockDuration = ChiRework.config().getLong("Abilities.TwisterPunch.BlockDuration");
        blockChance = ChiRework.config().getDouble("Abilities.TwisterPunch.BlockChance");
        fallDmg = ChiRework.config().getBoolean("Abilities.TwisterPunch.FallDamage");
        damage = ChiRework.config().getDouble("Abilities.TwisterPunch.Damage");
        speed = ChiRework.config().getInt("Abilities.TwisterPunch.Speed");
        circleRadius = ChiRework.config().getDouble("Abilities.TwisterPunch.SpinRadius");
        endCircleRadius = ChiRework.config().getDouble("Abilities.TwisterPunch.SpinRadiusEnd");
        circleRadiusSteps = ChiRework.config().getDouble("Abilities.TwisterPunch.SpinRadiusSteps");
        increment = ChiRework.config().getInt("Abilities.TwisterPunch.SpinStep");
        hitRadiusH = ChiRework.config().getDouble("Abilities.TwisterPunch.HitRadiusHorizontal");
        hitRadiusV = ChiRework.config().getDouble("Abilities.TwisterPunch.HitRadiusVertical");
        requiredDistanceSelf = ChiRework.config().getInt("Abilities.TwisterPunch.RequiredGroundDistance.Self");
        requiredDistanceOthers = ChiRework.config().getInt("Abilities.TwisterPunch.RequiredGroundDistance.Others");
        explosionDamage = ChiRework.config().getDouble("Abilities.TwisterPunch.ExplosionDamage");
        explosionRadius = ChiRework.config().getDouble("Abilities.TwisterPunch.ExplosionRadius");
        explosionBlastPower = ChiRework.config().getDouble("Abilities.TwisterPunch.ExplosionBlastPower");
        pushSelf = ChiRework.config().getDouble("Abilities.TwisterPunch.Push.Self");
        pushOthers = ChiRework.config().getDouble("Abilities.TwisterPunch.Push.Others");

        hitEntities = new ArrayList<>();
        explodeEntities = new ArrayList<>();
        left = player.getMainHand() == MainHand.LEFT;
        spinProgress = left ? 0 : 360;
        increment = left ? increment : -increment;
        explode = Utils.requiredDistanceGround(player.getLocation(), requiredDistanceSelf);
        yaw = player.getLocation().getYaw() + 90;
        effect = new ParticleEffect().type(Particle.CRIT).count(1).speed(0);

        start();
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }

        if (explodeEntities.isEmpty() && didHitEntity && ((left && spinProgress > 360) || (!left && spinProgress < 0))) {
            bPlayer.addCooldown(this);
            remove();
            return;
        }

        Iterator<LivingEntity> iter = explodeEntities.iterator();
        while (iter.hasNext()) {
            LivingEntity entity = iter.next();
            if (entity.getVelocity().getY() > 0 || entity.isDead()) {
                iter.remove();
                continue;
            }
            if (!Utils.isOnGround(entity)) continue;

            Location loc = entity.getLocation().add(0, -1, 0);

            for (Block block : Utils.getBlocksAround(loc, explosionRadius, explosionBlastPower)) {
                TempBlock tb = new TempBlock(block, Material.AIR.createBlockData(), 4000);
                Utils.spawnFB(block.getLocation().add(0.5, 0, 0.5), loc, tb.getState().getType(), this);
            }

            loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 2f);
            new ParticleEffect().type(Particle.EXPLOSION_EMITTER).location(loc).count(1).speed(0).spawn();

            entity.setNoDamageTicks(0);
            Utils.damage(entity, explosionDamage, this);
            GeneralMethods.setVelocity(this, entity, new Vector(0, 0, 0));
            loc.getWorld().playSound(entity.getLocation(), Sound.ENTITY_GENERIC_BIG_FALL, 2f, 0f);
            iter.remove();
        }

        for (int i = 0; i < speed; i++) {
            if ((left && spinProgress > 360) || (!left && spinProgress < 0)) {
                if (!didHitEntity) {
                    bPlayer.addCooldown(this);
                    remove();
                }
                if (!appliedVel && pushSelf != 0) GeneralMethods.setVelocity(this, player, new Vector(0, pushSelf, 0));
                appliedVel = true;
                return;
            }

            Location loc = player.getLocation();

            double x, z;
            x = Math.cos(Math.toRadians(yaw + spinProgress));
            z = Math.sin(Math.toRadians(yaw + spinProgress));

            for (LivingEntity e : getEntitiesAround(loc.clone().add(circleRadius * x, 0.8, circleRadius * z), hitRadiusH, hitRadiusV)) {
                if (player.getUniqueId() == e.getUniqueId()) continue;
                if (hitEntities.contains(e)) continue;

                if (Utils.damage(e, damage, this)) {
                    Utils.punchSound(e.getLocation(), true);
                    if (e instanceof Player p) {
                        if (!fallDmg) FallHandler.stopFall(p);
                        if (Utils.willChiBlock(p, blockChance)) Utils.blockChi(p, blockDuration);
                    }
                }

                if (!Utils.isOnGround(e)) {
                    Vector velocity = e.getLocation().toVector().subtract(player.getLocation().toVector()).normalize().setY(0).multiply(pushOthers);
                    if (velocity.lengthSquared() != 0) GeneralMethods.setVelocity(this, e, velocity);
                }
                hitEntities.add(e);
                if (explode && Utils.requiredDistanceGround(e.getLocation(), requiredDistanceOthers))
                    explodeEntities.add(e);
                didHitEntity = true;
            }

            double radiusStep = (circleRadius - endCircleRadius) / circleRadiusSteps;
            double heightStep = 1.4 / circleRadiusSteps;
            for (double j = endCircleRadius; j < circleRadius; j += radiusStep) {
                loc.add(0, heightStep, 0);

                effect.location(loc.clone().add(j * x, 0, j * z)).spawn();
            }

            spinProgress += increment;
        }
    }

    public List<LivingEntity> getEntitiesAround(Location location, double radiusHorizontal, double radiusVertical) {
        return new ArrayList<>(location.getWorld().getNearbyLivingEntities(location, radiusHorizontal, radiusVertical, radiusHorizontal));
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
        return ChiRework.config().getBoolean("Abilities.TwisterPunch.Enabled");
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "TwisterPunch";
    }

    @Override
    public String getDescription() {
        return ChiRework.config().getString("Language.TwisterPunch.Description");
    }

    @Override
    public String getInstructions() {
        return ChiRework.config().getString("Language.TwisterPunch.Instructions");
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