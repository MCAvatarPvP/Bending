package com.projectkorra.projectkorra.prediction.action;

import com.projectkorra.projectkorra.prediction.protocol.PaperPredictionProtocol;

/**
 * One-shot identity metadata for the next native input callback.
 *
 * <p>Protocol v47 sends a tag immediately before its vanilla packet on the
 * same ordered connection. A second tag therefore supersedes an unconsumed
 * one, and every callback consumes the pending value even when its semantics
 * disagree. Those rules prevent stale metadata from crossing into a later,
 * visually identical cast.</p>
 */
public final class NativeActionTagStream {
    private PaperPredictionProtocol.ActionTag pending;

    public void offer(final PaperPredictionProtocol.ActionTag tag) {
        pending = tag;
    }

    public long consume(final PaperPredictionProtocol.InputKind kind,
                        final int selectedSlot, final String ability) {
        final PaperPredictionProtocol.ActionTag tag = pending;
        pending = null;
        if (tag == null || tag.clientSequence() <= 0L || tag.kind() != kind
                || tag.selectedSlot() != selectedSlot || ability == null
                || !ability.equalsIgnoreCase(tag.ability())) return 0L;
        return tag.clientSequence();
    }

    public void clear() {
        pending = null;
    }
}
