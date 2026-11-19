package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import net.minecraft.server.world.ServerWorld;
import com.projectkorra.projectkorra.prediction.rollback.RollbackWorldSettings;
import java.util.function.Supplier;

/** Paper's unsplit-loot policy is scoped to a single private native loot calculation. */
public final class FabricRollbackLoot {
    private record Policy(ServerWorld world, boolean split, boolean alternativeLuck) { }
    private static final ThreadLocal<Policy> ACTIVE = new ThreadLocal<>();
    private FabricRollbackLoot() { }
    static <T> T generate(ServerWorld world, RollbackWorldSettings.Policy policy, Supplier<T> generate) {
        if (policy.splitOverstackedLoot() && !policy.alternativeLuckFormula()) return generate.get();
        Policy previous = ACTIVE.get();
        ACTIVE.set(new Policy(world, policy.splitOverstackedLoot(), policy.alternativeLuckFormula()));
        try { return generate.get(); }
        finally {
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
        }
    }
    public static boolean unsplit(ServerWorld world) {
        var policy = ACTIVE.get();
        return policy != null && world == policy.world && !policy.split;
    }
    public static boolean alternativeLuck() {
        var policy = ACTIVE.get();
        return policy != null && policy.alternativeLuck;
    }
    public static int alternativeWeight(int base, int quality, float luck) {
        double weight = (base + quality * luck) * 100.0;
        double impact = weight * ((weight - 100) / 100 / 100);
        return (int) Math.max(0, Math.ceil(weight - impact * (Math.min(100f, luck * 10f) / 100f)));
    }
}
