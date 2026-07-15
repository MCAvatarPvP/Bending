package com.projectkorra.projectkorra.firebending.combo;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.ability.util.ComboUtil;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.firebending.FireJet;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.EntityHitboxProvider;
import com.projectkorra.projectkorra.util.ParticleEffect;

import java.util.ArrayList;
import java.util.List;

public class JetBlaze extends FireAbility implements ComboAbility, EntityHitboxProvider {

    private boolean firstTime;
    private int progressCounter;
    private long time;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute(Attribute.SPEED)
    private double speed;
    @Attribute(Attribute.FIRE_TICK)
    private double fireTicks;
    private Vector direction;
    private ArrayList<LivingEntity> affectedEntities;
    private ArrayList<FireComboStream> tasks;
    @Attribute(Attribute.DURATION)
    private long duration;
    private int firstParticleAmount, secondParticleAmount;

    public JetBlaze(final Player player) {
        super(player);

        if (!this.bPlayer.canBendIgnoreBinds(this)) {
            return;
        }

        this.firstTime = true;
        this.time = System.currentTimeMillis();
        this.affectedEntities = new ArrayList<>();
        this.tasks = new ArrayList<>();

        this.damage = applyModifiersDamage(getConfig().getDouble("Abilities.Fire.JetBlaze.Damage"));
        this.duration = getConfig().getLong("Abilities.Fire.JetBlaze.Duration");
        this.speed = getConfig().getDouble("Abilities.Fire.JetBlaze.Speed");
        this.cooldown = applyModifiersCooldown(getConfig().getLong("Abilities.Fire.JetBlaze.Cooldown"));
        this.fireTicks = getConfig().getDouble("Abilities.Fire.JetBlaze.FireTicks");
        this.firstParticleAmount = getConfig().getInt("Abilities.Fire.JetBlaze.FirstParticleAmount");
        this.secondParticleAmount = getConfig().getInt("Abilities.Fire.JetBlaze.SecondParticleAmount");

        if (this.bPlayer.isAvatarState()) {
            this.cooldown = 0;
            this.damage = getConfig().getDouble("Abilities.Avatar.AvatarState.Fire.JetBlaze.Damage");
            this.fireTicks = getConfig().getDouble("Abilities.Avatar.AvatarState.Fire.JetBlaze.FireTicks");
        }

        this.start();
    }

    @Override
    public Object createNewComboInstance(final Player player) {
        return new JetBlaze(player);
    }

    @Override
    public ArrayList<AbilityInformation> getCombination() {
        return ComboUtil.generateCombinationFromList(this, ConfigManager.defaultConfig.get().getStringList("Abilities.Fire.JetBlaze.Combination"));
    }

    @Override
    public void progress() {
        if (this.firstTime) {
            if (this.bPlayer.isOnCooldown("JetBlaze") && !this.bPlayer.isAvatarState()) {
                this.remove();
                return;
            }
            this.firstTime = false;
        } else if (System.currentTimeMillis() - this.time > this.duration) {
            this.remove();
            return;
        } else if (hasAbility(this.player, FireJet.class)) {
            this.direction = this.player.getVelocity().clone().multiply(-1);
            final FireJet fj = getAbility(this.player, FireJet.class);
            fj.setSpeed(this.speed);
            fj.setDuration(this.duration);
            fj.setParticleAmount(firstParticleAmount);

            final FireComboStream fs = new FireComboStream(this.player, this, this.direction, this.player.getLocation(), 5, 1);
            fs.setDensity(secondParticleAmount);
            fs.setSpread(1.0F);
            fs.setUseNewParticles(true);
            fs.setCollisionRadius(2);
            fs.setParticleEffect(ParticleEffect.SMOKE_NORMAL);
            fs.setDamage(this.damage);
            fs.setFireTicks(this.fireTicks);
            fs.runTaskTimer(ProjectKorra.plugin, 0, 1L);
            this.tasks.add(fs);
            if (this.progressCounter % 4 == 0) {
                this.player.getWorld().playSound(this.player.getLocation(), Sound.ENTITY_CREEPER_PRIMED, 1, 0F);
            }
        }
    }

    @Override
    public void remove() {
        for (final FireComboStream task : this.tasks) {
            task.remove();
        }
        super.remove();
        this.bPlayer.addCooldown("JetBlaze", this.cooldown);
    }

    @Override
    public boolean isSneakAbility() {
        return false;
    }

    @Override
    public long getCooldown() {
        return this.cooldown;
    }

    @Override
    public String getName() {
        return "JetBlaze";
    }

    @Override
    public Location getLocation() {
        return this.player.getLocation();
    }

    @Override
    public List<Location> getEntityHitLocations() {
        final List<Location> locations = new ArrayList<>();
        for (final FireComboStream task : this.tasks) {
            if (!task.isCancelled() && task.getLocation() != null) {
                locations.add(task.getLocation().clone());
            }
        }
        return locations;
    }

    @Override
    public double getEntityHitRadius() {
        return 2D;
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    public ArrayList<LivingEntity> getAffectedEntities() {
        return this.affectedEntities;
    }
}
