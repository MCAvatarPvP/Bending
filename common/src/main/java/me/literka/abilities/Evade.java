package me.literka.abilities;

import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.ChiAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.event.AbilityDamageEntityEvent;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import me.literka.ChiRework;
import me.literka.util.ParticleEffect;
import me.literka.util.Utils;

import java.util.ArrayList;
import java.util.List;

public class Evade extends ChiAbility implements AddonAbility {

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.DURATION)
    private long maxDuration;
    private String successMessage;
    private String failMessage;

    public Evade(Player player) {
        super(player);

        Utils.sendActionBar(ChiRework.config().getString("Abilities.Evade.WarningMessage"), player);
        cooldown = ChiRework.config().getLong("Abilities.Evade.Cooldown");
        maxDuration = ChiRework.config().getLong("Abilities.Evade.MaxDuration");
        successMessage = ChiRework.config().getString("Abilities.Evade.SuccessMessage");
        failMessage = ChiRework.config().getString("Abilities.Evade.FailMessage");

        start();
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }

        if (!bPlayer.canBendIgnoreCooldowns(this)) {
            bPlayer.addCooldown(this);
            remove();
            return;
        }

        if (maxDuration != 0 && System.currentTimeMillis() > getStartTime() + maxDuration) {
            Utils.sendActionBar(failMessage, player);
            bPlayer.addCooldown(this);
            remove();
        }
    }

    public void onAbilityHit(AbilityDamageEntityEvent event) {
        List<String> list = new ArrayList<>();
        for (String s : ChiRework.config().getStringList("Abilities.Evade.IgnoreAbilities")) {
            list.add(s.toLowerCase());
        }
        if (list.contains(event.getAbility().getClass().getSimpleName().toLowerCase())) return;

        Utils.sendActionBar(successMessage, player);
        Location location = player.getEyeLocation();
        ParticleEffect effect = new ParticleEffect().type(Particle.POOF)
                .count(2)
                .offset(0.1, 0.1, 0.1)
                .speed(0);
        Utils.spawnCircleParticles(location, effect, 0, 0, 15, 0.8);
        location.getWorld().playSound(location, Sound.ITEM_TOTEM_USE, 0.15f, 1.5f);
        event.setCancelled(true);
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
        return ChiRework.config().getBoolean("Abilities.Evade.Enabled");
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
        return ChiRework.config().getString("Language.Evade.Description");
    }

    @Override
    public String getInstructions() {
        return ChiRework.config().getString("Language.Evade.Instructions");
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

    @Override
    public boolean isModern() {
        return true;
    }
}