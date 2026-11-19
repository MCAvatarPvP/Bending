package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position;
import java.io.*;
import java.util.*;

/** Match rules for physical block breaks and drops. Bending uses its own region and ability rules. */
public record RollbackDuelInteractions(boolean build, boolean bedWars, Set<String> allowedArenaMaterials, Set<Position> placed, RollbackItemRules items) {
    public RollbackDuelInteractions(boolean build, boolean bedWars, Set<String> allowedArenaMaterials, Set<Position> placed) {
        this(build, bedWars, allowedArenaMaterials, placed, RollbackItemRules.DEFAULT);
    }
    public RollbackDuelInteractions withItems(RollbackItemRules settings) {
        return new RollbackDuelInteractions(build, bedWars, allowedArenaMaterials, placed, settings);
    }
    public enum Decision { DENY, ALLOW, LIVE_MATCH }
    public record LiveBreak(UUID player, int x, int y, int z, String material) {
        public LiveBreak { Objects.requireNonNull(player); Objects.requireNonNull(material); }
    }
    public static final RollbackDuelInteractions STANDARD = new RollbackDuelInteractions(false, false, Set.of(), Set.of());
    private static final Comparator<Position> ORDER = Comparator.comparingInt(Position::x)
            .thenComparingInt(Position::y).thenComparingInt(Position::z);

    public RollbackDuelInteractions {
        Objects.requireNonNull(items);
        if (allowedArenaMaterials.size() > 8192 || placed.size() > 32768 || !build && !placed.isEmpty())
            throw new IllegalArgumentException("Invalid duel block rules");
        allowedArenaMaterials = Set.copyOf(allowedArenaMaterials);
        placed = Set.copyOf(placed);
    }
    public Decision breakDecision(int x, int y, int z, String material) {
        if (material.contains("BED")) return bedWars ? Decision.LIVE_MATCH : Decision.ALLOW;
        if (material.equals("FIRE") || bedWars && (material.equals("OAK_PLANKS") || material.equals("END_STONE"))
                || allowedArenaMaterials.contains(material) || build && placed.contains(new Position(x,y,z))) return Decision.ALLOW;
        return Decision.DENY;
    }
    public boolean consumeDrop(String material) { return material.equals("GLASS_BOTTLE"); }

    void write(DataOutput out) throws IOException {
        out.writeBoolean(build); out.writeBoolean(bedWars);
        out.writeInt(allowedArenaMaterials.size());
        for (String material : new TreeSet<>(allowedArenaMaterials)) out.writeUTF(material);
        out.writeInt(placed.size());
        for (var position : placed.stream().sorted(ORDER).toList()) {
            out.writeInt(position.x()); out.writeInt(position.y()); out.writeInt(position.z());
        }
        items.write(out);
    }
    static RollbackDuelInteractions read(DataInput in) throws IOException {
        boolean build = in.readBoolean(), beds = in.readBoolean();
        int count = in.readInt();
        if (count < 0 || count > 8192) throw new IllegalArgumentException("Duel material count");
        var materials = new HashSet<String>();
        for (int i=0; i<count; i++) {
            var material = in.readUTF();
            if (material.isBlank() || material.length()>128 || !materials.add(material)) throw new IllegalArgumentException("Duel material");
        }
        count = in.readInt();
        if (count<0 || count>32768) throw new IllegalArgumentException("Duel placed block count");
        var placed = new HashSet<Position>();
        for (int i=0; i<count; i++)
            if (!placed.add(new Position(in.readInt(), in.readInt(), in.readInt()))) throw new IllegalArgumentException("Duplicate placed block");
        return new RollbackDuelInteractions(build, beds, materials, placed, RollbackItemRules.read(in));
    }
}
