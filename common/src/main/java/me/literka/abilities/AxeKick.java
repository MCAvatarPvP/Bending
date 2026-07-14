package me.literka.abilities;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.inventory.MainHand;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.FallHandler;
import me.literka.ChiRework;
import me.literka.ModernChiAbility;
import me.literka.util.Utils;

import java.util.ArrayList;
import java.util.List;

public class AxeKick extends ModernChiAbility implements AddonAbility {

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private long blockDuration;
    @Attribute("BlockChance")
    private double blockChance;
    private double angle;
    private double angleStep;
    private double length;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    private double radiusH;
    private double radiusV;
    private double pushUpSelf;
    private double pushUpOther;
    private int speed;
    private boolean fallDmg;

    private List<LivingEntity> hitEntities;
    private Location origin;
    private List<Location> locs;
    private int t;

    public AxeKick(Player player) {
        super(player);

        cooldown = ChiRework.config().getLong("Abilities.AxeKick.Cooldown");
        blockDuration = ChiRework.config().getLong("Abilities.AxeKick.BlockDuration");
        blockChance = ChiRework.config().getDouble("Abilities.AxeKick.BlockChance");
        angle = ChiRework.config().getDouble("Abilities.AxeKick.Angle");
        angleStep = ChiRework.config().getDouble("Abilities.AxeKick.AngleStep");
        length = ChiRework.config().getDouble("Abilities.AxeKick.Length");
        damage = ChiRework.config().getDouble("Abilities.AxeKick.Damage");
        radiusH = ChiRework.config().getDouble("Abilities.AxeKick.RadiusHorizontal");
        radiusV = ChiRework.config().getDouble("Abilities.AxeKick.RadiusVertical");
        pushUpSelf = ChiRework.config().getDouble("Abilities.AxeKick.Push.Self");
        pushUpOther = ChiRework.config().getDouble("Abilities.AxeKick.Push.Others");
        speed = ChiRework.config().getInt("Abilities.AxeKick.Speed");
        fallDmg = ChiRework.config().getBoolean("Abilities.AxeKick.FallDamage");

        hitEntities = new ArrayList<>();
        origin = player.getLocation().add(0, 0.75, 0);

        locs = new ArrayList<>();
        boolean isLeft = player.getMainHand() == MainHand.LEFT;
        double halfAngle = angle / 2;
        for (double i = -halfAngle; i <= halfAngle; i += angleStep) {
            double a = Math.toRadians(90 + (isLeft ? i : -i));
            double x = 1 * Math.cos(a);
            double z = 1 * Math.sin(a);
            Vector vec = new Vector(x, 0, z);
            vec.rotateAroundX(Math.toRadians(origin.getPitch()));
            vec.rotateAroundY(Math.toRadians((origin.getYaw()) * -1));
            Location loc = origin.clone().add(vec);
            loc.setDirection(vec);
            locs.add(loc);
        }

        t = 0;

        start();
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }

        for (int i = 0; i < speed; i++) {
            if (t > locs.size() - 1) {
                bPlayer.addCooldown(this);
                remove();
                return;
            }

            Location tLoc = locs.get(t);
            for (double d = 0; d < length; d += 0.5) {
                Location clone = tLoc.clone().add(tLoc.getDirection().multiply(d));
                if (clone.getBlock().isSolid()) break;

                Utils.spawnParticles(clone, 2, 0.15, 0.15, 0.15);

                for (LivingEntity e : clone.getWorld().getNearbyLivingEntities(clone, radiusH, radiusV)) {
                    if (player.getUniqueId() == e.getUniqueId()) continue;
                    if (hitEntities.contains(e)) continue;

                    Utils.damage(e, damage, this);

                    if (hitEntities.isEmpty()) {
                        if (pushUpSelf != 0) GeneralMethods.setVelocity(this, player, new Vector(0, pushUpSelf, 0));
                        Utils.punchSound(e.getLocation(), true);
                    }
                    if (pushUpOther != 0) GeneralMethods.setVelocity(this, e, new Vector(0, pushUpOther, 0));
                    if (e instanceof Player p) {
                        if (!fallDmg) FallHandler.stopFall(p);
                        if (Utils.willChiBlock(p, blockChance)) Utils.blockChi(p, blockDuration);
                    }

                    hitEntities.add(e);
                }
            }

            t++;
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
        return ChiRework.config().getBoolean("Abilities.AxeKick.Enabled");
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "AxeKick";
    }

    @Override
    public String getDescription() {
        return ChiRework.config().getString("Language.AxeKick.Description");
    }

    @Override
    public String getInstructions() {
        return ChiRework.config().getString("Language.AxeKick.Instructions");
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
        return ChiRework.authors;
    }

    @Override
    public String getVersion() {
        return ChiRework.name + " " + ChiRework.version;
    }
}