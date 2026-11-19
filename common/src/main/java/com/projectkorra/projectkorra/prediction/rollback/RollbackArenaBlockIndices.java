package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.earthbending.ShockwaveExecutionPolicy.MovedColumns;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.waterbending.Torrent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/** Location-indexed bending state belonging to the same terrain lease as its temporary block stacks. */
public final class RollbackArenaBlockIndices {
    private static final RollbackArenaBlockIndices LIVE = new RollbackArenaBlockIndices();

    public static RollbackArenaBlockIndices live() { return LIVE; }

    public final RollbackParticipantAbilityIndices participants = new RollbackParticipantAbilityIndices();
    public final Map<Integer[], Block> rippleBlocks = new ConcurrentHashMap<>();
    public final MovedColumns rippleColumns = new MovedColumns();
    public final Map<Block, Block> spout = new ConcurrentHashMap<>();
    public final Map<Block, Block> water = new ConcurrentHashMap<>();
    public final Map<Block, Block> surge = new ConcurrentHashMap<>();
    public final Map<Block, Player> walls = new ConcurrentHashMap<>();
    public final List<TempBlock> surgeSources = new ArrayList<>();
    public final Map<Block, TempBlock> spoutIce = new ConcurrentHashMap<>();
    public final Map<TempBlock, Player> freezeOwner = new ConcurrentHashMap<>();
    public final List<Block> frozen = new ArrayList<>();
    public final Map<TempBlock, Torrent.FrozenSource> torrent = new ConcurrentHashMap<>();
    public final Map<Block, Long> spearExpiry = new ConcurrentHashMap<>();
    public final Map<Block, TempBlock> spearLayers = new ConcurrentHashMap<>();
    public final Map<Block, TempBlock> iceWave = new ConcurrentHashMap<>();
    public final Map<Block, Player> illumination = new ConcurrentHashMap<>();
    public final Map<Block, TempBlock> melted = new ConcurrentHashMap<>();

    public static RollbackArenaBlockIndices current() {
        var globals = RollbackDomain.abilityGlobals();
        return globals == null ? null : globals.blockIndices();
    }

    public static RollbackArenaBlockIndices capture(Predicate<Block> inside, Set<UUID> players) {
        var result = new RollbackArenaBlockIndices();
        result.transfer(new Transfer(Operation.CAPTURE, inside, players));
        return result;
    }

    public void detach(Predicate<Block> inside, Set<UUID> players) {
        transfer(new Transfer(Operation.CHECK_SOURCE, inside, players));
        transfer(new Transfer(Operation.DETACH, inside, players));
    }

    public void validate(Predicate<Block> inside, Set<UUID> players) {
        transfer(new Transfer(Operation.CHECK_DESTINATION, inside, players));
    }

    public void merge(Predicate<Block> inside, Set<UUID> players) {
        validate(inside, players);
        transfer(new Transfer(Operation.MERGE, inside, players));
    }

    private void transfer(Transfer transfer) {
        participants.transfer(transfer.operation, transfer.players);
        transferRipple(transfer);
        transfer.map(LIVE.spout, spout, Function.identity());
        transfer.map(LIVE.water, water, Function.identity());
        transfer.map(LIVE.surge, surge, Function.identity());
        transfer.map(LIVE.walls, walls, Function.identity());
        transfer.list(LIVE.surgeSources, surgeSources, TempBlock::getBlock);
        transfer.map(LIVE.spoutIce, spoutIce, Function.identity());
        transfer.map(LIVE.freezeOwner, freezeOwner, TempBlock::getBlock);
        transfer.list(LIVE.frozen, frozen, Function.identity());
        transfer.map(LIVE.torrent, torrent, TempBlock::getBlock);
        transfer.map(LIVE.spearExpiry, spearExpiry, Function.identity());
        transfer.map(LIVE.spearLayers, spearLayers, Function.identity());
        transfer.map(LIVE.iceWave, iceWave, Function.identity());
        transfer.map(LIVE.illumination, illumination, Function.identity());
        transfer.map(LIVE.melted, melted, Function.identity());
    }

