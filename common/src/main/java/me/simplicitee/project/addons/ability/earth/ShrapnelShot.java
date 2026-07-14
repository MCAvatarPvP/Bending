package me.simplicitee.project.addons.ability.earth;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.MetalAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Item;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.metadata.FixedMetadataValue;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.ParticleEffect;
import me.simplicitee.project.addons.ProjectAddons;
import me.simplicitee.project.addons.Util;

public class ShrapnelShot extends MetalAbility implements AddonAbility {

    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;

    private Item nugget;
    private double speed;

    public ShrapnelShot(Player player) {
        super(player);

        if (bPlayer.isOnCooldown("Shrapnel")) {
            return;
        } else if (!bPlayer.canBend(this)) {
            return;
        }

        this.speed = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Earth.Shrapnel.Shot.Speed");
        this.damage = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Earth.Shrapnel.Shot.Damage");
        this.cooldown = ProjectAddons.instance.getConfig(bPlayer).getLong("Abilities.Earth.Shrapnel.Shot.Cooldown");

        Location spawn = GeneralMethods.getRightSide(player.getLocation(), 0.12).add(0, 1.3, 0);

        nugget = player.getWorld().dropItem(spawn, new ItemStack(Material.IRON_NUGGET));
        nugget.setMetadata("shrapnel", new FixedMetadataValue(ProjectKorra.plugin, 0));
        nugget.setPickupDelay(Integer.MAX_VALUE);
        nugget.setVelocity(player.getLocation().getDirection().add(new Vector(0, 0.105, 0)).normalize().multiply(speed));

        bPlayer.addCooldown("Shrapnel", cooldown);
        start();
    }

    public ShrapnelShot(Player player, Vector direction, double speed, double damage) {
        super(player);

        if (!bPlayer.canBendIgnoreCooldowns(this)) {
            return;
        }

        this.speed = speed;
        this.damage = damage;
        this.cooldown = ProjectAddons.instance.getConfig(bPlayer).getLong("Abilities.Earth.Shrapnel.Shot.Cooldown");

        Location spawn = GeneralMethods.getRightSide(player.getLocation(), 0.12).add(0, 1.3, 0);

        nugget = player.getWorld().dropItem(spawn, new ItemStack(Material.IRON_NUGGET));
        nugget.setMetadata("shrapnel", new FixedMetadataValue(ProjectKorra.plugin, 0));
        nugget.setPickupDelay(Integer.MAX_VALUE);
        nugget.setVelocity(direction.add(new Vector(0, 0.105, 0)).normalize().multiply(speed));

        start();
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public Location getLocation() {
        return nugget == null ? null : nugget.getLocation();
    }

    @Override
    public String getName() {
        return "Shrapnel";
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
    public void progress() {
        if (!player.isOnline() || player.isDead()) {
            remove();
            return;
        }

        if (nugget.isDead()) {
            remove();
            return;
        }

        if (nugget.isOnGround()) {
            remove();
            return;
        }

        ParticleEffect.CRIT.display(nugget.getLocation(), 1);
        player.getWorld().playSound(nugget.getLocation(), Sound.ENTITY_ARROW_HIT, 0.2f, 1f);
        double dmg = Util.clamp(0, damage, damage * (nugget.getVelocity().length() / speed));

        for (Entity e : GeneralMethods.getEntitiesAroundPoint(nugget.getLocation(), 1.5)) {
            if (e instanceof LivingEntity && e.getEntityId() != player.getEntityId()) {
                player.getWorld().playSound(e.getLocation(), Sound.BLOCK_ANVIL_PLACE, 0.4f, 1f);
                DamageHandler.damageEntity(e, dmg, this);
                ((LivingEntity) e).setNoDamageTicks(0);
                nugget.remove();
                remove();
                return;
            }
        }
    }

    @Override
    public void remove() {
        if (nugget != null && !nugget.isDead()) {
            nugget.remove();
        }
        super.remove();
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
    public String getDescription() {
        return "Use your metalbending to throw pieces of shrapnel, dealing damage when they hit entities.";
    }

    @Override
    public String getInstructions() {
        return "Click to shoot a single piece of shrapnel at high velocity to the targeted location, click while sneaking to launch several shotgun-style.";
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return ProjectAddons.instance.getConfig(bPlayer).getBoolean("Abilities.Earth.Shrapnel.Enabled");
    }
}
