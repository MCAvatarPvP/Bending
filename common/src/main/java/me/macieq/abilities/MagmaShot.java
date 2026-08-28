package me.macieq.abilities;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.LavaAbility;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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

public class MagmaShot extends LavaAbility implements AddonAbility {
   private static final BlockData LAVA_DATA;
   private static final BlockData MAGMA_BLOCK_DATA;
   private final Map<TempFallingBlock, Boolean> shots = new HashMap<>();
   private final World world;
   private Block sourceBlock;
   private Location sourceLocation;
   private Location shotSpawnLocation;
   private boolean progressing;
   private double sourceRange;
   private double keepSourceRangeSq;
   private double hitbox;
   private double damage;
   private double launchPower;
   private int amount;
   private long cooldown;
   private long trailDuration;
   private double lavaDamage;
   private int fireTicks;

   public MagmaShot(Player player) {
      super(player);
      this.world = this.player.getWorld();
      MagmaShot existing = (MagmaShot)getAbility(player, this.getClass());
      if (existing != null) {
         if (existing.progressing) {
            return;
         }

         existing.remove();
      }

      this.setConfig();
      this.sourceBlock = Utils.getLavaSource(player, this.sourceRange, this);
      if (this.sourceBlock != null) {
         this.sourceLocation = this.sourceBlock.getLocation().add((double)0.5F, (double)0.5F, (double)0.5F);
         this.shotSpawnLocation = this.sourceLocation.clone().add((double)0.0F, (double)1.0F, (double)0.0F);
         this.start();
      }
   }

   private void setConfig() {
      PKConfigurationSection config = MainConfig.getConfig();
      this.cooldown = config.getLong("Abilities.MagmaShot.Cooldown");
      this.launchPower = config.getDouble("Abilities.MagmaShot.LaunchPower");
      this.damage = config.getDouble("Abilities.MagmaShot.Damage");
      this.lavaDamage = config.getDouble("Abilities.MagmaShot.LavaDamage");
      this.fireTicks = config.getInt("Abilities.MagmaShot.FireTicks");
      this.hitbox = config.getDouble("Abilities.MagmaShot.Hitbox");
      this.sourceRange = (double)config.getLong("Abilities.MagmaShot.SourceRange");
      long keepSourceRange = config.getLong("Abilities.MagmaShot.KeepSourceRange");
      this.keepSourceRangeSq = (double)(keepSourceRange * keepSourceRange);
      this.amount = config.getInt("Abilities.MagmaShot.Amount");
      this.trailDuration = (long)config.getInt("Abilities.MagmaShot.TrailDuration");
   }

   public void progress() {
      this.check();
      if ((this.amount != 0 || !this.shots.isEmpty()) && this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
         this.progressShots();
         if (this.amount > 0) {
            Utils.playFocusLavaEffect(this.sourceLocation);
         }

      } else {
         this.remove();
      }
   }

   private void progressShots() {
      Iterator<Map.Entry<TempFallingBlock, Boolean>> iterator = this.shots.entrySet().iterator();

      while(iterator.hasNext()) {
         Map.Entry<TempFallingBlock, Boolean> entry = iterator.next();
         TempFallingBlock tfb = entry.getKey();
         FallingBlock fb = tfb.getFallingBlock();
         Location location = tfb.getLocation();
         boolean extinguished = entry.getValue();
         if (!fb.isDead() && !RegionProtection.isRegionProtected(this.player, location, this)) {
            if (isWater(location.getBlock().getBlockData()) && !extinguished) {
               FallingBlock fallingBlock = tfb.getFallingBlock();
               fallingBlock.setVelocity(fallingBlock.getVelocity().multiply((double)0.5F));
               this.world.playSound(location, Sound.valueOf("BLOCK_LAVA_EXTINGUISH"), SoundCategory.MASTER, 0.5F, 0.9F);
               this.world.spawnParticle(Particle.CLOUD, location, 10, 0.2, 0.2, 0.2, 0.05);
               extinguished = true;
               this.shots.put(tfb, true);
            }

            if (!extinguished) {
               new TempBlock(location.getBlock(), LAVA_DATA, this.trailDuration, this);
               this.world.spawnParticle(Particle.BLOCK, location, 1, (double)0.0F, (double)0.0F, (double)0.0F, (double)0.0F, LAVA_DATA);
               this.world.spawnParticle(Particle.SMOKE, location, 5, 0.2, 0.2, 0.2, 0.05);
            }

            for(Entity e : GeneralMethods.getEntitiesAroundPoint(location, this.hitbox)) {
               if (e instanceof LivingEntity && !e.equals(this.player)) {
                  DamageHandler.damageEntity(e, this.player, this.damage, this, false, false);
                  tfb.remove();
                  iterator.remove();
                  this.world.spawnParticle(Particle.BLOCK, location, 20, (double)0.5F, (double)0.5F, (double)0.5F, 0.05, MAGMA_BLOCK_DATA);
                  break;
               }
            }
         } else {
            tfb.remove();
            iterator.remove();
         }
      }

   }

   public void onClick() {
      if (this.amount > 0) {
         this.progressing = true;
         Vector direction = this.player.getEyeLocation().getDirection().normalize().multiply(this.launchPower).add(new Vector((double)0.0F, 0.2, (double)0.0F));
         this.shots.put(new TempFallingBlock(this.shotSpawnLocation, MAGMA_BLOCK_DATA, direction, this), false);
         this.world.spawnParticle(Particle.LAVA, this.sourceLocation, 10, 0.2, 0.2, 0.2, (double)1.0F);
         this.world.playSound(this.sourceLocation, Sound.valueOf("ITEM_BUCKET_EMPTY_LAVA"), SoundCategory.MASTER, 0.5F, 0.75F);
         this.world.playSound(this.sourceLocation, Sound.valueOf("ENTITY_WARDEN_HEARTBEAT"), SoundCategory.MASTER, 0.5F, 1.2F);
         --this.amount;
         if (this.amount == 0) {
            this.bPlayer.addCooldown(this);
         }
      }

   }

   private void check() {
      if (this.amount > 0) {
         if (!this.bPlayer.canBend(this)) {
            if (this.progressing) {
               this.bPlayer.addCooldown(this);
            }

            this.amount = 0;
         } else {
            if (!Utils.isLavasourceable(this.player, this.sourceBlock, this) || this.player.getLocation().distanceSquared(this.sourceLocation) > this.keepSourceRangeSq) {
               if (this.progressing) {
                  this.bPlayer.addCooldown(this);
               }

               this.amount = 0;
            }

         }
      }
   }

   public double getLavaDamage() {
      return this.lavaDamage;
   }

   public int getFireTicks() {
      return this.fireTicks;
   }

   public void remove() {
      this.shots.keySet().forEach(TempFallingBlock::remove);
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
      return "MagmaShot";
   }

   public Location getLocation() {
      return null;
   }

   public List<Location> getLocations() {
      return this.shots.keySet().stream().map(TempFallingBlock::getLocation).toList();
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
      return "Harness nearby lava and launch a barrage of magma projectiles, each leaving a brief trail of molten rock in its wake";
   }

   public String getInstructions() {
      return "Sneak to select a lava source, then click to launch a magma projectile";
   }

   public boolean isEnabled() {
      return MainConfig.getConfig().getBoolean("Abilities.MagmaShot.Enabled");
   }

   static {
      LAVA_DATA = Material.LAVA.createBlockData();
      MAGMA_BLOCK_DATA = Material.MAGMA_BLOCK.createBlockData();
   }
}
