package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AbilityTimingDebugger {

    private static final String ENABLE_PROPERTY = "projectkorra.abilityTimingDebug";
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "false"));
    private static final Map<UUID, ArrayDeque<InputEvent>> PENDING_INPUTS = new ConcurrentHashMap<>();
    private static final Map<Integer, AbilityTrace> ABILITY_TRACES = new ConcurrentHashMap<>();

    private AbilityTimingDebugger() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void logEnableHint() {
        if (ENABLED) {
            ProjectKorra.log.info("[PK-TIMING] Ability timing debug enabled via -D" + ENABLE_PROPERTY + "=true");
        }
    }

    public static void recordInput(final Player player, final String trigger, final String boundAbility) {
        if (!ENABLED || player == null) {
            return;
        }

        final InputEvent event = new InputEvent(
                player.getUniqueId(),
                player.getName(),
                nullToUnknown(trigger),
                nullToUnknown(boundAbility),
                System.nanoTime(),
                CoreAbility.getCurrentTick()
        );

        PENDING_INPUTS.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>()).addLast(event);
        ProjectKorra.log.info(format(
                "INPUT",
                event.playerName,
                event.boundAbility,
                event.boundAbility,
                -1,
                event.tick,
                -1,
                event.trigger,
                "pending=1"
        ));
    }

    public static void recordStart(final CoreAbility ability) {
        if (!ENABLED || ability == null || ability.getPlayer() == null) {
            return;
        }

        final Player player = ability.getPlayer();
        final InputEvent input = pollMatchingInput(player.getUniqueId(), ability.getName());
        final AbilityTrace trace = new AbilityTrace(
                ability.getId(),
                player.getUniqueId(),
                player.getName(),
                ability.getName(),
                ability.getClass().getSimpleName(),
                System.nanoTime(),
                CoreAbility.getCurrentTick(),
                input
        );
        ABILITY_TRACES.put(ability.getId(), trace);

        final long inputDeltaTicks = input == null ? -1 : trace.startTick - input.tick;
        final long inputDeltaMicros = input == null ? -1 : (trace.startNanoTime - input.nanoTime) / 1_000L;
        final String trigger = input == null ? "none" : input.trigger;
        ProjectKorra.log.info(format(
                "START",
                trace.playerName,
                trace.abilityName,
                trace.abilityClass,
                trace.abilityId,
                trace.startTick,
                inputDeltaTicks,
                trigger,
                "inputDeltaUs=" + inputDeltaMicros
        ));
    }

    public static void recordFirstProgress(final CoreAbility ability) {
        if (!ENABLED || ability == null) {
            return;
        }

        final AbilityTrace trace = ABILITY_TRACES.get(ability.getId());
        if (trace == null || trace.firstProgressLogged) {
            return;
        }

        trace.firstProgressLogged = true;
        trace.firstProgressNanoTime = System.nanoTime();
        trace.firstProgressTick = CoreAbility.getCurrentTick();

        final long startDeltaTicks = trace.firstProgressTick - trace.startTick;
        final long startDeltaMicros = (trace.firstProgressNanoTime - trace.startNanoTime) / 1_000L;
        final long inputDeltaTicks = trace.input == null ? -1 : trace.firstProgressTick - trace.input.tick;
        final long inputDeltaMicros = trace.input == null ? -1 : (trace.firstProgressNanoTime - trace.input.nanoTime) / 1_000L;
        final String trigger = trace.input == null ? "none" : trace.input.trigger;

        ProjectKorra.log.info(format(
                "FIRST_PROGRESS",
                trace.playerName,
                trace.abilityName,
                trace.abilityClass,
                trace.abilityId,
                trace.firstProgressTick,
                inputDeltaTicks,
                trigger,
                "startDeltaTicks=" + startDeltaTicks + " startDeltaUs=" + startDeltaMicros + " inputDeltaUs=" + inputDeltaMicros
        ));
    }

    public static void clear(final CoreAbility ability) {
        if (!ENABLED || ability == null) {
            return;
        }
        ABILITY_TRACES.remove(ability.getId());
    }

    private static InputEvent pollMatchingInput(final UUID playerId, final String abilityName) {
        final ArrayDeque<InputEvent> queue = PENDING_INPUTS.get(playerId);
        if (queue == null) {
            return null;
        }

        InputEvent fallback = null;
        InputEvent matched = null;
        while (!queue.isEmpty()) {
            final InputEvent event = queue.pollFirst();
            if (event == null) {
                break;
            }

            if (fallback == null) {
                fallback = event;
            }

            if (event.boundAbility.equalsIgnoreCase(abilityName)) {
                matched = event;
                break;
            }
        }

        if (queue.isEmpty()) {
            PENDING_INPUTS.remove(playerId, queue);
        }

        return matched != null ? matched : fallback;
    }

    private static String format(
            final String phase,
            final String playerName,
            final String abilityName,
            final String abilityClass,
            final int abilityId,
            final long tick,
            final long inputDeltaTicks,
            final String trigger,
            final String extra
    ) {
        return "[PK-TIMING] phase=" + phase
                + " player=" + playerName
                + " ability=" + abilityName
                + " class=" + abilityClass
                + " id=" + abilityId
                + " tick=" + tick
                + " inputDeltaTicks=" + inputDeltaTicks
                + " trigger=" + trigger
                + " " + extra;
    }

    private static String nullToUnknown(final String value) {
        return value == null || value.isEmpty() ? "unknown" : value;
    }

    private static final class InputEvent {
        private final UUID playerId;
        private final String playerName;
        private final String trigger;
        private final String boundAbility;
        private final long nanoTime;
        private final long tick;

        private InputEvent(final UUID playerId, final String playerName, final String trigger, final String boundAbility, final long nanoTime, final long tick) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.trigger = trigger;
            this.boundAbility = boundAbility;
            this.nanoTime = nanoTime;
            this.tick = tick;
        }
    }

    private static final class AbilityTrace {
        private final int abilityId;
        private final UUID playerId;
        private final String playerName;
        private final String abilityName;
        private final String abilityClass;
        private final long startNanoTime;
        private final long startTick;
        private final InputEvent input;
        private boolean firstProgressLogged;
        private long firstProgressNanoTime;
        private long firstProgressTick;

        private AbilityTrace(
                final int abilityId,
                final UUID playerId,
                final String playerName,
                final String abilityName,
                final String abilityClass,
                final long startNanoTime,
                final long startTick,
                final InputEvent input
        ) {
            this.abilityId = abilityId;
            this.playerId = playerId;
            this.playerName = playerName;
            this.abilityName = abilityName;
            this.abilityClass = abilityClass;
            this.startNanoTime = startNanoTime;
            this.startTick = startTick;
            this.input = input;
        }
    }
}
