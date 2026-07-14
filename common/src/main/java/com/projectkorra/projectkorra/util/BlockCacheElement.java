package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

public class BlockCacheElement {

    private Player player;
    private Block block;
    private CoreAbility ability;
    private boolean allowed;
    private long time;

    public BlockCacheElement(final Player player, final Block block, final CoreAbility ability, final boolean allowed, final long time) {
        this.player = player;
        this.block = block;
        this.ability = ability;
        this.allowed = allowed;
        this.time = time;
    }

    public CoreAbility getAbility() {
        return this.ability;
    }

    public void setAbility(final CoreAbility ability) {
        this.ability = ability;
    }

    public Block getBlock() {
        return this.block;
    }

    public void setBlock(final Block block) {
        this.block = block;
    }

    public Player getPlayer() {
        return this.player;
    }

    public void setPlayer(final Player player) {
        this.player = player;
    }

    public long getTime() {
        return this.time;
    }

    public void setTime(final long time) {
        this.time = time;
    }

    public boolean isAllowed() {
        return this.allowed;
    }

    public void setAllowed(final boolean allowed) {
        this.allowed = allowed;
    }
}
