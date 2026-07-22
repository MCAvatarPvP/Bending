package me.simplicitee.project.addons.ability.earth;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.LavaAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import me.simplicitee.project.addons.ProjectAddons;
import me.simplicitee.project.addons.util.AnimationBuilder;

import java.util.*;

public class LavaSurge extends LavaAbility implements AddonAbility {

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute("Burn")
    private boolean burn;
    @Attribute("BurnDuration")
    private long burnTime;
    @Attribute(Attribute.SPEED)
    private double speed;
    @Attribute("SourceRadius")
    private double sourceRadius;
    @Attribute(Attribute.SELECT_RANGE)
    private int selectRange;
    @Attribute("Blocks")
    private int maxBlocks;
    private double magmaRadius;
    private long magmaDuration;
    private long shootDelay;
    private boolean normalBehavior;
    private long duration;

    private int shotBlocks;
    private Location sourceCenter;
    private Map<Block, Boolean> source;
    private boolean shot, launchedAll;
    private Vector direction;
    private Set<FallingBlock> blocks;
    private Map<FallingBlock, Long> timeLived;
    private long time;
    private final Random gameplayRandom;

    public LavaSurge(Player player) {
        super(player);
        this.gameplayRandom = PredictionDeterminism.random(player == null ? null : player.getUniqueId(),
                getClass().getName() + ":falling-block-velocity");

        this.normalBehavior = ProjectAddons.instance.getConfig(bPlayer).getBoolean("Abilities.Earth.LavaSurge.NormalBehavior");
        if (hasAbility(player, LavaSurge.class)) {
            LavaSurge abil = getAbility(player, LavaSurge.class);
            if (!abil.hasShot() && (bPlayer.isOnCooldown(this) && !normalBehavior)) {
                abil.retargetSource();
            }
            return;
        }

        this.cooldown = ProjectAddons.instance.getConfig(bPlayer).getLong("Abilities.Earth.LavaSurge.Cooldown");
        this.damage = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Earth.LavaSurge.Damage");
        this.burn = ProjectAddons.instance.getConfig(bPlayer).getBoolean("Abilities.Earth.LavaSurge.Burn.Enabled");
        this.burnTime = ProjectAddons.instance.getConfig(bPlayer).getLong("Abilities.Earth.LavaSurge.Burn.Duration");
        this.speed = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Earth.LavaSurge.Speed");
        this.sourceRadius = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Earth.LavaSurge.SourceRadius");
        this.selectRange = ProjectAddons.instance.getConfig(bPlayer).getInt("Abilities.Earth.LavaSurge.SelectRange");
        this.maxBlocks = ProjectAddons.instance.getConfig(bPlayer).getInt("Abilities.Earth.LavaSurge.MaxBlocks");
        this.magmaRadius = ProjectAddons.instance.getConfig(bPlayer).getLong("Abilities.Earth.LavaSurge.MagmaRadius");
        this.magmaDuration = ProjectAddons.instance.getConfig(bPlayer).getLong("Abilities.Earth.LavaSurge.MagmaDuration");
        this.shootDelay = ProjectAddons.instance.getConfig(bPlayer).getLong("Abilities.Earth.LavaSurge.ShootDelay");
        this.duration = ProjectAddons.instance.getConfig(bPlayer).getLong("Abilities.Earth.LavaSurge.Duration");
        this.shot = false;
        this.shotBlocks = 0;
        this.launchedAll = false;
        this.blocks = new HashSet<>();
        this.timeLived = new HashMap<>();
        time = System.currentTimeMillis() + duration;

        if (prepare()) {
            start();
        }
    }

    private boolean prepare() {
        Block b = player.getTargetBlock(getTransparentMaterialSet(), selectRange);
        this.sourceCenter = b.getLocation().clone().add(0.5, 0.5, 0.5);

        Set<Block> total = new HashSet<>();
        int count = 0;

        for (double i = 1; i <= sourceRadius; i += 0.5) {
            if (count >= maxBlocks) {
                break;
            }

            List<Block> list = GeneralMethods.getBlocksAroundPoint(sourceCenter, i);
            Iterator<Block> iter = list.iterator();

            while (iter.hasNext()) {
                Block b2 = iter.next();
                if (total.contains(b2)) {
                    iter.remove();
                } else if (!isEarthbendable(b2.getType(), bPlayer.canMetalbend(), true, true)) {
                    iter.remove();
                } else if (count >= maxBlocks) {
                    iter.remove();
                } else {
                    count++;
                }
            }

            total.addAll(list);
        }

        if (total.isEmpty()) {
            return false;
        }

        this.source = new HashMap<>();
        for (Block block : total) {
            source.put(block, false);
            if (magmaDuration != 0) {
                new AnimationBuilder(block)
                        .effect(Particle.LAVA)
                        .addStep(Material.MAGMA_BLOCK, magmaDuration)
                        .addDestroyTask(() -> {
                            new TempBlock(block, GeneralMethods.getLavaData(0));
                            source.put(block, true);
                        })
                        .start();
            } else {
                new AnimationBuilder(block)
                        .effect(Particle.LAVA)
                        .addStep(GeneralMethods.getLavaData(0), shootDelay)
                        .addDestroyTask(() -> {
                            new TempBlock(block, GeneralMethods.getLavaData(0));
                            source.put(block, true);
                        })
                        .start();
            }
        }

        if (!normalBehavior) bPlayer.addCooldown(this);
        return true;
    }

