package com.projectkorra.projectkorra.platform.mc.block.data.type;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;

import java.util.EnumSet;
import java.util.Set;

public class Fire extends BlockData {
    private final EnumSet<BlockFace> faces = EnumSet.noneOf(BlockFace.class);

    public Fire() {
        this(Material.FIRE);
    }

    public Fire(Material material) {
        super(material);
    }

    public void setFace(BlockFace face, boolean value) {
        if (face == null) return;
        if (value) {
            faces.add(face);
        } else {
            faces.remove(face);
        }
    }

    public boolean hasFace(BlockFace face) {
        return faces.contains(face);
    }

    public Set<BlockFace> getFaces() {
        return EnumSet.copyOf(faces);
    }

    @Override
    public Fire clone() {
        Fire clone = new Fire(getMaterial());
        clone.faces.addAll(faces);
        return clone;
    }
}
