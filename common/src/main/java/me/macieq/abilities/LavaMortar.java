package me.macieq.abilities;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.LavaAbility;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import me.macieq.MainConfig;
import me.macieq.utils.Utils;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.Color;
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
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

public class LavaMortar extends LavaAbility implements AddonAbility, ComboAbility {
   private static final Particle.DustOptions DUST_OPTIONS = new Particle.DustOptions(Color.fromRGB(255, 132, 30), 1.0F);
   private static final BlockData LAVA_DATA;
   private static final BlockData MAGMA_BLOCK_DATA;
   private static final BlockData AIR_DATA;
   private static final BlockData BARRIER_DATA;
   private final Random random;
   private final List<Location> sources = new ArrayList<>();
   private final List<TempBlock> tempBlocks = new ArrayList<>();
   private Location location;
   private Vector direction;
   private State state;
   private final World world;
   private double radius;
   private int tick;
   private long cooldown;
   private double launchPower;
   private double gravity;
   private double minDamage;
   private double maxDamage;
   private double sourceRadius;
   private double sourceSpeed;
   private double manipulationSpeed;
   private double maxRadius;
   private long sourceInterval;
   private double radiusPerSource;
   private double minPoolRadius;
   private double maxPoolRadius;
   private long magmaDuration;
   private long lavaDuration;
   private double lavaDamage;
   private int fireTicks;

   public LavaMortar(Player player) {
      super(player);
      this.random = PredictionDeterminism.random(player.getUniqueId(), getClass().getName());
      this.state = LavaMortar.State.SOURCING;
      this.world = this.player.getWorld();
      this.radius = (double)0.0F;
      if (this.bPlayer.canBendIgnoreBinds(this)) {
         this.setConfig();
         Location eye = player.getEyeLocation();
         this.location = eye.add(eye.getDirection().multiply(4));
         this.start();
      }
   }

   private void setConfig() {
      PKConfigurationSection config = MainConfig.getConfig();
      this.cooldown = config.getLong("Abilities.LavaMortar.Cooldown");
      this.launchPower = config.getDouble("Abilities.LavaMortar.LaunchPower");
      this.gravity = config.getDouble("Abilities.LavaMortar.Gravity");
      this.minDamage = config.getDouble("Abilities.LavaMortar.MinDamage");
      this.maxDamage = config.getDouble("Abilities.LavaMortar.MaxDamage");
      this.sourceRadius = config.getDouble("Abilities.LavaMortar.SourceRadius");
      this.sourceSpeed = config.getDouble("Abilities.LavaMortar.SourceSpeed");
      this.manipulationSpeed = config.getDouble("Abilities.LavaMortar.ManipulationSpeed");
      this.maxRadius = config.getDouble("Abilities.LavaMortar.MaxRadius");
      this.sourceInterval = config.getLong("Abilities.LavaMortar.SourceInterval") / 50L;
      this.radiusPerSource = config.getDouble("Abilities.LavaMortar.RadiusGainPerSource");
      this.minPoolRadius = config.getDouble("Abilities.LavaMortar.MinPoolRadius");
      this.maxPoolRadius = config.getDouble("Abilities.LavaMortar.MaxPoolRadius");
      this.magmaDuration = config.getLong("Abilities.LavaMortar.MagmaDuration");
      this.lavaDuration = config.getLong("Abilities.LavaMortar.LavaDuration");
      this.lavaDamage = config.getDouble("Abilities.LavaMortar.LavaDamage");
      this.fireTicks = config.getInt("Abilities.LavaMortar.FireTicks");
   }

