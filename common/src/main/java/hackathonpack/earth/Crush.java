package hackathonpack.earth;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.earthbending.EarthSmash;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.metadata.FixedMetadataValue;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.PredictionDeterminism;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.TempBlock;
import hackathonpack.UtilityMethods;

import java.util.*;

public class Crush extends EarthAbility implements ComboAbility, AddonAbility {
    private Location startLocation;
    private long cooldown;
    private double damage;
    private int maxBfsLevel;
    private int maxBlockCount;
    private int currentBlockCount;
    private boolean isRegenOn;
    private long latency;
    private long duration;
    private long parryCd;
    private double radius;
    private Block head;
    private Map<Location, Material> firstBlockMaterials;
    private Map<Location, BlockData> firstBlockDatas;
    private ArrayList<FallingBlock> fallingBlocks;
    private State state;
    private final Random gameplayRandom;

    public Crush(final Player player) {
        super(player);
        this.gameplayRandom = PredictionDeterminism.random(player == null ? null : player.getUniqueId(),
                getClass().getName() + ":falling-block-velocity");
        if (this.bPlayer.isOnCooldown(this) || !this.bPlayer.canBendIgnoreBindsCooldowns(this) || !ConfigManager.getConfig().getBoolean(path("Enable")))
            return;
        setField();
        start();
    }

    private static String path(final String node) {
        return "ExtraAbilities.Hiro3.HackathonPack.Earth.Crush." + node;
    }

    private static Vector rotateVectorAroundXZ(final Vector vector, final double degrees) {
        final Vector right = new Vector(-vector.getZ(), 0, vector.getX());
        final double rad = Math.toRadians(degrees);
        return vector.clone().multiply(Math.cos(rad)).add(vector.clone().crossProduct(right).multiply(Math.sin(rad)));
    }

    private void setField() {
        this.startLocation = this.player.getLocation();
        this.cooldown = ConfigManager.getConfig().getLong(path("Cooldown"));
        this.damage = ConfigManager.getConfig().getDouble(path("Damage"));
        this.maxBfsLevel = ConfigManager.getConfig().getInt(path("MaximumDepth"));
        this.maxBlockCount = ConfigManager.getConfig().getInt(path("MaximumBlockAmount"));
        this.isRegenOn = ConfigManager.getConfig().getBoolean(path("Revert.Enable"));
        this.latency = ConfigManager.getConfig().getLong(path("Revert.Latency"));
        this.duration = ConfigManager.getConfig().getLong(path("Duration"));
        this.parryCd = ConfigManager.getConfig().getLong(path("ParryCD"));
        this.radius = ConfigManager.getConfig().getLong(path("Radius"));
        this.firstBlockMaterials = new HashMap<>();
        this.firstBlockDatas = new HashMap<>();
        this.fallingBlocks = new ArrayList<>();
        this.state = State.WAITING;
    }

