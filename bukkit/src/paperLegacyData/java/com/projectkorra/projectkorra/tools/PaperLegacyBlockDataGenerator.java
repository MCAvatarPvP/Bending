package com.projectkorra.projectkorra.tools;

import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.legacy.CraftLegacy;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

/** Build-time export of the exact legacy bytes from the target Paper bundle. */
public final class PaperLegacyBlockDataGenerator {
    private PaperLegacyBlockDataGenerator() { }

    public static void main(String[] args) throws IOException {
        Path destination = Path.of(args[0]);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        CraftRegistry.setMinecraftRegistry(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        var values = new TreeMap<String, Byte>();
        for (var state : Block.BLOCK_STATE_REGISTRY) {
            byte value = CraftLegacy.toLegacyData(state);
            if (value != 0) values.put(BlockStateParser.serialize(state), value);
        }
        if (values.isEmpty()) throw new IOException("Missing Paper legacy block data");
        Files.createDirectories(destination.getParent());
        try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(destination)))) {
            out.writeUTF(args[1]);
            out.writeInt(values.size());
            for (var entry : values.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeByte(entry.getValue());
            }
        }
    }
}
