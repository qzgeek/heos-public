package heos.folia.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * UUID-based player storage with account binding support.
 * Default: SQLite. Optional: MySQL (configured via config.yml).
 *
 * Binding model (star topology, many-to-one):
 *   bound_uuid → target_uuid
 *   - One account can only be a "bound" to ONE target (bound_uuid UNIQUE)
 *   - One target can have MANY bounds
 *   - A target cannot itself be bound (no chains)
 *   - Only one account per binding group can be online at a time
 */
public final class FoliaStorage {
    private static final Gson GSON = new GsonBuilder().create();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger LOGGER = Logger.getLogger("Heos");
    private static final String TABLE = "players";
    private static final String BIND_TABLE = "bindings";

    private final Path root;
    private Connection connection;
    private SecretKeySpec key;
    private boolean useMySQL;
    private String mysqlUrl;
    private String mysqlUser;
    private String mysqlPass;

    public FoliaStorage(Path root) {
        this.root = root;
    }

    /** Configure MySQL connection. Call before initialize(). */
    public void configureMySQL(String url, String user, String password) {
        this.useMySQL = true;
        this.mysqlUrl = url;
        this.mysqlUser = user;
        this.mysqlPass = password;
    }

    public synchronized void initialize() {
        if (connection != null) return;
        try {
            if (useMySQL) {
                initMySQL();
            } else {
                initSQLite();
            }
            createTables();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize Heos storage", e);
        }
    }

