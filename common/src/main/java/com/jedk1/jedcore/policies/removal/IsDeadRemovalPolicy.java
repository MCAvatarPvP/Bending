package com.jedk1.jedcore.policies.removal;

import com.projectkorra.projectkorra.platform.mc.entity.Player;

public class IsDeadRemovalPolicy implements RemovalPolicy {
    private Player player;

    public IsDeadRemovalPolicy(Player player) {
        this.player = player;
    }

    @Override
    public boolean shouldRemove() {
        return this.player == null || !this.player.isOnline() || this.player.isDead();
    }

    @Override
    public String getName() {
        return "IsDead";
    }
}