    @Override
    public void progress() {
        if (!player.isOnline() || player.isDead()) {
            remove();
            return;
        }

        if (System.currentTimeMillis() >= time && duration != 0) {
            remove();
            return;
        }

        if (shot) {
            if (!launchedAll && shotBlocks < maxBlocks) {
                Vector v = direction.clone().add(new Vector(randomOffset(), 0.07, randomOffset())).normalize().multiply(speed);
                TempFallingBlock tfb = new TempFallingBlock(sourceCenter.clone().add(0, 0.7, 0), Material.MAGMA_BLOCK.createBlockData(), v, this, true);
                FallingBlock fb = tfb.getFallingBlock();
                tfb.setOnPlace(ignored -> removeBlock(fb));

                blocks.add(fb);
                timeLived.put(fb, System.currentTimeMillis());
                shotBlocks++;
            }

            if (shotBlocks >= maxBlocks) {
                launchedAll = true;
            }

            Iterator<FallingBlock> iter = blocks.iterator();

            while (iter.hasNext()) {
                FallingBlock fb = iter.next();
                if (fb.isDead()) {
                    iter.remove();
                    continue;
                }

                if (timeLived.containsKey(fb)) {
                    if (timeLived.get(fb) + 4000 <= System.currentTimeMillis()) {
                        iter.remove();
                        fb.remove();
                        continue;
                    }
                }

                for (Entity e : GeneralMethods.getEntitiesAroundPoint(fb.getLocation(), magmaRadius)) {
                    if (e instanceof LivingEntity) {
                        DamageHandler.damageEntity(e, damage, this);
                        ((LivingEntity) e).setNoDamageTicks(0);

                        if (burn) {
                            ((LivingEntity) e).setFireTicks((int) (burnTime / 1000 * 20));
                        }

                        iter.remove();
                        fb.remove();
                        timeLived.remove(fb);
                    }
                }
            }

            if (blocks.isEmpty()) {
                remove();
                return;
            }
        }
    }

    @Override
    public void remove() {
        super.remove();
        for (Block b : source.keySet()) {
            TempBlock tb = null;

            if (TempBlock.isTempBlock(b)) {
                tb = TempBlock.get(b);
                tb.setType(Material.AIR);
            } else {
                tb = new TempBlock(b, Material.AIR);
            }

            tb.setRevertTime(3000);
        }
        bPlayer.addCooldown(this);
        source.clear();
        blocks.clear();
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
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "LavaSurge";
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
        return "Simplicitee";
    }

    @Override
    public String getVersion() {
        return ProjectAddons.instance.version();
    }

    public void retargetSource() {
        for (Block b : source.keySet()) {
            if (TempBlock.isTempBlock(b)) {
                TempBlock.get(b).revertBlock();
            }
        }

        source.clear();
        if (!prepare()) {
            remove();
        }
    }

    public void shoot() {
        if (shot) {
            return;
        }

        for (boolean b : source.values()) {
            if (!b) {
                return;
            }
        }

        this.direction = player.getEyeLocation().getDirection().clone();

        playLavabendingSound(sourceCenter);

        this.shot = true;
    }

    public boolean hasShot() {
        return shot;
    }

    public void removeBlock(FallingBlock fb) {
        this.blocks.remove(fb);
        this.timeLived.remove(fb);
        fb.remove();
    }

    @Override
    public boolean isEnabled() {
        return ProjectAddons.instance.getConfig(bPlayer).getBoolean("Abilities.Earth.LavaSurge.Enabled");
    }

    @Override
    public String getDescription() {
        return "Throw a surging wave of lava in the direction you are looking!";
    }

    @Override
    public String getInstructions() {
        return "Sneak to create your lava source (or select an existing source) and click to throw the wave!";
    }

    private double randomOffset() {
        return (this.gameplayRandom.nextDouble() - 0.5) / 4;
    }
}
