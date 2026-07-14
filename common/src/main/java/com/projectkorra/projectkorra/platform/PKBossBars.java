package com.projectkorra.projectkorra.platform;

import com.projectkorra.projectkorra.platform.mc.boss.BarColor;
import com.projectkorra.projectkorra.platform.mc.boss.BarStyle;
import com.projectkorra.projectkorra.platform.mc.boss.BossBar;

public interface PKBossBars {
    BossBar.Delegate create(String title, BarColor color, BarStyle style);
}
