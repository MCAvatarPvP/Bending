package me.literka.abilities;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.ChiAbility;
import com.projectkorra.projectkorra.airbending.Suffocate;
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

public class RapidPunch extends ChiAbility implements AddonAbility {

    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute("Hits")
    private int punches;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private int numPunches;
    private long interval;
    private double blockChance;
    private long blockDuration;
    private double horizontalPush;
    private double verticalPush;
    private boolean fallDmg;

    private long last;
    private ParticleEffect effect;
    private LivingEntity target;
    private boolean wasOnGround;
    private boolean oldChi;

    public RapidPunch(Player player, LivingEntity target) {
        super(player);

        damage = ChiRework.config().getDouble("Abilities.RapidPunch.Damage");
        punches = ChiRework.config().getInt("Abilities.RapidPunch.Punches");
        cooldown = ChiRework.config().getLong("Abilities.RapidPunch.Cooldown");
        interval = ChiRework.config().getLong("Abilities.RapidPunch.Interval");
        blockChance = ChiRework.config().getDouble("Abilities.RapidPunch.BlockChance");
        blockDuration = ChiRework.config().getLong("Abilities.RapidPunch.BlockDuration");
        fallDmg = ChiRework.config().getBoolean("Abilities.RapidPunch.FallDamage");
        horizontalPush = ChiRework.config().getDouble("Abilities.RapidPunch.Push.Horizontal");
        verticalPush = ChiRework.config().getDouble("Abilities.RapidPunch.Push.Vertical");
        oldChi = bPlayer.getElements().contains(Element.CHI);

        last = System.currentTimeMillis() - interval;
        effect = new ParticleEffect().type(Particle.WAX_ON).count(1).speed(25);
        this.target = target;
        wasOnGround = Utils.isOnGround(target);

        bPlayer.addCooldown(this);
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

        if (numPunches >= punches) {
            remove();
            return;
        }

        if (System.currentTimeMillis() >= last + interval) {
            Utils.damage(target, damage, this);
            if (!oldChi) {
                Utils.punchSound(target.getLocation(), false);
            }

            if (!wasOnGround && horizontalPush != 0 && verticalPush != 0 && !oldChi) {
                Vector vel = target.getVelocity().setY(0).multiply(horizontalPush).setY(verticalPush);
                double y = (player.getLocation().getY() - target.getLocation().getY()) * 0.1;
                GeneralMethods.setVelocity(this, player, vel);
                GeneralMethods.setVelocity(this, target, vel.setY(vel.getY() + (y > 0 ? y : 0)));
            }

            Vector vector = target.getEyeLocation().toVector().subtract(player.getEyeLocation().toVector());
            Location location = target.getEyeLocation().setDirection(vector);
            double angleX = -120 + Math.random() * 20;
            Utils.spawnCircleParticles(location, effect, location.getYaw(), angleX, 12, 0.1);

            if (target instanceof Player p) {
                if (!fallDmg) FallHandler.stopFall(p);
                if (Utils.willChiBlock(p, blockChance)) {
                    Utils.blockChi(p, blockDuration);
                }
                if (Suffocate.isChannelingSphere(p)) {
                    Suffocate.remove(p);
                }
            }

            target.setNoDamageTicks(0);
            last = System.currentTimeMillis();
            numPunches++;
        }
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
        return ChiRework.config().getBoolean("Abilities.RapidPunch.Enabled");
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "RapidPunch";
    }

    @Override
    public String getDescription() {
        return ChiRework.config().getString("Language.RapidPunch.Description");
    }

    @Override
    public String getInstructions() {
        return ChiRework.config().getString("Language.RapidPunch.Instructions");
    }

    @Override
    public Location getLocation() {
        return target.getLocation();
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
