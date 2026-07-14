package com.projectkorra.projectkorra.platform.mc.entity;

import com.projectkorra.projectkorra.platform.mc.*;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.platform.mc.inventory.MainHand;
import com.projectkorra.projectkorra.platform.mc.inventory.PlayerInventory;
import com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public class Player extends LivingEntity implements CommandSender, OfflinePlayer {
    public String getName() {
        return "";
    }

    public UUID getUniqueId() {
        return new UUID(0, 0);
    }

    public boolean hasPermission(String p) {
        return true;
    }

    public void sendMessage(String message) {
    }

    public PlayerInventory getInventory() {
        return new PlayerInventory();
    }

    public GameMode getGameMode() {
        return GameMode.CREATIVE;
    }

    public boolean isOnline() {
        return true;
    }

    public boolean hasMetadata(String key) {
        return false;
    }

    public String getDisplayName() {
        return getName();
    }

    public void setDisplayName(String name) {
    }

    public boolean isFlying() {
        return false;
    }

    public void setFlying(boolean flying) {
    }

    public boolean getAllowFlight() {
        return false;
    }

    public void setAllowFlight(boolean allow) {
    }

    public Location getEyeLocation() {
        return getLocation();
    }

    public Block getTargetBlock(Set<Material> transparent, int range) {
        return new Block();
    }

    public boolean isSneaking() {
        return false;
    }

    public void setSneaking(boolean value) {
    }

    public boolean isSprinting() {
        return false;
    }

    public void setSprinting(boolean value) {
    }

    public void playSound(Location location, Sound sound, float volume, float pitch) {
    }

    public void playNote(Location location, Instrument instrument, Note note) {
    }

    public int getPing() {
        return 0;
    }

    public double getAbsorptionAmount() {
        return 0;
    }

    public void setAbsorptionAmount(double amount) {
    }

    public boolean isOp() {
        return false;
    }

    public MainHand getMainHand() {
        return MainHand.RIGHT;
    }

    public int getNoDamageTicks() {
        return 0;
    }

    public void setNoDamageTicks(int ticks) {
    }

    public int getMaximumNoDamageTicks() {
        return 20;
    }

    public void setRotation(float yaw, float pitch) {
    }

    public boolean eject() {
        return true;
    }

    public boolean isInsideVehicle() {
        return false;
    }

    public boolean isGliding() {
        return false;
    }

    public void setGliding(boolean value) {
    }

    public float getExp() {
        return 0;
    }

    public void setExp(float value) {
    }

    public float getFlySpeed() {
        return 0.1F;
    }

    public void setFlySpeed(float value) {
    }

    public boolean getCanPickupItems() {
        return true;
    }

    public void setCanPickupItems(boolean value) {
    }

    public boolean isSwimming() {
        return false;
    }

    public boolean isGlowing() {
        return false;
    }

    public void setGlowing(boolean value) {
    }

    public Scoreboard getScoreboard() {
        return new Scoreboard();
    }

    public void setScoreboard(Scoreboard board) {
    }

    public boolean canSee(Player player) {
        return true;
    }

    public boolean hasLineOfSight(Entity entity) {
        return true;
    }

    public Block getTargetBlockExact(int range) {
        return new Block();
    }

    public List<Block> getLastTwoTargetBlocks(Set<Material> transparent, int range) {
        return List.of(new Block(), new Block());
    }

    public List<Entity> getNearbyEntities(double x, double y, double z) {
        return List.of();
    }

    public <T extends Projectile> T launchProjectile(Class<T> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalArgumentException(type.getName(), ex);
        }
    }

    public void sendBlockChange(Location location, BlockData data) {
    }

    public <T> void spawnParticle(Particle particle, Location location, int count, double ox, double oy, double oz, double extra, T data, boolean force) {
    }

    public void spawnParticle(Particle particle, Location location, int count, double ox, double oy, double oz) {
    }

    public float getExhaustion() {
        return 0;
    }

    public void setExhaustion(float value) {
    }
}
