package com.projectkorra.projectkorra.storage;

import com.projectkorra.projectkorra.ProjectKorra;

import java.sql.*;
import java.util.*;

public class SqlStorageAdapter implements StorageAdapter {

    private final Database database;
    private final StorageType type;
    private final PlayerRepository playerRepository;
    private final PresetRepository presetRepository;
    private final CooldownRepository cooldownRepository;
    private final TempElementRepository tempElementRepository;
    private final BoardRepository boardRepository;
    private final StatisticsRepository statisticsRepository;

    public SqlStorageAdapter(final Database database, final StorageType type) {
        this.database = database;
        this.type = type;
        this.playerRepository = new SqlPlayerRepository();
        this.presetRepository = new SqlPresetRepository();
        this.cooldownRepository = new SqlCooldownRepository();
        this.tempElementRepository = new SqlTempElementRepository();
        this.boardRepository = new SqlBoardRepository();
        this.statisticsRepository = new SqlStatisticsRepository();
    }

    @Override
    public void initialize() {
        if (this.database.open() == null) {
            throw new StorageException("Unable to open SQL connection for " + this.type.name());
        }
        this.ensureBaseTables();
        this.statisticsRepository.ensureSchema();
        this.convertOldCooldownsTable();
    }

    @Override
    public void shutdown() {
        this.database.close();
    }

    @Override
    public StorageType getType() {
        return this.type;
    }

    @Override
    public PlayerRepository players() {
        return this.playerRepository;
    }

    @Override
    public PresetRepository presets() {
        return this.presetRepository;
    }

    @Override
    public CooldownRepository cooldowns() {
        return this.cooldownRepository;
    }

    @Override
    public TempElementRepository tempElements() {
        return this.tempElementRepository;
    }

    @Override
    public BoardRepository boards() {
        return this.boardRepository;
    }

    @Override
    public StatisticsRepository statistics() {
        return this.statisticsRepository;
    }

    private Connection connection() throws SQLException {
        Connection conn = this.database.getConnection();
        if (conn == null || conn.isClosed()) {
            conn = this.database.open();
        }
        return conn;
    }

    private void ensureBaseTables() {
        this.ensurePlayersTable();
        this.ensurePresetsTable();
        this.ensureCooldownsTable();
        this.ensureBoardTable();
        this.ensureTempElementsTable();
    }

