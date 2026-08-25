package com.projectkorra.projectkorra.airbending.combo;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** Maintains the delayed AirStream rings without scheduler tasks. */
final class AirStreamVisualTrail {

    private static final int EMISSION_COUNT = 10;
    private static final int EMISSION_INTERVAL = 2;
    private static final int HISTORY_SIZE = (EMISSION_COUNT - 1) * EMISSION_INTERVAL + 1;

    private final Deque<Frame> history = new ArrayDeque<>(HISTORY_SIZE);
    private final List<Frame> visibleFrames = new ArrayList<>(EMISSION_COUNT);
    private final List<Frame> visibleFramesView = Collections.unmodifiableList(this.visibleFrames);

    void advance(final Location location, final Vector direction, final boolean preserveTrail) {
        if (location == null || direction == null) {
            return;
        }

        // Seed a stream that collided on its first attempted movement. Once a
        // history exists, rollback must not age any portion of the trail.
        if (preserveTrail && !this.history.isEmpty()) {
            return;
        }

        this.history.addFirst(new Frame(location, direction));
        if (this.history.size() > HISTORY_SIZE) {
            this.history.removeLast();
        }
        this.rebuildVisibleFrames();
    }

    List<Frame> visibleFrames() {
        return this.visibleFramesView;
    }

    private void rebuildVisibleFrames() {
        this.visibleFrames.clear();

        int index = 0;
        for (final Frame frame : this.history) {
            if (index % EMISSION_INTERVAL == 0) {
                this.visibleFrames.add(frame);
            }
            index++;
        }
    }

    static final class Frame {
        private final Location location;
        private final Vector direction;

        private Frame(final Location location, final Vector direction) {
            this.location = location.clone();
            this.direction = direction.clone();
        }

        Location location() {
            return this.location.clone();
        }

        Vector direction() {
            return this.direction.clone();
        }
    }
}
