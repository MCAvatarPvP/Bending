package com.projectkorra.projectkorra.prediction.action;

import com.projectkorra.projectkorra.prediction.protocol.PaperPredictionProtocol.MeleeAction;
import java.util.UUID;

/** A tag only attributes the actual attack and damage callbacks for one native target. */
public final class NativeMeleeActionStream {
    private MeleeAction pending;
    private MeleeAction attacking;
    private long tagTick;
    private long attackTick;

    public void offer(MeleeAction tag, long tick) {
        pending = tag.sequence() > 0 && tag.slot() >= 0 && tag.slot() < 9 && !tag.ability().isBlank() ? tag : null;
        attacking = null;
        tagTick = tick;
    }

    public void attack(long tick, int id, UUID target, boolean accepted) {
        var tag = pending;
        pending = null;
        attacking = accepted && tick - tagTick <= 2 && matches(tag, id, target) ? tag : null;
        attackTick = tick;
    }

    public MeleeAction damage(long tick, int id, UUID target) {
        var tag = attacking;
        attacking = null;
        return tick == attackTick && matches(tag, id, target) ? tag : null;
    }

    public void clear() { pending = null; attacking = null; }

    private static boolean matches(MeleeAction tag, int id, UUID target) {
        return tag != null && tag.targetId() == id && tag.target().equals(target);
    }
}
