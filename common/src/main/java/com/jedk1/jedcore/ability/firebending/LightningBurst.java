package com.jedk1.jedcore.ability.firebending;

import com.jedk1.jedcore.JCMethods;
import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.LightningAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.firebending.FireShield;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class LightningBurst extends LightningAbility implements AddonAbility {

    private static final ConcurrentHashMap<Integer, Bolt> BOLTS = new ConcurrentHashMap<>();
    private static int ID = Integer.MIN_VALUE;
    private final Random chargingRandom;
    private int nextVisualBoltOrdinal;
    private int nextDamageBoltOrdinal;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown, avatarCooldown;
    @Attribute(Attribute.CHARGE_DURATION)
    private long chargeUp, avatarChargeup;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute(Attribute.RADIUS)
    private double radius;
    private boolean canSwapSlots;
    private boolean allowWhenFireShield;
    private boolean charged;

    public LightningBurst(Player player) {
        super(player);
        this.chargingRandom = PredictionDeterminism.random(player.getUniqueId(), getClass().getName() + ":charging");
        if (!bPlayer.canBend(this) || hasAbility(player, LightningBurst.class)) {
            return;
        }

        setFields();
        if (bPlayer.isAvatarState() || JCMethods.isSozinsComet(player.getWorld())) {
            chargeUp = avatarChargeup;
            cooldown = avatarCooldown;
        }

        start();
    }

    public static void progressAll() {
        BOLTS.values().forEach(Bolt::progress);
    }

    public void setFields() {
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Fire.LightningBurst.Cooldown");
        chargeUp = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Fire.LightningBurst.ChargeUp");
        avatarCooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Fire.LightningBurst.AvatarCooldown");
        avatarChargeup = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Fire.LightningBurst.AvatarChargeUp");
        damage = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Fire.LightningBurst.Damage");
        radius = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Fire.LightningBurst.Radius");
        canSwapSlots = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Fire.LightningBurst.CanSwapSlots");
        allowWhenFireShield = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Fire.LightningBurst.AllowWhenFireShield");
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }
        if (!bPlayer.canBendIgnoreBindsCooldowns(this)) {
            remove();
            return;
        }
        String name = bPlayer.getBoundAbilityName();
        if (!(name.equalsIgnoreCase(getName()) || canSwapSlots && name.equalsIgnoreCase("FireJet"))) {
            remove();
            return;
        }
        if (!allowWhenFireShield && CoreAbility.hasAbility(player, FireShield.class)) {
            remove();
            return;
        }
        if (RegionProtection.isRegionProtected(player, player.getLocation(), this)) {
            remove();
            return;
        }
        if (!player.isSneaking()) {
            if (!isCharging()) {
                Location fake = player.getLocation().add(0, -2, 0);
                fake.setPitch(0);
                for (int i = -180; i < 180; i += 55) {
                    fake.setYaw(i);
                    for (double j = -180; j <= 180; j += 55) {
                        Location temp = fake.clone();
                        Vector dir = fake.getDirection().clone().multiply(2 * Math.cos(Math.toRadians(j)));
                        temp.add(dir);
                        temp.setY(temp.getY() + 2 + (2 * Math.sin(Math.toRadians(j))));
                        dir = GeneralMethods.getDirection(player.getLocation().add(0, 0, 0), temp);
                        spawnBolt(player.getLocation().clone().add(0, 1, 0).setDirection(dir), radius, 1, 20, true);
                    }
                }
                bPlayer.addCooldown(this);
            }
            remove();
        } else if (System.currentTimeMillis() > getStartTime() + chargeUp) {
            setCharging(false);
            displayCharging();
        }
    }

    private void spawnBolt(Location location, double max, double gap, int arc, boolean doDamage) {
        int id = ID;
        BOLTS.put(id, new Bolt(this, location, id, max, gap, arc, doDamage));
        if (ID == Integer.MAX_VALUE)
            ID = Integer.MIN_VALUE;
        ID++;
    }

    private void displayCharging() {
        Location fake = player.getLocation().add(0, 0, 0);
        fake.setPitch(0);
        for (int i = -180; i < 180; i += 55) {
            fake.setYaw(i);
            for (double j = -180; j <= 180; j += 55) {
                if (this.chargingRandom.nextInt(100) == 0) {
                    Location temp = fake.clone();
                    Vector dir = fake.getDirection().clone().multiply(1.2 * Math.cos(Math.toRadians(j)));
                    temp.add(dir);
                    temp.setY(temp.getY() + 1.2 + (1.2 * Math.sin(Math.toRadians(j))));
                    dir = GeneralMethods.getDirection(temp, player.getLocation().add(0, 1, 0));
                    spawnBolt(temp.setDirection(dir), 1, 0.2, 20, false);
                }
            }
        }
    }

    public boolean isCharging() {
        return !charged;
    }

    public void setCharging(boolean charging) {
        this.charged = !charging;
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
    public String getName() {
        return "LightningBurst";
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
    public String getAuthor() {
        return JedCore.dev;
    }

    @Override
    public String getVersion() {
        return JedCore.version;
    }

    @Override
    public String getDescription() {
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Fire.LightningBurst.Description");
    }

    public long getAvatarCooldown() {
        return avatarCooldown;
    }

    public void setAvatarCooldown(long avatarCooldown) {
        this.avatarCooldown = avatarCooldown;
    }

    public long getChargeUp() {
        return chargeUp;
    }

    public void setChargeUp(long chargeUp) {
        this.chargeUp = chargeUp;
    }

    public long getAvatarChargeup() {
        return avatarChargeup;
    }

    public void setAvatarChargeup(long avatarChargeup) {
        this.avatarChargeup = avatarChargeup;
    }

    public double getDamage() {
        return damage;
    }

    public void setDamage(double damage) {
        this.damage = damage;
    }

    public double getRadius() {
        return radius;
    }

    public void setRadius(double radius) {
        this.radius = radius;
    }

    public boolean isCharged() {
        return charged;
    }

    public void setCharged(boolean charged) {
        this.charged = charged;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Fire.LightningBurst.Enabled");
    }

    public class Bolt {

        private final LightningBurst ability;
        private final float initYaw;
        private final float initPitch;
        private final double max;
        private final double gap;
        private final int id;
        private final int arc;
        private final boolean doDamage;
        private final Random random;
        private Location location;
        private double step;

        public Bolt(LightningBurst ability, Location location, int id, double max, double gap, int arc, boolean doDamage) {
            this.ability = ability;
            this.location = location;
            this.id = id;
            this.max = max;
            this.arc = arc;
            this.gap = gap;
            this.doDamage = doDamage;
            final int ordinal = doDamage
                    ? LightningBurst.this.nextDamageBoltOrdinal++
                    : LightningBurst.this.nextVisualBoltOrdinal++;
            this.random = PredictionDeterminism.random(
                    player.getUniqueId(),
                    LightningBurst.this.getClass().getName()
                            + (doDamage ? ":damage-bolt:" : ":visual-bolt:") + ordinal,
                    LightningBurst.this.getPredictionDeterministicSeed());
            initYaw = location.getYaw();
            initPitch = location.getPitch();
        }

        private void progress() {
            if (this.step >= max) {
                BOLTS.remove(id);
                return;
            }
            if (RegionProtection.isRegionProtected(player, location, LightningBurst.this) || !isTransparent(location.getBlock())) {
                BOLTS.remove(id);
                return;
            }
            double step = 0.2;
            for (double i = 0; i < gap; i += step) {
                this.step += step;
                location = location.add(location.getDirection().clone().multiply(step));

                playLightningbendingParticle(location, 0f, 0f, 0f);
            }
            switch (this.random.nextInt(3)) {
                case 0:
                    location.setYaw(initYaw - arc);
                    break;
                case 1:
                    location.setYaw(initYaw + arc);
                    break;
                default:
                    location.setYaw(initYaw);
                    break;
            }
            switch (this.random.nextInt(3)) {
                case 0:
                    location.setPitch(initPitch - arc);
                    break;
                case 1:
                    location.setPitch(initPitch + arc);
                    break;
                default:
                    location.setPitch(initPitch);
                    break;
            }

            if (this.random.nextInt(3) == 0) {
                location.getWorld().playSound(location, Sound.ENTITY_CREEPER_PRIMED, 1, 0);
            }

            for (Entity entity : GeneralMethods.getEntitiesAroundPoint(location, 2)) {
                if (entity instanceof LivingEntity && entity.getEntityId() != player.getEntityId() && doDamage) {
                    DamageHandler.damageEntity(entity, damage, ability);
                }
            }
        }

        public LightningBurst getAbility() {
            return ability;
        }

        public Location getLocation() {
            return location;
        }

        public float getInitYaw() {
            return initYaw;
        }

        public float getInitPitch() {
            return initPitch;
        }

        public double getStep() {
            return step;
        }

        public double getMax() {
            return max;
        }

        public double getGap() {
            return gap;
        }

        public int getId() {
            return id;
        }

        public int getArc() {
            return arc;
        }

        public boolean isDoDamage() {
            return doDamage;
        }
    }
}
