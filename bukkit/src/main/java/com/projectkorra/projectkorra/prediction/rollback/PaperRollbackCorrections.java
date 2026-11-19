package com.projectkorra.projectkorra.prediction.rollback;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** Exports confirmed checkpoints for one Paper match and pairs them with the input publication. */
public final class PaperRollbackCorrections {
    public record Settings(String definitions, int maximumBytes, int intervalTicks, Supplier<byte[]> encoder) {
        public Settings {
            Objects.requireNonNull(definitions);
            Objects.requireNonNull(encoder);
            if (!definitions.matches("[0-9a-f]{64}") || maximumBytes <= RollbackStateCorrection.HEADER_BYTES
                    || maximumBytes > RollbackStateCorrection.MAXIMUM_BYTES || intervalTicks < 1) {
                throw new IllegalArgumentException("Invalid rollback correction settings");
            }
        }
    }

    /** The sender invokes this encoder while the authority has installed its confirmed checkpoint. */
    public static Settings forMatch(RollbackBootstrapData initial, PaperRollbackNativeScene scene,
            RollbackPlayerExecution<?> execution, RollbackRound round, RollbackActorRegistry actors,
            RollbackGameplayGraph.Imported graph, int maximumBytes, int intervalTicks) {
        var states = new PaperRollbackTerrainTransfer();
        return new Settings(initial.definitions(), maximumBytes, intervalTicks, () -> {
            long tick = round.tick();
            long nanos = initial.epochNanos() + Math.multiplyExact(tick, RollbackDuelRuntime.ENGINE.stepNanos());
            var data = new RollbackConfirmedData(round.exportData(), scene.captureRoster(initial.roster(), nanos),
                    PaperRollbackRandomStreams.capture(scene), execution.captureRollbackState(),
                    scene.logical().terrain().changesSince(initial.world().terrain()), actors.exportState(),
                    graph.bending().exportRepair(graph.codec().withBindings(actors.bindings())));
            return data.encode(initial, states, RollbackDuelRuntime.TERRAIN, maximumBytes - RollbackStateCorrection.HEADER_BYTES);
        });
    }

    private final UUID session;
    private final Settings settings;
    private final Function<Supplier<byte[]>, RollbackCombatRuntime.Export> exportConfirmedState;
    private long lastConfirmedTick;

    PaperRollbackCorrections(UUID session, Settings settings,
            Function<Supplier<byte[]>, RollbackCombatRuntime.Export> exportConfirmedState) {
        this.session = Objects.requireNonNull(session);
        this.settings = Objects.requireNonNull(settings);
        this.exportConfirmedState = Objects.requireNonNull(exportConfirmedState);
    }

    List<byte[]> encode(RollbackAuthorityUpdate authority) {
        if (!session.equals(authority.session()) || authority.finalizedTick() < lastConfirmedTick) {
            throw new IllegalStateException("Correction publication changed session or moved backwards");
        }
        if (authority.finalizedTick() == 0 || lastConfirmedTick != 0
                && authority.finalizedTick() - lastConfirmedTick < settings.intervalTicks()) return List.of();

        var snapshot = exportConfirmedState.apply(() -> {
            byte[] payload = settings.encoder().get();
            if (payload.length == 0 || payload.length > settings.maximumBytes() - RollbackStateCorrection.HEADER_BYTES) {
                throw new IllegalStateException("Confirmed rollback state exceeds the session payload budget");
            }
            return payload;
        });
        if (snapshot.tick() != authority.finalizedTick() || snapshot.confirmedTick() != authority.finalizedTick()
                || snapshot.revision() != authority.revision()) {
            throw new IllegalStateException("Confirmed checkpoint differs from the authority publication");
        }
        var correction = new RollbackStateCorrection(session, authority.publication(), authority.revision(),
                authority.finalizedTick(), settings.definitions(), snapshot.bytes());
        var chunks = RollbackStateCorrectionChunk.split(correction).stream().map(RollbackStateCorrectionChunk::encode).toList();
        lastConfirmedTick = authority.finalizedTick();
        return chunks;
    }
}