    private void ensurePlayersTable() {
        if (!this.database.tableExists("pk_players")) {
            ProjectKorra.log.info("Creating pk_players table");
            final String query;
            if (this.type == StorageType.MYSQL) {
                query = "CREATE TABLE `pk_players` (" +
                        "`uuid` varchar(36) NOT NULL," +
                        "`player` varchar(16) NOT NULL," +
                        "`element` varchar(255)," +
                        "`subelement` varchar(255)," +
                        "`firecolor` varchar(255)," +
                        "`aircolor` varchar(255)," +
                        "`watercosmetic` varchar(255)," +
                        "`earthcosmetic` varchar(255)," +
                        "`style` varchar(255)," +
                        "`sprinkle` varchar(5)," +
                        "`permaremoved` varchar(5)," +
                        "`sourceholes` varchar(5)," +
                        "`viewdistance` varchar(3)," +
                        "`detailedactionbar` varchar(5)," +
                        "`oldscooter` varchar(5)," +
                        "`slot1` varchar(255)," +
                        "`slot2` varchar(255)," +
                        "`slot3` varchar(255)," +
                        "`slot4` varchar(255)," +
                        "`slot5` varchar(255)," +
                        "`slot6` varchar(255)," +
                        "`slot7` varchar(255)," +
                        "`slot8` varchar(255)," +
                        "`slot9` varchar(255)," +
                        "PRIMARY KEY (uuid));";
            } else {
                query = "CREATE TABLE `pk_players` (" +
                        "`uuid` TEXT(36) PRIMARY KEY," +
                        "`player` TEXT(16)," +
                        "`element` TEXT(255)," +
                        "`subelement` TEXT(255)," +
                        "`firecolor` TEXT(255)," +
                        "`aircolor` TEXT(255)," +
                        "`watercosmetic` TEXT(255)," +
                        "`earthcosmetic` TEXT(255)," +
                        "`style` TEXT(255)," +
                        "`sprinkle` TEXT(5)," +
                        "`permaremoved` TEXT(5)," +
                        "`sourceholes` TEXT(5)," +
                        "`viewdistance` TEXT(3)," +
                        "`detailedactionbar` TEXT(5)," +
                        "`oldscooter` TEXT(5)," +
                        "`slot1` TEXT(255)," +
                        "`slot2` TEXT(255)," +
                        "`slot3` TEXT(255)," +
                        "`slot4` TEXT(255)," +
                        "`slot5` TEXT(255)," +
                        "`slot6` TEXT(255)," +
                        "`slot7` TEXT(255)," +
                        "`slot8` TEXT(255)," +
                        "`slot9` TEXT(255));";
            }
            this.database.modifyQuery(query, false);
        } else {
            try {
                final DatabaseMetaData metaData = this.connection().getMetaData();
                if (!metaData.getColumns(null, null, "pk_players", "subelement").next()) {
                    ProjectKorra.log.info("Updating Database with subelements...");
                    this.connection().setAutoCommit(false);
                    final String columnQuery = (this.type == StorageType.MYSQL)
                            ? "ALTER TABLE `pk_players` ADD subelement varchar(255);"
                            : "ALTER TABLE `pk_players` ADD subelement TEXT(255);";
                    this.database.modifyQuery(columnQuery, false);
                    this.connection().commit();
                    this.database.modifyQuery("UPDATE pk_players SET subelement = '-';", false);
                    this.connection().setAutoCommit(true);
                    ProjectKorra.log.info("Database Updated.");
                }
            } catch (final SQLException e) {
                throw new StorageException("Failed checking pk_players schema", e);
            }

            if (!this.database.columnExists("pk_players", "viewdistance")) {
                String query = this.type == StorageType.MYSQL
                        ? "ALTER TABLE `pk_players` ADD COLUMN viewdistance varchar(3);"
                        : "ALTER TABLE `pk_players` ADD COLUMN viewdistance TEXT(3);";
                this.database.modifyQuery(query, false);
                ProjectKorra.log.info("Updated Database with viewdistance.");
            }

            if (!this.database.columnExists("pk_players", "watercosmetic")) {
                final String query = this.type == StorageType.MYSQL
                        ? "ALTER TABLE `pk_players` ADD COLUMN watercosmetic varchar(255);"
                        : "ALTER TABLE `pk_players` ADD COLUMN watercosmetic TEXT(255);";
                this.database.modifyQuery(query, false);
                ProjectKorra.log.info("Updated Database with watercosmetic.");
            }

            if (!this.database.columnExists("pk_players", "detailedactionbar")) {
                final String query = this.type == StorageType.MYSQL
                        ? "ALTER TABLE `pk_players` ADD COLUMN detailedactionbar varchar(5);"
                        : "ALTER TABLE `pk_players` ADD COLUMN detailedactionbar TEXT(5);";
                this.database.modifyQuery(query, false);
                ProjectKorra.log.info("Updated Database with detailed action bar preferences.");
            }

            if (!this.database.columnExists("pk_players", "oldscooter")) {
                final String query = this.type == StorageType.MYSQL
                        ? "ALTER TABLE `pk_players` ADD COLUMN oldscooter varchar(5);"
                        : "ALTER TABLE `pk_players` ADD COLUMN oldscooter TEXT(5);";
                this.database.modifyQuery(query, false);
                ProjectKorra.log.info("Updated Database with old scooter preferences.");
            }
        }
    }

