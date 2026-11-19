package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position;
import java.io.*;
import java.util.*;

/** Match rules for physical block breaks and drops. Bending uses its own region and ability rules. */
public record RollbackDuelInteractions(boolean build, boolean bedWars, Set<String> allowedArenaMaterials, Set<Position> placed, RollbackItemRules items, Set<String> liveBreakMaterials) {
    public RollbackDuelInteractions withLiveBreakMaterials(Set<String> materials) {
        return new RollbackDuelInteractions(build, bedWars, allowedArenaMaterials, placed, items, materials);
    }
    public RollbackDuelInteractions(boolean build, boolean bedWars, Set<String> allowedArenaMaterials, Set<Position> placed) {
        this(build, bedWars, allowedArenaMaterials, placed, RollbackItemRules.DEFAULT, Set.of());
    }
    public RollbackDuelInteractions withItems(RollbackItemRules settings) {
        return new RollbackDuelInteractions(build, bedWars, allowedArenaMaterials, placed, settings, liveBreakMaterials);
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
        liveBreakMaterials = Set.copyOf(liveBreakMaterials);
        if (liveBreakMaterials.size() > 8192) throw new IllegalArgumentException("Native break material count");
        if (allowedArenaMaterials.size() > 8192 || placed.size() > 32768 || !build && !placed.isEmpty())
            throw new IllegalArgumentException("Invalid duel block rules");
        allowedArenaMaterials = Set.copyOf(allowedArenaMaterials);
        placed = Set.copyOf(placed);
    }
    public Decision breakDecision(int x, int y, int z, String material) {
        if (material.contains("BED")) return bedWars ? Decision.LIVE_MATCH : creativeBreakDecision(material);
        if (material.equals("FIRE") || bedWars && (material.equals("OAK_PLANKS") || material.equals("END_STONE"))
                || allowedArenaMaterials.contains(material) || build && placed.contains(new Position(x,y,z))) return creativeBreakDecision(material);
        return Decision.DENY;
    }
    public Decision creativeBreakDecision(String material) {
        return liveBreakMaterials.contains(material) ? Decision.LIVE_MATCH : Decision.ALLOW;
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
        out.writeShort(liveBreakMaterials.size());
        for (String name : new TreeSet<>(liveBreakMaterials)) out.writeUTF(name);
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
        var items = RollbackItemRules.read(in);
        int special = in.readUnsignedShort();
        if (special > 8192) throw new IllegalArgumentException("Native break material count");
        var live = new HashSet<String>();
        for (int i = 0; i < special; i++) if (!live.add(in.readUTF())) throw new IllegalArgumentException("Duplicate native break material");
        return new RollbackDuelInteractions(build, beds, materials, placed, items, live);
    }
}
