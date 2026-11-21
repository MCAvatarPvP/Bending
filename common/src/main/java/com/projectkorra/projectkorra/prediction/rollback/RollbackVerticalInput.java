package com.projectkorra.projectkorra.prediction.rollback;

/** Client vertical input phase, before native player travel. Permissions/mode transitions belong to the control path. */
public final class RollbackVerticalInput {
    private RollbackVerticalInput() { }
    public interface Body {
        boolean touchingWater();
        boolean affectedByFluids();
        boolean sneaking();
        boolean jumping();
        boolean flying();
        float flySpeed();
        void addVertical(double amount);
    }
    public static void apply(Body body) {
        if (body.touchingWater() && body.sneaking() && body.affectedByFluids()) body.addVertical(-.04F);
        if (body.flying()) {
            int direction = 0;
            if (body.sneaking()) direction--;
            if (body.jumping()) direction++;
            if (direction != 0) body.addVertical(direction * body.flySpeed() * 3F);
        }
    }
}