    private void transferRipple(Transfer transfer) {
        var selected = new HashMap<Integer[], Block>();
        LIVE.rippleBlocks.forEach((key, block) -> {
            if (transfer.inside.test(block)) selected.put(key, block);
        });
        switch (transfer.operation) {
            case CAPTURE -> selected.forEach((key, block) -> {
                requireColumn(LIVE, key, block);
                rippleBlocks.put(key, block);
                rippleColumns.mark(block.getWorld().getName(), block.getX(), block.getZ());
            });
            case CHECK_SOURCE -> {
                if (!selected.equals(rippleBlocks)) throw Transfer.changed();
                selected.forEach((key, block) -> requireColumn(LIVE, key, block));
            }
            case DETACH -> selected.forEach((key, block) -> {
                LIVE.rippleBlocks.remove(key);
                LIVE.rippleColumns.remove(block.getWorld().getName(), block.getX(), block.getZ());
            });
            case CHECK_DESTINATION -> {
                if (!selected.isEmpty()) throw Transfer.changed();
                rippleBlocks.forEach((key, block) -> {
                    if (!transfer.inside.test(block) || LIVE.rippleColumns.contains(block.getWorld().getName(), block.getX(), block.getZ()))
                        throw Transfer.changed();
                    requireColumn(this, key, block);
                });
            }
            case MERGE -> rippleBlocks.forEach((key, block) -> {
                LIVE.rippleBlocks.put(key, block);
                LIVE.rippleColumns.mark(block.getWorld().getName(), block.getX(), block.getZ());
            });
        }
    }

    private static void requireColumn(RollbackArenaBlockIndices state, Integer[] key, Block block) {
        if (key.length != 2 || key[0] != block.getX() || key[1] != block.getZ()
                || !state.rippleColumns.contains(block.getWorld().getName(), block.getX(), block.getZ()))
            throw Transfer.changed();
    }

    enum Operation { CAPTURE, CHECK_SOURCE, DETACH, CHECK_DESTINATION, MERGE }

    private record Transfer(Operation operation, Predicate<Block> inside, Set<UUID> players) {
        private void check(Object value, Block block) {
            if (value instanceof Player player && !players.contains(player.getUniqueId()))
                throw new IllegalStateException("Bending block points to an unenrolled player");
            if (value instanceof Torrent.FrozenSource source) check(source.player(), block);
            if (value instanceof Block other && !block.equals(other))
                throw new IllegalStateException("Bending block points to another position");
            if (value instanceof TempBlock layer && (!layer.getBlock().equals(block)
                    || layer.getOwnerId().filter(id -> !players.contains(id)).isPresent()))
                throw new IllegalStateException("Bending block references a foreign temporary layer");
        }

        <K,V> void map(Map<K,V> live, Map<K,V> owned, Function<K,Block> position) {
            switch (operation) {
                case CAPTURE, CHECK_SOURCE -> {
                    var selected = new HashMap<K,V>();
                    live.forEach((key, value) -> {
                        Block block = position.apply(key);
                        if (inside.test(block)) {
                            check(key, block);
                            check(value, block);
                            selected.put(key, value);
                        } else if (value instanceof Player player && players.contains(player.getUniqueId())
                                || value instanceof Torrent.FrozenSource source && players.contains(source.player().getUniqueId())) {
                            throw new IllegalStateException("Bending block owned by a participant lies outside the arena");
                        }
                    });
                    if (operation == Operation.CAPTURE) owned.putAll(selected);
                    else if (!owned.equals(selected)) throw changed();
                }
                case DETACH -> owned.keySet().forEach(live::remove);
                case CHECK_DESTINATION -> {
                    if (live.keySet().stream().map(position).anyMatch(inside)) throw changed();
                    owned.forEach((key, value) -> {
                        Block block = position.apply(key);
                        if (!inside.test(block)) throw changed();
                        check(key, block);
                        check(value, block);
                    });
                }
                case MERGE -> live.putAll(owned);
            }
        }

        <K> void list(List<K> live, List<K> owned, Function<K,Block> position) {
            switch (operation) {
                case CAPTURE, CHECK_SOURCE -> {
                    var selected = live.stream().filter(key -> inside.test(position.apply(key))).toList();
                    for (K key : selected) check(key, position.apply(key));
                    if (operation == Operation.CAPTURE) owned.addAll(selected);
                    else if (!owned.equals(selected)) throw changed();
                }
                case DETACH -> live.removeIf(key -> inside.test(position.apply(key)));
                case CHECK_DESTINATION -> {
                    if (live.stream().map(position).anyMatch(inside)) throw changed();
                    for (K key : owned) {
                        Block block = position.apply(key);
                        if (!inside.test(block)) throw changed();
                        check(key, block);
                    }
                }
                case MERGE -> live.addAll(owned);
            }
        }

        private static IllegalStateException changed() {
            return new IllegalStateException("Bending block indices overlap another terrain owner");
        }
    }
}
