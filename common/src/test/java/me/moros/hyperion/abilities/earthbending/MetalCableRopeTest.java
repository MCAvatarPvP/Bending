package me.moros.hyperion.abilities.earthbending;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.support.AbilityWorld;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetalCableRopeTest {
    private final AbilityWorld world = new AbilityWorld();
    private final MetalCableRope rope = new MetalCableRope();
    private final Location hand = new Location(world, 0.5, 66, 0.5);
    private final Location end = new Location(world, 0.5, 66, 12.5);

    @AfterEach void cleanup() throws Exception { rope.remove(); world.close(); }

    @Test void ropeSagsAndSwaysWhileKeepingBothEndsPinned() {
        List<Location> points = null;
        for (int tick = 0; tick < 60; tick++) points = rope.update(hand, end, new Vector(0, 0, 1), false);
        assertEquals(hand, points.getFirst());
        assertEquals(end, points.getLast());
        assertTrue(points.stream().anyMatch(point -> point.getY() < hand.getY() - 0.2));
        hand.setX(2);
        List<Location> moved = rope.update(hand, end, new Vector(0, 0, 1), false);
        assertEquals(hand, moved.getFirst());
        assertEquals(end, moved.getLast());
        List<Location> next = rope.update(hand, end, new Vector(0, 0, 1), false);
        assertTrue(next.get(next.size() / 2).distanceSquared(moved.get(moved.size() / 2)) > 1.0E-8,
                "interior nodes continue moving after an endpoint moves");
    }

    @Test void pullingTightensTheRope() {
        double looseSag = 0;
        double tautSag = 0;
        for (int tick = 0; tick < 100; tick++) looseSag = sag(rope.update(hand, end, new Vector(0, 0, 1), false));
        for (int tick = 0; tick < 100; tick++) tautSag = sag(rope.update(hand, end, new Vector(0, 0, 1), true));
        assertTrue(tautSag < looseSag * 0.85, "tension must visibly reduce slack: " + looseSag + " -> " + tautSag);
    }

    @Test void everyRenderedSegmentMeetsTheNextWithoutGaps() {
        List<Location> points = rope.update(hand, end, new Vector(0, 0, 1), false);
        for (int i = 0; i < points.size() - 1; i++) {
            var display = world.displays.get(i);
            var transform = display.transformation;
            // Center of the rod's upper face, transformed from block model coordinates.
            Vector3f tip = new Vector3f(0.5F, 1, 0.5F).mul(transform.scale());
            transform.leftRotation().transform(tip);
            tip.add(transform.translation());
            Location renderedEnd = display.getLocation().add(tip.x, tip.y, tip.z);
            assertEquals(0, renderedEnd.distanceSquared(points.get(i + 1)), 1.0E-10);
            assertEquals(points.get(i), display.getLocation());
        }
    }

    @Test void ropeReusesDisplaysAndCapsLongCableEntityCounts() {
        rope.update(hand, end, new Vector(0, 0, 1), false);
        int initial = world.displays.size();
        for (int tick = 0; tick < 50; tick++) rope.update(hand, end, new Vector(0, 0, 1), true);
        assertEquals(initial, world.displays.size());
        end.setZ(100);
        rope.update(hand, end, new Vector(0, 0, 1), true);
        assertEquals(51, world.displays.stream().filter(Entity::isValid).count());
        end.setZ(2);
        rope.update(hand, end, new Vector(0, 0, 1), true);
        assertEquals(6, world.displays.stream().filter(Entity::isValid).count());
        rope.remove();
        rope.remove();
        assertTrue(world.displays.stream().noneMatch(Entity::isValid));
    }

    @Test void saggingNodesStayAboveSolidGround() {
        for (int z = -1; z <= 14; z++) world.getBlockAt(0, 63, z).setType(Material.STONE);
        hand.setY(64.15);
        end.setY(64.15);
        for (int tick = 0; tick < 80; tick++) {
            var points = rope.update(hand, end, new Vector(0, 0, 1), false);
            assertTrue(points.stream().allMatch(point -> point.getY() >= 64.03));
        }
    }

    @Test void verticalAndCoincidentEndpointsRemainFinite() {
        end.setX(hand.getX());
        end.setZ(hand.getZ());
        for (double y : new double[]{hand.getY(), 80, 40, hand.getY()}) {
            end.setY(y);
            var points = rope.update(hand, end, new Vector(0, 1, 0), true);
            assertEquals(hand, points.getFirst());
            assertEquals(end, points.getLast());
            assertTrue(points.stream().allMatch(point -> Double.isFinite(point.getX())
                    && Double.isFinite(point.getY()) && Double.isFinite(point.getZ())));
            for (var display : world.displays) {
                if (display.isValid()) assertTrue(display.transformation.translation().isFinite());
            }
        }
    }

    private double sag(List<Location> points) {
        return hand.getY() - points.stream().mapToDouble(Location::getY).min().orElseThrow();
    }
}
