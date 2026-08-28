package me.macieq.abilities;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.LavaAbility;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.configuration.PKConfigurationSection;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

public class LavaManipulation extends LavaAbility implements AddonAbility {
   private static final BlockData LAVA_DATA;
   private static final BlockData MAGMA_BLOCK_DATA;
   private static final BlockData AIR_DATA;
   private static final BlockData BARRIER_DATA;
   private final List<TempBlock> lavaBlocks = new ArrayList<>();
   private final List<Block> affectedBlocks = new ArrayList<>();
   private final List<TempBlock> meltedTempBlocks = new ArrayList<>();
   private final Random random;
   private final World world;
   private Location location;
   public State state;
   private TempFallingBlock tempFallingBlock;
   private long cooldown;
   private long sourceRange;
   private long holdDistance;
   private long maxHoldDistanceSq;
   private double slowSpeed;
   private double fastSpeed;
   private long length;
   private double meltRadius;
   private long meltTime;
   private long meltDuration;
   private double launchPower;
   private double hitbox;
   private double damage;
   private double lavaDamage;
   private int fireTicks;

   public LavaManipulation(Player player) {
      super(player);
      this.world = this.player.getWorld();
      this.random = PredictionDeterminism.random(player.getUniqueId(), getClass().getName());
      this.setConfig();
      Block block = Utils.getTargetedBlock(player, this.sourceRange,
            candidate -> Utils.isLavasourceable(player, candidate, this));
      if (block != null) {
         this.location = block.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F);
         this.state = LavaManipulation.State.BACK;
         this.world.playSound(this.location, Sound.valueOf("ITEM_BUCKET_FILL_LAVA"), SoundCategory.MASTER, 0.5F, 0.75F);
         this.world.playSound(this.location, Sound.valueOf("ENTITY_WARDEN_HEARTBEAT"), SoundCategory.MASTER, 0.5F, 1.2F);
         this.start();
      }
   }

   private void setConfig() {
      PKConfigurationSection config = MainConfig.getConfig();
      this.cooldown = config.getLong("Abilities.LavaManipulation.Cooldown");
      this.sourceRange = config.getLong("Abilities.LavaManipulation.SourceRange");
      this.launchPower = config.getDouble("Abilities.LavaManipulation.LaunchPower");
      this.damage = config.getDouble("Abilities.LavaManipulation.Damage");
      this.hitbox = config.getDouble("Abilities.LavaManipulation.Hitbox");
      this.slowSpeed = config.getDouble("Abilities.LavaManipulation.Speed");
      this.fastSpeed = config.getDouble("Abilities.LavaManipulation.SneakSpeed");
      this.length = config.getLong("Abilities.LavaManipulation.Length");
      this.holdDistance = config.getLong("Abilities.LavaManipulation.HoldDistance");
      long maxHoldDistance = config.getLong("Abilities.LavaManipulation.MaxHoldDistance");
      this.maxHoldDistanceSq = maxHoldDistance * maxHoldDistance;
      this.meltRadius = config.getDouble("Abilities.LavaManipulation.MeltRadius");
      this.meltTime = config.getLong("Abilities.LavaManipulation.MeltTime");
      this.meltDuration = config.getLong("Abilities.LavaManipulation.MeltDuration");
      this.lavaDamage = config.getDouble("Abilities.LavaManipulation.LavaDamage");
      this.fireTicks = config.getInt("Abilities.LavaManipulation.LavaFireTicks");
   }

   public void progress() {
      if (this.shouldRemove()) {
         this.bPlayer.addCooldown(this);
         this.remove();
      } else {
         this.manage();
      }
   }

   private void manage() {
      switch (this.state) {
         case BACK -> {
            double speed = this.player.isSneaking() ? this.fastSpeed : this.slowSpeed;
            Location destination = this.player.getEyeLocation().add(this.player.getEyeLocation().getDirection().multiply((float)this.holdDistance));
            Vector direction = GeneralMethods.getDirection(this.location, destination).normalize().multiply(speed);
            this.manageMovement(direction);
         }
         case FORWARD -> {
            double speed = this.player.isSneaking() ? this.fastSpeed : this.slowSpeed;
            Vector direction = this.player.getEyeLocation().getDirection().normalize().multiply(speed);
            this.manageMovement(direction);
         }
         case SHOT -> {
            FallingBlock fallingBlock = this.tempFallingBlock.getFallingBlock();
            if (fallingBlock.isDead()) {
               ((TempBlock)this.lavaBlocks.getFirst()).revertBlock();
               this.lavaBlocks.removeFirst();
               if (this.lavaBlocks.isEmpty()) {
                  this.state = LavaManipulation.State.MONITOR;
               }

               return;
            }

            this.location = fallingBlock.getLocation();
            if (isWater(this.location.getBlock().getBlockData())) {
               fallingBlock.setVelocity(fallingBlock.getVelocity().multiply((double)0.5F));
               this.world.playSound(this.location, Sound.valueOf("BLOCK_LAVA_EXTINGUISH"), SoundCategory.MASTER, 0.5F, 0.9F);
               this.world.spawnParticle(Particle.CLOUD, this.location, 10, 0.2, 0.2, 0.2, 0.05);
               this.state = LavaManipulation.State.EXTINGUISHED;
               return;
            }

            this.affectEntities();
            this.lavaBlocks.add(new TempBlock(this.location.getBlock(), LAVA_DATA, this));
            if ((long)this.lavaBlocks.size() > this.length) {
               ((TempBlock)this.lavaBlocks.getFirst()).revertBlock();
               this.lavaBlocks.removeFirst();
            }
         }
         case EXTINGUISHED -> {
            FallingBlock fallingBlock = this.tempFallingBlock.getFallingBlock();
            if (!this.lavaBlocks.isEmpty()) {
               ((TempBlock)this.lavaBlocks.getFirst()).revertBlock();
               this.lavaBlocks.removeFirst();
            }

            if (fallingBlock.isDead() && this.lavaBlocks.isEmpty()) {
               this.state = LavaManipulation.State.MONITOR;
               return;
            }

            this.location = fallingBlock.getLocation();
            this.affectEntities();
         }
         case MONITOR -> {
            if (this.meltedTempBlocks.isEmpty()) {
               this.remove();
               return;
            }
         }
      }

      this.meltedTempBlocks.removeIf(TempBlock::isReverted);
   }

   private void affectEntities() {
      for(Entity entity : GeneralMethods.getEntitiesAroundPoint(this.location, this.hitbox)) {
         if (entity instanceof LivingEntity && !entity.equals(this.player)) {
            DamageHandler.damageEntity(entity, this.damage, this);
            this.tempFallingBlock.remove();
         }
      }

   }

   public void onClick() {
      switch (this.state.ordinal()) {
         case 0:
            if (this.player.isSneaking()) {
               this.state = LavaManipulation.State.FORWARD;
               return;
            }
            break;
         case 1:
            if (this.player.isSneaking()) {
               this.state = LavaManipulation.State.BACK;
               return;
            }
            break;
         default:
            return;
      }

      this.tempFallingBlock = new TempFallingBlock(this.location, BARRIER_DATA, this.player.getLocation().getDirection().normalize().multiply(this.launchPower), this);
      this.state = LavaManipulation.State.SHOT;
      this.world.playSound(this.location, Sound.valueOf("ITEM_BUCKET_EMPTY_LAVA"), SoundCategory.MASTER, 0.5F, 0.75F);
      this.world.playSound(this.location, Sound.valueOf("ENTITY_WARDEN_HEARTBEAT"), SoundCategory.MASTER, 0.5F, 1.2F);
      this.bPlayer.addCooldown(this);
   }

   private void manageMovement(Vector direction) {
      for(TempBlock tb : this.meltedTempBlocks) {
         if (tb.isReverted()) {
            this.affectedBlocks.remove(tb.getBlock());
         }
      }

      for(TempBlock tb : this.lavaBlocks) {
         if (this.random.nextInt() % 128 == 0) {
            this.world.spawnParticle(Particle.LANDING_LAVA, tb.getLocation().add((double)0.5F, (double)0.0F, (double)0.5F), 1, 0.2, (double)0.0F, 0.2);
            this.world.playSound(this.location, Sound.valueOf("BLOCK_LAVA_POP"), SoundCategory.MASTER, 0.5F, 1.0F);
         }
      }

      Location testLocation = this.location.clone().add(direction);
      if (!(this.player.getLocation().distanceSquared(this.location) > (double)this.maxHoldDistanceSq) && !RegionProtection.isRegionProtected(this.player, testLocation, this)) {
         Block testBlock = testLocation.getBlock();
         if (!isWater(testBlock)) {
            if (!testBlock.getType().isSolid()) {
               if (!this.location.getBlock().equals(testBlock)) {
                  this.lavaBlocks.add(new TempBlock(testBlock, LAVA_DATA, this));
                  if ((long)this.lavaBlocks.size() > this.length) {
                     ((TempBlock)this.lavaBlocks.getFirst()).revertBlock();
                     this.lavaBlocks.removeFirst();
                  }
               }

               this.location = testLocation;
            } else {
               for(Block block : this.getBlocks(testLocation)) {
                  this.melt(block);
               }

            }
         }
      } else {
         this.bPlayer.addCooldown(this);
         this.remove();
      }
   }

   private void melt(Block block) {
      if (!this.affectedBlocks.contains(block)) {
         Platform.scheduler().runLater(() -> {
            if (isPlant(block)) {
               this.meltedTempBlocks.add(new TempBlock(block, AIR_DATA, this.meltDuration, this));
               this.world.spawnParticle(Particle.SMOKE, block.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F), 5, 0.2, 0.2, 0.2, 0.05);
            } else {
               TempBlock tempBlock = new TempBlock(block, MAGMA_BLOCK_DATA, this.meltTime, this);
               Platform.scheduler().runLater(() -> {
                  if (!this.isRemoved() && !(block.getLocation().distance(this.location) > this.meltRadius)) {
                     if (getMovedEarth().containsKey(block)) {
                        tempBlock.revertBlock();
                        revertBlock(block);
                     } else {
                        this.meltedTempBlocks.add(new TempBlock(block, AIR_DATA, this.meltDuration, this));
                     }

                  } else {
                     this.affectedBlocks.remove(block);
                  }
               }, this.meltTime / 50L);
            }
         }, (long)this.random.nextInt(5, 15));
         this.affectedBlocks.add(block);
      }
   }

   private boolean shouldRemove() {
      return this.state != LavaManipulation.State.SHOT && this.state != LavaManipulation.State.MONITOR && this.state != LavaManipulation.State.EXTINGUISHED && !this.bPlayer.canBend(this) || (this.state == LavaManipulation.State.SHOT || this.state == LavaManipulation.State.MONITOR || this.state == LavaManipulation.State.EXTINGUISHED) && !this.bPlayer.canBendIgnoreBindsCooldowns(this);
   }

   private List<Block> getBlocks(Location location) {
      return Utils.getNearBlocks(location, this.meltRadius).stream().filter(this.blockFilter()).toList();
   }

   private Predicate<Block> blockFilter() {
      return (block) -> (isEarthbendable(block.getType(), true, true, false, true) || isPlant(block)) && !RegionProtection.isRegionProtected(this.player, block.getLocation(), this);
   }

   public double getLavaDamage() {
      return this.lavaDamage;
   }

   public int getFireTicks() {
      return this.fireTicks;
   }

   public void remove() {
      this.meltedTempBlocks.forEach(TempBlock::revertBlock);
      this.lavaBlocks.forEach(TempBlock::revertBlock);
      if (this.tempFallingBlock != null) {
         this.tempFallingBlock.remove();
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
      return "LavaManipulation";
   }

   public Location getLocation() {
      return this.location;
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
      return "Control a whip of molten rock, guiding it through the air and melting blocks in its path.";
   }

   public String getInstructions() {
      return "\n(Source) Sneak + click on a lava source\n(Change direction) Click while sneaking to redirect the lava whip\n(Shoot) Left Click to launch the whip";
   }

   public boolean isEnabled() {
      return MainConfig.getConfig().getBoolean("Abilities.LavaManipulation.Enabled");
   }

   static {
      LAVA_DATA = Material.LAVA.createBlockData();
      MAGMA_BLOCK_DATA = Material.MAGMA_BLOCK.createBlockData();
      AIR_DATA = Material.AIR.createBlockData();
      BARRIER_DATA = Material.BARRIER.createBlockData();
   }

   public static enum State {
      BACK,
      FORWARD,
      SHOT,
      EXTINGUISHED,
      MONITOR;
   }
}
