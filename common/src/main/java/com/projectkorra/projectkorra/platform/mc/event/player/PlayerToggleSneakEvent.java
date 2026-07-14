package com.projectkorra.projectkorra.platform.mc.event.player;

import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.Event;

public class PlayerToggleSneakEvent extends Event {
    public Player getPlayer() {
        return new Player();
    }
}
