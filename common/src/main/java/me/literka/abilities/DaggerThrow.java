package me.literka.abilities;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.ChiAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.configuration.PKConfigurationSection;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.Arrow;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.metadata.FixedMetadataValue;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.region.RegionProtection;
import me.literka.ChiRework;
import me.literka.util.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class DaggerThrow extends ChiAbility implements AddonAbility {
    private static final List<AbilityInteraction> INTERACTIONS = new ArrayList<>();
    private final List<Arrow> arrows = new ArrayList<>();
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private boolean limitEnabled;
    @Attribute("MaxShots")
    private int maxShots;
    private boolean particles;
    private double damage;
    private double push;
    private boolean requiresArrows;
    private long endTime;
    private int shots = 1;
    private int hits = 0;

    public DaggerThrow(Player player) {
        super(player);

        if (bPlayer.isOnCooldown("DaggerThrowShot")) {
            return;
        }

        if (hasAbility(player, DaggerThrow.class)) {
            DaggerThrow dt = getAbility(player, DaggerThrow.class);
            dt.shootArrow();
            return;
        }

        cooldown = ChiRework.config().getLong("Abilities.DaggerThrow.Cooldown");
        limitEnabled = ChiRework.config().getBoolean("Abilities.DaggerThrow.MaxDaggers.Enabled");
        maxShots = ChiRework.config().getInt("Abilities.DaggerThrow.MaxDaggers.Amount");
        particles = ChiRework.config().getBoolean("Abilities.DaggerThrow.ParticleTrail");
        damage = ChiRework.config().getDouble("Abilities.DaggerThrow.Damage");
        push = ChiRework.config().getDouble("Abilities.DaggerThrow.Push");
        requiresArrows = ChiRework.config().getBoolean("Abilities.DaggerThrow.RequiresArrows");

        loadInteractions();

        shootArrow();
        start();
    }

    private void loadInteractions() {
        INTERACTIONS.clear();

        String path = "Abilities.DaggerThrow.Interactions";

        PKConfigurationSection section = ChiRework.config().getConfigurationSection(path);
        for (String abilityName : section.getKeys(false)) {
            INTERACTIONS.add(new AbilityInteraction(abilityName));
        }
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }
        if (System.currentTimeMillis() > endTime) {
            bPlayer.addCooldown(this);
            remove();
            return;
        }
        if (shots > maxShots && limitEnabled) {
            bPlayer.addCooldown(this);
            remove();
        }
    }

    private void shootArrow() {
        if (requiresArrows && !removeArrowFromInventory()) return;

        shots++;
        Location location = player.getEyeLocation();

        Vector vector = location.toVector().
                add(location.getDirection().multiply(2.5)).
                toLocation(location.getWorld()).toVector().
                subtract(player.getEyeLocation().toVector());

        Arrow arrow = player.launchProjectile(Arrow.class);
        arrow.setVelocity(vector);
        arrow.getLocation().setDirection(vector);
        arrow.setDamage(0);
        arrow.setKnockbackStrength(0);
        arrow.setPickupStatus(Arrow.PickupStatus.DISALLOWED);
        arrow.setMetadata("daggerthrow", new FixedMetadataValue(ChiRework.plugin, this));

        if (particles) {
            arrow.setCritical(true);
        }

        location.getWorld().playSound(location, Sound.ITEM_TRIDENT_THROW, 1, 2);
        arrows.add(arrow);
        endTime = System.currentTimeMillis() + 500;
        bPlayer.addCooldown("DaggerThrowShot", 100);
    }

    public boolean removeArrowFromInventory() {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() != Material.ARROW) return false;

            if (item.getAmount() == 1) {
                boolean empty = player.getInventory().removeItem(item).isEmpty();
                ItemStack offHand = player.getInventory().getItemInOffHand();

                if (!empty && offHand != null && offHand.getType() == Material.ARROW && offHand.getAmount() == 1) {
                    player.getInventory().setItemInOffHand(null);
                }
            } else if (item.getAmount() > 1) item.setAmount(item.getAmount() - 1);

            return true;
        }
        return false;
    }

    public void damageEntityFromArrow(LivingEntity entity, Arrow arrow) {
        if (RegionProtection.isRegionProtected(player, arrow.getLocation(), "DaggerThrow")) {
            return;
        }

        entity.setNoDamageTicks(0);
        boolean inAir = !Utils.isOnGround(player) && !Utils.isOnGround(entity);
        Utils.damage(entity, damage, this);
        if (inAir && push != 0) {
            GeneralMethods.setVelocity(this, player, player.getVelocity().setY(push));
            GeneralMethods.setVelocity(this, entity, entity.getVelocity().setY(push));
        }
        player.playSound(player.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 1f, 1);

        ++hits;

        if (!(entity instanceof Player target)) return;

        BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(target);

        for (AbilityInteraction interaction : INTERACTIONS) {
            if (!interaction.enabled) continue;
            if (hits < interaction.hitRequirement) continue;

            CoreAbility abilityDefinition = CoreAbility.getAbility(interaction.name);
            if (abilityDefinition == null) continue;

            CoreAbility ability = CoreAbility.getAbility(target, abilityDefinition.getClass());
            if (ability == null) continue;

            ability.remove();
            bPlayer.addCooldown(ability, interaction.cooldown);
        }
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public Location getLocation() {
        return null;
    }

    @Override
    public List<Location> getLocations() {
        return arrows.stream().map(Arrow::getLocation).collect(Collectors.toList());
    }

    @Override
    public double getCollisionRadius() {
        return ChiRework.config().getDouble("Abilities.DaggerThrow.CollisionRadius");
    }

    @Override
    public void handleCollision(Collision collision) {
        if (collision.isRemovingFirst()) {
            Location location = collision.getLocationFirst();

            Optional<Arrow> collidedObject = arrows.stream().filter(arrow -> arrow.getLocation().equals(location)).findAny();

            if (collidedObject.isPresent()) {
                arrows.remove(collidedObject.get());
                collidedObject.get().remove();
            }
        }
    }

    @Override
    public String getName() {
        return "DaggerThrow";
    }

    @Override
    public String getDescription() {
        return ChiRework.config().getString("Language.DaggerThrow.Description");
    }

    @Override
    public String getInstructions() {
        return ChiRework.config().getString("Language.DaggerThrow.Instructions");
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
    public String getAuthor() {
        return ChiRework.authors;
    }

    @Override
    public String getVersion() {
        return ChiRework.version;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return ChiRework.config().getBoolean("Abilities.DaggerThrow.Enabled");
    }

    @Override
    public boolean isModern() {
        return true;
    }

    private class AbilityInteraction {
        public boolean enabled;
        public long cooldown;
        public int hitRequirement;
        public String name;

        public AbilityInteraction(String abilityName) {
            this.name = abilityName;
            loadConfig();
        }

        public void loadConfig() {
            this.enabled = ChiRework.config().getBoolean("Abilities.DaggerThrow.Interactions." + name + ".Enabled", true);
            this.cooldown = ChiRework.config().getLong("Abilities.DaggerThrow.Interactions." + name + ".Cooldown", 1000);
            this.hitRequirement = ChiRework.config().getInt("Abilities.DaggerThrow.Interactions." + name + ".HitsRequired", 1);
        }
    }
}
