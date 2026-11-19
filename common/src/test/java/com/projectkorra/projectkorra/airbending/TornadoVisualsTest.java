package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TornadoVisualsTest {
    private static final double EPSILON = 1.0e-9;
    private static final TornadoVisuals.Settings DEFAULT_SETTINGS = new TornadoVisuals.Settings(3, 24, 8, 5);

    @Test
    void growingTheTornadoDoesNotIncreaseParticleOrPacketWork() {
        final List<Particle> small = render(4, 1, DEFAULT_SETTINGS);
        final List<Particle> large = render(64, 32, DEFAULT_SETTINGS);

        assertFalse(small.isEmpty());
        assertEquals(small.size(), large.size(), "size must not control emission loop counts");
        assertEquals(particleCount(small), particleCount(large));
        assertTrue(particleCount(large) <= 500, "large tornadoes need a predictable per-frame work budget");
        assertTrue(maxHeight(large) > maxHeight(small) * 10, "the fixed budget must still cover the larger funnel");
        assertTrue(maxRadius(large) > maxRadius(small) * 10);
    }

    @Test
    void extremeParticleConfigurationIsCappedBeforeRendering() {
        final TornadoVisuals.Settings extreme = new TornadoVisuals.Settings(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        final List<Particle> particles = render(64, 32, extreme);

        assertFalse(particles.isEmpty());
        assertTrue(particles.size() <= 500);
        assertTrue(particleCount(particles) <= 500);
    }

    @Test
    void negativeParticleConfigurationStillProducesAVisibleFiniteFunnel() {
        final List<Particle> particles = render(8, 2, new TornadoVisuals.Settings(-1, -1, -1, -1));

        assertFalse(particles.isEmpty());
        assertTrue(particleCount(particles) <= 500);
        assertFiniteParticles(particles);
    }

    @Test
    void renderingDoesNotMutateAbilityPositionOrFacing() {
        final World world = new World();
        final Location base = new Location(world, 17, 64, -23);
        base.setYaw(35);
        base.setPitch(-10);
        final Vector facing = new Vector(3, 4, -5);
        final List<Particle> particles = new ArrayList<>();

        TornadoVisuals.render(base, facing, 24, 8, 1.25, DEFAULT_SETTINGS,
                (location, count, spread) -> particles.add(new Particle(location, count, spread)));

        assertEquals(17, base.getX(), EPSILON);
        assertEquals(64, base.getY(), EPSILON);
        assertEquals(-23, base.getZ(), EPSILON);
        assertEquals(35, base.getYaw(), EPSILON);
        assertEquals(-10, base.getPitch(), EPSILON);
        assertEquals(3, facing.getX(), EPSILON);
        assertEquals(4, facing.getY(), EPSILON);
        assertEquals(-5, facing.getZ(), EPSILON);
        assertFalse(particles.isEmpty());
        for (final Particle particle : particles) {
            assertSame(world, particle.location().getWorld());
        }
    }

    @Test
    void verticalAndZeroFacingDoNotGenerateNaNParticles() {
        for (final Vector facing : List.of(new Vector(0, 1, 0), new Vector(0, 0, 0))) {
            final List<Particle> particles = new ArrayList<>();
            TornadoVisuals.render(new Location(new World(), 0, 0, 0), facing,
                    24, 8, 1.25, DEFAULT_SETTINGS,
                    (location, count, spread) -> particles.add(new Particle(location, count, spread)));

            assertFalse(particles.isEmpty());
            assertFiniteParticles(particles);
        }
    }

    @Test
    void anUnavailableWorldDoesNotEmitParticles() {
        final List<Particle> particles = new ArrayList<>();
        final TornadoVisuals.ParticleSink sink = (location, count, spread) ->
                particles.add(new Particle(location, count, spread));

        TornadoVisuals.render(null, new Vector(1, 0, 0), 24, 8, 1.25, DEFAULT_SETTINGS, sink);
        TornadoVisuals.render(new Location(null, 0, 0, 0), new Vector(1, 0, 0),
                24, 8, 1.25, DEFAULT_SETTINGS, sink);

        assertTrue(particles.isEmpty());
    }

    private static List<Particle> render(final double height, final double radius,
                                         final TornadoVisuals.Settings settings) {
        final List<Particle> particles = new ArrayList<>();
        TornadoVisuals.render(new Location(new World(), 0, 0, 0), new Vector(1, 0, 0),
                height, radius, 1.25, settings,
                (location, count, spread) -> particles.add(new Particle(location, count, spread)));
        return particles;
    }

    private static int particleCount(final List<Particle> particles) {
        return particles.stream().mapToInt(Particle::count).sum();
    }

    private static double maxHeight(final List<Particle> particles) {
        return particles.stream().mapToDouble(particle -> particle.location().getY()).max().orElseThrow();
    }

    private static double maxRadius(final List<Particle> particles) {
        return particles.stream().mapToDouble(particle -> Math.hypot(
                particle.location().getX(), particle.location().getZ())).max().orElseThrow();
    }

    private static void assertFiniteParticles(final List<Particle> particles) {
        for (final Particle particle : particles) {
            assertTrue(Double.isFinite(particle.location().getX()));
            assertTrue(Double.isFinite(particle.location().getY()));
            assertTrue(Double.isFinite(particle.location().getZ()));
            assertTrue(Double.isFinite(particle.spread()) && particle.spread() >= 0);
            assertTrue(particle.count() > 0);
        }
    }

    private record Particle(Location location, int count, double spread) { }
}
