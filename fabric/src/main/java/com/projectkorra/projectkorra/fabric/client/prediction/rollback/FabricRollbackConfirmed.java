package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.*;

/** Imports only the authenticated confirmed frontier into the enrolled private match. */
public final class FabricRollbackConfirmed {
    private FabricRollbackConfirmed() { }
    public static RollbackClientRuntime.Repair<RollbackDomain.Checkpoint, Object> repair(RollbackBootstrapData initial,
            RollbackDuelRuntime.Match match, FabricRollbackNativeScene.Duel duel, FabricRollbackExecution<Object> execution,
            int maximumEnvelopeBytes) {
        return new RollbackClientRuntime.Repair<>(initial.definitions(), maximumEnvelopeBytes, (tick, bytes) -> {
            var data = RollbackConfirmedData.decode(bytes, tick, initial, new FabricRollbackTerrainTransfer(),
                    RollbackDuelRuntime.TERRAIN, maximumEnvelopeBytes - RollbackStateCorrection.HEADER_BYTES);
            var installBending = match.graph().prepareRepair(data.bending(), tick, duel.actors().registry()::bindings);
            long nanos = Math.addExact(initial.epochNanos(), Math.multiplyExact(tick, RollbackDuelRuntime.ENGINE.stepNanos()));
            return match.combat().importConfirmedState(tick, () -> {
                var scene = duel.scene();
                scene.logical().terrain().importChanges(initial.world().terrain(), data.terrain());
                duel.actors().importState(data.actors());
                scene.roster().importConfirmed(data.roster(), nanos);
                scene.restoreStreams(data.random());
                duel.round().importData(data.round());
                execution.restoreRollbackState(data.actionSequences());
                installBending.run();
            });
        });
    }
}