   public void progress() {
      if ((this.state != LavaMortar.State.SOURCING || this.bPlayer.canBendIgnoreBinds(this) && this.bPlayer.getBoundAbilityName().equalsIgnoreCase("LavaManipulation")) && this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
         switch (this.state.ordinal()) {
            case 0 -> this.source();
            case 1 -> this.shot();
            case 2 -> this.monitor();
         }

         if (this.state != LavaMortar.State.MONITOR) {
            List<Block> blocks = Utils.getNearBlocks(this.location, this.radius);
            if (blocks.isEmpty() && this.radius > (double)0.0F) {
               blocks.add(this.location.getBlock());
            }

            for(Block block : blocks) {
               if (!block.isSolid() && !isPlant(block)) {
                  this.tempBlocks.add(new TempBlock(block, LAVA_DATA, (long)(this.random.nextInt(2) * 100 + 100), this));
               }
            }
         }

         this.tempBlocks.removeIf(TempBlock::isReverted);
         ++this.tick;
      } else {
         this.remove();
      }
   }

   private void source() {
      if (!this.player.isSneaking()) {
         if (this.radius == (double)0.0F) {
            this.remove();
         } else {
            this.state = LavaMortar.State.SHOT;
            this.direction = this.player.getEyeLocation().getDirection().normalize().multiply(this.launchPower);
            this.sources.clear();
            this.bPlayer.addCooldown(this);
            this.world.playSound(this.location, Sound.valueOf("ITEM_BUCKET_FILL_LAVA"), SoundCategory.MASTER, 2.0F, 0.6F);
            this.world.playSound(this.location, Sound.valueOf("ENTITY_SQUID_AMBIENT"), SoundCategory.MASTER, 2.0F, 0.7F);
            this.world.playSound(this.location, Sound.valueOf("ENTITY_WARDEN_HEARTBEAT"), SoundCategory.MASTER, 2.0F, 1.0F);
         }
      } else if (this.radius > (double)0.0F && this.tempBlocks.isEmpty()) {
         this.remove();
      } else {
         Location eye = this.player.getEyeLocation();
         eye.add(eye.getDirection().multiply((double)4.0F + this.radius));
         if (this.radius == (double)0.0F) {
            this.location = eye;
         } else if (this.location.distanceSquared(eye) > this.manipulationSpeed) {
            this.location.add(GeneralMethods.getDirection(this.location, eye).normalize().multiply(this.manipulationSpeed));
         }

         if (this.radius < this.maxRadius) {
            if ((long)this.tick % this.sourceInterval == 0L) {
               List<Block> blocks = Utils.getNearBlocks(this.location, this.sourceRadius).stream().filter((block) -> isLava(block) && !this.tempBlocks.contains(TempBlock.get(block))).toList();
               if (!blocks.isEmpty()) {
                  Location source = ((Block)blocks.get(this.random.nextInt(blocks.size()))).getLocation().add((double)0.5F, (double)0.5F, (double)0.5F);
                  this.sources.add(source);
                  this.world.spawnParticle(Particle.LAVA, source, 2, 0.2, 0.2, 0.2);
                  this.world.playSound(source, Sound.valueOf("ITEM_BUCKET_EMPTY_LAVA"), SoundCategory.MASTER, 0.2F, 0.5F);
               }
            }

            this.handleSources();
         }

      }
   }

   private void handleSources() {
      Iterator<Location> iterator = this.sources.iterator();

      while(iterator.hasNext()) {
         Location source = (Location)iterator.next();
         if (source.getBlock().isSolid()) {
            iterator.remove();
         } else if (source.distanceSquared(this.location) <= this.manipulationSpeed) {
            this.radius += this.radiusPerSource;
            this.radius = Math.min(this.maxRadius, this.radius);
            if (this.radius >= this.maxRadius) {
               this.sources.clear();
               return;
            }

            this.world.playSound(source, Sound.valueOf("ITEM_BUCKET_FILL_LAVA"), SoundCategory.MASTER, 0.2F, 0.5F);
            iterator.remove();
         } else {
            this.spawnSourceParticle(source);
            source.add(GeneralMethods.getDirection(source, this.location).normalize().multiply(this.sourceSpeed));
         }
      }

   }