    private void initSQLite() throws Exception {
        Files.createDirectories(root);
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("player_data.db").toAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA synchronous=NORMAL");
        }
    }

    private void initMySQL() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        connection = DriverManager.getConnection(mysqlUrl, mysqlUser, mysqlPass);
    }

    private void createTables() throws Exception {
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                    + "username VARCHAR(64) NOT NULL,"
                    + "username_lower VARCHAR(64) NOT NULL,"
                    + "last_ip VARCHAR(45) NULL,"
                    + "data TEXT NOT NULL"
                    + ");");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_players_username_lower ON "
                    + TABLE + "(username_lower);");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + BIND_TABLE + " ("
                    + "id INTEGER " + (useMySQL ? "AUTO_INCREMENT" : "PRIMARY KEY AUTOINCREMENT") + ","
                    + "target_uuid VARCHAR(36) NOT NULL,"
                    + "target_name VARCHAR(64) NOT NULL,"
                    + "bound_uuid VARCHAR(36) NOT NULL UNIQUE,"
                    + "bound_name VARCHAR(64) NOT NULL,"
                    + "status VARCHAR(16) NOT NULL DEFAULT 'pending',"
                    + "created_at BIGINT NOT NULL,"
                    + "accepted_at BIGINT"
                    + (useMySQL ? ", PRIMARY KEY (id)" : "")
                    + ");");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bindings_target ON "
                    + BIND_TABLE + "(target_uuid);");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bindings_bound ON "
                    + BIND_TABLE + "(bound_uuid);");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bindings_target_name ON "
                    + BIND_TABLE + "(target_name);");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bindings_status ON "
                    + BIND_TABLE + "(status);");
        }
    }

    // ================ Player Data ================

    public synchronized FoliaPlayerData load(UUID uuid) {
        initialize();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uuid, username, last_ip, data FROM " + TABLE + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return readPlayerData(rs);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to load player data for " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    public synchronized FoliaPlayerData load(String username) {
        initialize();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uuid, username, last_ip, data FROM " + TABLE + " WHERE username_lower = ?")) {
            ps.setString(1, username.toLowerCase(Locale.ENGLISH));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return readPlayerData(rs);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to load player data for " + username + ": " + e.getMessage());
            return null;
        }
    }

    public synchronized List<FoliaPlayerData> loadAllByName(String username) {
        initialize();
        List<FoliaPlayerData> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uuid, username, last_ip, data FROM " + TABLE + " WHERE username_lower = ?")) {
            ps.setString(1, username.toLowerCase(Locale.ENGLISH));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(readPlayerData(rs));
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to load all by name " + username + ": " + e.getMessage());
        }
        return results;
    }

    public synchronized void save(FoliaPlayerData data) {
        initialize();
        if (data.uuid == null) {
            LOGGER.warning("Cannot save player data with null UUID");
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO " + TABLE + " (uuid, username, username_lower, last_ip, data) VALUES (?,?,?,?,?) "
                        + (useMySQL
                        ? "ON DUPLICATE KEY UPDATE username=VALUES(username), username_lower=VALUES(username_lower), last_ip=VALUES(last_ip), data=VALUES(data)"
                        : "ON CONFLICT(uuid) DO UPDATE SET username=excluded.username, username_lower=excluded.username_lower, last_ip=excluded.last_ip, data=excluded.data"))) {
            ps.setString(1, data.uuid.toString());
            ps.setString(2, data.username);
            ps.setString(3, data.username.toLowerCase(Locale.ENGLISH));
            ps.setString(4, data.lastIp);
            ps.setString(5, encrypt(GSON.toJson(data)));
            ps.executeUpdate();
        } catch (Exception e) {
            LOGGER.warning("Failed to save player data: " + e.getMessage());
        }
    }

    public synchronized boolean delete(UUID uuid) {
        initialize();
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM " + TABLE + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            LOGGER.warning("Failed to delete player data: " + e.getMessage());
            return false;
        }
    }

    private FoliaPlayerData readPlayerData(ResultSet rs) throws Exception {
        FoliaPlayerData data = GSON.fromJson(decrypt(rs.getString("data")), FoliaPlayerData.class);
        if (data == null) data = new FoliaPlayerData(rs.getString("username"));
        data.uuid = UUID.fromString(rs.getString("uuid"));
        data.username = rs.getString("username");
        data.lastIp = rs.getString("last_ip");
        return data;
    }

    // ================ Account Bindings ================

    public static final class BindingEntry {
        public final long id;
        public final UUID targetUuid;   // 被绑定账号
        public final UUID boundUuid;    // 绑定账号
        public final String targetName;
        public final String boundName;
        public final String status;     // "pending" or "active"
        public final long createdAt;
        public final Long acceptedAt;

        BindingEntry(long id, UUID targetUuid, UUID boundUuid, String targetName, String boundName,
                     String status, long createdAt, Long acceptedAt) {
            this.id = id;
            this.targetUuid = targetUuid;
            this.boundUuid = boundUuid;
            this.targetName = targetName;
            this.boundName = boundName;
            this.status = status;
            this.createdAt = createdAt;
            this.acceptedAt = acceptedAt;
        }

        public boolean isActive() { return "active".equals(status); }
        public boolean isPending() { return "pending".equals(status); }
    }

    /**
     * Request binding: boundPlayer wants to bind to targetPlayer (identified by name).
     * target_uuid is stored as placeholder until the target accepts.
     */
    public synchronized BindingEntry requestBinding(UUID boundUuid, String boundName, String targetName) {
        initialize();
        long now = System.currentTimeMillis();
        String placeholderTargetUuid = "pending:" + targetName.toLowerCase(Locale.ENGLISH);
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO " + BIND_TABLE + " (target_uuid, target_name, bound_uuid, bound_name, status, created_at) VALUES (?,?,?,?,'pending',?)")) {
            ps.setString(1, placeholderTargetUuid);
            ps.setString(2, targetName);
            ps.setString(3, boundUuid.toString());
            ps.setString(4, boundName);
            ps.setLong(5, now);
            ps.executeUpdate();
        } catch (Exception e) {
            LOGGER.warning("Failed to create binding request: " + e.getMessage());
            return null;
        }
        // Return the inserted row
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, target_uuid, target_name, bound_uuid, bound_name, status, created_at, accepted_at FROM "
                        + BIND_TABLE + " WHERE bound_uuid = ? AND status = 'pending' ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, boundUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return readBindingRow(rs);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to read binding request: " + e.getMessage());
        }
        return null;
    }

    /** Target player accepts binding by specifying the bound player's name (double-blind). */
    public synchronized BindingEntry acceptBinding(UUID targetUuid, String targetName, String boundName) {
        initialize();
        String placeholder = "pending:" + targetName.toLowerCase(Locale.ENGLISH);
        BindingEntry found = null;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, target_uuid, target_name, bound_uuid, bound_name, status, created_at, accepted_at FROM "
                        + BIND_TABLE + " WHERE target_uuid = ? AND bound_name = ? AND status = 'pending' ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, placeholder);
            ps.setString(2, boundName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) found = readBindingRow(rs);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to find binding: " + e.getMessage());
            return null;
        }
        if (found == null) return null;

        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE " + BIND_TABLE + " SET target_uuid = ?, status = 'active', accepted_at = ? WHERE id = ?")) {
            ps.setString(1, targetUuid.toString());
            ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, found.id);
            ps.executeUpdate();
        } catch (Exception e) {
            LOGGER.warning("Failed to accept binding: " + e.getMessage());
            return null;
        }
        return getBindingById(found.id);
    }

    /** Get the target UUID that a bound UUID maps to (active binding). Returns null if not bound. */
    public synchronized UUID getTargetUuid(UUID boundUuid) {
        initialize();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT target_uuid FROM " + BIND_TABLE + " WHERE bound_uuid = ? AND status = 'active'")) {
            ps.setString(1, boundUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String val = rs.getString("target_uuid");
                    if (val != null && !val.startsWith("pending:")) return UUID.fromString(val);
                }
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to look up target for " + boundUuid + ": " + e.getMessage());
        }
        return null;
    }

    /** Get all active bound UUIDs for a target UUID (who is bound TO this target). */
    public synchronized List<UUID> getBoundUuids(UUID targetUuid) {
        initialize();
        List<UUID> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT bound_uuid FROM " + BIND_TABLE + " WHERE target_uuid = ? AND status = 'active'")) {
            ps.setString(1, targetUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(UUID.fromString(rs.getString("bound_uuid")));
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to list bound UUIDs: " + e.getMessage());
        }
        return list;
    }

    /** Check if a UUID is a target (has active bound accounts). */
    public synchronized boolean isTarget(UUID uuid) {
        initialize();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + BIND_TABLE + " WHERE target_uuid = ? AND status = 'active'")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1) > 0;
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to check isTarget: " + e.getMessage());
        }
        return false;
    }

    /** Check if a UUID is already bound (has an active binding TO someone). */
    public synchronized boolean isBound(UUID uuid) {
        initialize();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + BIND_TABLE + " WHERE bound_uuid = ? AND status = 'active'")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1) > 0;
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to check isBound: " + e.getMessage());
        }
        return false;
    }

    /** Get all UUIDs in the same binding group (target + all bound accounts). Returns empty if not in any group. */
    public synchronized List<UUID> getBindingGroup(UUID anyUuid) {
        initialize();
        List<UUID> group = new ArrayList<>();
        // Check if this is a bound account → get its target
        UUID target = getTargetUuid(anyUuid);
        if (target != null) {
            group.add(target);
            group.addAll(getBoundUuids(target));
            return group;
        }
        // Check if this is a target → get all bound accounts
        List<UUID> bounds = getBoundUuids(anyUuid);
        if (!bounds.isEmpty()) {
            group.add(anyUuid);
            group.addAll(bounds);
            return group;
        }
        return group;
    }

    /** Get pending bindings targeting a player name. */
    public synchronized List<BindingEntry> getPendingForTarget(String targetName) {
        initialize();
        List<BindingEntry> list = new ArrayList<>();
        String placeholder = "pending:" + targetName.toLowerCase(Locale.ENGLISH);
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, target_uuid, target_name, bound_uuid, bound_name, status, created_at, accepted_at FROM "
                        + BIND_TABLE + " WHERE target_uuid = ? AND status = 'pending'")) {
            ps.setString(1, placeholder);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(readBindingRow(rs));
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to list pending bindings: " + e.getMessage());
        }
        return list;
    }

    /** Revoke (delete) a binding by ID. */
    public synchronized boolean revokeBinding(long id) {
        initialize();
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM " + BIND_TABLE + " WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            LOGGER.warning("Failed to revoke binding: " + e.getMessage());
            return false;
        }
    }

    /** List all bindings (all statuses). */
    public synchronized List<BindingEntry> listAllBindings() {
        initialize();
        List<BindingEntry> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, target_uuid, target_name, bound_uuid, bound_name, status, created_at, accepted_at FROM "
                        + BIND_TABLE + " ORDER BY id")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(readBindingRow(rs));
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to list all bindings: " + e.getMessage());
        }
        return list;
    }

    private BindingEntry getBindingById(long id) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, target_uuid, target_name, bound_uuid, bound_name, status, created_at, accepted_at FROM "
                        + BIND_TABLE + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return readBindingRow(rs);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to get binding " + id + ": " + e.getMessage());
        }
        return null;
    }

    private static BindingEntry readBindingRow(ResultSet rs) throws Exception {
        String targetUuidStr = rs.getString("target_uuid");
        UUID targetUuid = null;
        if (targetUuidStr != null && !targetUuidStr.startsWith("pending:")) {
            targetUuid = UUID.fromString(targetUuidStr);
        }
        long acceptedLong = rs.getLong("accepted_at");
        Long acceptedAt = rs.wasNull() ? null : acceptedLong;
        return new BindingEntry(
                rs.getLong("id"),
                targetUuid,
                UUID.fromString(rs.getString("bound_uuid")),
                rs.getString("target_name"),
                rs.getString("bound_name"),
                rs.getString("status"),
                rs.getLong("created_at"),
                acceptedAt);
    }

    // ================ Lifecycle ================

    public synchronized void close() {
        if (connection == null) return;
        try { connection.close(); } catch (Exception ignored) {}
        connection = null;
    }

    // ================ Encryption (SQLite only) ================

    private String encrypt(String text) throws Exception {
        byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
        byte[] enc = c.doFinal(text.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[nonce.length + enc.length];
        System.arraycopy(nonce, 0, combined, 0, nonce.length);
        System.arraycopy(enc, 0, combined, nonce.length, enc.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    private String decrypt(String text) throws Exception {
        if (text.contains(":")) {
            String[] parts = text.split(":", 2);
            return decrypt(Base64.getDecoder().decode(parts[0]), Base64.getDecoder().decode(parts[1]));
        }
        byte[] combined = Base64.getDecoder().decode(text);
        byte[] nonce = new byte[12];
        byte[] enc = new byte[combined.length - 12];
        System.arraycopy(combined, 0, nonce, 0, 12);
        System.arraycopy(combined, 12, enc, 0, enc.length);
        return decrypt(nonce, enc);
    }

    private String decrypt(byte[] nonce, byte[] enc) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
        return new String(c.doFinal(enc), StandardCharsets.UTF_8);
    }

    private SecretKeySpec key() throws Exception {
        if (key != null) return key;
        Path kp = root.resolve("secret.key");
        if (Files.exists(kp)) {
            key = new SecretKeySpec(Files.readAllBytes(kp), "AES");
            return key;
        }
        byte[] kb = new byte[32]; RANDOM.nextBytes(kb);
        Files.write(kp, kb);
        key = new SecretKeySpec(kb, "AES");
        return key;
    }
}
