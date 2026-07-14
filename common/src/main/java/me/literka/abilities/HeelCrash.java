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
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.FallHandler;
import com.projectkorra.projectkorra.util.TempBlock;
import me.literka.ChiRework;
import me.literka.ModernChiAbility;
import me.literka.util.ParticleEffect;
import me.literka.util.Utils;

public class HeelCrash extends ModernChiAbility implements AddonAbility {

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private long blockDuration;
    @Attribute("BlockChance")
    private double blockChance;
    private double hitDamage;
    private double explosionDamage;
    private double explosionRadius;
    private double explosionBlastPower;
    private double heightPush;
    private double forwardPush;
    private int requiredDistance;

    private Location origin;
    private Location location;
    private LivingEntity target;
    private boolean damaged;

    public HeelCrash(Player player, LivingEntity target) {
        super(player);

        cooldown = ChiRework.config().getLong("Abilities.HeelCrash.Cooldown");
        blockDuration = ChiRework.config().getLong("Abilities.HeelCrash.BlockDuration");
        blockChance = ChiRework.config().getDouble("Abilities.HeelCrash.BlockChance");
        hitDamage = ChiRework.config().getDouble("Abilities.HeelCrash.HitDamage");
        explosionDamage = ChiRework.config().getDouble("Abilities.HeelCrash.ExplosionDamage");
        explosionRadius = ChiRework.config().getDouble("Abilities.HeelCrash.ExplosionRadius");
        explosionBlastPower = ChiRework.config().getDouble("Abilities.HeelCrash.ExplosionBlastPower");
        heightPush = ChiRework.config().getDouble("Abilities.HeelCrash.HeightPush");
        forwardPush = ChiRework.config().getDouble("Abilities.HeelCrash.ForwardPush");
        requiredDistance = ChiRework.config().getInt("Abilities.HeelCrash.RequiredGroundDistance");

        this.target = target;
        location = target.getEyeLocation();
        origin = player.getEyeLocation();
        origin.setDirection(location.toVector().subtract(origin.toVector()));

        if (!Utils.requiredDistanceGround(player.getLocation(), requiredDistance)) return;
        if (!Utils.requiredDistanceGround(target.getLocation(), requiredDistance)) return;

        damaged = false;

        start();
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }

        if (damaged) {
            if (target.getVelocity().getY() > 0) {
                remove();
                return;
            }

            if (!Utils.isOnGround(target)) return;
            Location loc = target.getLocation().add(0, -1, 0);

            for (Block block : Utils.getBlocksAround(loc, explosionRadius, explosionBlastPower)) {
                TempBlock tb = new TempBlock(block, Material.AIR.createBlockData(), 4000);
                Utils.spawnFB(block.getLocation().add(0.5, 0, 0.5), loc, tb.getState().getType(), this);
            }

            loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 2f);
            new ParticleEffect().type(Particle.EXPLOSION_EMITTER).location(loc).count(1).speed(0).spawn();

            target.setNoDamageTicks(0);
            Utils.damage(target, explosionDamage, this);
            GeneralMethods.setVelocity(this, target, new Vector(0, 0, 0));
            loc.getWorld().playSound(target.getLocation(), Sound.ENTITY_GENERIC_BIG_FALL, 2f, 0f);
            remove();
        } else {
            if (target.isDead() || ((target instanceof Player t) && !t.isOnline())) {
                remove();
                return;
            }

            ParticleEffect effect = new ParticleEffect().count(2).offset(0.05, 0.05, 0.05);
            effect.type(Particle.POOF).speed(0.35);
            double angleX = (Math.random() - 0.5) * 20;
            Utils.spawnCircleParticles(target.getLocation(), effect, origin.getYaw(), angleX, 12, 0.1);
            effect.type(Particle.WAX_ON).speed(22);
            Utils.spawnCircleParticles(target.getLocation(), effect, origin.getYaw(), angleX, 12, 0.1);

            Utils.damage(target, hitDamage, this);
            Utils.punchSound(target.getLocation(), true);
            Vector dir = location.toVector().subtract(player.getLocation().toVector()).normalize();
            if (heightPush != 0 || forwardPush != 0)
                GeneralMethods.setVelocity(this, target, new Vector(0, heightPush, 0).add(dir.multiply(forwardPush)));
            if (target instanceof Player p) {
                FallHandler.stopFall(p);
                if (Utils.willChiBlock(p, blockChance)) Utils.blockChi(p, blockDuration);
            }
            bPlayer.addCooldown(this);
            damaged = true;
        }
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
        return ChiRework.config().getBoolean("Abilities.HeelCrash.Enabled");
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "HeelCrash";
    }

    @Override
    public String getDescription() {
        return ChiRework.config().getString("Language.HeelCrash.Description");
    }

    @Override
    public String getInstructions() {
        return ChiRework.config().getString("Language.HeelCrash.Instructions");
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