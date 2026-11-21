package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.BendingManager;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.chiblocking.util.ChiblockingManager;
import com.projectkorra.projectkorra.earthbending.util.EarthbendingManager;
import com.projectkorra.projectkorra.firebending.util.FirebendingManager;
import com.projectkorra.projectkorra.util.RegenHandler;
import com.projectkorra.projectkorra.waterbending.util.WaterbendingManager;

/** Runs the ordinary post-ability callbacks against the installed private roster in server registration order. */
final class RollbackBendingMaintenance {
    private static final WaterbendingManager WATER = new WaterbendingManager(ProjectKorra.plugin);
    private static final EarthbendingManager EARTH = new EarthbendingManager(ProjectKorra.plugin);
    private static final FirebendingManager FIRE = new FirebendingManager(ProjectKorra.plugin);
    private static final ChiblockingManager CHI = new ChiblockingManager(ProjectKorra.plugin);
    private static final RegenHandler REGEN = new RegenHandler(ProjectKorra.plugin);
    private static final BendingManager.TempElementsRunnable EXPIRY = new BendingManager.TempElementsRunnable();

    private RollbackBendingMaintenance() { }

    static void run(long tick, long regenerationDelay, long expiryDelay) {
        WATER.run();
        EARTH.run();
        FIRE.run();
        CHI.run();
        if (due(tick, regenerationDelay)) REGEN.run();
        if (due(tick, expiryDelay)) EXPIRY.run();
    }

    static boolean due(long tick, long firstTick) {
        return firstTick > 0 && tick >= firstTick && (tick - firstTick) % 20 == 0;
    }
}
