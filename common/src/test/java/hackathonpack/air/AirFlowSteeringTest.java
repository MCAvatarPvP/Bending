package hackathonpack.air;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.support.AbilityWorld;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class AirFlowSteeringTest {
    @ParameterizedTest @ValueSource(doubles = {1, -1})
    @SuppressWarnings({"rawtypes", "unchecked"})
    void launchedFlowTracksYawAndPitchWithoutMovingItsSource(double direction) throws Exception {
        try (var world = new AbilityWorld()) {
            var aim = new Location(world, 0, 2, 0);
            var player = new Player() {
                @Override public Location getEyeLocation() { return aim.clone(); }
                @Override public World getWorld() { return world; }
            };
            var flow = new TestFlow(player, direction);
            var source = new Location(world, 4, 5, 6);
            field("flowLocation").set(flow, source);
            field("launchTime").setLong(flow, System.currentTimeMillis());
            Field state = field("state");
            state.set(flow, Enum.valueOf((Class) state.getType(), "LAUNCHED"));
            for (float[] rotation : new float[][]{{0, 0}, {90, 30}, {-135, -65}, {180, 90}}) {
                aim.setYaw(rotation[0]); aim.setPitch(rotation[1]); aim.setX(aim.getX() + 10);
                flow.progress();
                Vector expected = aim.getDirection().multiply(direction);
                Vector actual = (Vector) field("flowDirection").get(flow);
                assertEquals(expected.getX(), actual.getX(), 1e-9);
                assertEquals(expected.getY(), actual.getY(), 1e-9);
                assertEquals(expected.getZ(), actual.getZ(), 1e-9);
                assertEquals(4, source.getX()); assertEquals(5, source.getY()); assertEquals(6, source.getZ());
                assertEquals(0, source.getYaw()); assertEquals(0, source.getPitch());
            }
            var path = (AirFlowPath) field("path").get(flow);
            while (path.size() < 40) flow.progress();
            Vector locked = ((Vector) field("flowDirection").get(flow)).clone();
            aim.setYaw(45); aim.setPitch(0);
            flow.progress();
            assertEquals(40, path.size());
            assertVector(locked, (Vector) field("flowDirection").get(flow));
            assertVector(new Vector(0, 0, direction * 0.5), path.step(0));
            assertVector(locked.clone().normalize().multiply(0.5), path.step(39));
        }
    }
    private static void assertVector(Vector expected, Vector actual) {
        assertEquals(expected.getX(), actual.getX(), 1e-9);
        assertEquals(expected.getY(), actual.getY(), 1e-9);
        assertEquals(expected.getZ(), actual.getZ(), 1e-9);
    }

    @org.junit.jupiter.api.Test
    void curvedPathStopsAtTravelledRangeAndCanBeFollowedRepeatedly() {
        var path = new AirFlowPath();
        var aim = new Vector(0, 0, 1);
        path.extend(aim, 1.25);
        aim.setX(1).setZ(0);
        path.extend(aim, 1.25);
        assertFalse(path.isFull(1.25));
        path.extend(new Vector(0, 1, 0), 1.25);
        assertTrue(path.isFull(1.25));
        path.extend(new Vector(0, 0, -1), 1.25);
        assertEquals(3, path.size());
        for (int particle = 0; particle < 2; particle++) {
            Vector position = new Vector();
            double distance = 0;
            for (int segment = 0; segment < path.size(); segment++) {
                Vector step = path.step(segment);
                distance += step.length();
                position.add(step);
                step.multiply(0); // A consumer must not change the saved path.
            }
            assertEquals(1.25, distance, 1e-9);
            assertVector(new Vector(0.5, 0.25, 0.5), position);
        }
    }

    private static Field field(String name) throws Exception {
        Field field = AbstractAirFlow.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static final class TestFlow extends AbstractAirFlow {
        private final double direction;
        TestFlow(Player player, double direction) {
            super(null); this.player = player; this.direction = direction;
            setFlowFields(0, 60_000, 20, 0, 3);
        }
        @Override protected int sourceDistance() { return 4; }
        @Override protected double directionMultiplier() { return direction; }
        @Override public long getCooldown() { return 0; }
        @Override public Location getLocation() { return null; }
        @Override public String getName() { return "AirFlowSteeringTest"; }
        @Override public boolean isHarmlessAbility() { return false; }
        @Override public boolean isSneakAbility() { return false; }
    }
}
