package com.projectkorra.projectkorra.platform.mc.block;

import com.projectkorra.projectkorra.platform.mc.World;
import java.util.Objects;

/** Immutable world/coordinate view identity; unrelated addon Block state does not opt in implicitly. */
public interface BlockValue {
    // Reflexive fast paths only; distinct wrappers are compared through their world handles and coordinates.
    @SuppressWarnings("WrapperReferenceEquality")
    static boolean same(Block left, Object other) {
        if (left == other) return true;
        if (!(other instanceof Block right) || !(other instanceof BlockValue)) return false;
        World a = left.getWorld(), b = right.getWorld();
        boolean sameWorld = a == b || a != null && b != null && a.handle() != null && a.handle() == b.handle();
        return sameWorld && left.getX() == right.getX() && left.getY() == right.getY() && left.getZ() == right.getZ();
    }
    static int hash(Block block) { return Objects.hash(block.getX(), block.getY(), block.getZ()); }
}
