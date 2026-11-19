package com.projectkorra.projectkorra.prediction.rollback;

import com.jedk1.jedcore.JedCore;
import me.literka.ChiRework;
import me.macieq.FloorIsLava;
import me.moros.hyperion.Hyperion;
import me.simplicitee.project.addons.ProjectAddons;

/** Embedded addon handles are local singletons. Only their metadata identity crosses the wire. */
public enum RollbackMetadataOwner {
    CHI_REWORK, JED_CORE, HYPERION, PROJECT_ADDONS, MOLTEN;

    public static Object portable(Object owner) {
        if (owner instanceof ChiRework && owner == ChiRework.plugin) return CHI_REWORK;
        if (owner instanceof JedCore && owner == JedCore.plugin) return JED_CORE;
        if (owner instanceof Hyperion && owner == Hyperion.getPlugin()) return HYPERION;
        if (owner instanceof ProjectAddons && owner == ProjectAddons.instance) return PROJECT_ADDONS;
        if (owner instanceof FloorIsLava && owner == FloorIsLava.plugin) return MOLTEN;
        return owner;
    }

    static Object local(Object owner) {
        if (!(owner instanceof RollbackMetadataOwner embedded)) return owner;
        return switch (embedded) {
            case CHI_REWORK -> ChiRework.plugin;
            case JED_CORE -> JedCore.plugin;
            case HYPERION -> Hyperion.getPlugin();
            case PROJECT_ADDONS -> ProjectAddons.instance;
            case MOLTEN -> FloorIsLava.plugin;
        };
    }
}
