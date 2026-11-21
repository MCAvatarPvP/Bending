package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.*;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.Pose;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainCodec;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollbackArenaActionsTest {
    @Test void mutableEffectsAreDetachedBeforeThePrivateStepFinishes() {
        var output = new RollbackStepOutput<Object>();
        var nativeActions = (RollbackArenaActions.Native) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{RollbackArenaActions.Native.class}, (proxy, method, args) -> { throw new AssertionError(method); });
        var states = new RollbackTerrainCodec.BlockStates() {
            @Override public String encode(BlockData data) { return data.getExactState(); }
            @Override public BlockData decode(Material material, String exact) { throw new AssertionError(); }
        };
        var actions = new RollbackArenaActions(output, nativeActions, states, item -> RollbackItemData.EMPTY);
        var pos = new Pose(1, 2, 3, 0, 0);
        var data = new BlockData(Material.STONE);
        data.setExactState("minecraft:stone");
        var step = new RollbackStep<Object>(1, 4);
        try (var clock = RollbackClock.at(0, 1, 50_000_000); var binding = output.open(step)) {
            actions.particle(null, pos, Particle.BLOCK, 5, 1, 2, 3, 4, data);
            data.setExactState("minecraft:air");
            actions.effect(null, pos, Effect.SMOKE, 4, 64);
        }
        assertEquals(List.of(
                new RollbackArenaActions.ParticleOutput(null, pos, "BLOCK", 5, 1, 2, 3, 4,
                        new RollbackArenaActions.BlockValue(Material.STONE, "minecraft:stone"), false),
                new RollbackArenaActions.EffectOutput(pos, "SMOKE", 4, 64)), step.finish());
        assertThrows(IllegalStateException.class, () -> actions.lightning(null, pos, true));
    }
}
