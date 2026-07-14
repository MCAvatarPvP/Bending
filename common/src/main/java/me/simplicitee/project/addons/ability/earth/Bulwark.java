package me.simplicitee.project.addons.ability.earth;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import me.simplicitee.project.addons.ProjectAddons;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Bulwark extends EarthAbility implements AddonAbility {

    private static Vector UP = new Vector(0, 1, 0);

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute(Attribute.SPEED)
    private double throwSpeed;
    @Attribute(Attribute.HEIGHT)
    private int height;
    @Attribute(Attribute.RANGE)
    private double range;

    private Set<Block> blocks, moved, tops;
    private Set<TempFallingBlock> fbs;
    private Location start;
    private boolean launched = false, init = false;
    private long launchTime;
    private int step = 0;

    public Bulwark(Player player) {
        super(player);

        boolean allowJumping = ProjectAddons.instance.getConfig(bPlayer).getBoolean("Abilities.Earth.Bulwark.AllowJumping");
        if (!GeneralMethods.isOnGround(player) && !allowJumping) {
            return;
        }

        if (allowJumping) {
            Location playerLoc = player.getLocation();
            for (double i = 0; i <= 1.5; i += 0.5) {
                Block block = playerLoc.clone().add(0, -i, 0).getBlock();
                if (block.getType().isSolid()) {
                    Location loc = block.getLocation();
                    loc.add(0, 1, 0);
                    loc.setX(playerLoc.getX());
                    loc.setZ(playerLoc.getZ());
                    loc.setDirection(playerLoc.getDirection());
                    start = loc;
                    break;
                }
            }
        } else {
            start = player.getLocation();
        }

        if (start == null) return;

        if (!isEarthbendable(start.getBlock().getRelative(BlockFace.DOWN))) {
            return;
        }

        this.cooldown = ProjectAddons.instance.getConfig(bPlayer).getLong("Abilities.Earth.Bulwark.Cooldown");
        this.damage = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Earth.Bulwark.Damage");
        this.throwSpeed = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Earth.Bulwark.ThrowSpeed");
        this.height = ProjectAddons.instance.getConfig(bPlayer).getInt("Abilities.Earth.Bulwark.Height");
        this.range = ProjectAddons.instance.getConfig(bPlayer).getDouble("Abilities.Earth.Bulwark.Range");
        this.blocks = new HashSet<>();
        this.moved = new HashSet<>();
        this.tops = new HashSet<>();
        this.fbs = new HashSet<>();
        this.launchTime = 0;

        Location front = start.clone().add(start.getDirection().setY(0).normalize().multiply(2.5));
        Location copy = front.clone();
        Vector toLeft = GeneralMethods.getDirection(front, GeneralMethods.getLeftSide(start, 3)).normalize().multiply(0.5);
        Vector toRight = GeneralMethods.getDirection(front, GeneralMethods.getRightSide(start, 3)).normalize().multiply(0.5);

        loadPillar(front);

        for (int i = 0; i <= 5; ++i) {
            loadPillar(front.add(toLeft));
            loadPillar(copy.add(toRight));
        }

        start();
    }

    private void loadPillar(Location loc) {
        Block top = GeneralMethods.getTopBlock(loc, 2);

        if (!isEarthbendable(top)) {
            return;
        }

        tops.add(top);
    }

    @Override
    public void progress() {
        if (!tops.isEmpty()) {
            for (Block block : tops) {
                if (moveEarth(block, UP, height)) {
                    blocks.add(block);
                    moved.add(block.getRelative(BlockFace.UP));
                }
            }

            tops.clear();
            if (++step < height) {
                tops.addAll(moved);
            } else {
                blocks.addAll(moved);
            }
            moved.clear();
        } else if (!launched) {
            if (start.distance(player.getLocation()) > range) {
                remove();
                return;
            }

            if (!player.isSneaking()) {
                remove();
                return;
            }
        } else {
            if (launchTime + 3000 <= System.currentTimeMillis()) {
                remove();
                return;
            }

            List<TempFallingBlock> removal = new ArrayList<>();
            for (TempFallingBlock fb : fbs) {
                if (fb.getFallingBlock().isDead()) {
                    removal.add(fb);
                    break;
                }

                for (Entity e : GeneralMethods.getEntitiesAroundPoint(fb.getLocation(), 0.8)) {
                    if (e instanceof LivingEntity && e.getEntityId() != player.getEntityId()) {
                        DamageHandler.damageEntity(e, damage, this);
                        removal.add(fb);
                        fb.remove();
                        break;
                    }
                }
            }

            for (TempFallingBlock fb : removal) {
                fbs.remove(fb);
            }

            if (fbs.isEmpty()) {
                remove();
            }
        }
    }

    @Override
    public void remove() {
        super.remove();
        blocks.forEach((b) -> revertBlock(b));
        for (TempFallingBlock fb : fbs) {
            fb.remove();
        }
        fbs.clear();
        blocks.clear();
        bPlayer.addCooldown(this);
    }

    public void clickFunction() {
        if (launched) {
            return;
        }

        launched = true;
        for (Block b : blocks) {
            BlockData data = b.getBlockData();
            revertBlock(b);
            Location location = b.getLocation().add(0.5, 0.75, 0.5);
            Vector velocity = player.getEyeLocation().getDirection().setY(0.195).normalize().multiply(throwSpeed);
            TempFallingBlock fb = new TempFallingBlock(location, data, velocity, this);
            fbs.add(fb);
        }

        launchTime = System.currentTimeMillis();
        blocks.clear();
        bPlayer.addCooldown(this);
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
        return "Bulwark";
    }

    @Override
    public Location getLocation() {
        return start;
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
    public String getDescription() {
        return "Raise a shield of earth in front of you, and it lowers when you release sneak!";
    }

    @Override
    public String getInstructions() {
        return "Hold Sneak";
    }

    @Override
    public boolean isEnabled() {
        return ProjectAddons.instance.getConfig(bPlayer).getBoolean("Abilities.Earth.Bulwark.Enabled");
    }
}
