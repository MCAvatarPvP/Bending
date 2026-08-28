package me.macieq.utils;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.region.RegionProtection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import com.projectkorra.projectkorra.platform.mc.Color;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.SoundCategory;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.block.data.Levelled;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

public class Utils {
   private static final ThreadLocalRandom random = ThreadLocalRandom.current();
   private static final BlockData lavaData;
   private static final Particle.DustOptions dustTransition;

   private Utils() {
   }

   public static void playFocusLavaEffect(Location location) {
      Location dustLocation = location.clone().add((double)0.0F, (double)0.5F, (double)0.0F);
      location.getWorld().spawnParticle(Particle.SMOKE, location, 3, 0.2, 0.2, 0.2, 0.02);
      location.getWorld().spawnParticle(Particle.BLOCK, location, 2, 0.2, 0.2, 0.2, (double)1.0F, lavaData);
      location.getWorld().spawnParticle(Particle.DUST, dustLocation, 1, 0.2, 0.1, 0.2, (double)1.0F, dustTransition);
      if (random.nextInt() % 10 == 0) {
         location.getWorld().spawnParticle(Particle.LAVA, location, 2, 0.2, 0.2, 0.2, (double)1.0F);
         location.getWorld().playSound(location, Sound.valueOf("BLOCK_LAVA_POP"), SoundCategory.MASTER, 0.5F, 1.0F);
      }

   }

   public static List<Block> getNearBlocks(Location location, double radius) {
      List<Block> list = new ArrayList<>();
      World world = location.getWorld();
      double ox = location.getX();
      double oy = location.getY();
      double oz = location.getZ();
      int minX = (int)Math.floor(ox - radius);
      int maxX = (int)Math.floor(ox + radius);
      int minY = (int)Math.floor(oy - radius);
      int maxY = (int)Math.floor(oy + radius);
      int minZ = (int)Math.floor(oz - radius);
      int maxZ = (int)Math.floor(oz + radius);
      double maxRSq = radius * radius;

      for(int x = minX; x <= maxX; ++x) {
         double dx = (double)x - ox;

         for(int y = minY; y <= maxY; ++y) {
            double dy = (double)y - oy;

            for(int z = minZ; z <= maxZ; ++z) {
               double dz = (double)z - oz;
               double distSq = dx * dx + dy * dy + dz * dz;
               if (distSq <= maxRSq) {
                  list.add(world.getBlockAt(x, y, z));
               }
            }
         }
      }

      return list;
   }

   public static Block getLavaSource(Player player, double range, CoreAbility ability) {
      return getTargetedBlock(player, range, block -> isLavasourceable(player, block, ability));
   }

   public static Block getTargetedBlock(Player player, double range, Predicate<Block> target) {
      Location cursor = player.getEyeLocation().clone();
      Vector step = cursor.getDirection().normalize().multiply(0.2);
      Block previous = null;

      for (double travelled = 0; travelled <= range; travelled += 0.2) {
         Block block = cursor.getBlock();
         if (!block.equals(previous)) {
            if (target.test(block)) {
               return block;
            }
            if (block.isSolid()) {
               return null;
            }
            previous = block;
         }
         cursor.add(step);
      }
      return null;
   }

   public static boolean isLavasourceable(Player player, Block block, CoreAbility ability) {
      boolean var10000;
      if (ElementalAbility.isLava(block)) {
         BlockData var4 = block.getBlockData();
         if (var4 instanceof Levelled) {
            Levelled levelled = (Levelled)var4;
            if ((levelled.getLevel() < 2 || levelled.getLevel() > 7) && !RegionProtection.isRegionProtected(player, block.getLocation(), ability)) {
               var10000 = true;
               return var10000;
            }
         }
      }

      var10000 = false;
      return var10000;
   }

   static {
      lavaData = Material.LAVA.createBlockData();
      dustTransition = new Particle.DustOptions(Color.fromRGB(255, 102, 0), 1.0F);
   }
}
