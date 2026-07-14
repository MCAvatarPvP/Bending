package com.projectkorra.projectkorra.platform.mc.block;

public enum BlockFace {
    DOWN, EAST, NORTH, NORTH_EAST, NORTH_WEST, SELF, SOUTH, SOUTH_EAST, SOUTH_WEST, UP, WEST;

    public BlockFace getOppositeFace() {
        return switch (this) {
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case EAST -> WEST;
            case WEST -> EAST;
            case UP -> DOWN;
            case DOWN -> UP;
            default -> SELF;
        };
    }

    public Object handle() {
        return this;
    }
}
