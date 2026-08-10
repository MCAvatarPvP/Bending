package com.projectkorra.projectkorra.platform.mc.event;

import com.projectkorra.projectkorra.platform.Platform;

public class HandlerList {
    public static void unregisterAll(Object o) {
        Platform.events().unregisterAll(o);
    }
}
