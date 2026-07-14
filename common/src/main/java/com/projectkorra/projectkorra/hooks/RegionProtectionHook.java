package com.projectkorra.projectkorra.hooks;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@FunctionalInterface
public interface RegionProtectionHook {

    boolean isRegionProtected(@NotNull Player player, @NotNull Location location, @Nullable CoreAbility ability);
}