    private void ensurePresetsTable() {
        if (!this.database.tableExists("pk_presets")) {
            ProjectKorra.log.info("Creating pk_presets table");
            final String query;
            if (this.type == StorageType.MYSQL) {
                query = "CREATE TABLE `pk_presets` (" +
                        "`uuid` varchar(36) NOT NULL," +
                        "`name` varchar(255) NOT NULL," +
                        "`slot1` varchar(255)," +
                        "`slot2` varchar(255)," +
                        "`slot3` varchar(255)," +
                        "`slot4` varchar(255)," +
                        "`slot5` varchar(255)," +
                        "`slot6` varchar(255)," +
                        "`slot7` varchar(255)," +
                        "`slot8` varchar(255)," +
                        "`slot9` varchar(255)," +
                        "PRIMARY KEY (uuid, name));";
            } else {
                query = "CREATE TABLE `pk_presets` (" +
                        "`uuid` TEXT(36)," +
                        "`name` TEXT(255)," +
                        "`slot1` TEXT(255)," +
                        "`slot2` TEXT(255)," +
                        "`slot3` TEXT(255)," +
                        "`slot4` TEXT(255)," +
                        "`slot5` TEXT(255)," +
                        "`slot6` TEXT(255)," +
                        "`slot7` TEXT(255)," +
                        "`slot8` TEXT(255)," +
                        "`slot9` TEXT(255)," +
                        "PRIMARY KEY (uuid, name));";
            }
            this.database.modifyQuery(query, false);
        }
    }

    private void ensureCooldownsTable() {
        if (!this.database.tableExists("pk_cooldowns")) {
            ProjectKorra.log.info("Creating pk_cooldowns table");
            final String query = (this.type == StorageType.MYSQL)
                    ? "CREATE TABLE `pk_cooldowns` (uuid VARCHAR(36) NOT NULL, cooldown VARCHAR(255) NOT NULL, value BIGINT, PRIMARY KEY (uuid, cooldown));"
                    : "CREATE TABLE `pk_cooldowns` (uuid TEXT(36) NOT NULL, cooldown TEXT(255) NOT NULL, value BIGINT, PRIMARY KEY (uuid, cooldown));";
            this.database.modifyQuery(query, false);
        }
    }

    private void ensureBoardTable() {
        if (!this.database.tableExists("pk_board")) {
            ProjectKorra.log.info("Creating pk_board table");
            final String query = (this.type == StorageType.MYSQL)
                    ? "CREATE TABLE `pk_board` (uuid VARCHAR(36) NOT NULL, enabled BOOLEAN NOT NULL, PRIMARY KEY (uuid));"
                    : "CREATE TABLE `pk_board` (uuid TEXT(36) NOT NULL, enabled INTEGER NOT NULL, PRIMARY KEY (uuid));";
            this.database.modifyQuery(query, false);
        }
    }

    private void ensureTempElementsTable() {
        if (!this.database.tableExists("pk_temp_elements")) {
            ProjectKorra.log.info("Creating pk_temp_elements table");
            final String query = (this.type == StorageType.MYSQL)
                    ? "CREATE TABLE `pk_temp_elements` (uuid VARCHAR(36) NOT NULL, element VARCHAR(255) NOT NULL, expiry BIGINT);"
                    : "CREATE TABLE `pk_temp_elements` (uuid TEXT(36) NOT NULL, element TEXT(255) NOT NULL, expiry BIGINT);";
            this.database.modifyQuery(query, false);
        }
    }

