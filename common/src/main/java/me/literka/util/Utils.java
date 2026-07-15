package me.literka.util;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.*;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.ChatUtil;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.prediction.PredictedContactSync;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import me.literka.abilities.StickyBomb;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Utils {

    public static Map<Player, Long> chiblocks = new ConcurrentHashMap<>();

    public static boolean willChiBlock(Player player, double chance) {
        BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer == null) return false;

        if (Math.random() > chance / 100.0) return false;
        else return !bPlayer.isChiBlocked();
    }

    public static void blockChi(Player player, long duration) {
        if (player == null) return;
        BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer == null) return;

        bPlayer.blockChi();
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_HURT, 1F, 0.0F);
        chiblocks.put(player, System.currentTimeMillis() + duration);
    }

    private static void processChiBlocks() {
        for (Map.Entry<Player, Long> entry : chiblocks.entrySet()) {
            Player player = entry.getKey();
            long time = entry.getValue();
            BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
            if (bPlayer == null) return;

            sendActionBar(Element.CHI.getColor() + "* Chiblocked *", player);

            if (System.currentTimeMillis() >= time) {
                bPlayer.unblockChi();
                chiblocks.remove(player);
                sendActionBar("", player);
            }
        }
    }

    private static void processBombCooldowns() {
        for (Map.Entry<Player, List<Long>> entry : StickyBomb.cooldowns.entrySet()) {
            Player player = entry.getKey();
            BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);

            Iterator<Long> iter = entry.getValue().iterator();
            while (iter.hasNext()) {
                if (System.currentTimeMillis() >= iter.next()) iter.remove();
            }

            if (!entry.getValue().isEmpty() && bPlayer != null) {
                long cooldown = entry.getValue().get(0) - System.currentTimeMillis() + 50;
                bPlayer.addCooldown(CoreAbility.getAbility(StickyBomb.class), cooldown);
            }
        }
    }

    public static void tick() {
        processChiBlocks();
        processBombCooldowns();
    }

    public static void sendActionBar(String message, Player player) {
        ChatUtil.sendActionBar(translateColors(message), player);
    }

    public static String translateColors(String string) {
        if (string == null) return "";

        String result = string;
        for (int i = 0; i < string.length(); i++) {
            char c = string.charAt(i);
            if (c == '&' && i + 8 <= string.length()) {
                String s = string.substring(i + 1, i + 8);
                result = string.replace("&" + s, ChatColor.of(s).toString());
            }
        }

        return result;
    }

    public static boolean damage(LivingEntity entity, double damage, Ability ability) {
        if (PredictedContactSync.mark(ability, entity)) return false;
        DamageHandler.damageEntity(entity, damage, ability);
        if (entity.getNoDamageTicks() > 0) {
            entity.setNoDamageTicks(4);
            return true;
        }
        return false;
    }

    public static void spawnCircleParticles(Location location, ParticleEffect effect, double angleY, double angleX, double step, double startRadius) {
        for (double i = 0; i < 180; i += step) {
            double x, z;
            x = startRadius * Math.cos(Math.toRadians(i));
            z = startRadius * Math.sin(Math.toRadians(i));

            Vector v = new Vector(x, 0, z);
            v.rotateAroundX(Math.toRadians(angleX));
            v.rotateAroundY(Math.toRadians(angleY * -1));
            Vector vel = v.clone().normalize();
            if (vel.getY() == 0) vel.setY(0.00000001);

            effect.location(location.clone().add(v)).velocity(vel.getX(), vel.getY(), vel.getZ()).spawn();
            effect.location(location.clone().subtract(v)).velocity(-vel.getX(), -vel.getY(), -vel.getZ()).spawn();
        }
    }

    public static void spawnParticles(Location location, int count, double offsetX, double offsetY, double offsetZ) {
        ParticleEffect effect = new ParticleEffect().location(location).count(count).offset(offsetX, offsetY, offsetZ).speed(0);
        effect.type(Particle.CRIT).spawn();
    }

    public static void punchSound(Location location, boolean strong) {
        float random = (float) ((Math.random() * 4 - 0.5) / 10);
        if (strong) {
            location.getWorld().playSound(location, Sound.ENTITY_HOGLIN_STEP, 1.3f, 1.4f + random);
        } else {
            location.getWorld().playSound(location, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.3f, 1.4f + random);
        }
    }

    public static List<Location> bezier(Location p1, Location p2, Location p3) {
        List<Location> locs = new ArrayList<>();
        for (double t = 0; t <= 1.05; t += 0.05) {
            Location a = p1.clone();
            Location b = p2.clone();
            Location c = p3.clone();
            locs.add(a.multiply(Math.pow(1 - t, 2)).add(b.multiply(2 * (1 - t) * t).add(c.multiply(Math.pow(t, 2)))));
        }
        return locs;
    }

    public static List<Vector> bezier(Location pivot, Location p1, Location p2, Location p3, Location p4) {
        List<Vector> locs = new ArrayList<>();
        for (double t = 0; t <= 1.05; t += 0.05) {
            Location bezier = p1.clone().multiply(Math.pow(1 - t, 3))
                    .add(p2.clone().multiply(3 * Math.pow(1 - t, 2) * t))
                    .add(p3.clone().multiply(3 * (1 - t) * Math.pow(t, 2)))
                    .add(p4.clone().multiply(Math.pow(t, 3)));
            locs.add(bezier.toVector().subtract(pivot.toVector()));
        }
        return locs;
    }

    public static Vector getOrthogonalVector(final Vector axis, final float yaw, final double degrees, final double length) {
        Vector ortho = new Vector(-axis.getZ(), 0, axis.getX());
        if (ortho.lengthSquared() == 0)
            ortho = new Vector(-Math.cos(Math.toRadians(yaw)), 0, -Math.sin(Math.toRadians(yaw)));
        ortho = ortho.normalize();
        ortho = ortho.multiply(length);

        return GeneralMethods.rotateVectorAroundVector(axis, ortho, -degrees);
    }

    public static List<Block> getBlocksAround(Location location, double radius, double blastPower) {
        List<Block> locs = new ArrayList<>();
        Location bLoc = location.toBlockLocation();
        int xLoc = (int) bLoc.getX();
        int zLoc = (int) bLoc.getZ();
        double radiusSquared = radius * radius;

        for (int x = (int) (xLoc - radius); x <= xLoc + radius; x++) {
            for (int z = (int) (zLoc - radius); z <= zLoc + radius; z++) {
                Block block = location.getWorld().getBlockAt(x, (int) bLoc.getY(), z);
                if (block.getLocation().distanceSquared(bLoc) <= radiusSquared && block.getType().getBlastResistance() <= blastPower) {
                    locs.add(block);
                }
            }
        }

        return locs;
    }

    public static void spawnFB(Location spawnLoc, Location middle, Material mat, CoreAbility ability) {
        Vector vel = new Vector(0, 0.5, 0);
        vel.add(spawnLoc.toVector().subtract(middle.toVector()).multiply(0.1));
        TempFallingBlock fb = new TempFallingBlock(spawnLoc.clone().add(0, 1, 0), mat.createBlockData(), vel, ability, true);
        fb.getFallingBlock();
    }

    public static boolean requiredDistanceGround(Location location, int distance) {
        for (int i = 0; i <= distance; i++) {
            if (GeneralMethods.isSolid(location.clone().subtract(0, i, 0).getBlock())) return false;
        }

        return true;
    }

    public static boolean isOnGround(Entity entity) {
        return GeneralMethods.isOnGround(entity);
    }

    public static boolean isFinite(Vector vector) {
        return Double.isFinite(vector.getX())
                || Double.isFinite(vector.getY())
                || Double.isFinite(vector.getZ());
    }
}
