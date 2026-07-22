package me.simplicitee.project.addons.ability.earth;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.ParticleEffect;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import me.simplicitee.project.addons.ProjectAddons;

import java.util.*;

public class Accretion extends EarthAbility implements AddonAbility {

    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute("Blocks")
    private int blocks;
    @Attribute(Attribute.SELECT_RANGE)
    private int selectRange;
    @Attribute("RevertTime")
    private long revertTime;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.SPEED)
    private double throwSpeed;
    private int maxAmplifier;

    private Map<FallingBlock, TempFallingBlock> tracker;
    private Set<TempBlock> temps;
    private boolean shot;
    private final Random gameplayRandom;

    public Accretion(Player player) {
        super(player);
        this.gameplayRandom = PredictionDeterminism.random(player == null ? null : player.getUniqueId(),
                getClass().getName() + ":source-blocks");

        if (bPlayer.isOnCooldown(this)) {
            return;
        }

        if (!isEarthbendable(player.getLocation().getBlock().getRelative(BlockFace.DOWN))) {
            return;
        }

        if (hasAbility(player, Accretion.class)) {
            return;
        }

        if (GeneralMethods.isRegionProtectedFromBuild(this, player.getLocation())) {
            return;
        }

        this.shot = false;
        this.damage = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Earth.Accretion.Damage");
        this.blocks = ProjectAddons.instance.getConfig(bPlayer).getInt("Abilities.Earth.Accretion.Blocks");
        this.selectRange = ProjectAddons.instance.getConfig(bPlayer).getInt("Abilities.Earth.Accretion.SelectRange");
        this.revertTime = ProjectAddons.instance.getConfig(bPlayer).getLong("Abilities.Earth.Accretion.RevertTime");
        this.cooldown = ProjectAddons.instance.getConfig(bPlayer).getLong("Abilities.Earth.Accretion.Cooldown");
        this.throwSpeed = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Earth.Accretion.ThrowSpeed");
        this.maxAmplifier = ProjectAddons.instance.getConfig(bPlayer).getInt("Abilities.Earth.Accretion.MaxAmplifier");

        this.tracker = new HashMap<>();
        this.temps = new HashSet<>();

        List<Location> list = GeneralMethods.getCircle(player.getLocation(), selectRange, 1, false, false, 0);

        for (int i = 0; i < list.size(); i++) {
            Block b = GeneralMethods.getTopBlock(list.get(this.gameplayRandom.nextInt(list.size())), 2);

            if (!isAir(b.getRelative(BlockFace.UP).getType())) {
                continue;
            }

            if (TempBlock.isTempBlock(b)) {
                continue;
            }

            if (!isEarthbendable(b.getType(), true, true, false)) {
                continue;
            }

            BlockData data = b.getType().createBlockData();
            temps.add(new TempBlock(b, Material.AIR));
            Location location = b.getLocation().add(0.5, 0.5, 0.5);
            Vector velocity = new Vector(0, 0.8, 0);
            TempFallingBlock fb = new TempFallingBlock(location, data, velocity, this);
            fb.setOnPlace(ignored -> blockCollision(fb.getFallingBlock(), fb.getLocation().getBlock()));
            tracker.put(fb.getFallingBlock(), fb);

            if (temps.size() == blocks) {
                break;
            }
        }

        playEarthbendingSound(player.getLocation());

        start();
    }

    @Override
    public void progress() {
        if (!player.isOnline() || player.isDead()) {
            remove();
            return;
        }

        Iterator<Map.Entry<FallingBlock, TempFallingBlock>> iter = tracker.entrySet().iterator();
        loop:
        while (iter.hasNext()) {
            TempFallingBlock fb = iter.next().getValue();
            ParticleEffect.BLOCK_CRACK.display(fb.getLocation(), 1, 0.1, 0.1, 0.1, fb.getData());

            if (shot) {
                for (Entity e : GeneralMethods.getEntitiesAroundPoint(fb.getLocation(), 1)) {
                    if (e instanceof LivingEntity && e.getEntityId() != player.getEntityId()) {
                        entityCollision(fb, (LivingEntity) e);
                        iter.remove();
                        continue loop;
                    }
                }
            }
        }

        if (tracker.isEmpty()) {
            remove();
            return;
        }

        if (System.currentTimeMillis() - getStartTime() >= 6000) {
            for (TempFallingBlock fb : tracker.values()) {
                fb.remove();
            }
            remove();
            return;
        }
    }

    @Override
    public void remove() {
        super.remove();
        tracker.clear();

        for (TempBlock tb : temps) {
            if (tb.getBlockData().getMaterial() == Material.AIR) {
                tb.revertBlock();
            }
        }

        temps.clear();
    }

    public void entityCollision(TempFallingBlock fb, LivingEntity entity) {
        int duration = 20;
        int amp = 1;
        if (entity.hasPotionEffect(PotionEffectType.SLOWNESS)) {
            PotionEffect effect = entity.getPotionEffect(PotionEffectType.SLOWNESS);

            duration += effect.getDuration();
            amp += effect.getAmplifier();

            entity.removePotionEffect(PotionEffectType.SLOWNESS);
        }

        if (amp > maxAmplifier) amp = maxAmplifier;

        entity.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, duration, amp, true, false));
        DamageHandler.damageEntity(entity, damage, this);
        fb.remove();
    }

    public void blockCollision(FallingBlock fb, Block block) {
        TempBlock tb;
        if (TempBlock.isTempBlock(block)) {
            tb = TempBlock.get(block);
            tb.setType(fb.getBlockData());
        } else {
            tb = new TempBlock(block, fb.getBlockData().getMaterial());
        }

        tb.setRevertTime(revertTime);

        temps.add(tb);
        tracker.remove(fb);
        fb.remove();
    }

    public void shoot() {
        if (shot) {
            return;
        }

        if (tracker.isEmpty()) {
            remove();
            return;
        }

        for (TempFallingBlock fb : tracker.values()) {
            Location target = null;
            Entity e = GeneralMethods.getTargetedEntity(player, 30);

            if (e != null) {
                target = e.getLocation();
            } else {
                target = GeneralMethods.getTargetedLocation(player, 30);
            }

            Vector velocity = GeneralMethods.getDirection(fb.getLocation(), target).add(new Vector(0, 0.185, 0)).normalize().multiply(throwSpeed);
            fb.getFallingBlock().setVelocity(velocity);
            playEarthbendingSound(fb.getLocation());
        }

        bPlayer.addCooldown(this);
        shot = true;
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
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "Accretion";
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
        return "Simplicitee";
    }

    @Override
    public String getVersion() {
        return ProjectAddons.instance.version();
    }

    @Override
    public boolean isEnabled() {
        return ProjectAddons.instance.getConfig(bPlayer).getBoolean("Abilities.Earth.Accretion.Enabled");
    }

    @Override
    public String getDescription() {
        return "Slam the earth to send blocks into the air, then shoot them all towards a single point! They will build up on an enemy, damaging and slowing them down! Each block that hits adds 1 second and level of slowness.";
    }

    @Override
    public String getInstructions() {
        return "Sneak to rise blocks, Left Click before they land to shoot!";
    }
}
