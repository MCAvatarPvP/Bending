package me.simplicitee.project.addons.ability.fire;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.CombustionAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.FluidCollisionMode;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.RayTraceResult;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.ParticleEffect;
import me.simplicitee.project.addons.ProjectAddons;

public class Explode extends CombustionAbility implements AddonAbility {

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute(Attribute.RADIUS)
    private double radius;
    @Attribute(Attribute.KNOCKBACK)
    private double knockback;
    @Attribute(Attribute.RANGE)
    private double range;

    private Location center;

    public Explode(Player player) {
        super(player);

        this.cooldown = ProjectAddons.instance.getConfig(bPlayer).getLong("Abilities.Fire.Explode.Cooldown");
        this.damage = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Fire.Explode.Damage");
        this.radius = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Fire.Explode.Radius");
        this.knockback = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Fire.Explode.Knockback");
        this.range = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Fire.Explode.Range");

        start();
    }

    @Override
    public void progress() {
        if (!player.isOnline() || player.isDead()) {
            remove();
            return;
        }

        if (player.isSneaking()) {
            this.center = GeneralMethods.getTargetedLocation(player, range, ElementalAbility.getTransparentMaterials());

            Location loc = player.getEyeLocation();
            RayTraceResult ray = player.getWorld().rayTrace(loc, loc.getDirection(), range, FluidCollisionMode.NEVER, true, 0, entity -> entity instanceof LivingEntity && entity.getEntityId() != player.getEntityId());
            if (ray != null) {
                if (ray.getHitEntity() != null) center = ray.getHitEntity().getLocation();
            }

            ParticleEffect.CRIT.display(center, 3, 0.3, 0.3, 0.3);
            player.getWorld().playSound(center, Sound.ENTITY_CREEPER_PRIMED, 0.2f, 8f);
        } else {
            if (center != null) {
                double offset = radius / 2;
                playFirebendingParticles(center, 7, offset, offset, offset);
                ParticleEffect.CRIT.display(center, 6, offset, offset, offset);
                ParticleEffect.EXPLOSION_HUGE.display(center, 1);
                player.getWorld().playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 2f, 3f);

                for (Entity e : GeneralMethods.getEntitiesAroundPoint(center, radius)) {
                    if (e instanceof LivingEntity) {
                        Vector direction = GeneralMethods.getDirection(center, ((LivingEntity) e).getEyeLocation()).normalize().multiply(knockback);
                        DamageHandler.damageEntity(e, damage, this);
                        GeneralMethods.setVelocity(this, e, direction);
                    }
                }

                remove();
            }
        }
    }

    @Override
    public void remove() {
        super.remove();
        bPlayer.addCooldown(this);
    }

    @Override
    public boolean isSneakAbility() {
        return true;
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "Explode";
    }

    @Override
    public Location getLocation() {
        return center;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public String getAuthor() {
        return "Simplicitee";
    }

    @Override
    public String getVersion() {
        return ProjectAddons.instance.version();
    }

    @Override
    public boolean isEnabled() {
        return ProjectAddons.instance.getConfig(bPlayer).getBoolean("Abilities.Fire.Explode.Enabled");
    }

    @Override
    public String getDescription() {
        return "Cause a spontaneous explosion where you are looking! Big boom!";
    }

    @Override
    public String getInstructions() {
        return "Hold sneak to aim, release sneak to explode!";
    }
}
