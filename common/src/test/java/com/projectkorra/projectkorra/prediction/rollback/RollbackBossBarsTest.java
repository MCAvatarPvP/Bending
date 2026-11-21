package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.boss.*;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackBossBarsTest {
    @Test void abilityBarsRewindAndDiscardedHandlesCannotMutateReplacementBars() {
        var world = RollbackWorldTest.world(Map.of());
        var player = PrivateCombatRollbackTest.player(world, 1);
        var bars = new RollbackBossBars(List.of(player), 2);
        var platform = (com.projectkorra.projectkorra.platform.ProjectKorraPlatform) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.projectkorra.projectkorra.platform.ProjectKorraPlatform.class},
                (proxy, method, args) -> { if (method.getName().equals("bossBars")) return bars; throw new AssertionError(method); });
        BossBar first;
        try (var scope = com.projectkorra.projectkorra.platform.Platform.using(platform)) {
            first = new BossBar("armor", BarColor.WHITE, BarStyle.SOLID);
        }
        first.addPlayer(player); first.setProgress(.4);
        var views = bars.views();
        var saved = new RollbackStateGraph(value -> false, field -> true, 100_000).capture(List.of(bars, first), List.of());
        first.setTitle("damaged"); first.setProgress(.1); first.removeAll(); first.setVisible(false);
        var discarded = bars.create("branch", BarColor.WHITE, BarStyle.SOLID);
        assertEquals("armor", views.getFirst().title()); assertEquals(.4, views.getFirst().progress());
        saved.restore(); assertEquals(views, bars.views()); assertEquals(.4, first.getProgress());
        var replacement = bars.create("replacement", BarColor.WHITE, BarStyle.SOLID);
        assertThrows(IllegalStateException.class, () -> discarded.setTitle("wrong"));
        assertThrows(IllegalArgumentException.class, () -> replacement.addPlayer(PrivateCombatRollbackTest.player(world, 1)));
        assertThrows(IllegalArgumentException.class, () -> first.setProgress(Double.NaN));
        assertThrows(IllegalStateException.class, () -> bars.create("over budget", BarColor.WHITE, BarStyle.SOLID));
        replacement.addPlayer(player); assertEquals(Set.of(player.getUniqueId()), bars.views().getLast().players());
        assertThrows(UnsupportedOperationException.class, () -> views.getFirst().players().clear());
    }
}
