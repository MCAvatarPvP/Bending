package me.literka.abilities;

import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.ChiAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import me.literka.ChiRework;
import me.literka.util.ParticleEffect;
import me.literka.util.Utils;

public class QuickStrike extends ChiAbility implements AddonAbility {

    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute("BlockChance")
    private double blockChance;
    private long blockDuration;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;

    private ParticleEffect effect;
    private LivingEntity target;

    public QuickStrike(Player player, LivingEntity target) {
        super(player);

        damage = ChiRework.config().getDouble("Abilities.QuickStrike.Damage");
        cooldown = ChiRework.config().getLong("Abilities.QuickStrike.Cooldown");
        blockChance = ChiRework.config().getDouble("Abilities.QuickStrike.BlockChance");
        blockDuration = ChiRework.config().getInt("Abilities.QuickStrike.BlockDuration");

        effect = new ParticleEffect().type(Particle.CRIT).count(1).speed(0.3);
        this.target = target;

        start();
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }

        if (target.isDead() || ((target instanceof Player t) && !t.isOnline())) {
            remove();
            return;
        }

        if (target == null) {
            remove();
            return;
        }

        bPlayer.addCooldown(this);
        Utils.damage(target, damage, this);
        Utils.punchSound(target.getLocation(), false);

        Vector vector = target.getEyeLocation().toVector().subtract(player.getEyeLocation().toVector());
        Location location = target.getEyeLocation().setDirection(vector);
        double angleX = -120 + Math.random() * 20;
        Utils.spawnCircleParticles(location, effect, location.getYaw(), angleX, 4, 0.1);

        if (target instanceof Player p && Utils.willChiBlock(p, blockChance)) {
            Utils.blockChi(p, blockDuration);
        }

        remove();
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
        return ChiRework.config().getBoolean("Abilities.QuickStrike.Enabled");
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "QuickStrike";
    }

    @Override
    public String getDescription() {
        return ChiRework.config().getString("Language.QuickStrike.Description");
    }

    @Override
    public String getInstructions() {
        return ChiRework.config().getString("Language.QuickStrike.Instructions");
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
