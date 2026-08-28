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
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import me.macieq.MainConfig;
import me.macieq.utils.Utils;
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

public class Eruption extends LavaAbility implements AddonAbility {
   private static final Particle.DustOptions DUST_TRANSITION;
   private static final Particle.DustOptions DUST_OPTIONS;
   private static final BlockData MAGMA_BLOCK_DATA;
   private static final BlockData LAVA_DATA;
   private static final BlockData BARRIER_DATA;
   private static final BlockData AIR_DATA;
   private final Set<Entity> hit = new HashSet<>();
   private final Set<TempFallingBlock> columns = new HashSet<>();
   private final Set<TempBlock> tempBlocks = new HashSet<>();
   private final List<Block> blocksToAffect = new ArrayList<>();
   private final World world;
   private final Random random;
   private State state;
   private Location center;
   private double counter;
   private boolean magmaFormed;
   private long createTime;
   private boolean isLava;
   private long cooldown;
   private double damage;
   private double lavaDamage;
   private int fireTicks;
   private double hitbox;
   private double knockup;
   private long sourceRange;
   private double radius;
   private double lavaRadius;
   private double lavaRadiusSq;
   private double lavaCenterRadius;
   private double lavaCenterRadiusSq;
   private double height;
   private double velocityY;
   private double chargeTime;
   private double bigChargeTime;
   private double poolCreationSpeed;
   private long poolDuration;
   private long trailDuration;
   private boolean affectSelf;
   private boolean multihits;

   public Eruption(Player player) {
      super(player);
      this.world = this.player.getWorld();
      this.random = PredictionDeterminism.random(player.getUniqueId(), getClass().getName());
      this.state = Eruption.State.CHARGING;
      this.setConfig();
      this.start();
   }

   private void setConfig() {
      PKConfigurationSection config = MainConfig.getConfig();
      this.cooldown = config.getLong("Abilities.Eruption.Cooldown");
      this.damage = config.getDouble("Abilities.Eruption.Damage");
      this.lavaDamage = config.getDouble("Abilities.Eruption.LavaDamage");
      this.fireTicks = config.getInt("Abilities.Eruption.FireTicks");
      this.hitbox = config.getDouble("Abilities.Eruption.Hitbox");
      this.knockup = config.getDouble("Abilities.Eruption.Knockup");
      this.sourceRange = config.getLong("Abilities.Eruption.SourceRange");
      this.poolCreationSpeed = config.getDouble("Abilities.Eruption.PoolCreationSpeed");
      this.poolDuration = config.getLong("Abilities.Eruption.PoolDuration");
      this.trailDuration = config.getLong("Abilities.Eruption.TrailDuration");
      this.affectSelf = config.getBoolean("Abilities.Eruption.AffectSelf");
      this.multihits = config.getBoolean("Abilities.Eruption.Multihits");
      this.chargeTime = config.getDouble("Abilities.Eruption.SmallEruption.ChargeTime");
      this.bigChargeTime = config.getDouble("Abilities.Eruption.BigEruption.ChargeTime");
   }

   public void progress() {
      if (this.bPlayer.canBendIgnoreBindsCooldowns(this) && (this.state == Eruption.State.PROGRESSING || this.bPlayer.canBend(this))) {
         if (this.createTime != 0L && System.currentTimeMillis() - this.createTime >= this.poolDuration) {
            this.remove();
         } else {
            if (this.state == Eruption.State.PROGRESSING) {
               this.handleProgressing();
            } else {
               this.handleCharging();
            }

         }
      } else {
         this.remove();
      }
   }

   private void handleCharging() {
      long time = System.currentTimeMillis() - this.getStartTime();
      if ((double)time >= this.bigChargeTime) {
         this.state = Eruption.State.BIG_CHARGED;
      } else if ((double)time >= this.chargeTime) {
         this.state = Eruption.State.CHARGED;
      }

      if (this.state == Eruption.State.CHARGED || this.state == Eruption.State.BIG_CHARGED) {
         Location particleLocation = this.player.getEyeLocation().add(this.player.getEyeLocation().getDirection().normalize().multiply(1.2));
         Particle.DustOptions options = this.state == Eruption.State.BIG_CHARGED ? DUST_TRANSITION : DUST_OPTIONS;
         this.world.spawnParticle(Particle.DUST, particleLocation, 1, 0.3, 0.1, 0.3, 0.0, options);
      }

      if (!this.player.isSneaking()) {
         Block block = Utils.getTargetedBlock(this.player, this.sourceRange,
               blockx -> isEarthbendable(blockx.getType(), true, true, false, true)
                     || Utils.isLavasourceable(this.player, blockx, this));
         if (block == null) {
            this.remove();
            return;
         }

         this.center = block.getLocation();
         this.isLava = isLava(block);
         if (this.state == Eruption.State.CHARGING && !this.isLava) {
            this.remove();
            return;
         }

         this.prepareVariables(this.isLava);
         this.startEruption();
      }

   }

