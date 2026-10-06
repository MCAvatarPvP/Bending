package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.attribute.AttributeCache;
import com.projectkorra.projectkorra.attribute.markers.DayNightFactor;
import java.lang.annotation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollbackAttributeBindingsTest {
    @Retention(RetentionPolicy.RUNTIME) @interface Detail {
        String text(); int[] values(); Class<?> type(); DayNightFactor nested();
    }
    @DayNightFactor(factor = 2, invert = true)
    @Detail(text = "local", values = {3, 7}, type = String[].class, nested = @DayNightFactor(factor = 4))
    private double speed;

    @Test void independentlyCreatedDefinitionsBindWithoutCopyingReflectionOrAnnotationObjects() throws Exception {
        var field = getClass().getDeclaredField("speed");
        var left = new AttributeCache(field, "Speed");
        var right = new AttributeCache(field, "Speed");
        left.addMaker(field.getAnnotation(DayNightFactor.class)); left.addMaker(field.getAnnotation(Detail.class));
        right.addMaker(field.getAnnotation(Detail.class)); right.addMaker(field.getAnnotation(DayNightFactor.class));
        var a = left.rollbackMetadataBinding("ability#Speed");
        var b = right.rollbackMetadataBinding("ability#Speed");
        assertEquals(a.id(), b.id()); assertNotSame(a.value(), b.value());
        var sender = codec(a); var receiver = codec(b);
        var copy = (AttributeCache) receiver.decode(sender.encode(List.of(left))).getFirst();
        assertNotSame(left, copy);
        assertTrue(copy.sameRollbackDefinition(right));
        assertSame(b.value(), copy.rollbackMetadataBinding("ability#Speed").value());
        assertNotSame(left.getInitialValues(), copy.getInitialValues());
    }

    @Test void changedMarkersOrAttributeIdentityRejectTransfer() throws Exception {
        var field = getClass().getDeclaredField("speed");
        var source = new AttributeCache(field, "Speed");
        var target = new AttributeCache(field, "Speed");
        source.addMaker(field.getAnnotation(DayNightFactor.class));
        var a = source.rollbackMetadataBinding("ability#Speed");
        var b = target.rollbackMetadataBinding("ability#Speed");
        assertNotEquals(a.id(), b.id());
        target.addMaker(field.getAnnotation(Detail.class).nested());
        assertNotEquals(a.id(), target.rollbackMetadataBinding("ability#Speed").id(),
                "Different values of the same marker must change the binding schema");
        assertNotEquals(a.id(), source.rollbackMetadataBinding("otherAbility#Speed").id());
        byte[] wire = codec(a).encode(List.of(source));
        assertThrows(IllegalArgumentException.class, () -> codec(b).decode(wire));
        assertNotEquals(RollbackDefinitionSchema.fingerprint(new int[]{3, 7}),
                RollbackDefinitionSchema.fingerprint(new int[]{7, 3}));
        assertNotEquals(RollbackDefinitionSchema.fingerprint(-0.0), RollbackDefinitionSchema.fingerprint(0.0));
        assertThrows(IllegalArgumentException.class, () -> RollbackDefinitionSchema.fingerprint(new Object()));
    }

    private static RollbackGraphCodec codec(RollbackGraphCodec.Binding binding) {
        return new RollbackGraphCodec(RollbackGameplayCatalog.create(List.of(AttributeCache.class), List.of(binding)),
                new RollbackGraphCodec.Limits(1000, 1000, 100000, 10000));
    }
}
