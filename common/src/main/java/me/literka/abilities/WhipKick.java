package me.literka.abilities;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.*;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.RayTraceResult;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.FallHandler;
import me.literka.ChiRework;
import me.literka.ModernChiAbility;
import me.literka.util.ParticleEffect;
import me.literka.util.Utils;

import java.util.ArrayList;
import java.util.List;

public class WhipKick extends ModernChiAbility implements AddonAbility {

    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute(Attribute.RANGE)
    private double range;
    private double speed;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private long blockDuration;
    @Attribute("BlockChance")
    private double blockChance;
    private boolean fallDmg;
    private double pushOthers;
    private boolean stopVelocityInEnd;
    private boolean onlyOnGround;

    private List<LivingEntity> affectedEntities;
    private Location origin;
    private Location location;
    private Vector prevVel;
    private double currentSpeed;

    public WhipKick(Player player) {
        super(player);

        damage = ChiRework.config().getDouble("Abilities.WhipKick.Damage");
        range = ChiRework.config().getDouble("Abilities.WhipKick.Range");
        speed = ChiRework.config().getDouble("Abilities.WhipKick.Speed");
        cooldown = ChiRework.config().getLong("Abilities.WhipKick.Cooldown");
        blockDuration = ChiRework.config().getLong("Abilities.WhipKick.BlockDuration");
        blockChance = ChiRework.config().getDouble("Abilities.WhipKick.BlockChance");
        fallDmg = ChiRework.config().getBoolean("Abilities.WhipKick.FallDamage");
        pushOthers = ChiRework.config().getDouble("Abilities.WhipKick.PushOthers");
        stopVelocityInEnd = ChiRework.config().getBoolean("Abilities.WhipKick.StopVelocityInEnd");
        onlyOnGround = ChiRework.config().getBoolean("Abilities.WhipKick.OnlyOnGround");

        if (onlyOnGround && !Utils.isOnGround(player)) return;

        affectedEntities = new ArrayList<>();
        origin = player.getLocation();
        location = origin.clone();
        location.setPitch(0);
        currentSpeed = speed;

        start();
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }

        if (origin.distanceSquared(player.getLocation()) >= range * range) {
            bPlayer.addCooldown(this);
            if (stopVelocityInEnd) GeneralMethods.setVelocity(this, player, new Vector(0, 0, 0));
            remove();
            return;
        }

        double currSpeedSquared = currentSpeed / 2;
        if (prevVel != null && prevVel.distanceSquared(player.getVelocity()) > currSpeedSquared) {
            bPlayer.addCooldown(this);
            if (stopVelocityInEnd) GeneralMethods.setVelocity(this, player, new Vector(0, 0, 0));
            remove();
            return;
        }

        prevVel = location.getDirection().multiply(currentSpeed);
        currentSpeed = speed / range * (range - origin.distance(player.getLocation()) + 1);
        if (prevVel.lengthSquared() != 0) GeneralMethods.setVelocity(this, player, prevVel.clone());
        new ParticleEffect().type(Particle.CRIT)
                .location(player.getLocation().add(0, 1, 0))
                .count(10)
                .offset(0.25, 0.55, 0.25)
                .speed(2)
                .velocity(location.getDirection().multiply(-1))
                .spawn();

        for (Entity e : GeneralMethods.getEntitiesAroundPoint(player.getLocation(), 2)) {
            if (!(e instanceof LivingEntity le) || affectedEntities.contains(le)) continue;
            if (e.getEntityId() == player.getEntityId()) continue;

            Location loc = player.getLocation().add(0, 0.3, 0);
            Vector between = le.getLocation().toVector().subtract(loc.toVector());
            double distance = between.length();

            RayTraceResult ray = loc.getWorld().rayTraceBlocks(loc, between.normalize(), distance, FluidCollisionMode.NEVER, true);
            if (ray != null) continue;

            BoundingBox box = le.getBoundingBox();
            Vector v = box.getMax().subtract(box.getMin()).multiply(new Vector(0.5, 0.4, 0.5));
            Location targetLoc = le.getEyeLocation();
            ParticleEffect effect = new ParticleEffect()
                    .location(targetLoc)
                    .count(8)
                    .offset(v.getX(), v.getY(), v.getZ());
            effect.type(Particle.WAX_ON).spawn();
            targetLoc.getWorld().playSound(targetLoc, Sound.ENTITY_ITEM_FRAME_ADD_ITEM, SoundCategory.MASTER, 1f, 2f);
            targetLoc.getWorld().playSound(targetLoc, Sound.ENTITY_SHULKER_BULLET_HIT, 1f, 2f);

            if (Utils.damage(le, damage, this)) Utils.punchSound(e.getLocation(), true);
            if (pushOthers != 0) GeneralMethods.setVelocity(this, le, new Vector(0, pushOthers, 0));
            if (le instanceof Player p) {
                if (!fallDmg) FallHandler.stopFall(p);
                if (Utils.willChiBlock(p, blockChance)) Utils.blockChi(p, blockDuration);
            }
            affectedEntities.add(le);
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
        return ChiRework.config().getBoolean("Abilities.WhipKick.Enabled");
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "WhipKick";
    }

    @Override
    public String getDescription() {
        return ChiRework.config().getString("Language.WhipKick.Description");
    }

    @Override
    public String getInstructions() {
        return ChiRework.config().getString("Language.WhipKick.Instructions");
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