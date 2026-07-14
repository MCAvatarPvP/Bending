package com.projectkorra.projectkorra.storage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight non-SQL storage used as a safe fallback for Fabric dev/client
 * prediction runs where the Bukkit server's SQL settings are copied into the
 * config but the matching JDBC driver is not present. It keeps authoritative
 * runtime state working without pretending to persist data across restarts.
 */
public final class MemoryStorageAdapter implements StorageAdapter {
    private final Map<UUID, PlayerRecord> players = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> tempElements = new ConcurrentHashMap<>();
    private final Map<UUID, List<PresetRecord>> presets = new ConcurrentHashMap<>();
    private final Set<UUID> disabledBoards = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> statKeys = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Integer, Long>> statistics = new ConcurrentHashMap<>();
    private final AtomicInteger nextStatId = new AtomicInteger(1);

    private final PlayerRepository playerRepository = new MemoryPlayerRepository();
    private final PresetRepository presetRepository = new MemoryPresetRepository();
    private final CooldownRepository cooldownRepository = new MemoryCooldownRepository();
    private final TempElementRepository tempElementRepository = new MemoryTempElementRepository();
    private final BoardRepository boardRepository = new MemoryBoardRepository();
    private final StatisticsRepository statisticsRepository = new MemoryStatisticsRepository();

    private static boolean parseBool(final String value, final boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    @Override
    public void initialize() {
    }

    @Override
    public void shutdown() {
    }

    @Override
    public StorageType getType() {
        return StorageType.MEMORY;
    }

    @Override
    public PlayerRepository players() {
        return playerRepository;
    }

    @Override
    public PresetRepository presets() {
        return presetRepository;
    }

    @Override
    public CooldownRepository cooldowns() {
        return cooldownRepository;
    }

    @Override
    public TempElementRepository tempElements() {
        return tempElementRepository;
    }

    @Override
    public BoardRepository boards() {
        return boardRepository;
    }

    @Override
    public StatisticsRepository statistics() {
        return statisticsRepository;
    }

    private final class MemoryPlayerRepository implements PlayerRepository {
        @Override
        public Optional<PlayerRecord> load(final UUID uuid) {
            return Optional.ofNullable(players.get(uuid));
        }

        @Override
        public void createDefault(final UUID uuid, final String playerName) {
            players.computeIfAbsent(uuid, key -> new PlayerRecord(
                    uuid, playerName, null, null, null, null, null, null, null,
                    false, false, false, "256", false, false, Collections.emptyMap()));
        }

        @Override
        public void update(final UUID uuid, final Map<PlayerColumn, String> columns) {
            final PlayerRecord old = players.get(uuid);
            if (old == null) return;
            final Map<Integer, String> slots = new HashMap<>(old.getSlots());
            for (int i = 1; i <= 9; i++) {
                final PlayerColumn slotColumn = PlayerColumn.slotColumn(i);
                if (columns.containsKey(slotColumn)) slots.put(i, columns.get(slotColumn));
            }
            players.put(uuid, new PlayerRecord(
                    uuid,
                    columns.getOrDefault(PlayerColumn.NAME, old.getPlayerName()),
                    columns.getOrDefault(PlayerColumn.ELEMENT, old.getElements()),
                    columns.getOrDefault(PlayerColumn.SUBELEMENT, old.getSubelements()),
                    columns.getOrDefault(PlayerColumn.STYLE, old.getStyle()),
                    columns.getOrDefault(PlayerColumn.FIRE_COLOR, old.getFireColor()),
                    columns.getOrDefault(PlayerColumn.AIR_COLOR, old.getAirColor()),
                    columns.getOrDefault(PlayerColumn.WATER_COSMETIC, old.getWaterCosmetic()),
                    columns.getOrDefault(PlayerColumn.EARTH_COSMETIC, old.getEarthCosmetic()),
                    parseBool(columns.get(PlayerColumn.SPRINKLE), old.isSprinkle()),
                    parseBool(columns.get(PlayerColumn.PERMA_REMOVED), old.isPermaRemoved()),
                    parseBool(columns.get(PlayerColumn.SOURCE_HOLES), old.hasSourceHoles()),
                    columns.getOrDefault(PlayerColumn.VIEW_DISTANCE, old.getViewDistance()),
                    parseBool(columns.get(PlayerColumn.DETAILED_ACTION_BAR), old.isDetailedActionBarEnabled()),
                    parseBool(columns.get(PlayerColumn.OLD_SCOOTER), old.isOldScooterEnabled()),
                    slots));
        }
    }

    private final class MemoryPresetRepository implements PresetRepository {
        @Override
        public List<PresetRecord> load(final UUID uuid) {
            return new ArrayList<>(presets.getOrDefault(uuid, List.of()));
        }

        @Override
        public void save(final PresetRecord record) {
            final List<PresetRecord> list = new ArrayList<>(presets.getOrDefault(record.getUuid(), List.of()));
            list.removeIf(existing -> existing.getName().equalsIgnoreCase(record.getName()));
            list.add(record);
            presets.put(record.getUuid(), list);
        }

        @Override
        public void delete(final UUID uuid, final String name) {
            final List<PresetRecord> list = new ArrayList<>(presets.getOrDefault(uuid, List.of()));
            list.removeIf(existing -> existing.getName().equalsIgnoreCase(name));
            presets.put(uuid, list);
        }
    }

    private final class MemoryCooldownRepository implements CooldownRepository {
        @Override
        public Map<String, Long> load(final UUID uuid) {
            return new HashMap<>(cooldowns.getOrDefault(uuid, Map.of()));
        }

        @Override
        public Optional<Long> load(final UUID uuid, final String name) {
            return Optional.ofNullable(cooldowns.getOrDefault(uuid, Map.of()).get(name));
        }

        @Override
        public void upsert(final UUID uuid, final String name, final long value) {
            cooldowns.computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>()).put(name, value);
        }

        @Override
        public void deleteAll(final UUID uuid) {
            cooldowns.remove(uuid);
        }
    }

