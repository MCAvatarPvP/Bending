package hackathonpack.air;

import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.ArrayList;

/** Records the growing tip so subsequent air follows the same finite curve. */
final class AirFlowPath {
    private static final double SPEED = 0.5;
    private final ArrayList<Vector> steps = new ArrayList<>();
    private double length;

    boolean isFull(final double range) {
        return this.length >= range;
    }

    void extend(final Vector direction, final double range) {
        if (isFull(range)) return;
        final double distance = Math.min(SPEED, range - this.length);
        this.steps.add(direction.clone().normalize().multiply(distance));
        this.length = Math.min(range, this.length + distance);
    }

    int size() {
        return this.steps.size();
    }

    Vector step(final int index) {
        return this.steps.get(index).clone();
    }
}