   private void spawnSourceParticle(Location location) {
      this.world.spawnParticle(Particle.BLOCK, location, 1, 0.05, 0.05, 0.05, 0.0, Material.LAVA.createBlockData());
      this.world.spawnParticle(Particle.FALLING_LAVA, location, 1, 0.1, 0.1, 0.1);
      this.world.spawnParticle(Particle.DUST, location, 2, 0.1, 0.1, 0.1, 0.0, DUST_OPTIONS);
   }

   private void shot() {
      Block hitBlock = this.findImpactBlock();
      if (hitBlock != null) {
         if (isWater(hitBlock.getBlockData())) {
            this.world.playSound(this.location, Sound.valueOf("BLOCK_LAVA_EXTINGUISH"), SoundCategory.MASTER, 0.5F, 0.9F);
            this.world.spawnParticle(Particle.CLOUD, this.location, (int)this.radius * 20, this.radius, 0.2, this.radius, 0.05);
         } else {
            this.explode(hitBlock);
         }

         this.state = LavaMortar.State.MONITOR;
      } else {
         for(Entity entity : GeneralMethods.getEntitiesAroundPoint(this.location, Math.max(this.radius, (double)1.0F))) {
            if (entity instanceof LivingEntity && !entity.equals(this.player)) {
               DamageHandler.damageEntity(entity, this.minDamage + (this.maxDamage - this.minDamage) * (this.radius / this.maxRadius), this);
               this.explode(entity.getLocation().getBlock());
               this.state = LavaMortar.State.MONITOR;
            }
         }

         this.direction.setY(this.direction.getY() - this.gravity);
         this.location.add(this.direction);
      }
   }

   private Block findImpactBlock() {
      Vector step = this.direction.clone().normalize().multiply(0.1);
      Location cursor = this.location.clone();
      double range = Math.max(1.0, this.radius + this.direction.length());
      Block previous = null;

      for(double travelled = 0.0; travelled <= range; travelled += 0.1) {
         Block block = cursor.getBlock();
         if (!block.equals(previous)) {
            previous = block;
            TempBlock tempBlock = TempBlock.get(block);
            boolean ownProjectile = tempBlock != null && tempBlock.getAbility().orElse(null) == this;
            if (!ownProjectile && (isWater(block.getBlockData()) || isLava(block) || !block.isPassable())) {
               return block;
            }
         }

         cursor.add(step);
      }

      return null;
   }