    private void convertOldCooldownsTable() {
        if (!this.database.tableExists("pk_cooldown_ids")) {
            return;
        }

        final Map<Integer, String> oldCooldownIDs = new HashMap<>();
        final Map<String, Map<String, Long>> oldTable = new HashMap<>();

        try (ResultSet rs = this.database.readQuery("SELECT * FROM pk_cooldown_ids")) {
            while (rs != null && rs.next()) {
                oldCooldownIDs.put(rs.getInt("id"), rs.getString("cooldown_name"));
            }
        } catch (final SQLException e) {
            ProjectKorra.log.warning("Failed to get cooldown ids from database.");
            e.printStackTrace();
        }

        try (ResultSet rs = this.database.readQuery("SELECT * FROM pk_cooldowns")) {
            while (rs != null && rs.next()) {
                final String uuid = rs.getString("uuid");
                final int cooldownID = rs.getInt("cooldown_id");
                final long cooldown = rs.getLong("value");

                oldTable.computeIfAbsent(uuid, ignored -> new HashMap<>());

                final String cooldownName = oldCooldownIDs.get(cooldownID);

                if (cooldownName == null || cooldownName.isEmpty()) {
                    ProjectKorra.log.warning("Failed to get cooldown name from database.");
                    continue;
                }

                oldTable.get(uuid).put(cooldownName, cooldown);
            }
        } catch (final SQLException e) {
            ProjectKorra.log.warning("Failed to get cooldowns from database.");
            e.printStackTrace();
        }

        try {
            this.database.close();
            this.database.open();
            this.database.modifyQuery("DROP TABLE pk_cooldowns", false);
            this.database.modifyQuery("DROP TABLE pk_cooldown_ids", false);
            this.database.close();
            this.database.open();
        } catch (final Exception e) {
            throw new StorageException("Failed to rebuild cooldown tables", e);
        }

        final String createQuery = (this.type == StorageType.MYSQL)
                ? "CREATE TABLE `pk_cooldowns` (uuid VARCHAR(36) NOT NULL, cooldown VARCHAR(255) NOT NULL, value BIGINT NOT NULL, PRIMARY KEY (uuid, cooldown));"
                : "CREATE TABLE `pk_cooldowns` (uuid TEXT(36) NOT NULL, cooldown TEXT(255) NOT NULL, value BIGINT NOT NULL, PRIMARY KEY (uuid, cooldown));";
        this.database.modifyQuery(createQuery, false);

        for (final String uuid : oldTable.keySet()) {
            for (final Map.Entry<String, Long> entry : oldTable.get(uuid).entrySet()) {
                this.database.modifyQuery("INSERT INTO pk_cooldowns (uuid, cooldown, value) VALUES ('" + uuid + "', '" + entry.getKey() + "', " + entry.getValue() + ")", false);
            }
        }

        try {
            this.connection().setAutoCommit(true);
        } catch (final SQLException e) {
            throw new StorageException("Failed completing cooldown migration", e);
        }
        ProjectKorra.log.info("Finished converting old cooldowns to new cooldowns table!");
    }

    private class SqlPlayerRepository implements PlayerRepository {

        @Override
        public Optional<PlayerRecord> load(final UUID uuid) {
            try (PreparedStatement ps = connection().prepareStatement("SELECT * FROM pk_players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    final Map<Integer, String> slots = new HashMap<>();
                    for (int slot = 1; slot <= 9; slot++) {
                        String value = rs.getString("slot" + slot);
                        if (value != null && !"null".equalsIgnoreCase(value)) {
                            slots.put(slot, value);
                        }
                    }
                    final boolean sprinkle = Boolean.parseBoolean(rs.getString("sprinkle"));
                    final boolean permaRemoved = Boolean.parseBoolean(rs.getString("permaremoved"));
                    final boolean sourceHoles = Boolean.parseBoolean(rs.getString("sourceholes"));
                    return Optional.of(new PlayerRecord(
                            uuid,
                            rs.getString("player"),
                            rs.getString("element"),
                            rs.getString("subelement"),
                            rs.getString("style"),
                            rs.getString("firecolor"),
                            rs.getString("aircolor"),
                            rs.getString("watercosmetic"),
                            rs.getString("earthcosmetic"),
                            sprinkle,
                            permaRemoved,
                            sourceHoles,
                            rs.getString("viewdistance"),
                            Boolean.parseBoolean(rs.getString("detailedactionbar")),
                            Boolean.parseBoolean(rs.getString("oldscooter")),
                            slots));
                }
            } catch (final SQLException e) {
                throw new StorageException("Failed to load player " + uuid, e);
            }
        }

        @Override
        public void createDefault(final UUID uuid, final String playerName) {
            try (PreparedStatement ps = connection().prepareStatement("INSERT INTO pk_players (uuid, player, viewdistance) VALUES (?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, playerName);
                ps.setString(3, "256");
                ps.executeUpdate();
            } catch (final SQLException e) {
                throw new StorageException("Failed to create player " + uuid, e);
            }
        }

        @Override
        public void update(final UUID uuid, final Map<PlayerColumn, String> columns) {
            if (columns == null || columns.isEmpty()) {
                return;
            }
            final StringBuilder builder = new StringBuilder("UPDATE pk_players SET ");
            boolean first = true;
            for (final PlayerColumn column : columns.keySet()) {
                if (!first) {
                    builder.append(", ");
                }
                builder.append(column.getColumnName()).append(" = ?");
                first = false;
            }
            builder.append(" WHERE uuid = ?");
            try (PreparedStatement ps = connection().prepareStatement(builder.toString())) {
                int index = 1;
                for (final PlayerColumn column : columns.keySet()) {
                    final String value = columns.get(column);
                    if (value == null) {
                        ps.setNull(index++, Types.VARCHAR);
                    } else {
                        ps.setString(index++, value);
                    }
                }
                ps.setString(index, uuid.toString());
                ps.executeUpdate();
            } catch (final SQLException e) {
                throw new StorageException("Failed updating player " + uuid, e);
            }
        }
    }

