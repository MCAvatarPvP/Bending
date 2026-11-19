package com.projectkorra.projectkorra.prediction.rollback;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.MarsagliaPolarGaussian;

import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackPlayerFields.Field;

/** Reads only the native random sources owned by the private scene. */
public final class PaperRollbackRandomStreams {
    private static final Field<AtomicLong> BITS = new Field<>(LegacyRandomSource.class, "seed", AtomicLong.class);
    private static final Field<MarsagliaPolarGaussian> CACHE = new Field<>(LegacyRandomSource.class, "gaussianSource", MarsagliaPolarGaussian.class);
    private static final Field<Boolean> HAS = new Field<>(MarsagliaPolarGaussian.class, "haveNextNextGaussian", boolean.class);
    private static final Field<Double> VALUE = new Field<>(MarsagliaPolarGaussian.class, "nextNextGaussian", double.class);
    private PaperRollbackRandomStreams() { }

    static RollbackRandom.State capture(RandomSource random) {
        if (random.getClass() != LegacyRandomSource.class) throw new IllegalStateException("Unexpected private Paper random source");
        var cache = CACHE.get(random);
        if (cache.randomSource != random) throw new IllegalStateException("Private random stream changed owners");
        return new RollbackRandom.State(BITS.get(random).get(), HAS.get(cache), VALUE.get(cache));
    }

    public static RollbackRandomStreams capture(PaperRollbackNativeScene scene) {
        var players = new TreeMap<UUID, RollbackRandom.State>();
        scene.roster().forEach((id, state) -> players.put(id, capture(state.ownedPlayer().getRandom())));
        return new RollbackRandomStreams(capture(scene.services().random()), scene.services().soundRandomState(), players);
    }
}