    private final class MemoryTempElementRepository implements TempElementRepository {
        @Override
        public Map<String, Long> load(final UUID uuid) {
            return new HashMap<>(tempElements.getOrDefault(uuid, Map.of()));
        }

        @Override
        public void replace(final UUID uuid, final Map<String, Long> values) {
            tempElements.put(uuid, new ConcurrentHashMap<>(values));
        }
    }

    private final class MemoryBoardRepository implements BoardRepository {
        @Override
        public Set<UUID> loadDisabled() {
            return new HashSet<>(disabledBoards);
        }

        @Override
        public boolean isDisabled(final UUID uuid) {
            return disabledBoards.contains(uuid);
        }

        @Override
        public void setBoardState(final UUID uuid, final boolean enabled) {
            if (enabled) disabledBoards.remove(uuid);
            else disabledBoards.add(uuid);
        }
    }

    private final class MemoryStatisticsRepository implements StatisticsRepository {
        @Override
        public void ensureSchema() {
        }

        @Override
        public void ensureKey(final String statName) {
            getOrCreateKey(statName);
        }

        @Override
        public Map<String, Integer> loadKeys() {
            return new HashMap<>(statKeys);
        }

        @Override
        public int getOrCreateKey(final String statName) {
            return statKeys.computeIfAbsent(statName, ignored -> nextStatId.getAndIncrement());
        }

        @Override
        public Map<Integer, Long> load(final UUID uuid) {
            return new HashMap<>(statistics.getOrDefault(uuid, Map.of()));
        }

        @Override
        public void applyDeltas(final UUID uuid, final Map<Integer, Long> deltas) {
            final Map<Integer, Long> map = statistics.computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>());
            deltas.forEach((key, delta) -> map.merge(key, delta, Long::sum));
        }

        @Override
        public long getStat(final UUID uuid, final int statId) {
            return statistics.getOrDefault(uuid, Map.of()).getOrDefault(statId, 0L);
        }

        @Override
        public Set<UUID> getTrackedPlayers() {
            return new HashSet<>(statistics.keySet());
        }
    }
}