   private void prepareVariables(boolean lava) {
      PKConfigurationSection config = MainConfig.getConfig();
      if (lava) {
         this.radius = config.getDouble("Abilities.Eruption.OnLavaEruption.Radius");
         this.lavaCenterRadius = config.getDouble("Abilities.Eruption.OnLavaEruption.CenterRadius");
         this.height = config.getDouble("Abilities.Eruption.OnLavaEruption.Height");
         if (this.lavaCenterRadius > this.radius) {
            this.lavaCenterRadius = this.radius;
         }
      } else {
         switch (this.state.ordinal()) {
            case 1:
               this.radius = config.getDouble("Abilities.Eruption.SmallEruption.MagmaRadius");
               this.lavaRadius = config.getDouble("Abilities.Eruption.SmallEruption.LavaRadius");
               this.lavaCenterRadius = config.getDouble("Abilities.Eruption.SmallEruption.LavaCenterRadius");
               this.height = config.getDouble("Abilities.Eruption.SmallEruption.Height");
               break;
            case 2:
               this.radius = config.getDouble("Abilities.Eruption.BigEruption.MagmaRadius");
               this.lavaRadius = config.getDouble("Abilities.Eruption.BigEruption.LavaRadius");
               this.lavaCenterRadius = config.getDouble("Abilities.Eruption.BigEruption.LavaCenterRadius");
               this.height = config.getDouble("Abilities.Eruption.BigEruption.Height");
         }

         if (this.lavaRadius > this.radius) {
            this.lavaRadius = this.radius;
         }

         if (this.lavaCenterRadius > this.lavaRadius) {
            this.lavaCenterRadius = this.lavaRadius;
         }
      }

      this.lavaRadiusSq = this.lavaRadius * this.lavaRadius;
      this.lavaCenterRadiusSq = this.lavaCenterRadius * this.lavaCenterRadius;
      this.velocityY = Math.sqrt(0.08 * this.height) + 0.015 * this.height;
   }

   private void startEruption() {
      for(Block block : GeneralMethods.getBlocksAroundPoint(this.center, this.radius)) {
         Block up = block.getRelative(BlockFace.UP);
         if (this.isLava) {
            if (!Utils.isLavasourceable(this.player, block, this)) {
               continue;
            }
         } else if (!isEarthbendable(block.getType(), true, true, false, true) || !isTransparent(this.player, this.getName(), up) || up.isLiquid()) {
            continue;
         }

         this.blocksToAffect.add(block);
      }

      if (!this.blocksToAffect.isEmpty() && !this.blocksToAffect.stream().noneMatch((blockx) -> blockx.getLocation().distanceSquared(this.center) <= this.lavaCenterRadiusSq)) {
         Collections.shuffle(this.blocksToAffect, this.random);
         this.state = Eruption.State.PROGRESSING;
         this.bPlayer.addCooldown(this);
      } else {
         this.remove();
      }
   }

   private void handleProgressing() {
      if (this.magmaFormed) {
         this.handleLavaColumns();
      } else {
         this.handlePoolCreating();
      }

   }

   private void handlePoolCreating() {
      if (this.isLava) {
         for(Block b : this.blocksToAffect) {
            if (b.getLocation().distanceSquared(this.center) <= this.lavaCenterRadiusSq) {
               this.lavaColumn(b, this.velocityY);
            }

            this.lavaColumn(b, this.velocityY - 0.1);
         }

         this.world.spawnParticle(Particle.LAVA, this.center.clone().add((double)0.0F, (double)1.0F, (double)0.0F), (int)Math.floor((double)10.0F * this.radius), this.radius / (double)2.0F, (double)1.0F, this.radius / (double)2.0F);
         this.playEruptionSound();
         this.magmaFormed = true;
         this.createTime = System.currentTimeMillis();
      } else {
         while(this.counter >= (double)1.0F) {
            if (this.blocksToAffect.isEmpty()) {
               for(TempBlock tb : this.tempBlocks) {
                  if (tb.getBlockData().getMaterial() != Material.AIR) {
                     double distanceFromCenterSq = tb.getLocation().distanceSquared(this.center);
                     if (distanceFromCenterSq <= this.lavaRadiusSq) {
                        tb.setType(LAVA_DATA);
                        if (distanceFromCenterSq <= this.lavaCenterRadiusSq) {
                           this.lavaColumn(tb.getBlock(), this.velocityY);
                        }

                        this.lavaColumn(tb.getBlock(), this.velocityY - 0.1);
                        Vector vector = tb.getLocation().add((double)0.5F, (double)0.0F, (double)0.5F).toVector().subtract(this.center.toVector()).setY(3).normalize().multiply(this.random.nextDouble() * this.velocityY);
                        new TempFallingBlock(tb.getLocation().add((double)0.5F, (double)1.0F, (double)0.5F), MAGMA_BLOCK_DATA, vector, this);
                     }
                  }
               }

               this.magmaFormed = true;
               this.world.playSound(this.center, Sound.valueOf("ENTITY_GENERIC_EXPLODE"), SoundCategory.MASTER, 0.5F, 0.5F);
               this.playEruptionSound();
               this.createTime = System.currentTimeMillis();
               return;
            }

            Block b = (Block)this.blocksToAffect.getFirst();
            Block check = b;

            for(int i = 0; i < 2; ++i) {
               check = check.getRelative(BlockFace.UP);
               if (isPlant(check) || isSnow(check)) {
                  if (getMovedEarth().containsKey(check)) {
                     check.setType(Material.AIR);
                  } else {
                     this.tempBlocks.add(new TempBlock(check, AIR_DATA, this));
                  }
               }
            }

            if (getMovedEarth().containsKey(b)) {
               revertBlock(b);
            }

            this.tempBlocks.add(new TempBlock(b, MAGMA_BLOCK_DATA, this));
            this.blocksToAffect.removeFirst();
            --this.counter;
         }

         this.counter += this.poolCreationSpeed;
      }

   }

