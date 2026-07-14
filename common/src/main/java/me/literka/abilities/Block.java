package me.literka.abilities;

import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.event.AbilityDamageEntityEvent;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import me.literka.ChiRework;
import me.literka.ModernChiAbility;
import me.literka.util.ParticleEffect;
import me.literka.util.Utils;

import java.util.List;

public class Block extends ModernChiAbility implements AddonAbility {

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.DURATION)
    private long duration;
    private double damageMultiplier;
    private List<String> cannotBlock;

    public Block(Player player) {
        super(player);

        cooldown = ChiRework.config().getLong("Abilities.Block.Cooldown");
        duration = ChiRework.config().getLong("Abilities.Block.Duration");
        damageMultiplier = ChiRework.config().getDouble("Abilities.Block.DamageMultiplier");
        cannotBlock = ChiRework.config().getStringList("Abilities.Block.CannotBlock");

        start();
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }

        if (!player.isSneaking()) {
            bPlayer.addCooldown(this);
            remove();
            return;
        }

        if (!bPlayer.canBendIgnoreCooldowns(this)) {
            bPlayer.addCooldown(this);
            remove();
            return;
        }

        if (duration != 0 && System.currentTimeMillis() > getStartTime() + duration) {
            bPlayer.addCooldown(this);
            remove();
        }
    }

    public void onAbilityHit(AbilityDamageEntityEvent event) {
        if (cannotBlock.contains(event.getAbility().getClass().getSimpleName())) return;

        Location loc = player.getEyeLocation();
        ParticleEffect effect = new ParticleEffect().type(Particle.POOF)
                .count(2)
                .offset(0.1, 0.1, 0.1)
                .speed(0);
        Utils.spawnCircleParticles(loc, effect, 0, 0, 15, 0.8);
        loc.getWorld().playSound(loc, Sound.ITEM_TOTEM_USE, 0.15f, 1.5f);
        event.setDamage(event.getDamage() * damageMultiplier);
        bPlayer.addCooldown(this);
        remove();
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
        return ChiRework.config().getBoolean("Abilities.Block.Enabled");
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "Block";
    }

    @Override
    public String getDescription() {
        return ChiRework.config().getString("Language.Block.Description");
    }

    @Override
    public String getInstructions() {
        return ChiRework.config().getString("Language.Block.Instructions");
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
