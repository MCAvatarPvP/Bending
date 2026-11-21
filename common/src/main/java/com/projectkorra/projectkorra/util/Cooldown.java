package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.prediction.rollback.RollbackTimeShift;

public class Cooldown {

    /**
     * The amount of time the cooldown is valid including the system time when
     * the cooldown was created
     */
    private long cooldown;
    /**
     * If the cooldown should be saved in the database
     */
    private final boolean database;

    public Cooldown(final long cooldown, final boolean database) {
        this.cooldown = cooldown;
        this.database = database;
    }

    public long getCooldown() {
        return this.cooldown;
    }

    public void rebaseRollbackTime(RollbackTimeShift shift) {
        shift.once(this, () -> cooldown = shift.time(cooldown));
    }

    public boolean isDatabase() {
        return this.database;
    }
}