   private void handleLavaColumns() {
      Set<Entity> processedEntities = new HashSet<>();
      Iterator<TempFallingBlock> iterator = this.columns.iterator();

      while(iterator.hasNext()) {
         FallingBlock fallingBlock = ((TempFallingBlock)iterator.next()).getFallingBlock();
         Location location = fallingBlock.getLocation();
         if (!fallingBlock.isDead() && !RegionProtection.isRegionProtected(this.player, location, this)) {
            for(Entity entity : GeneralMethods.getEntitiesAroundPoint(location, this.hitbox)) {
               if (entity instanceof LivingEntity && !processedEntities.contains(entity) && (!entity.equals(this.player) || this.affectSelf)) {
                  if (fallingBlock.getVelocity().getY() > (double)0.0F) {
                     GeneralMethods.setVelocity(this, entity, new Vector((double)0.0F, this.knockup, (double)0.0F));
                  }

                  processedEntities.add(entity);
                  if (!this.hit.contains(entity)) {
                     DamageHandler.damageEntity(entity, this.damage, this);
                     if (!this.multihits) {
                        this.hit.add(entity);
                     }
                  }
               }
            }

            this.tempBlocks.add(new TempBlock(location.getBlock(), LAVA_DATA, this.trailDuration, this));
         } else {
            iterator.remove();
         }
      }

      this.tempBlocks.removeIf(TempBlock::isReverted);
   }

   private void lavaColumn(Block block, double height) {
      this.columns.add(new TempFallingBlock(block.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F), BARRIER_DATA, new Vector((double)0.0F, height, (double)0.0F), this));
   }

   private void playEruptionSound() {
      this.world.playSound(this.center, Sound.valueOf("ITEM_BUCKET_EMPTY_LAVA"), SoundCategory.MASTER, 2.0F, 0.5F);
      this.world.playSound(this.center, Sound.valueOf("ENTITY_SQUID_AMBIENT"), SoundCategory.MASTER, 2.0F, 0.7F);
      this.world.playSound(this.center, Sound.valueOf("ENTITY_WARDEN_HEARTBEAT"), SoundCategory.MASTER, 2.0F, 1.0F);
      this.world.playSound(this.center, Sound.valueOf("ENTITY_WITCH_DRINK"), SoundCategory.MASTER, 0.5F, 0.7F);
   }

   public double getLavaDamage() {
      return this.lavaDamage;
   }

   public int getFireTicks() {
      return this.fireTicks;
   }

   public void remove() {
      this.tempBlocks.forEach(TempBlock::revertBlock);
      this.columns.forEach(TempFallingBlock::remove);
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
      return "Eruption";
   }

   public Location getLocation() {
      return null;
   }

   public List<Location> getLocations() {
      return this.columns.stream().map(TempFallingBlock::getLocation).toList();
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
      return "Erupt lava from beneath the ground, launching enemies into the air and leaving behind a pool of molten rock. Using Eruption on an existing lava pool allows you to unleash it instantly without charging";
   }

   public String getInstructions() {
      return "Hold Sneak to charge, then release Sneak to erupt lava at the targeted location. Tap Sneak while targeting lava to erupt instantly without charging";
   }

   public boolean isEnabled() {
      return MainConfig.getConfig().getBoolean("Abilities.Eruption.Enabled");
   }

   static {
      DUST_TRANSITION = new Particle.DustOptions(Color.RED, 1.0F);
      DUST_OPTIONS = new Particle.DustOptions(Color.BLACK, 1.0F);
      MAGMA_BLOCK_DATA = Material.MAGMA_BLOCK.createBlockData();
      LAVA_DATA = Material.LAVA.createBlockData();
      BARRIER_DATA = Material.BARRIER.createBlockData();
      AIR_DATA = Material.AIR.createBlockData();
   }

   private static enum State {
      CHARGING,
      CHARGED,
      BIG_CHARGED,
      PROGRESSING;
   }
}
