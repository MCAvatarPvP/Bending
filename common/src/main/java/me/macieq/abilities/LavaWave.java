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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import me.macieq.MainConfig;
import me.macieq.utils.Utils;
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

public class LavaWave extends LavaAbility implements AddonAbility {
   private static final BlockData LAVA_DATA;
   private static final BlockData BARRIER_DATA;
   private final Set<TempFallingBlock> streams = new HashSet<>();
   private final Set<Entity> hit = new HashSet<>();
   private final World world;
   private final Random random;
   private final Block sourceBlock;
   private Location sourceLocation;
   private Location streamSpawnLocation;
   private boolean progressing;
   private double sourceRange;
   private double keepSourceRangeSq;
   private double hitbox;
   private boolean removeOnHit;
   private boolean affectSelf;
   private boolean multihits;
   private double damage;
   private double knockback;
   private int yaw;
   private int pitch;
   private int amount;
   private double launchPower;
   private long cooldown;
   private long trailDuration;
   private double lavaDamage;
   private int fireTicks;

   public LavaWave(Player player) {
      super(player);
      this.world = this.player.getWorld();
      this.random = PredictionDeterminism.random(player.getUniqueId(), getClass().getName());
      this.setConfig();
      this.sourceBlock = Utils.getLavaSource(player, this.sourceRange, this);
      if (this.sourceBlock != null) {
         this.sourceLocation = this.sourceBlock.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F);
         this.streamSpawnLocation = this.sourceLocation.clone().add((double)0.0F, (double)1.0F, (double)0.0F);
         this.start();
      }
   }

   private void setConfig() {
      PKConfigurationSection config = MainConfig.getConfig();
      this.cooldown = config.getLong("Abilities.LavaWave.Cooldown");
      this.launchPower = config.getDouble("Abilities.LavaWave.LaunchPower");
      this.damage = config.getDouble("Abilities.LavaWave.Damage");
      this.lavaDamage = config.getDouble("Abilities.LavaWave.LavaDamage");
      this.fireTicks = config.getInt("Abilities.LavaWave.FireTicks");
      this.hitbox = config.getDouble("Abilities.LavaWave.Hitbox");
      this.sourceRange = (double)config.getLong("Abilities.LavaWave.SourceRange");
      long keepSourceRange = config.getLong("Abilities.LavaWave.KeepSourceRange");
      this.keepSourceRangeSq = (double)(keepSourceRange * keepSourceRange);
      this.amount = config.getInt("Abilities.LavaWave.Amount");
      this.yaw = config.getInt("Abilities.LavaWave.Width") / 2;
      this.pitch = config.getInt("Abilities.LavaWave.Height");
      this.knockback = config.getDouble("Abilities.LavaWave.Knockback");
      this.trailDuration = config.getLong("Abilities.LavaWave.TrailDuration");
      this.affectSelf = config.getBoolean("Abilities.LavaWave.AffectSelf");
      this.multihits = config.getBoolean("Abilities.LavaWave.Multihits");
      this.removeOnHit = config.getBoolean("Abilities.LavaWave.RemoveOnHit");
      if (this.pitch <= 10) {
         this.pitch = 11;
      }

   }

   public void progress() {
      if (this.shouldRemove()) {
         this.remove();
      } else {
         this.progressStreams();
         if (!this.progressing) {
            Utils.playFocusLavaEffect(this.sourceLocation);
         }

      }
   }

   private void progressStreams() {
      Iterator<TempFallingBlock> iterator = this.streams.iterator();

      while(iterator.hasNext()) {
         TempFallingBlock tfb = (TempFallingBlock)iterator.next();
         FallingBlock fb = tfb.getFallingBlock();
         Location location = tfb.getLocation();
         if (!fb.isDead() && !RegionProtection.isRegionProtected(this.player, location, this)) {
            if (isWater(location.getBlock().getBlockData())) {
               this.world.playSound(location, Sound.valueOf("BLOCK_LAVA_EXTINGUISH"), SoundCategory.MASTER, 0.5F, 0.9F);
               this.world.spawnParticle(Particle.CLOUD, location, 10, 0.2, 0.2, 0.2, 0.05);
               iterator.remove();
               return;
            }

            new TempBlock(location.getBlock(), LAVA_DATA, this.trailDuration, this);

            for(Entity entity : GeneralMethods.getEntitiesAroundPoint(location, this.hitbox)) {
               if (entity instanceof LivingEntity && (this.affectSelf || !entity.equals(this.player))) {
                  if (!this.hit.contains(entity)) {
                     DamageHandler.damageEntity(entity, this.damage, this);
                     GeneralMethods.setVelocity(this, entity, fb.getVelocity().clone().normalize().multiply(this.knockback).add(new Vector((double)0.0F, 0.2, (double)0.0F)));
                     if (!this.multihits) {
                        this.hit.add(entity);
                     }
                  }

                  if (this.removeOnHit) {
                     tfb.remove();
                     iterator.remove();
                  }
               }
            }

            this.world.spawnParticle(Particle.BLOCK, location, 1, (double)0.0F, (double)0.0F, (double)0.0F, (double)0.0F, LAVA_DATA);
            this.world.spawnParticle(Particle.SMOKE, location, 1, 0.2, 0.2, 0.2, 0.05);
            if (this.random.nextInt() % (this.amount * 4) == 0) {
               this.world.playSound(location, Sound.valueOf("BLOCK_LAVA_POP"), SoundCategory.MASTER, 0.5F, 1.0F);
            }
         } else {
            tfb.remove();
            iterator.remove();
         }
      }

   }

   private boolean shouldRemove() {
      if (this.progressing) {
         return this.streams.isEmpty() || !this.bPlayer.canBendIgnoreBindsCooldowns(this);
      } else {
         return !this.player.isSneaking() || this.player.getLocation().distanceSquared(this.sourceLocation) > this.keepSourceRangeSq || !Utils.isLavasourceable(this.player, this.sourceBlock, this) || !this.bPlayer.canBend(this);
      }
   }

   public void onClick() {
      if (!this.progressing) {
         Location origin = this.player.getEyeLocation();

         for(int i = 0; i < this.amount; ++i) {
            Location location = origin.clone();
            int width = (int)Math.floor((double)this.yaw * (this.random.nextDouble() * (double)2.0F - (double)1.0F));
            int height = -this.random.nextInt(10, this.pitch);
            location.setYaw(origin.getYaw() + (float)width);
            location.setPitch((float)height);
            Vector direction = location.getDirection().normalize().multiply(this.launchPower);
            this.streams.add(new TempFallingBlock(this.streamSpawnLocation, BARRIER_DATA, direction, this));
         }

         this.world.spawnParticle(Particle.LAVA, this.sourceLocation, 15, 0.2, 0.2, 0.2, (double)1.0F);
         this.world.playSound(this.sourceLocation, Sound.valueOf("ITEM_BUCKET_EMPTY_LAVA"), SoundCategory.MASTER, 1.5F, 1.0F);
         this.world.playSound(this.sourceLocation, Sound.valueOf("ENTITY_SQUID_AMBIENT"), SoundCategory.MASTER, 1.5F, 1.2F);
         this.world.playSound(this.sourceLocation, Sound.valueOf("ENTITY_WARDEN_HEARTBEAT"), SoundCategory.MASTER, 1.5F, 1.5F);
         this.world.playSound(this.sourceLocation, Sound.valueOf("ENTITY_WITCH_DRINK"), SoundCategory.MASTER, 0.5F, 1.2F);
         this.progressing = true;
         this.bPlayer.addCooldown(this);
      }
   }

   public double getLavaDamage() {
      return this.lavaDamage;
   }

   public int getFireTicks() {
      return this.fireTicks;
   }

   public void remove() {
      this.streams.forEach(TempFallingBlock::remove);
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
      return "LavaWave";
   }

   public Location getLocation() {
      return null;
   }

   public List<Location> getLocations() {
      List<Location> list = new ArrayList<>();

      for(TempFallingBlock tfb : this.streams) {
         list.add(tfb.getLocation());
      }

      return list;
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
      return "Source nearby lava and unleash a wave of molten rock that damages and knocks back enemies caught in its path";
   }

   public String getInstructions() {
      return "Hold sneak on a lava source, then click to launch a lava wave";
   }

   public boolean isEnabled() {
      return MainConfig.getConfig().getBoolean("Abilities.LavaWave.Enabled");
   }

   static {
      LAVA_DATA = Material.LAVA.createBlockData();
      BARRIER_DATA = Material.BARRIER.createBlockData();
   }
}
