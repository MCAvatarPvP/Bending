package com.projectkorra.projectkorra.prediction.rollback;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** World-independent block data generated from the same Paper bundle as the native adapters. */
final class PaperRollbackLegacyBlocks {
    private static final Map<String, Byte> VALUES = load();

    private PaperRollbackLegacyBlocks() { }

    static Map<String, Byte> values() {
        return VALUES;
    }

    private static Map<String, Byte> load() {
        try (var resource = PaperRollbackLegacyBlocks.class.getResourceAsStream("/rollback/paper-legacy-blocks.bin.gz")) {
            if (resource == null) throw new IOException("Missing Paper legacy block table");
            try (var in = new DataInputStream(new GZIPInputStream(resource))) {
                String version = in.readUTF();
                if (!version.equals(net.minecraft.SharedConstants.getCurrentVersion().name())) {
                    throw new IOException("Paper legacy block table targets Minecraft " + version);
                }
                int count = in.readInt();
                if (count <= 0 || count > 65536) throw new IOException("Invalid Paper legacy table size");
                var values = new HashMap<String, Byte>(count);
                for (int i = 0; i < count; i++) {
                    String state = in.readUTF();
                    byte value = in.readByte();
                    if (value == 0 || values.put(state, value) != null) throw new IOException("Invalid Paper legacy block " + state);
                }
                if (in.read() != -1) throw new IOException("Trailing Paper legacy block data");
                return Map.copyOf(values);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not load Paper legacy block data", e);
        }
    }
}