    private class SqlPresetRepository implements PresetRepository {

        @Override
        public List<PresetRecord> load(final UUID uuid) {
            final List<PresetRecord> records = new ArrayList<>();
            try (PreparedStatement ps = connection().prepareStatement("SELECT * FROM pk_presets WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final Map<Integer, String> slots = new HashMap<>();
                        for (int slot = 1; slot <= 9; slot++) {
                            final String value = rs.getString("slot" + slot);
                            if (value != null && !"null".equalsIgnoreCase(value)) {
                                slots.put(slot, value);
                            }
                        }
                        records.add(new PresetRecord(uuid, rs.getString("name"), slots));
                    }
                }
            } catch (final SQLException e) {
                throw new StorageException("Failed to load presets for " + uuid, e);
            }
            return records;
        }

        @Override
        public void save(final PresetRecord record) {
            final String query = "INSERT INTO pk_presets (uuid, name, slot1, slot2, slot3, slot4, slot5, slot6, slot7, slot8, slot9) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = connection().prepareStatement(query)) {
                ps.setString(1, record.getUuid().toString());
                ps.setString(2, record.getName());
                for (int i = 1; i <= 9; i++) {
                    final String ability = record.getSlots().get(i);
                    if (ability == null) {
                        ps.setNull(2 + i, Types.VARCHAR);
                    } else {
                        ps.setString(2 + i, ability);
                    }
                }
                ps.executeUpdate();
            } catch (final SQLException e) {
                throw new StorageException("Failed to save preset " + record.getName(), e);
            }
        }

        @Override
        public void delete(final UUID uuid, final String name) {
            try (PreparedStatement ps = connection().prepareStatement("DELETE FROM pk_presets WHERE uuid = ? AND name = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.executeUpdate();
            } catch (final SQLException e) {
                throw new StorageException("Failed to delete preset " + name, e);
            }
        }
    }

    private class SqlCooldownRepository implements CooldownRepository {

        @Override
        public Map<String, Long> load(final UUID uuid) {
            final Map<String, Long> cooldowns = new HashMap<>();
            try (PreparedStatement ps = connection().prepareStatement("SELECT * FROM pk_cooldowns WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        cooldowns.put(rs.getString("cooldown"), rs.getLong("value"));
                    }
                }
            } catch (final SQLException e) {
                throw new StorageException("Failed to load cooldowns for " + uuid, e);
            }
            return cooldowns;
        }

