package me.macieq.abilities;

import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.LavaAbility;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.TempBlock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import me.macieq.MainConfig;
import me.macieq.utils.Utils;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.SoundCategory;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.configuration.PKConfigurationSection;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

public class VolcanicFlow extends LavaAbility implements AddonAbility, ComboAbility {
   private static final BlockData LAVA_DATA;
   private static final BlockData MAGMA_BLOCK_DATA;
   private static final BlockData AIR_DATA;
   private final Set<Block> affectedBlocks = new HashSet<>();
   private final List<TempBlock> tempBlocks = new ArrayList<>();
   private final World world;
   private final Random random;
   private Location location;
   private double maxY;
   private int maxSkips;
   private int skips;
   private boolean monitor;
   private long cooldown;
   private long sourceRange;
   private long duration;
   private double radius;
   private double speed;
   private long magmaDuration;
   private long lavaDuration;
   private boolean requiresLava;
   private boolean requiresSneak;
   private int maxStep;
   private double lavaDamage;
   private int fireTicks;

   public VolcanicFlow(Player player) {
      super(player);
      this.world = this.player.getWorld();
      this.random = PredictionDeterminism.random(player.getUniqueId(), getClass().getName());
      if (this.bPlayer.canBendIgnoreBinds(this)) {
         this.setConfig();
         Block block = Utils.getTargetedBlock(player, this.sourceRange,
               candidate -> this.requiresLava
                     ? Utils.isLavasourceable(player, candidate, this)
                     : isEarthbendable(player, candidate));
         if (block != null && !RegionProtection.isRegionProtected(player, block.getLocation(), this)) {
            this.location = block.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F);
            this.maxY = this.location.getY();
            this.start();
         }
      }
   }

   private void setConfig() {
      PKConfigurationSection config = MainConfig.getConfig();
      this.cooldown = config.getLong("Abilities.VolcanicFlow.Cooldown");
      this.sourceRange = config.getLong("Abilities.VolcanicFlow.SourceRange");
      this.speed = config.getDouble("Abilities.VolcanicFlow.Speed");
      this.radius = config.getDouble("Abilities.VolcanicFlow.Radius");
      this.maxStep = config.getInt("Abilities.VolcanicFlow.MaxHeightDifference");
      this.duration = config.getLong("Abilities.VolcanicFlow.FlowDuration");
      this.magmaDuration = config.getLong("Abilities.VolcanicFlow.MagmaDuration");
      this.lavaDuration = config.getLong("Abilities.VolcanicFlow.LavaDuration");
      this.requiresLava = config.getBoolean("Abilities.VolcanicFlow.RequiresLavaSource");
      this.requiresSneak = config.getBoolean("Abilities.VolcanicFlow.RequiresSneaking");
      this.lavaDamage = config.getDouble("Abilities.VolcanicFlow.LavaDamage");
      this.fireTicks = config.getInt("Abilities.VolcanicFlow.LavaFireTicks");
      this.maxSkips = this.speed == (double)0.0F ? 0 : (int)Math.ceil(this.radius / this.speed);
      this.skips = this.maxSkips;
   }

   public void progress() {
      label53: {
         if (this.monitor) {
            if (!this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
               break label53;
            }
         } else if (!this.bPlayer.canBendIgnoreBinds(this)) {
            break label53;
         }

         if (RegionProtection.isRegionProtected(this.player, this.location, this)) {
            this.setMonitor();
         }

         if (!this.bPlayer.getBoundAbilityName().equalsIgnoreCase("LavaManipulation") || this.requiresSneak && !this.player.isSneaking()) {
            this.setMonitor();
         }

         if (this.monitor) {
            if (this.tempBlocks.isEmpty()) {
               this.remove();
               return;
            }
         } else {
            this.move();
         }

         for(TempBlock tempBlock : this.tempBlocks) {
            if (tempBlock.isReverted() && tempBlock.getBlockData().getMaterial() != Material.MAGMA_BLOCK) {
               this.affectedBlocks.remove(tempBlock.getBlock());
            }
         }

         this.tempBlocks.removeIf(TempBlock::isReverted);
         return;
      }

      this.remove();
   }

   private void move() {
      if (System.currentTimeMillis() >= this.getStartTime() + this.duration) {
         this.setMonitor();
      } else {
         if (this.random.nextInt() % 20 == 0) {
            this.world.playSound(this.location, Sound.valueOf("BLOCK_LAVA_AMBIENT"), SoundCategory.MASTER, 0.5F, 1.8F);
         }

         Block block = this.location.getBlock();
         this.manageStep(block);
         this.lava(block);
         Location eye = this.player.getEyeLocation();
         eye.setPitch(0.0F);
         this.location.add(eye.getDirection().normalize().multiply(this.speed));
      }
   }

   private void manageStep(Block block) {
      Block up = block.getRelative(BlockFace.UP);
      if ((up.getType().isSolid() || isLava(up)) && this.location.getY() < this.maxY) {
         Location check = this.location.clone();

         for(int i = 0; i < this.maxStep; ++i) {
            check.add((double)0.0F, (double)1.0F, (double)0.0F);
            if (this.isTransparent(check.getBlock().getRelative(BlockFace.UP))) {
               this.location.add((double)0.0F, (double)1.0F, (double)0.0F);
               break;
            }
         }
      } else if (this.isTransparent(block) && !block.isLiquid()) {
         Location check = this.location.clone();
         boolean step = false;

         for(int i = 0; i < this.maxStep; ++i) {
            check.add((double)0.0F, (double)-1.0F, (double)0.0F);
            if (this.isEarthbendable(check.getBlock())) {
               step = true;
               break;
            }
         }

         if (step) {
            this.location.add((double)0.0F, (double)-1.0F, (double)0.0F);
         } else {
            --this.skips;
            if (this.skips <= 0) {
               this.setMonitor();
            }
         }
      } else if (this.isEarthbendable(block) && !isWater(block.getRelative(BlockFace.UP))) {
         this.skips = this.maxSkips;
      } else {
         --this.skips;
         if (this.skips <= 0) {
            this.setMonitor();
         }
      }

   }

   private void lava(Block center) {
      for(Block block : this.getBlocks()) {
         if (!this.affectedBlocks.contains(block)) {
            Platform.scheduler().runLater(() -> {
               if (!this.affectedBlocks.contains(block) && !this.isRemoved()) {
                  this.affectedBlocks.add(block);
                  TempBlock tempBlock = new TempBlock(block, MAGMA_BLOCK_DATA, this.magmaDuration + 100L, this);
                  this.tempBlocks.add(tempBlock);
                  Block check = block;

                  for(int i = 0; i < 2; ++i) {
                     check = check.getRelative(BlockFace.UP);
                     if (isPlant(check) || isSnow(check)) {
                        this.tempBlocks.add(new TempBlock(check, AIR_DATA, this.lavaDuration, this));
                        this.world.spawnParticle(Particle.SMOKE, check.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F), 5, 0.2, 0.2, 0.2, 0.05);
                     }
                  }

                  Platform.scheduler().runLater(() -> {
                     if (!this.isRemoved()) {
                        if (getMovedEarth().containsKey(block)) {
                           tempBlock.revertBlock();
                           revertBlock(block);
                        } else {
                           BlockData data = block.getY() > center.getY() ? AIR_DATA : LAVA_DATA;
                           this.tempBlocks.add(new TempBlock(block, data, this.lavaDuration, this));
                        }

                     }
                  }, this.magmaDuration / 50L);
               }
            }, (long)this.distance(center, block) * 15L);
         }
      }

   }

   private List<Block> getBlocks() {
      return Utils.getNearBlocks(this.location, this.radius).stream().filter(this.blockFilter()).toList();
   }

   private Predicate<Block> blockFilter() {
      return (block) -> block.getY() >= this.location.getBlockY() && isEarthbendable(block.getType(), true, true, false, true) && !isWater(block.getRelative(BlockFace.UP)) && !RegionProtection.isRegionProtected(this.player, block.getLocation(), this);
   }

   private int distance(Block from, Block to) {
      return Math.max(Math.abs(from.getX() - to.getX()), Math.abs(from.getZ() - to.getZ()));
   }

   public double getLavaDamage() {
      return this.lavaDamage;
   }

   public int getFireTicks() {
      return this.fireTicks;
   }

   public void setMonitor() {
      if (!this.monitor) {
         this.monitor = true;
         this.bPlayer.addCooldown(this);
      }
   }

   public void remove() {
      this.tempBlocks.forEach(TempBlock::revertBlock);
      if (!this.monitor) {
         this.bPlayer.addCooldown(this);
      }

      super.remove();
   }

   public boolean isSneakAbility() {
      return true;
   }

   public boolean isHarmlessAbility() {
      return false;
   }

   public long getCooldown() {
      return this.cooldown;
   }

   public String getName() {
      return "VolcanicFlow";
   }

   public Location getLocation() {
      return null;
   }

   public void load() {
   }

   public void stop() {
   }

   public String getAuthor() {
      return "Macie_Q";
   }

   public String getVersion() {
      return "1.0";
   }

   public String getDescription() {
      return "Release a river of molten rock that pours across the terrain, melting everything in its path";
   }

   public String getInstructions() {
      return "(LavaManipulation) Shift down -> (LavaFlow) Shift up -> (LavaFlow) Shift down -> (LavaManipulation) Shift up -> (LavaManipulation) Shift down";
   }

   public Object createNewComboInstance(Player player) {
      return new VolcanicFlow(player);
   }

   public ArrayList<ComboManager.AbilityInformation> getCombination() {
      return new ArrayList<>(List.of(new ComboManager.AbilityInformation("LavaManipulation", ClickType.SHIFT_DOWN), new ComboManager.AbilityInformation("LavaFlow", ClickType.SHIFT_UP), new ComboManager.AbilityInformation("LavaFlow", ClickType.SHIFT_DOWN), new ComboManager.AbilityInformation("LavaManipulation", ClickType.SHIFT_UP), new ComboManager.AbilityInformation("LavaManipulation", ClickType.SHIFT_DOWN)));
   }

   public boolean isEnabled() {
      return MainConfig.getConfig().getBoolean("Abilities.VolcanicFlow.Enabled");
   }

   static {
      LAVA_DATA = Material.LAVA.createBlockData();
      MAGMA_BLOCK_DATA = Material.MAGMA_BLOCK.createBlockData();
      AIR_DATA = Material.AIR.createBlockData();
   }
}
