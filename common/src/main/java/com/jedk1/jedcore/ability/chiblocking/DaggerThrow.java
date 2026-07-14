package com.jedk1.jedcore.ability.chiblocking;

import com.jedk1.jedcore.JCMethods;
import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.util.AbilitySelector;
import com.projectkorra.projectkorra.BendingPlayer;
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
import com.projectkorra.projectkorra.platform.mc.metadata.FixedMetadataValue;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class DaggerThrow extends ChiAbility implements AddonAbility {
    private static final List<AbilityInteraction> INTERACTIONS = new ArrayList<>();
    private static boolean particles;
    private static double damage;
    private final List<Arrow> arrows = new ArrayList<>();
    private long endTime;
    private int shots = 1;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private boolean limitEnabled;
    @Attribute("MaxShots")
    private int maxShots;
    private int hits = 0;
    private boolean requiresArrows;

    public DaggerThrow(Player player) {
        super(player);

        if (this instanceof DamageAbility) {
            return;
        }

        if (!bPlayer.canBend(this)) {
            return;
        }

        if (bPlayer.isOnCooldown("DaggerThrowShot")) {
            return;
        }

        if (hasAbility(player, DaggerThrow.class)) {
            DaggerThrow dt = getAbility(player, DaggerThrow.class);
            dt.shootArrow();
            return;
        }

        setFields();

        start();
        if (!isRemoved()) {
            shootArrow();
        }
    }

    public static void damageEntityFromArrow(LivingEntity entity, Arrow arrow) {
        if (RegionProtection.isRegionProtected((Player) arrow.getShooter(), arrow.getLocation(), "DaggerThrow")) {
            return;
        }

        arrow.setVelocity(new Vector(0, 0, 0));
        entity.setNoDamageTicks(0);
        double prevHealth = entity.getHealth();
        Player shooter = (Player) arrow.getShooter();
        DamageAbility da = new DamageAbility(shooter);
        DamageHandler.damageEntity(entity, damage, da);
        shooter.playSound(shooter.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 1f, 1);
        da.remove();
        if (prevHealth > entity.getHealth()) {
            arrow.remove();
        }

        if (!(entity instanceof Player)) {
            return;
        }

        DaggerThrow dt = CoreAbility.getAbility(shooter, DaggerThrow.class);
        if (dt == null) {
            return;
        }

        ++dt.hits;

        Player target = (Player) entity;
        BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(target);

        for (AbilityInteraction interaction : INTERACTIONS) {
            if (!interaction.enabled) continue;
            if (dt.hits < interaction.hitRequirement) continue;

            CoreAbility abilityDefinition = AbilitySelector.getAbility(interaction.name);
            if (abilityDefinition == null) continue;

            CoreAbility ability = CoreAbility.getAbility(target, abilityDefinition.getClass());
            if (ability == null) continue;

            ability.remove();
            bPlayer.addCooldown(ability, interaction.cooldown);
        }
    }

    public static boolean hasParticleTrail() {
        return particles;
    }

    public static double getDamage() {
        return damage;
    }

    public void setFields() {
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Chi.DaggerThrow.Cooldown");
        limitEnabled = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Chi.DaggerThrow.MaxDaggers.Enabled");
        maxShots = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Chi.DaggerThrow.MaxDaggers.Amount");
        particles = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Chi.DaggerThrow.ParticleTrail");
        damage = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Chi.DaggerThrow.Damage");
        requiresArrows = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Chi.DaggerThrow.RequiresArrows");

        loadInteractions();
    }

    private void loadInteractions() {
        INTERACTIONS.clear();

        String path = "Abilities.Chi.DaggerThrow.Interactions";

        PKConfigurationSection section = JedCoreConfig.getConfig(this.bPlayer).getConfigurationSection(path);
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
        if (requiresArrows && !JCMethods.removeItemFromInventory(player, Material.ARROW, 1)) return;

        shots++;
        Location location = player.getEyeLocation();

        Vector vector = location.toVector().
                add(location.getDirection().multiply(2.5)).
                toLocation(location.getWorld()).toVector().
                subtract(player.getEyeLocation().toVector());

        Arrow arrow = player.launchProjectile(Arrow.class);
        arrow.setVelocity(vector);
        arrow.getLocation().setDirection(vector);
        arrow.setKnockbackStrength(0);
        arrow.setBounce(false);
        arrow.setMetadata("daggerthrow", new FixedMetadataValue(JedCore.plugin, "1"));

        if (particles) {
            arrow.setCritical(true);
        }

        arrows.add(arrow);
        endTime = System.currentTimeMillis() + 500;
        bPlayer.addCooldown("DaggerThrowShot", 100);
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    public void setCooldown(long cooldown) {
        this.cooldown = cooldown;
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
        return JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Chi.DaggerThrow.AbilityCollisionRadius");
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
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public boolean isSneakAbility() {
        return false;
    }

    @Override
    public String getAuthor() {
        return JedCore.dev;
    }

    @Override
    public String getVersion() {
        return JedCore.version;
    }

    @Override
    public String getDescription() {
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Chi.DaggerThrow.Description");
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public int getShots() {
        return shots;
    }

    public void setShots(int shots) {
        this.shots = shots;
    }

    public boolean isLimitEnabled() {
        return limitEnabled;
    }

    public void setLimitEnabled(boolean limitEnabled) {
        this.limitEnabled = limitEnabled;
    }

    public int getMaxShots() {
        return maxShots;
    }

    public void setMaxShots(int maxShots) {
        this.maxShots = maxShots;
    }

    public int getHits() {
        return hits;
    }

    public void setHits(int hits) {
        this.hits = hits;
    }

    public List<Arrow> getArrows() {
        return arrows;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Chi.DaggerThrow.Enabled");
    }

    public static class DamageAbility extends DaggerThrow {

        public DamageAbility(Player player) {
            super(player);
            start();
        }

        @Override
        public long getCooldown() {
            return 0;
        }

        @Override
        public Location getLocation() {
            return null;
        }

        @Override
        public String getName() {
            return "DaggerThrow";
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
        public void progress() {
            remove();
        }
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
            this.enabled = JedCoreConfig.getConfig(bPlayer).getBoolean("Abilities.Chi.DaggerThrow.Interactions." + name + ".Enabled", true);
            this.cooldown = JedCoreConfig.getConfig(bPlayer).getLong("Abilities.Chi.DaggerThrow.Interactions." + name + ".Cooldown", 1000);
            this.hitRequirement = JedCoreConfig.getConfig(bPlayer).getInt("Abilities.Chi.DaggerThrow.Interactions." + name + ".HitsRequired", 1);
        }
    }
}
