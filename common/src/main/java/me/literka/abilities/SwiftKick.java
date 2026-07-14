package me.literka.abilities;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.ChiAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.FallHandler;
import me.literka.ChiRework;
import me.literka.util.ParticleEffect;
import me.literka.util.Utils;

public class SwiftKick extends ChiAbility implements AddonAbility {

    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute("BlockChance")
    private double blockChance;
    private long blockDuration;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private double pushSelf;
    private double pushOthers;
    private boolean fallDmg;

    private ParticleEffect effect;
    private LivingEntity target;
    private boolean modernChi;

    public SwiftKick(Player player, final LivingEntity target) {
        super(player);

        damage = ChiRework.config().getDouble("Abilities.SwiftKick.Damage");
        blockChance = ChiRework.config().getDouble("Abilities.SwiftKick.BlockChance");
        blockDuration = ChiRework.config().getLong("Abilities.SwiftKick.BlockDuration");
        cooldown = ChiRework.config().getInt("Abilities.SwiftKick.Cooldown");
        fallDmg = ChiRework.config().getBoolean("Abilities.SwiftKick.FallDamage");
        pushSelf = ChiRework.config().getDouble("Abilities.SwiftKick.Push.Self");
        pushOthers = ChiRework.config().getDouble("Abilities.SwiftKick.Push.Others");
        modernChi = bPlayer.getElements().contains(ChiRework.MODERN_CHI);

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

        if (!ElementalAbility.isAir(player.getLocation().subtract(0, 0.5, 0).getBlock().getType())) {
            remove();
            return;
        }

        Utils.damage(target, damage, this);
        Utils.punchSound(target.getLocation(), true);
        if (pushOthers != 0 && modernChi) GeneralMethods.setVelocity(this, target, new Vector(0, pushOthers, 0));
        if (pushSelf != 0 && modernChi) GeneralMethods.setVelocity(this, player, new Vector(0, pushSelf, 0));

        Vector vector = target.getEyeLocation().toVector().subtract(player.getEyeLocation().toVector());
        Location location = target.getEyeLocation().setDirection(vector);
        double angleX = -80 + Math.random() * 20;
        Utils.spawnCircleParticles(location, effect, location.getYaw(), angleX, 4, 0.1);

        if (target instanceof Player p) {
            if (!fallDmg) FallHandler.stopFall(p);
            if (Utils.willChiBlock(p, blockChance)) Utils.blockChi(p, blockDuration);
        }
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
        return ChiRework.config().getBoolean("Abilities.SwiftKick.Enabled");
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "SwiftKick";
    }

    @Override
    public String getDescription() {
        return ChiRework.config().getString("Language.SwiftKick.Description");
    }

    @Override
    public String getInstructions() {
        return ChiRework.config().getString("Language.SwiftKick.Instructions");
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
