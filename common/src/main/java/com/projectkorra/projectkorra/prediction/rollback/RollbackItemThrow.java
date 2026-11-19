package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.Motion;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleUnaryOperator;

/** Native player-directed item throw, including the four draws from the throwing player's RNG. */
public final class RollbackItemThrow {
    private RollbackItemThrow() { }

    public static Motion motion(float pitch, float yaw, DoubleUnaryOperator nativeSin, DoubleUnaryOperator nativeCos,
            DoubleSupplier nextFloat) {
        float pitchRadians = pitch * ((float) Math.PI / 180F);
        float yawRadians = yaw * ((float) Math.PI / 180F);
        float pitchSin = (float) nativeSin.applyAsDouble(pitchRadians);
        float pitchCos = (float) nativeCos.applyAsDouble(pitchRadians);
        float yawSin = (float) nativeSin.applyAsDouble(yawRadians);
        float yawCos = (float) nativeCos.applyAsDouble(yawRadians);
        float scatterAngle = (float) nextFloat.getAsDouble() * ((float) Math.PI * 2F);
        float scatterRadius = 0.02F * (float) nextFloat.getAsDouble();
        float verticalScatter = ((float) nextFloat.getAsDouble() - (float) nextFloat.getAsDouble()) * 0.1F;
        return new Motion(-yawSin * pitchCos * 0.3F + Math.cos(scatterAngle) * scatterRadius,
                -pitchSin * 0.3F + 0.1F + verticalScatter,
                yawCos * pitchCos * 0.3F + Math.sin(scatterAngle) * scatterRadius);
    }
}
