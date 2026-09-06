package com.projectkorra.projectkorra;

import com.projectkorra.projectkorra.ability.util.MultiAbilityManager;
import com.projectkorra.projectkorra.board.BendingBoard;
import com.projectkorra.projectkorra.board.BendingBoardManager;
import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.*;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.inventory.PlayerInventory;
import com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class OfflineBendingPlayerLifecycleTest {
    @TempDir Path directory;
    private final UUID uuid = UUID.randomUUID();
    private Session session = new Session();
    private Object previousPlatform;
    private Config previousLanguage;
    private boolean previousBoardEnabled;
    private List<MultiAbilityManager.MultiAbilityInfo> previousMultiAbilities;

    @BeforeEach
    void setUp() throws Exception {
        previousPlatform = field(Platform.class, "current").get(null);
        previousLanguage = ConfigManager.languageConfig;
        previousBoardEnabled = field(BendingBoardManager.class, "enabled").getBoolean(null);
        previousMultiAbilities = new ArrayList<>(MultiAbilityManager.multiAbilityList);
        PKPlayers players = proxy(PKPlayers.class, (p, method, args) -> switch (method.getName()) {
            case "getPlayer", "getOfflinePlayer" -> new PlayerWrapper(session);
            default -> throw new AssertionError(method);
        });
        PKEventBus events = proxy(PKEventBus.class, (p, method, args) -> null);
        PKScoreboards boards = proxy(PKScoreboards.class, (p, method, args) -> new Scoreboard());
        PKScheduler scheduler = proxy(PKScheduler.class, (p, method, args) -> {
            if (method.getName().equals("isPrimaryThread")) return true;
            if (method.getName().equals("callSync")) {
                return CompletableFuture.completedFuture(((Callable<?>) args[0]).call());
            }
            throw new AssertionError("Unexpected scheduling during player lookup: " + method);
        });
        Platform.install(proxy(ProjectKorraPlatform.class, (p, method, args) -> switch (method.getName()) {
            case "players" -> players;
            case "events" -> events;
            case "scoreboards" -> boards;
            case "scheduler" -> scheduler;
            case "dataFolder" -> directory;
            default -> throw new AssertionError(method);
        }));
        var constructor = Config.class.getDeclaredConstructor(File.class, boolean.class);
        constructor.setAccessible(true);
        ConfigManager.languageConfig = constructor.newInstance(directory.resolve("language.yml").toFile(), false);
        ConfigManager.languageConfig.set("Board.Title", "Bending");
        ConfigManager.languageConfig.set("Board.Prefix.Text", "> ");
        ConfigManager.languageConfig.set("Board.Prefix.SelectedColor", "WHITE");
        ConfigManager.languageConfig.set("Board.Prefix.NonSelectedColor", "GRAY");
        ConfigManager.languageConfig.set("Board.EmptySlot", "Empty {slot_number}");
        ConfigManager.languageConfig.set("Board.MiscSeparator", "---");
        field(BendingBoardManager.class, "enabled").setBoolean(null, true);
        new MultiAbilityManager();
    }

    @AfterEach
    void tearDown() throws Exception {
        Player player = new PlayerWrapper(session);
        MultiAbilityManager.playerAbilities.remove(player);
        MultiAbilityManager.playerBoundAbility.remove(player);
        MultiAbilityManager.playerSlot.remove(player);
        MultiAbilityManager.multiAbilityList.clear();
        MultiAbilityManager.multiAbilityList.addAll(previousMultiAbilities);
        OfflineBendingPlayer.PLAYERS.remove(uuid);
        OfflineBendingPlayer.ONLINE_PLAYERS.remove(uuid);
        ((Map<?, ?>) field(BendingBoardManager.class, "scoreboardPlayers").get(null)).remove(player);
        field(BendingBoardManager.class, "enabled").setBoolean(null, previousBoardEnabled);
        ConfigManager.languageConfig = previousLanguage;
        field(Platform.class, "current").set(null, previousPlatform);
    }

    @Test
    void freshWrappersDoNotReloadOrRetireTheCurrentLogin() throws Exception {
        BendingPlayer original = registerPlayer();
        Player current = Platform.players().getPlayer(uuid);

        assertNotSame(original.getPlayer(), current);
        assertSame(original.getPlayer().handle(), current.handle());
        assertSame(original, OfflineBendingPlayer.loadAsync(uuid, false).get());
        assertSame(original, OfflineBendingPlayer.convertToOffline(original));
        assertSame(original, BendingPlayer.getBendingPlayer(current));
    }

    @Test
    void waterArmsCleanupRestoresBindsAndTheExistingBoardBeforeRelog() {
        BendingPlayer original = registerPlayer();
        Map<Integer, String> binds = Map.of(1, "WaterManipulation", 4, "WaterArms", 9, "Surge");
        original.getAbilities().putAll(binds);
        session.inventory.setHeldItemSlot(3);
        Player first = original.getPlayer();
        BendingBoard board = BendingBoardManager.getBoard(first).orElseThrow();
        board.show();

        MultiAbilityManager.bindMultiAbility(new PlayerWrapper(session), "WaterArms");
        assertTrue(MultiAbilityManager.hasMultiAbilityBound(first, "WaterArms"));
        assertEquals("Pull", ChatColor.stripColor(session.board.getTeam("slot1").getSuffix()));

        MultiAbilityManager.unbindMultiAbility(new PlayerWrapper(session));

        assertSame(original, BendingPlayer.getBendingPlayer(new PlayerWrapper(session)));
        assertSame(board, BendingBoardManager.getBoard(new PlayerWrapper(session)).orElseThrow());
        assertEquals(binds, original.getAbilities());
        assertEquals(3, session.inventory.getHeldItemSlot());
        assertEquals("WaterManipulation", ChatColor.stripColor(session.board.getTeam("slot1").getSuffix()));
        assertEquals("WaterArms", ChatColor.stripColor(session.board.getTeam("slot4").getSuffix()));
        assertFalse(MultiAbilityManager.hasMultiAbilityBound(first));
        assertFalse(MultiAbilityManager.playerBoundAbility.containsKey(first));
        assertFalse(MultiAbilityManager.playerSlot.containsKey(first));

        // Quit and ability removal can both request cleanup.
        MultiAbilityManager.remove(new PlayerWrapper(session));
        MultiAbilityManager.unbindMultiAbility(new PlayerWrapper(session));
        assertEquals(binds, original.getAbilities());

        session.online = false;
        OfflineBendingPlayer offline = OfflineBendingPlayer.convertToOffline(original);
        session = new Session();
        BendingPlayer reconnected = OfflineBendingPlayer.convertToOnline(offline);
        assertNotSame(original, reconnected);
        assertEquals(binds, reconnected.getAbilities());
        assertSame(session, reconnected.getPlayer().handle());
    }

    @Test
    void aNewLoginWithTheSameUuidStillReplacesTheOldPlayer() {
        BendingPlayer original = registerPlayer();
        original.getAbilities().put(1, "WaterArms");
        // Some platforms can still report the previous handle as online briefly.
        session = new Session();
        assertEquals(original.getPlayer(), new PlayerWrapper(session));

        OfflineBendingPlayer offline = OfflineBendingPlayer.convertToOffline(original);
        assertNotSame(original, offline);
        BendingPlayer reconnected = OfflineBendingPlayer.convertToOnline(offline);
        assertSame(session, reconnected.getPlayer().handle());
        assertEquals(Map.of(1, "WaterArms"), reconnected.getAbilities());
    }

    private BendingPlayer registerPlayer() {
        BendingPlayer player = new BendingPlayer(new PlayerWrapper(session));
        player.loading = false;
        OfflineBendingPlayer.PLAYERS.put(uuid, player);
        OfflineBendingPlayer.ONLINE_PLAYERS.put(uuid, player);
        return player;
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class Session {
        final PlayerInventory inventory = new PlayerInventory();
        Scoreboard board = new Scoreboard();
        boolean online = true;
    }

    private final class PlayerWrapper extends Player {
        private final Session value;

        private PlayerWrapper(Session value) { this.value = value; }
        @Override public UUID getUniqueId() { return uuid; }
        @Override public Object handle() { return value; }
        @Override public boolean isOnline() { return value.online; }
        @Override public PlayerInventory getInventory() { return value.inventory; }
        @Override public Scoreboard getScoreboard() { return value.board; }
        @Override public void setScoreboard(Scoreboard board) { value.board = board; }
        @Override public boolean equals(Object other) {
            return other instanceof Entity entity && uuid.equals(entity.getUniqueId());
        }
        @Override public int hashCode() { return uuid.hashCode(); }
    }
}
