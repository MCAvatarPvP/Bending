package com.projectkorra.projectkorra.earthbending;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.prediction.PredictionDeterminism;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class OldEarthGrab extends EarthAbility {

    private Type type;
    private Location center;
    @Attribute(Attribute.RADIUS)
    private double radius;
    @Attribute(Attribute.HEIGHT)
    private int height;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private Set<Block> checked;
    @Attribute(Attribute.RANGE)
    private double maxRange;
    private Location loc;
    private LivingEntity target;
    private final Random random;

    public OldEarthGrab(Player player, Type type) {
        super(player);
        this.random = PredictionDeterminism.random(player == null ? null : player.getUniqueId(), getClass().getName());

        if (!this.bPlayer.canBend(this)) {
            return;
        }

        this.type = type;
        this.center = player.getLocation().clone().subtract(0, 1, 0);
        this.radius = getConfig().getDouble("Abilities.Earth.OldEarthGrab.Radius");
        this.height = getConfig().getInt("Abilities.Earth.OldEarthGrab.Height");
        this.cooldown = getConfig().getLong("Abilities.Earth.OldEarthGrab.Cooldown");
        this.checked = new HashSet<>();

        if (type == Type.OTHERS) {
            this.loc = player.getLocation().clone();

            if (GeneralMethods.isRegionProtectedFromBuild(player, this.loc)) {
                return;
            }
            if (!isEarthbendable(this.loc.getBlock().getRelative(BlockFace.DOWN).getType(), true, true, true)) {
                return;
            }
            this.maxRange = getConfig().getDouble("Abilities.Earth.OldEarthGrab.Range");
            Entity t = GeneralMethods.getTargetedEntity(player, maxRange);
            if (t instanceof LivingEntity) {
                target = (LivingEntity) t;
            }

            if (target == null) return;
        }

        this.start();
    }

    @Override
    public void progress() {
        if (type == Type.SELF) {
            run(center);
        } else {
            if (!this.player.isOnline() || this.player.isDead()) {
                this.remove(true);
                return;
            }

            if (target == null || target.isDead()) {
                this.remove(false);
                return;
            }

            run(target.getLocation().clone().subtract(0, 1, 0));
            this.remove(false);
        }
    }

    private void run(Location loc) {
        for (int i = 0; i < 2; i++) {
            for (final Location check : this.getCircle(loc, this.radius + i, 10)) {
                Block currBlock = check.getBlock();
                if (this.checked.contains(currBlock)) {
                    continue;
                }

                currBlock = this.getAppropriateBlock(currBlock);
                if (currBlock == null) {
                    continue;
                }

                new RaiseEarth(this.player, currBlock.getLocation(), Math.round(this.height - i));
                this.checked.add(currBlock);
            }
        }

        this.bPlayer.addCooldown("OldEarthGrab", this.getCooldown());
        this.remove();
    }

    public void remove(final boolean cooldown) {
        super.remove();
        if (cooldown) {
            this.bPlayer.addCooldown("OldEarthGrab", getConfig().getLong("Abilities.Earth.OldEarthGrab.Cooldown"));
        }
    }

    private Block getAppropriateBlock(final Block block) {
        if (!GeneralMethods.isSolid(block.getRelative(BlockFace.UP)) && GeneralMethods.isSolid(block)) {
            return block;
        }
        final Block top = GeneralMethods.getTopBlock(block.getLocation(), 2);
        if (GeneralMethods.isSolid(top.getRelative(BlockFace.UP))) {
            return null;
        }
        return top;
    }

    private List<Location> getCircle(final Location center, final double radius, double interval) {
        final List<Location> result = new ArrayList<>();
        interval = Math.toRadians(Math.abs(interval));
        for (double theta = 0; theta < 2 * Math.PI; theta += interval) {
            final double x = Math.cos(theta) * (radius + (this.random.nextDouble() / 3.1));
            final double z = Math.sin(theta) * (radius + (this.random.nextDouble() / 3.1));
            result.add(center.clone().add(x, 0, z));
        }
        return result;
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
        return this.cooldown;
    }

    @Override
    public String getName() {
        return "OldEarthGrab";
    }

    @Override
    public Location getLocation() {
        return this.center;
    }

    public enum Type {
        SELF, OTHERS
    }
}
