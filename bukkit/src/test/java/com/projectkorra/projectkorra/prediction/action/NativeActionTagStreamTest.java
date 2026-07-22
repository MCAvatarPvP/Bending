package com.projectkorra.projectkorra.prediction.action;

import com.projectkorra.projectkorra.prediction.protocol.PaperPredictionProtocol;
import com.projectkorra.projectkorra.prediction.protocol.PaperPredictionProtocol.InputKind;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeActionTagStreamTest {
    private final UUID session = UUID.randomUUID();

    @Test
    void exactImmediatelyFollowingInputReceivesTheClientIdentity() {
        final NativeActionTagStream tags = new NativeActionTagStream();
        tags.offer(tag(50L, InputKind.LEFT_CLICK, "EarthSmash"));

        assertEquals(50L, tags.consume(InputKind.LEFT_CLICK, 2, "EarthSmash"));
        assertEquals(0L, tags.consume(InputKind.LEFT_CLICK, 2, "EarthSmash"));
    }

    @Test
    void mismatchedInputConsumesTagInsteadOfPoisoningNextRepeatedCast() {
        final NativeActionTagStream tags = new NativeActionTagStream();
        tags.offer(tag(50L, InputKind.LEFT_CLICK, "EarthSmash"));

        assertEquals(0L, tags.consume(InputKind.RIGHT_CLICK, 2, "EarthSmash"));
        assertEquals(0L, tags.consume(InputKind.LEFT_CLICK, 2, "EarthSmash"));
    }

    @Test
    void newerTagReplacesAnInputThatNeverReachedTheNativeCallback() {
        final NativeActionTagStream tags = new NativeActionTagStream();
        tags.offer(tag(50L, InputKind.LEFT_CLICK, "EarthSmash"));
        tags.offer(tag(51L, InputKind.LEFT_CLICK, "EarthSmash"));

        assertEquals(51L, tags.consume(InputKind.LEFT_CLICK, 2, "EarthSmash"));
    }

    private PaperPredictionProtocol.ActionTag tag(final long sequence, final InputKind kind,
                                                   final String ability) {
        return new PaperPredictionProtocol.ActionTag(this.session, sequence, kind, 2, ability);
    }
}
