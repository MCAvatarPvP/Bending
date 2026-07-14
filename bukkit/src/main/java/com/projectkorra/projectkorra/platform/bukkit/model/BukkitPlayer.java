package com.projectkorra.projectkorra.platform.bukkit.model;

import com.projectkorra.projectkorra.platform.model.PKBlock;
import com.projectkorra.projectkorra.platform.model.PKLocation;
import com.projectkorra.projectkorra.platform.model.PKPlayer;
import org.bukkit.entity.Player;

public final class BukkitPlayer extends BukkitLivingEntity implements PKPlayer {
    private final Player player;

    public BukkitPlayer(final Player player) {
        super(player);
        this.player = player;
    }

    @Override
    public String name() {
        return this.player.getName();
    }

    @Override
    public void sendMessage(final String message) {
        this.player.sendMessage(message);
    }

    @Override
    public boolean hasPermission(final String permission) {
        return this.player.hasPermission(permission);
    }

    @Override
    public boolean sneaking() {
        return this.player.isSneaking();
    }

    @Override
    public boolean sprinting() {
        return this.player.isSprinting();
    }

    @Override
    public PKLocation eyeLocation() {
        return new BukkitLocation(this.player.getEyeLocation());
    }

    @Override
    public PKBlock targetBlock(final int range) {
        return new BukkitBlock(this.player.getTargetBlockExact(range));
    }
}