    @Override
    public void progress() {
        if (this.state == State.WAITING) {
            if (!this.player.isSneaking()) {
                remove();
                return;
            }
            if (System.currentTimeMillis() - getStartTime() > this.duration) {
                this.bPlayer.addCooldown(this, this.parryCd);
                remove();
                return;
            }
            if (!checkCollision()) {
                final Location target = getTargetLocation(this.player, 4);
                this.startLocation = target.getBlock().getLocation();
                if (isEarthbendable(target.getBlock())) {
                    this.head = target.getBlock();
                    this.state = State.STARTED;
                }
            }
        } else if (this.state == State.STARTED) {
            this.bPlayer.addCooldown(this);
            final ArrayList<Location> start = new ArrayList<>();
            start.add(this.head.getLocation());
            this.currentBlockCount++;
            breakBlocks(start);
            bfs(this.head);
            this.state = State.PROGRESSING;
        } else {
            for (int i = this.fallingBlocks.size() - 1; i >= 0; i--) {
                final FallingBlock falling = this.fallingBlocks.get(i);
                if (!falling.isDead()) {
                    for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(falling.getLocation(), 2)) {
                        if (entity instanceof LivingEntity && !entity.equals(this.player))
                            DamageHandler.damageEntity(entity, this.damage, this);
                    }
                } else {
                    this.fallingBlocks.remove(i);
                }
            }
        }
    }

    private void bfs(final Block head) {
        final Queue<Block> queue = new LinkedList<>();
        final Set<Location> visited = new HashSet<>();
        visited.add(head.getLocation());
        queue.add(head);
        new BukkitRunnable() {
            int bfsLevel = 1;
            int previousNeighborAmount = 1;

            @Override
            public void run() {
                if (queue.isEmpty() || this.bfsLevel > maxBfsLevel || currentBlockCount > maxBlockCount) {
                    cancel();
                    regen();
                    remove();
                    return;
                }
                final int previousBlockCount = currentBlockCount;
                for (int i = 0; i < this.previousNeighborAmount; i++) {
                    if (queue.isEmpty()) break;
                    final ArrayList<Location> neighbors = new ArrayList<>();
                    final Block block = queue.poll();
                    for (final Block candidate : GeneralMethods.getBlocksAroundPoint(block.getLocation(), 1.5)) {
                        if (isEarthbendable(candidate) && visited.add(candidate.getLocation()) && currentBlockCount <= maxBlockCount) {
                            queue.add(candidate);
                            neighbors.add(candidate.getLocation());
                            currentBlockCount++;
                        }
                    }
                    breakBlocks(neighbors);
                }
                this.previousNeighborAmount = currentBlockCount - previousBlockCount;
                this.bfsLevel++;
            }
        }.runTaskTimer(ProjectKorra.plugin, 0, 2);
    }

    private void breakBlocks(final ArrayList<Location> locations) {
        Vector direction = this.player.getLocation().getDirection().clone();
        int flag = 0;
        if (direction.getY() <= -0.35) {
            direction.setY(direction.getY() * -1);
            flag = 2;
        }
        for (final Location loc : locations) {
            this.firstBlockMaterials.putIfAbsent(loc, loc.getBlock().getType());
            this.firstBlockDatas.putIfAbsent(loc, loc.getBlock().getBlockData().clone());
            final FallingBlock falling = loc.getWorld().spawnFallingBlock(loc, loc.getBlock().getBlockData());
            falling.setMetadata("CrushFallingBlock", new FixedMetadataValue(ProjectKorra.plugin, null));
            falling.setVelocity(rotateVectorAroundXZ(direction.clone(), this.gameplayRandom.nextDouble() * 60 - 20).multiply(0.5));
            falling.setVelocity(falling.getVelocity().add(UtilityMethods.rotateVectorAroundY(falling.getVelocity(),
                    this.gameplayRandom.nextDouble() * 90 * (1 + flag) - 45 * (1 + flag))).multiply(flag == 0 ? 0.5 : 1));
            falling.setDropItem(false);
            this.fallingBlocks.add(falling);
            loc.getBlock().setType(Material.AIR);
        }
        if (!locations.isEmpty()) playEarthbendingSound(locations.get(0));
    }

    private Location getTargetLocation(final Player player, final double range) {
        final Vector direction = player.getLocation().getDirection().clone().multiply(0.1);
        final Location loc = player.getEyeLocation().clone();
        final Location start = loc.clone();
        do {
            loc.add(direction);
        } while (start.distance(loc) < range && !GeneralMethods.isSolid(loc.getBlock()));
        return loc;
    }

    private boolean checkCollision() {
        for (final EarthSmash smash : getAbilities(EarthSmash.class)) {
            final Location loc = smash.getLocation();
            if (loc != null && loc.getWorld().equals(this.player.getWorld()) && loc.distance(getLocation()) <= getCollisionRadius()) {
                handleEarthSmash(smash);
                return true;
            }
        }
        return false;
    }

    private void handleEarthSmash(final EarthSmash smash) {
        final ArrayList<Block> blocks = new ArrayList<>(smash.getBlocks());
        final ArrayList<Material> materials = new ArrayList<>();
        for (final TempBlock tempBlock : smash.getAffectedBlocks()) materials.add(tempBlock.getBlock().getType());
        smash.remove();
        for (int i = 0; i < blocks.size() && i < materials.size(); i++) blocks.get(i).setType(materials.get(i));
        this.isRegenOn = false;
        this.startLocation = blocks.isEmpty() ? this.player.getLocation() : blocks.get(0).getLocation();
        this.head = blocks.isEmpty() ? this.startLocation.getBlock() : blocks.get(0);
        this.state = State.STARTED;
    }

    public void regen() {
        if (!this.isRegenOn) return;
        new BukkitRunnable() {
            @Override
            public void run() {
                for (final Location loc : firstBlockMaterials.keySet()) {
                    loc.getBlock().setType(firstBlockMaterials.get(loc));
                    loc.getBlock().setBlockData(firstBlockDatas.get(loc));
                }
            }
        }.runTaskLater(ProjectKorra.plugin, this.latency * 20);
    }

    public ArrayList<FallingBlock> getFallingBlocks() {
        return this.fallingBlocks;
    }

    @Override
    public long getCooldown() {
        return this.cooldown;
    }

    @Override
    public Location getLocation() {
        return this.startLocation;
    }

    @Override
    public double getCollisionRadius() {
        return this.radius;
    }

    @Override
    public String getName() {
        return "Crush";
    }

    @Override
    public String getDescription() {
        return "Crush the obstacles you face.";
    }

    @Override
    public String getInstructions() {
        return "Shockwave (Sneak) -> Collapse (Left Click)";
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public boolean isSneakAbility() {
        return true;
    }

    @Override
    public String getAuthor() {
        return "Hiro3";
    }

    @Override
    public String getVersion() {
        return UtilityMethods.getVersion();
    }

    @Override
    public Object createNewComboInstance(final Player player) {
        return new Crush(player);
    }

    @Override
    public ArrayList<AbilityInformation> getCombination() {
        return UtilityMethods.getConfiguredCombination(path("Enable"), path("Combination"), new ArrayList<>(Arrays.asList(new AbilityInformation("Shockwave", ClickType.SHIFT_DOWN), new AbilityInformation("EarthSmash", ClickType.LEFT_CLICK))));
    }

    @Override
    public void load() {
        ConfigManager.getConfig().addDefault(path("Enable"), true);
        ConfigManager.getConfig().addDefault(path("Cooldown"), 5000);
        ConfigManager.getConfig().addDefault(path("Damage"), 3);
        ConfigManager.getConfig().addDefault(path("MaximumBlockAmount"), 40);
        ConfigManager.getConfig().addDefault(path("MaximumDepth"), 30);
        ConfigManager.getConfig().addDefault(path("Revert.Enable"), true);
        ConfigManager.getConfig().addDefault(path("Revert.Latency"), 30);
        ConfigManager.getConfig().addDefault(path("Duration"), 500);
        ConfigManager.getConfig().addDefault(path("ParryCD"), 2000);
        ConfigManager.getConfig().addDefault(path("Radius"), 1.0);
        ConfigManager.getConfig().addDefault(path("Combination"), Arrays.asList("Shockwave: SHIFT_DOWN", "EarthSmash: LEFT_CLICK"));
        ConfigManager.defaultConfig.save();
        ProjectKorra.log.info("Succesfully enabled " + getName() + " by " + getAuthor());
    }

    @Override
    public void stop() {
        ProjectKorra.log.info("Successfully disabled " + getName() + " by " + getAuthor());
        super.remove();
    }

    private enum State {WAITING, STARTED, PROGRESSING}
}