        @Override
        public Optional<Long> load(final UUID uuid, final String name) {
            try (PreparedStatement ps = connection().prepareStatement("SELECT value FROM pk_cooldowns WHERE uuid = ? AND cooldown = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(rs.getLong("value"));
                    }
                }
            } catch (final SQLException e) {
                throw new StorageException("Failed loading cooldown " + name, e);
            }
            return Optional.empty();
        }

        @Override
        public void upsert(final UUID uuid, final String name, final long value) {
            final String update = "UPDATE pk_cooldowns SET value = ? WHERE uuid = ? AND cooldown = ?";
            try (PreparedStatement ps = connection().prepareStatement(update)) {
                ps.setLong(1, value);
                ps.setString(2, uuid.toString());
                ps.setString(3, name);
                final int rows = ps.executeUpdate();
                if (rows == 0) {
                    try (PreparedStatement insert = connection().prepareStatement("INSERT INTO pk_cooldowns (uuid, cooldown, value) VALUES (?, ?, ?)")) {
                        insert.setString(1, uuid.toString());
                        insert.setString(2, name);
                        insert.setLong(3, value);
                        insert.executeUpdate();
                    }
                }
            } catch (final SQLException e) {
                throw new StorageException("Failed upserting cooldown " + name, e);
            }
        }

        @Override
        public void deleteAll(final UUID uuid) {
            try (PreparedStatement ps = connection().prepareStatement("DELETE FROM pk_cooldowns WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (final SQLException e) {
                throw new StorageException("Failed deleting cooldowns for " + uuid, e);
            }
        }
    }

    private class SqlTempElementRepository implements TempElementRepository {

        @Override
        public Map<String, Long> load(final UUID uuid) {
            final Map<String, Long> data = new HashMap<>();
            try (PreparedStatement ps = connection().prepareStatement("SELECT * FROM pk_temp_elements WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        data.put(rs.getString("element"), rs.getLong("expiry"));
                    }
                }
            } catch (final SQLException e) {
                throw new StorageException("Failed to load temp elements for " + uuid, e);
            }
            return data;
        }

        @Override
        public void replace(final UUID uuid, final Map<String, Long> values) {
            try {
                final Connection conn = connection();
                final boolean autoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try (PreparedStatement delete = conn.prepareStatement("DELETE FROM pk_temp_elements WHERE uuid = ?")) {
                    delete.setString(1, uuid.toString());
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = conn.prepareStatement("INSERT INTO pk_temp_elements (uuid, element, expiry) VALUES (?, ?, ?)")) {
                    for (final Map.Entry<String, Long> entry : values.entrySet()) {
                        insert.setString(1, uuid.toString());
                        insert.setString(2, entry.getKey());
                        insert.setLong(3, entry.getValue());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                conn.commit();
                conn.setAutoCommit(autoCommit);
            } catch (final SQLException e) {
                throw new StorageException("Failed replacing temp elements for " + uuid, e);
            }
        }
    }

    private class SqlBoardRepository implements BoardRepository {

        @Override
        public Set<UUID> loadDisabled() {
            final Set<UUID> uuids = new HashSet<>();
            try (PreparedStatement ps = connection().prepareStatement("SELECT uuid FROM pk_board WHERE enabled = 0")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        uuids.add(UUID.fromString(rs.getString("uuid")));
                    }
                }
            } catch (final SQLException e) {
                throw new StorageException("Failed to load board data", e);
            }
            return uuids;
        }

        @Override
        public boolean isDisabled(final UUID uuid) {
            try (PreparedStatement ps = connection().prepareStatement("SELECT enabled FROM pk_board WHERE uuid = ? LIMIT 1")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    return rs.getInt("enabled") == 0;
                }
            } catch (final SQLException e) {
                throw new StorageException("Failed checking board for " + uuid, e);
            }
        }

        @Override
        public void setBoardState(final UUID uuid, final boolean enabled) {
            final String update = "UPDATE pk_board SET enabled = ? WHERE uuid = ?";
            try (PreparedStatement ps = connection().prepareStatement(update)) {
                ps.setInt(1, enabled ? 1 : 0);
                ps.setString(2, uuid.toString());
                final int rows = ps.executeUpdate();
                if (rows == 0) {
                    try (PreparedStatement insert = connection().prepareStatement("INSERT INTO pk_board (uuid, enabled) VALUES (?, ?)")) {
                        insert.setString(1, uuid.toString());
                        insert.setInt(2, enabled ? 1 : 0);
                        insert.executeUpdate();
                    }
                }
            } catch (final SQLException e) {
                throw new StorageException("Failed setting board state", e);
            }
        }
    }

    private class SqlStatisticsRepository implements StatisticsRepository {

        @Override
        public void ensureSchema() {
            if (!database.tableExists("pk_statKeys")) {
                ProjectKorra.log.info("Creating pk_statKeys table");
                final String query;
                if (type == StorageType.MYSQL) {
                    query = "CREATE TABLE `pk_statKeys` (`id` INTEGER PRIMARY KEY AUTO_INCREMENT, `statName` VARCHAR(64));";
                } else {
                    query = "CREATE TABLE `pk_statKeys` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `statName` TEXT(64));";
                }
                database.modifyQuery(query, false);
            }
            if (!database.tableExists("pk_stats")) {
                ProjectKorra.log.info("Creating pk_stats table");
                final String query;
                if (type == StorageType.MYSQL) {
                    query = "CREATE TABLE `pk_stats` (`statId` INTEGER, `uuid` VARCHAR(36), `statValue` BIGINT, PRIMARY KEY (statId, uuid));";
                } else {
                    query = "CREATE TABLE `pk_stats` (`statId` INTEGER, `uuid` TEXT(36), `statValue` BIGINT, PRIMARY KEY (statId, uuid));";
                }
                database.modifyQuery(query, false);
            }
        }

        @Override
        public void ensureKey(final String statName) {
            this.getOrCreateKey(statName);
        }

        @Override
        public Map<String, Integer> loadKeys() {
            final Map<String, Integer> keys = new HashMap<>();
            try (PreparedStatement ps = connection().prepareStatement("SELECT * FROM pk_statKeys");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.put(rs.getString("statName"), rs.getInt("id"));
                }
            } catch (final SQLException e) {
                throw new StorageException("Failed loading statistic keys", e);
            }
            return keys;
        }

        @Override
        public int getOrCreateKey(final String statName) {
            try (PreparedStatement check = connection().prepareStatement("SELECT id FROM pk_statKeys WHERE statName = ?")) {
                check.setString(1, statName);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("id");
                    }
                }
            } catch (final SQLException e) {
                throw new StorageException("Failed loading statistic key " + statName, e);
            }

            try (PreparedStatement insert = connection().prepareStatement("INSERT INTO pk_statKeys (statName) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, statName);
                insert.executeUpdate();
                try (ResultSet generated = insert.getGeneratedKeys()) {
                    if (generated.next()) {
                        return generated.getInt(1);
                    }
                }
            } catch (final SQLException e) {
                throw new StorageException("Failed creating statistic key " + statName, e);
            }
            throw new StorageException("Unable to create statistic key " + statName);
        }

        @Override
        public Map<Integer, Long> load(final UUID uuid) {
            final Map<Integer, Long> stats = new HashMap<>();
            try (PreparedStatement ps = connection().prepareStatement("SELECT * FROM pk_stats WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        stats.put(rs.getInt("statId"), rs.getLong("statValue"));
                    }
                }
            } catch (final SQLException e) {
                throw new StorageException("Failed loading stats for " + uuid, e);
            }
            return stats;
        }

        @Override
        public void applyDeltas(final UUID uuid, final Map<Integer, Long> deltas) {
            try (PreparedStatement update = connection().prepareStatement("UPDATE pk_stats SET statValue = statValue + ? WHERE uuid = ? AND statId = ?");
                 PreparedStatement insert = connection().prepareStatement("INSERT INTO pk_stats (statId, uuid, statValue) VALUES (?, ?, ?)")) {
                for (final Map.Entry<Integer, Long> entry : deltas.entrySet()) {
                    final long delta = entry.getValue();
                    if (delta == 0) continue;
                    update.setLong(1, delta);
                    update.setString(2, uuid.toString());
                    update.setInt(3, entry.getKey());
                    final int rows = update.executeUpdate();
                    if (rows == 0) {
                        insert.setInt(1, entry.getKey());
                        insert.setString(2, uuid.toString());
                        insert.setLong(3, delta);
                        insert.executeUpdate();
                    }
                }
            } catch (final SQLException e) {
                throw new StorageException("Failed applying stat deltas for " + uuid, e);
            }
        }

        @Override
        public long getStat(final UUID uuid, final int statId) {
            try (PreparedStatement ps = connection().prepareStatement("SELECT statValue FROM pk_stats WHERE uuid = ? AND statId = ?")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, statId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong("statValue");
                    }
                }
            } catch (final SQLException e) {
                throw new StorageException("Failed reading statistic for " + uuid, e);
            }
            return 0;
        }

        @Override
        public Set<UUID> getTrackedPlayers() {
            final Set<UUID> uuids = new HashSet<>();
            try (PreparedStatement ps = connection().prepareStatement("SELECT DISTINCT uuid FROM pk_stats");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    uuids.add(UUID.fromString(rs.getString("uuid")));
                }
            } catch (final SQLException e) {
                throw new StorageException("Failed loading tracked statistic players", e);
            }
            return uuids;
        }
    }
}

