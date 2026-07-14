package me.literka.abilities;

import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.MovementHandler;
import me.literka.ChiRework;
import me.literka.ModernChiAbility;
import me.literka.util.ParticleEffect;
import me.literka.util.Utils;

import java.util.ArrayList;
import java.util.List;

public class Headbutt extends ModernChiAbility implements AddonAbility {

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private int speed;
    private double selfDamage;
    private double otherDamage;
    private double selfBlockChance;
    private double otherBlockChance;
    private long selfBlockDuration;
    private long otherBlockDuration;
    private long selfStunDuration;
    private long otherStunDuration;
    private String selfMessage;
    private String otherMessage;

    private LivingEntity target;
    private List<Location> locs;
    private int currLoc;

    public Headbutt(Player player, LivingEntity target) {
        super(player);

        if (!player.isSneaking()) return;

        cooldown = ChiRework.config().getLong("Abilities.Headbutt.Cooldown");
        speed = ChiRework.config().getInt("Abilities.Headbutt.Speed");
        selfDamage = ChiRework.config().getDouble("Abilities.Headbutt.Damage.Self");
        otherDamage = ChiRework.config().getDouble("Abilities.Headbutt.Damage.Others");
        selfBlockChance = ChiRework.config().getDouble("Abilities.Headbutt.BlockChance.Self");
        otherBlockChance = ChiRework.config().getDouble("Abilities.Headbutt.BlockChance.Others");
        selfBlockDuration = ChiRework.config().getLong("Abilities.Headbutt.BlockDuration.Self");
        otherBlockDuration = ChiRework.config().getLong("Abilities.Headbutt.BlockDuration.Others");
        selfStunDuration = ChiRework.config().getLong("Abilities.Headbutt.Stun.Self");
        otherStunDuration = ChiRework.config().getLong("Abilities.Headbutt.Stun.Others");
        selfMessage = Utils.translateColors(ChiRework.config().getString("Abilities.Headbutt.Message.Self"));
        otherMessage = Utils.translateColors(ChiRework.config().getString("Abilities.Headbutt.Message.Others"));

        this.target = target;
        this.locs = getLine(player.getEyeLocation(), target.getEyeLocation());
        this.currLoc = 0;

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

        for (int i = 0; i < speed; i++) {
            Location location = locs.get(currLoc);
            Utils.spawnParticles(location, 16, 0.175, 0.175, 0.175);
            currLoc++;

            if (currLoc >= locs.size()) {
                Utils.damage(player, selfDamage, this);
                Utils.damage(target, otherDamage, this);
                Utils.punchSound(player.getLocation(), true);
                Utils.punchSound(target.getLocation(), true);
                new MovementHandler(player, this).stopWithDuration(selfStunDuration, selfMessage);
                new MovementHandler(target, this).stopWithDuration(otherStunDuration, otherMessage);
                if (Utils.willChiBlock(player, selfBlockChance))
                    Utils.blockChi(player, selfBlockDuration);
                if (target instanceof Player p && Utils.willChiBlock(p, otherBlockChance))
                    Utils.blockChi(p, otherBlockDuration);

                Location targetLoc = target.getEyeLocation().add(0, -0.2, 0);
                targetLoc.add(targetLoc.getDirection().multiply(0.3));
                ParticleEffect effect = new ParticleEffect().count(1).type(Particle.CRIT).speed(speed);

                Vector vector = target.getEyeLocation().toVector().subtract(player.getEyeLocation().toVector());
                targetLoc.setDirection(vector);
                Utils.spawnCircleParticles(targetLoc, effect, targetLoc.getYaw(), targetLoc.getPitch() - 75, 12, 0.1);
                remove();
                break;
            }
        }
    }

    private List<Location> getLine(Location first, Location second) {
        List<Location> locs = new ArrayList<>();
        Vector vector = second.toVector().subtract(first.toVector()).normalize();

        for (int i = 0; i < first.distance(second); i++) {
            locs.add(first.clone().add(vector.clone().multiply(i)));
        }

        return locs;
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
        return ChiRework.config().getBoolean("Abilities.Headbutt.Enabled");
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "Headbutt";
    }

    @Override
    public String getDescription() {
        return ChiRework.config().getString("Language.Headbutt.Description");
    }

    @Override
    public String getInstructions() {
        return ChiRework.config().getString("Language.Headbutt.Instructions");
    }

    @Override
    public Location getLocation() {
        return null;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public String getAuthor() {
        return ChiRework.authors + ", __Skye (Concept)";
    }

    @Override
    public String getVersion() {
        return ChiRework.name + " " + ChiRework.version;
    }
}
