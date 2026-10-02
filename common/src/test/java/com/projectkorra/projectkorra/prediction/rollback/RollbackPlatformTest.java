package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.*;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.model.PKAdapter;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class RollbackPlatformTest {
    @TempDir Path directory;
    @Test void lookupAndChunkCallbacksUseOnlyThePrivateRosterAndArena() {
        var world = RollbackWorldTest.world(Map.of());
        var first = PrivateCombatRollbackTest.player(world, 1); var second = PrivateCombatRollbackTest.player(world, 2);
        world.entities().add(first); world.entities().add(second);
        var platform = platform(directory, world, List.of(second, first), new RollbackScheduler(10, 10));
        assertEquals(List.of(first, second), platform.players().onlinePlayers());
        assertSame(first, platform.players().getPlayer(first.getUniqueId()));
        assertSame(first, platform.players().getPlayer("PLAYER-1"));
        assertSame(second, platform.players().getOfflinePlayer("player-2"));
        assertNull(platform.players().getPlayer(new UUID(0, 3)));
        assertEquals(List.of(world), platform.worlds().worlds());
        var block = world.getBlockAt(1, 0, 1);
        platform.chunks().getChunkAtAsync(block.getLocation()).thenAccept(ignored -> block.setType(com.projectkorra.projectkorra.platform.mc.Material.STONE, false));
        assertEquals(com.projectkorra.projectkorra.platform.mc.Material.STONE, block.getType());
        assertThrows(IllegalArgumentException.class, () -> platform.chunks().getChunkAtAsync(new Location(new World(), 1, 0, 1)));
        assertThrows(IllegalStateException.class, () -> platform.chunks().getChunkAtAsync(new Location(world, 32, 0, 32)));
    }
    @Test void foreignIncompleteAndDuplicateRostersCannotBecomeAPlatform() {
        var world = RollbackWorldTest.world(Map.of());
        var first = PrivateCombatRollbackTest.player(world, 1); var second = PrivateCombatRollbackTest.player(world, 2);
        world.entities().add(first); world.entities().add(second);
        var scheduler = new RollbackScheduler(10, 10);
        assertThrows(IllegalArgumentException.class, () -> platform(directory, world, List.of(first), scheduler));
        assertThrows(IllegalArgumentException.class, () -> platform(directory, world, List.of(first, first), scheduler));
        var replacement = PrivateCombatRollbackTest.player(world, 2);
        assertThrows(IllegalArgumentException.class, () -> platform(directory, world, List.of(first, replacement), scheduler));
        assertThrows(IllegalArgumentException.class, () -> platform(directory, world,
                List.of(first, PrivateCombatRollbackTest.player(RollbackWorldTest.world(Map.of()), 2)), scheduler));
    }

    /** Native policy/presentation are fail-fast fixtures; the roster, world and event services are production code. */
    static RollbackPlatform platform(Path directory, RollbackWorld world, List<RollbackPlayer> roster, RollbackScheduler scheduler) {
        PKPlugins plugins = new PKPlugins() {
            @Override public <P> P getPlugin(String name) { return null; }
            @Override public boolean isPluginPresent(String name) { return false; }
            @Override public boolean isPluginEnabled(String name) { return false; }
        };
        PKServer server = (PKServer) Proxy.newProxyInstance(PKServer.class.getClassLoader(), new Class<?>[]{PKServer.class}, (proxy, method, args) -> {
            if (method.getName().equals("minecraftVersion")) return "1.21.11";
            throw new AssertionError(method);
        });
        var services = new RollbackPlatform.Services("fixture", plugins, unused(PKTags.class), material -> false,
                unused(PKPermissions.class), server, unused(PKScoreboards.class), unused(PKBossBars.class), unused(PKAdapter.class));
        return new RollbackPlatform(new RollbackPlatform.Identity("paper", directory, Logger.getLogger("RollbackPlatformTest")),
                services, scheduler, new RollbackEventBus(100, 10), world, roster);
    }
    private static <T> T unused(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> { throw new AssertionError(method); }));
    }
}
