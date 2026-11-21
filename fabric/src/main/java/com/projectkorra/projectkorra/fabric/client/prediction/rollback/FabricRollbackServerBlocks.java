package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.RollbackServer;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import java.util.Locale;

/** Registry-only block defaults for the private server facade on a client replica. */
public final class FabricRollbackServerBlocks implements RollbackServer.Blocks<Void> {
    @Override public BlockData create(Material material) {
        var id = Identifier.of("minecraft", java.util.Objects.requireNonNull(material).canonical().name().toLowerCase(Locale.ROOT));
        if (!Registries.BLOCK.containsId(id)) throw new IllegalArgumentException("Material is not a native block: " + material);
        return FabricMC.blockData(Registries.BLOCK.get(id).getDefaultState());
    }
    @Override public Void captureRollbackState() { return null; }
    @Override public void restoreRollbackState(Void ignored) { }
}
