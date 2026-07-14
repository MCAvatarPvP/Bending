package me.simplicitee.project.addons.ability.water;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.WaterAbility;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitTask;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.*;
import com.projectkorra.projectkorra.waterbending.util.WaterReturn;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Whip
        extends WaterAbility
        implements AddonAbility {
    private static final Map<UUID, Block> SOURCES = new HashMap<>();
    private static final Map<UUID, BukkitTask> SOURCE_PARTICLES = new HashMap<>();

    private long cooldown;
    private double range;
    private double damage;
    private TempBlock tempBlock;
    private Location location;
    private Block sourceBlock;
    private Block above;
    private Vector direction;

    public Whip(Player player) {
        super(player);
        if (this.bPlayer.isOnCooldown((Ability) this)) {
            return;
        }
        this.setFields();
    }

    public static void source(Player player) {
        if (WaterReturn.hasWaterBottle(player)) {
            return;
        }
        Block source = BlockSource.getWaterSourceBlock(player, 12.0, ClickType.SHIFT_DOWN, true, true, true, true, true);
        if (source == null) {
            unsource(player);
            return;
        }
        SOURCES.put(player.getUniqueId(), source);
        startSourceParticles(player, source);
    }

    public static void unsource(Player player) {
        SOURCES.remove(player.getUniqueId());
        stopSourceParticles(player);
    }

    private static void startSourceParticles(final Player player, final Block source) {
        stopSourceParticles(player);
        BukkitTask task = ProjectKorra.plugin.getServer().getScheduler().runTaskTimer(ProjectKorra.plugin, new Runnable() {
            @Override
            public void run() {
                Block heldSource = SOURCES.get(player.getUniqueId());
                if (!player.isOnline() || !player.isSneaking() || heldSource == null || !heldSource.equals(source)) {
                    unsource(player);
                    return;
                }
                playFocusWaterEffect(source);
            }
        }, 0L, 4L);
        SOURCE_PARTICLES.put(player.getUniqueId(), task);
    }

    private static void stopSourceParticles(Player player) {
        BukkitTask task = SOURCE_PARTICLES.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    public void setFields() {
        this.cooldown = ConfigManager.getConfig().getLong("ExtraAbilities.NickC1211.Whip.Cooldown");
        this.range = ConfigManager.getConfig().getDouble("ExtraAbilities.NickC1211.Whip.Range");
        this.damage = ConfigManager.getConfig().getDouble("ExtraAbilities.NickC1211.Whip.Damage");
        if (WaterReturn.hasWaterBottle(this.player)) {
            if (!this.player.isSneaking()) {
                return;
            }
            this.sourceBlock = this.player.getEyeLocation().clone().getBlock();
        } else {
            this.sourceBlock = SOURCES.remove(this.player.getUniqueId());
            stopSourceParticles(this.player);
        }
        if (this.sourceBlock == null) {
            return;
        }
        this.location = this.sourceBlock.getLocation().clone();
        this.above = this.sourceBlock.getRelative(0, 1, 0);
        this.start();
        if (!WaterReturn.hasWaterBottle((Player) this.player) && (this.sourceBlock.getType() == Material.FERN || this.sourceBlock.getType() == Material.SHORT_GRASS || this.sourceBlock.getType() == Material.POPPY || this.sourceBlock.getType() == Material.SUNFLOWER || this.sourceBlock.getType() == Material.OAK_SAPLING || this.sourceBlock.getType() == Material.RED_MUSHROOM || this.sourceBlock.getType() == Material.BROWN_MUSHROOM)) {
            this.location = this.above.getLocation().clone();
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && (this.sourceBlock.getType() == Material.WATER || this.sourceBlock.getType() == Material.ICE || this.sourceBlock.getType() == Material.PACKED_ICE || this.sourceBlock.getType() == Material.SNOW_BLOCK)) {
            this.location = this.above.getLocation().clone();
        }
    }

    public void progress() {
        if (!this.bPlayer.canBendIgnoreBindsCooldowns((CoreAbility) this)) {
            this.remove();
            return;
        }
        if (this.sourceBlock.getLocation().distance(this.location) > this.range) {
            this.remove();
            return;
        }
        if (!this.player.isSneaking()) {
            this.remove();
            return;
        }
        this.bPlayer.addCooldown((Ability) this);
        this.direction = this.player.getEyeLocation().getDirection();
        Location currentLoc = this.location.clone().add(this.direction);
        if (currentLoc.getBlock().getType() != Material.AIR && currentLoc.getBlock().getType() != Material.WATER && currentLoc.getBlock().getType() != Material.TALL_GRASS && currentLoc.getBlock().getType() != Material.ALLIUM && currentLoc.getBlock().getType() != Material.OXEYE_DAISY && currentLoc.getBlock().getType() != Material.AZURE_BLUET && currentLoc.getBlock().getType() != Material.BLUE_ORCHID && currentLoc.getBlock().getType() != Material.ICE && currentLoc.getBlock().getType() != Material.PACKED_ICE && currentLoc.getBlock().getType() != Material.BIRCH_LEAVES && currentLoc.getBlock().getType() != Material.SPRUCE_LEAVES && currentLoc.getBlock().getType() != Material.DARK_OAK_LEAVES && currentLoc.getBlock().getType() != Material.JUNGLE_LEAVES && currentLoc.getBlock().getType() != Material.ACACIA_LEAVES && currentLoc.getBlock().getType() != Material.OAK_LEAVES && currentLoc.getBlock().getType() != Material.OAK_LEAVES && currentLoc.getBlock().getType() != Material.SNOW_BLOCK && currentLoc.getBlock().getType() != Material.SHORT_GRASS && currentLoc.getBlock().getType() != Material.FERN && currentLoc.getBlock().getType() != Material.OAK_LOG && currentLoc.getBlock().getType() != Material.BIRCH_LOG && currentLoc.getBlock().getType() != Material.POPPY && currentLoc.getBlock().getType() != Material.SUNFLOWER && currentLoc.getBlock().getType() != Material.SNOW && currentLoc.getBlock().getType() != Material.CACTUS && currentLoc.getBlock().getType() != Material.SUGAR_CANE && currentLoc.getBlock().getType() != Material.OAK_SAPLING && currentLoc.getBlock().getType() != Material.PUMPKIN && currentLoc.getBlock().getType() != Material.PUMPKIN_STEM && currentLoc.getBlock().getType() != Material.MELON && currentLoc.getBlock().getType() != Material.MELON_STEM && currentLoc.getBlock().getType() != Material.WHEAT && currentLoc.getBlock().getType() != Material.LILY_PAD && currentLoc.getBlock().getType() != Material.BROWN_MUSHROOM && currentLoc.getBlock().getType() != Material.RED_MUSHROOM && currentLoc.getBlock().getType() != Material.RED_MUSHROOM_BLOCK && currentLoc.getBlock().getType() != Material.BROWN_MUSHROOM_BLOCK && currentLoc.getBlock().getType() != Material.VINE && currentLoc.getBlock().getType() != Material.ROSE_BUSH && currentLoc.getBlock().getType() != Material.PEONY && currentLoc.getBlock().getType() != Material.ORANGE_TULIP && currentLoc.getBlock().getType() != Material.PINK_TULIP && currentLoc.getBlock().getType() != Material.RED_TULIP && currentLoc.getBlock().getType() != Material.WHITE_TULIP && currentLoc.getBlock().getType() != Material.DANDELION) {
            this.remove();
            return;
        }
        this.location = currentLoc;
        if (WaterReturn.hasWaterBottle((Player) this.player)) {
            this.tempBlock = new TempBlock(currentLoc.getBlock(), Material.WATER, GeneralMethods.getWaterData((int) 2));
            WaterReturn.isBendableWaterTempBlock((Block) currentLoc.getBlock());
            ParticleEffect.WATER_DROP.display(this.location, 5, 0.5, 0.5, 0.5, 0.0);
            Whip.playWaterbendingSound((Location) this.location);
            this.tempBlock.setRevertTime(1200L);
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && WaterReturn.isWater((Block) this.sourceBlock)) {
            this.tempBlock = new TempBlock(this.location.getBlock(), Material.WATER);
            ParticleEffect.WATER_DROP.display(this.location, 5, 0.5, 0.5, 0.5, 0.0);
            Whip.playWaterbendingSound((Location) this.location);
            this.tempBlock.setRevertTime(1200L);
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && WaterReturn.isSnow((Block) this.sourceBlock)) {
            this.tempBlock = new TempBlock(currentLoc.getBlock(), Material.SNOW_BLOCK);
            this.location.getWorld().playSound(this.location, Sound.BLOCK_SNOW_BREAK, 1.0f, 1.0f);
            this.tempBlock.setRevertTime(3200L);
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && this.bPlayer.canUseSubElement(Element.SubElement.PLANT) && this.sourceBlock.getType() == Material.OAK_LEAVES) {
            this.tempBlock = new TempBlock(currentLoc.getBlock(), Material.OAK_LEAVES);
            this.location.getWorld().playSound(this.location, Sound.BLOCK_GRASS_BREAK, 1.0f, 1.0f);
            this.tempBlock.setRevertTime(3200L);
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && this.bPlayer.canUseSubElement(Element.SubElement.PLANT) && this.sourceBlock.getType() == Material.ACACIA_LEAVES) {
            this.tempBlock = new TempBlock(currentLoc.getBlock(), Material.ACACIA_LEAVES);
            this.location.getWorld().playSound(this.location, Sound.BLOCK_GRASS_BREAK, 1.0f, 1.0f);
            this.tempBlock.setRevertTime(3200L);
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && this.bPlayer.canUseSubElement(Element.SubElement.PLANT) && this.sourceBlock.getType() == Material.BIRCH_LEAVES) {
            this.tempBlock = new TempBlock(currentLoc.getBlock(), Material.BIRCH_LEAVES);
            this.location.getWorld().playSound(this.location, Sound.BLOCK_GRASS_BREAK, 1.0f, 1.0f);
            this.tempBlock.setRevertTime(3200L);
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && this.bPlayer.canUseSubElement(Element.SubElement.PLANT) && this.sourceBlock.getType() == Material.SPRUCE_LEAVES) {
            this.tempBlock = new TempBlock(currentLoc.getBlock(), Material.SPRUCE_LEAVES);
            this.location.getWorld().playSound(this.location, Sound.BLOCK_GRASS_BREAK, 1.0f, 1.0f);
            this.tempBlock.setRevertTime(3200L);
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && this.bPlayer.canUseSubElement(Element.SubElement.PLANT) && this.sourceBlock.getType() == Material.JUNGLE_LEAVES) {
            this.tempBlock = new TempBlock(currentLoc.getBlock(), Material.JUNGLE_LEAVES);
            this.location.getWorld().playSound(this.location, Sound.BLOCK_GRASS_BREAK, 1.0f, 1.0f);
            this.tempBlock.setRevertTime(2500L);
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && this.bPlayer.canUseSubElement(Element.SubElement.PLANT) && this.sourceBlock.getType() == Material.DARK_OAK_LEAVES) {
            this.tempBlock = new TempBlock(currentLoc.getBlock(), Material.DARK_OAK_LEAVES);
            this.location.getWorld().playSound(this.location, Sound.BLOCK_GRASS_BREAK, 1.0f, 1.0f);
            this.tempBlock.setRevertTime(2500L);
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && this.bPlayer.canUseSubElement(Element.SubElement.PLANT) && this.sourceBlock.getType() == Material.CACTUS) {
            this.tempBlock = new TempBlock(currentLoc.getBlock(), Material.OAK_LEAVES);
            this.location.getWorld().playSound(this.location, Sound.BLOCK_GRASS_BREAK, 1.0f, 1.0f);
            this.tempBlock.setRevertTime(1200L);
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && this.bPlayer.canUseSubElement(Element.SubElement.PLANT) && this.sourceBlock.getType() == Material.FERN || this.sourceBlock.getType() == Material.SHORT_GRASS || this.sourceBlock.getType() == Material.TALL_GRASS) {
            this.tempBlock = new TempBlock(currentLoc.getBlock(), Material.SHORT_GRASS);
            this.location.getWorld().playSound(this.location, Sound.BLOCK_GRASS_BREAK, 1.0f, 1.0f);
            this.tempBlock.setRevertTime(1200L);
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && this.bPlayer.canUseSubElement(Element.SubElement.PLANT) && this.sourceBlock.getType() == Material.POPPY || this.sourceBlock.getType() == Material.ALLIUM || this.sourceBlock.getType() == Material.SUNFLOWER || this.sourceBlock.getType() == Material.BLUE_ORCHID || this.sourceBlock.getType() == Material.OXEYE_DAISY || this.sourceBlock.getType() == Material.AZURE_BLUET || this.sourceBlock.getType() == Material.PEONY || this.sourceBlock.getType() == Material.RED_TULIP || this.sourceBlock.getType() == Material.ORANGE_TULIP || this.sourceBlock.getType() == Material.WHITE_TULIP || this.sourceBlock.getType() == Material.PINK_TULIP) {
            this.tempBlock = new TempBlock(currentLoc.getBlock(), Material.WATER, GeneralMethods.getWaterData((int) 2));
            ParticleEffect.WATER_DROP.display(this.location, 5, 0.5, 0.5, 0.5, 0.0);
            Whip.playWaterbendingSound((Location) this.location);
            this.tempBlock.setRevertTime(1200L);
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && this.bPlayer.canUseSubElement(Element.SubElement.PLANT) && this.sourceBlock.getType() == Material.RED_MUSHROOM || this.sourceBlock.getType() == Material.BROWN_MUSHROOM) {
            this.tempBlock = new TempBlock(currentLoc.getBlock(), Material.WATER, GeneralMethods.getWaterData((int) 2));
            ParticleEffect.WATER_DROP.display(this.location, 5, 0.5, 0.5, 0.5, 0.0);
            Whip.playWaterbendingSound((Location) this.location);
            this.tempBlock.setRevertTime(1200L);
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && this.bPlayer.canUseSubElement(Element.SubElement.PLANT) && this.sourceBlock.getType() == Material.PUMPKIN || this.sourceBlock.getType() == Material.MELON) {
            this.tempBlock = new TempBlock(currentLoc.getBlock(), Material.WATER);
            ParticleEffect.WATER_DROP.display(this.location, 5, 0.5, 0.5, 0.5, 0.0);
            Whip.playWaterbendingSound((Location) this.location);
            this.tempBlock.setRevertTime(1200L);
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && this.bPlayer.canUseSubElement(Element.SubElement.PLANT) && this.sourceBlock.getType() == Material.OAK_SAPLING || this.sourceBlock.getType() == Material.PUMPKIN_STEM || this.sourceBlock.getType() == Material.MELON_STEM) {
            this.tempBlock = new TempBlock(currentLoc.getBlock(), Material.WATER);
            ParticleEffect.WATER_DROP.display(this.location, 5, 0.5, 0.5, 0.5, 0.0);
            Whip.playWaterbendingSound((Location) this.location);
            this.tempBlock.setRevertTime(1200L);
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && this.bPlayer.canUseSubElement(Element.SubElement.PLANT) && this.sourceBlock.getType() == Material.VINE || this.sourceBlock.getType() == Material.LILY_PAD || this.sourceBlock.getType() == Material.WHEAT) {
            this.tempBlock = new TempBlock(currentLoc.getBlock(), Material.WATER);
            ParticleEffect.WATER_DROP.display(this.location, 5, 0.5, 0.5, 0.5, 0.0);
            Whip.playWaterbendingSound((Location) this.location);
            this.tempBlock.setRevertTime(1200L);
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && this.bPlayer.canUseSubElement(Element.SubElement.PLANT) && this.sourceBlock.getType() == Material.SUGAR_CANE) {
            this.tempBlock = new TempBlock(currentLoc.getBlock(), Material.SUGAR_CANE);
            this.location.getWorld().playSound(this.location, Sound.BLOCK_GRASS_BREAK, 1.0f, 1.0f);
            this.tempBlock.setRevertTime(1200L);
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && this.bPlayer.canUseSubElement(Element.SubElement.PLANT) && this.sourceBlock.getType() == Material.BROWN_MUSHROOM_BLOCK || this.sourceBlock.getType() == Material.RED_MUSHROOM_BLOCK) {
            this.tempBlock = new TempBlock(currentLoc.getBlock(), Material.WATER);
            ParticleEffect.WATER_DROP.display(this.location, 5, 0.5, 0.5, 0.5, 0.0);
            Whip.playWaterbendingSound((Location) this.location);
            this.tempBlock.setRevertTime(1200L);
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && this.sourceBlock.getType() == Material.OAK_LOG || this.sourceBlock.getType() == Material.BIRCH_LOG) {
            this.tempBlock = new TempBlock(currentLoc.getBlock(), Material.WATER);
            ParticleEffect.WATER_DROP.display(this.location, 5, 0.5, 0.5, 0.5, 0.0);
            Whip.playWaterbendingSound((Location) this.location);
            this.tempBlock.setRevertTime(1200L);
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && this.bPlayer.canUseSubElement(Element.SubElement.ICE) && this.sourceBlock.getType() == Material.ICE) {
            this.tempBlock = new TempBlock(currentLoc.getBlock(), Material.ICE);
            Whip.playIcebendingSound((Location) this.location);
            this.tempBlock.setRevertTime(3500L);
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && this.bPlayer.canUseSubElement(Element.SubElement.ICE) && this.sourceBlock.getType() == Material.PACKED_ICE) {
            this.tempBlock = new TempBlock(currentLoc.getBlock(), Material.PACKED_ICE);
            Whip.playIcebendingSound((Location) this.location);
            this.tempBlock.setRevertTime(3500L);
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && WaterReturn.isNight((World) this.player.getWorld())) {
            this.damage = 5.0;
            this.range = 22.0;
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && WaterReturn.isFullMoon((World) this.player.getWorld())) {
            this.damage = 6.0;
            this.range = 24.0;
        }
        if (WaterReturn.hasWaterBottle((Player) this.player) && this.sourceBlock.getLocation().distance(this.location) > this.range) {
            WaterReturn.emptyWaterBottle((Player) this.player);
            return;
        }
        if (!WaterReturn.hasWaterBottle((Player) this.player) && this.sourceBlock.getLocation().distance(this.location) > this.range) {
            this.sourceBlock.setType(Material.AIR);
        }
        for (Entity entity : GeneralMethods.getEntitiesAroundPoint((Location) this.location, (double) 2.0)) {
            if (!(entity instanceof LivingEntity) || entity.getEntityId() == this.player.getEntityId()) continue;
            Location location = this.player.getEyeLocation();
            Vector vector = location.getDirection();
            GeneralMethods.setVelocity(this, entity, vector.normalize().multiply(0.8f));
            if (entity instanceof LivingEntity) {
                DamageHandler.damageEntity((Entity) entity, (double) this.damage, (Ability) this);
            }
        }
    }

    public long getCooldown() {
        return this.cooldown;
    }

    public Location getLocation() {
        return this.location;
    }

    public String getName() {
        return "Whip";
    }

    public String getDescription() {
        return "Send a whip of water, plant, ice, or snow at your opponent.";
    }

    public String getInstructions() {
        return "Hold Shift and Left Click to shoot.";
    }

    public boolean isHarmlessAbility() {
        return false;
    }

    public boolean isSneakAbility() {
        return ConfigManager.getConfig().getBoolean("ExtraAbilities.NickC1211.Whip.Swim.Disabled");
    }

    public void remove() {
        new WaterReturn(this.player, this.location.getBlock());
        super.remove();
    }

    public String getAuthor() {
        return "NickC1211";
    }

    public String getVersion() {
        return "v6.3";
    }


    public void load() {
        ProjectKorra.log.info(String.valueOf(this.getName()) + " " + this.getVersion() + " by " + this.getAuthor() + " loaded!");
        ConfigManager.getConfig().addDefault("ExtraAbilities.NickC1211.Whip.Cooldown", (Object) 2000);
        ConfigManager.getConfig().addDefault("ExtraAbilities.NickC1211.Whip.Range", (Object) 20);
        ConfigManager.getConfig().addDefault("ExtraAbilities.NickC1211.Whip.Damage", (Object) 4);
        ConfigManager.getConfig().addDefault("ExtraAbilities.NickC1211.Whip.Swim.Disabled", (Object) true);
        ConfigManager.defaultConfig.save();
    }

    public void stop() {
        ProjectKorra.log.info(String.valueOf(this.getName()) + " " + this.getVersion() + " by " + this.getAuthor() + " disabled!");
    }
}
