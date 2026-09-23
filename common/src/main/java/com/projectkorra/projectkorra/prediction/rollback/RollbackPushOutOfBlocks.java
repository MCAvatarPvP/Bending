package com.projectkorra.projectkorra.prediction.rollback;

/** Minecraft client escape selection; native adapters supply height-aware suffocation shapes and immediate velocity writes. */
public final class RollbackPushOutOfBlocks {
    private RollbackPushOutOfBlocks() { }
    public enum Direction {
        WEST(-1, 0), EAST(1, 0), NORTH(0, -1), SOUTH(0, 1);
        private final int x, z;
        Direction(int x, int z) { this.x = x; this.z = z; }
        public int x() { return x; }
        public int z() { return z; }
    }
    private static final Direction[] ORDER = Direction.values();
    public interface Body {
        double x();
        double z();
        float width();
        boolean noClip();
        boolean collides(int columnX, int columnZ);
        /** Replace only the chosen horizontal component by 0.1 times its sign; preserve vertical/other horizontal velocity. */
        void push(Direction direction);
    }
    public static void apply(Body body) {
        if (body.noClip()) return;
        push(body, body.x() - body.width() * .35, body.z() + body.width() * .35);
        push(body, body.x() - body.width() * .35, body.z() - body.width() * .35);
        push(body, body.x() + body.width() * .35, body.z() - body.width() * .35);
        push(body, body.x() + body.width() * .35, body.z() + body.width() * .35);
    }
    private static void push(Body body, double x, double z) {
        int bx = floor(x), bz = floor(z);
        if (!body.collides(bx, bz)) return;
        double offsetX = x - bx, offsetZ = z - bz, nearest = Double.MAX_VALUE;
        Direction selected = null;
        for (var direction : ORDER) {
            double offset = direction.x != 0 ? offsetX : offsetZ;
            double distance = direction.x > 0 || direction.z > 0 ? 1 - offset : offset;
            if (distance < nearest && !body.collides(bx + direction.x, bz + direction.z)) {
                nearest = distance; selected = direction;
            }
        }
        if (selected != null) body.push(selected);
    }
    private static int floor(double value) { int integer = (int) value; return value < integer ? integer - 1 : integer; }
}