   private void explode(Block hit) {
      for(int i = 0; i < 5; ++i) {
         Location loc = ((TempBlock)this.tempBlocks.get(this.random.nextInt(this.tempBlocks.size()))).getLocation();
         Vector velocity = loc.toVector().subtract(hit.getLocation().toVector()).normalize().multiply(0.45);
         new TempFallingBlock(loc, BARRIER_DATA, velocity, this);
      }

      for(Block block : Utils.getNearBlocks(hit.getLocation(), this.minPoolRadius + (this.maxPoolRadius - this.minPoolRadius) * (this.radius / this.maxRadius)).stream().filter(this.blockFilter()).toList()) {
         Platform.scheduler().runLater(() -> {
            if (!this.isRemoved()) {
               TempBlock tempBlock = new TempBlock(block, MAGMA_BLOCK_DATA, this.magmaDuration, this);
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
                     }

                     this.tempBlocks.add(new TempBlock(block, LAVA_DATA, this.lavaDuration, this));
                  }
               }, this.magmaDuration / 50L);
            }
         }, (long)this.distance(hit, block) * 2L);
      }

      this.world.playSound(this.location, Sound.valueOf("ITEM_BUCKET_FILL_LAVA"), SoundCategory.MASTER, 2.0F, 0.6F);
      this.world.playSound(this.location, Sound.valueOf("ENTITY_SQUID_AMBIENT"), SoundCategory.MASTER, 2.0F, 0.7F);
      this.world.playSound(this.location, Sound.valueOf("ENTITY_WARDEN_HEARTBEAT"), SoundCategory.MASTER, 2.0F, 1.0F);
   }

   private int distance(Block from, Block to) {
      return Math.max(Math.abs(from.getX() - to.getX()), Math.abs(from.getZ() - to.getZ()));
   }

   private void monitor() {
      if (this.tempBlocks.isEmpty()) {
         this.remove();
      } else {
         Iterator<TempFallingBlock> iterator = TempFallingBlock.getFromAbility(this).iterator();

         while(iterator.hasNext()) {
            TempFallingBlock tempFallingBlock = (TempFallingBlock)iterator.next();
            FallingBlock fallingBlock = tempFallingBlock.getFallingBlock();
            if (fallingBlock.isDead()) {
               iterator.remove();
            } else {
               this.spawnSourceParticle(fallingBlock.getLocation());
               if (this.random.nextInt() % 4 == 0) {
                  this.world.spawnParticle(Particle.LAVA, fallingBlock.getLocation(), 1, (double)0.0F, (double)0.0F, (double)0.0F, (double)0.0F);
               }
            }
         }

      }
   }

   private Predicate<Block> blockFilter() {
      return (block) -> isEarthbendable(block.getType(), true, true, false, true) && this.isTouchingTransparent(block) && !isWater(block.getRelative(BlockFace.UP)) && !RegionProtection.isRegionProtected(this.player, block.getLocation(), this);
   }

   private boolean isTouchingTransparent(Block block) {
      BlockFace[] faces = new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST};

      for(BlockFace face : faces) {
         if (this.isTransparent(block.getRelative(face))) {
            return true;
         }
      }

      return false;
   }

   public double getLavaDamage() {
      return this.lavaDamage;
   }

   public int getFireTicks() {
      return this.fireTicks;
   }

   public void remove() {
      this.tempBlocks.forEach(TempBlock::revertBlock);
      TempFallingBlock.getFromAbility(this).forEach(TempFallingBlock::remove);
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
      return "LavaMortar";
   }

   public Location getLocation() {
      return this.location;
   }

   public void load() {
   }

   public void stop() {
   }

   public String getAuthor() {
      return "(Code) Macie_Q, (Concept) Kojlerek, Macie_Q";
   }

   public String getVersion() {
      return "1.0";
   }

   public String getDescription() {
      return "Gather nearby lava into a molten projectile and launch it into the air. Upon striking an enemy or the ground, it bursts into a small pool of lava";
   }

   public String getInstructions() {
      return "(MagmaShot) Shift down -> (MagmaShot) Shift up -> (MagmaShot) Shift down -> (LavaManipulation) Shift up -> (LavaManipulation) Shift down";
   }

   public Object createNewComboInstance(Player player) {
      return new LavaMortar(player);
   }

   public ArrayList<ComboManager.AbilityInformation> getCombination() {
      return new ArrayList<>(List.of(new ComboManager.AbilityInformation("MagmaShot", ClickType.SHIFT_DOWN), new ComboManager.AbilityInformation("MagmaShot", ClickType.SHIFT_UP), new ComboManager.AbilityInformation("MagmaShot", ClickType.SHIFT_DOWN), new ComboManager.AbilityInformation("LavaManipulation", ClickType.SHIFT_UP), new ComboManager.AbilityInformation("LavaManipulation", ClickType.SHIFT_DOWN)));
   }

   public boolean isEnabled() {
      return MainConfig.getConfig().getBoolean("Abilities.LavaMortar.Enabled");
   }

   static {
      LAVA_DATA = Material.LAVA.createBlockData();
      MAGMA_BLOCK_DATA = Material.MAGMA_BLOCK.createBlockData();
      AIR_DATA = Material.AIR.createBlockData();
      BARRIER_DATA = Material.BARRIER.createBlockData();
   }

   private static enum State {
      SOURCING,
      SHOT,
      MONITOR;
   }
}
