package me.literka.abilities;

import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageByEntityEvent;
import com.projectkorra.projectkorra.util.MovementHandler;
import me.literka.ChiRework;
import me.literka.ModernChiAbility;
import me.literka.util.ParticleEffect;
import me.literka.util.Utils;

public class Counter extends ModernChiAbility implements AddonAbility {

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.DURATION)
    private long duration;
    private long stunDuration;
    private String activeMessage;
    private String hitMessage;

    public Counter(Player player) {
        super(player);

        cooldown = ChiRework.config().getLong("Abilities.Counter.Cooldown");
        duration = ChiRework.config().getLong("Abilities.Counter.Duration");
        stunDuration = ChiRework.config().getLong("Abilities.Counter.StunDuration");
        activeMessage = ChiRework.config().getString("Abilities.Counter.ActiveMessage");
        hitMessage = Utils.translateColors(ChiRework.config().getString("Abilities.Counter.HitMessage"));

        start();
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            Utils.sendActionBar("", player);
            remove();
            return;
        }

        if (!bPlayer.canBendIgnoreBindsCooldowns(this) || !bPlayer.getBoundAbilityName().equalsIgnoreCase("Evade")) {
            bPlayer.addCooldown("Counter", cooldown);
            remove();
            return;
        }

        if (System.currentTimeMillis() > getStartTime() + duration) {
            Utils.sendActionBar("", player);
            bPlayer.addCooldown("Counter", cooldown);
            remove();
            return;
        }

        Utils.sendActionBar(activeMessage, player);
    }

    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof LivingEntity le)) return;
        Location loc = player.getEyeLocation();
        ParticleEffect effect = new ParticleEffect().type(Particle.POOF)
                .count(2)
                .offset(0.1, 0.1, 0.1)
                .speed(0);
        Utils.spawnCircleParticles(loc, effect, 0, 0, 15, 0.8);
        loc.getWorld().playSound(loc, Sound.ITEM_TOTEM_USE, 0.15f, 1.5f);
        new MovementHandler(le, this).stopWithDuration(stunDuration, hitMessage);
        bPlayer.addCooldown("Counter", cooldown);
        remove();
        event.setCancelled(true);
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
    public boolean isEnabled() {
        return ChiRework.config().getBoolean("Abilities.Counter.Enabled");
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "Evade";
    }

    @Override
    public String getDescription() {
        return ChiRework.config().getString("Language.Counter.Description");
    }

    @Override
    public String getInstructions() {
        return ChiRework.config().getString("Language.Counter.Instructions");
    }

    @Override
    public Location getLocation() {
        return player.getLocation();
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