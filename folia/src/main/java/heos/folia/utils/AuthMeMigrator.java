package heos.folia.utils;

import heos.folia.storage.FoliaPlayerData;
import heos.folia.storage.FoliaStorage;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.*;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Migrates player passwords from AuthMe Reloaded to LuoOS.
 *
 * Reads AuthMe's config.yml to auto-detect database backend and credentials.
 * Copies password hashes as-is — LuoOS supports AuthMe hash formats natively.
 * Passwords are automatically rehashed to native PBKDF2 format on first login.
 *
 * Usage: new AuthMeMigrator(logger, storage).migrate("plugins/AuthMe");
 */
public class AuthMeMigrator {
    private final Logger logger;
    private final FoliaStorage storage;

    public AuthMeMigrator(Logger logger, FoliaStorage storage) {
        this.logger = logger;
        this.storage = storage;
    }

    /**
     * Migrate from AuthMe. Provide the AuthMe plugin directory (e.g. "plugins/AuthMe").
     * Auto-detects SQLite/MySQL backend from AuthMe's config.yml.
     *
     * @param authMeDir path to AuthMe plugin directory
     * @return number of accounts migrated
     */
    public int migrate(String authMeDir) {
        File configFile = new File(authMeDir, "config.yml");
        if (!configFile.exists()) {
            logger.severe("AuthMe config.yml not found at " + configFile.getAbsolutePath());
            return 0;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        String backend = config.getString("DataSource.backend", "SQLITE").toUpperCase();
        String tableName = config.getString("DataSource.mySQLTablename", "authme");

        logger.info("AuthMe backend: " + backend + ", table: " + tableName);

        return switch (backend) {
            case "SQLITE" -> {
                String dbPath = config.getString("DataSource.mySQLDatabase",
                        new File(authMeDir, "authme.sqlite").getAbsolutePath());
                File dbFile = new File(dbPath);
                if (!dbFile.isAbsolute()) dbFile = new File(authMeDir, dbPath);
                yield migrateFromSQLite(dbFile.getAbsolutePath(), tableName);
            }
            case "MYSQL", "MARIADB" -> {
                String host = config.getString("DataSource.mySQLHost", "127.0.0.1");
                int port = config.getInt("DataSource.mySQLPort", 3306);
                String db = config.getString("DataSource.mySQLDatabase", "authme");
                String user = config.getString("DataSource.mySQLUsername", "root");
                String pass = config.getString("DataSource.mySQLPassword", "");
                String url = "jdbc:mysql://" + host + ":" + port + "/" + db
                        + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
                yield migrateFromMySQL(url, user, pass, tableName);
            }
            default -> {
                logger.severe("Unsupported AuthMe backend: " + backend);
                yield 0;
            }
        };
    }

    /**
     * Migrate from AuthMe SQLite database.
     */
    public int migrateFromSQLite(String dbPath, String tableName) {
        int count = 0;
        logger.info("Migrating from AuthMe SQLite: " + dbPath);

        try (Connection src = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            src.setAutoCommit(false);

            // AuthMe table schema: id, username (unique), realname, password, ip, lastlogin, x, y, z, world, email, ...
            String sql = "SELECT username, realname, password, ip, lastlogin, regdate, email"
                    + " FROM " + quote(tableName, true)
                    + " WHERE password IS NOT NULL AND password != ''";
            try (PreparedStatement ps = src.prepareStatement(sql)) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    String username = rs.getString("username");
                    String realname = safeString(rs, "realname");
                    String authHash = rs.getString("password");
                    String ip = safeString(rs, "ip");
                    long lastLogin = rs.getLong("lastlogin");

                    if (authHash == null || authHash.isEmpty()) continue;
                    if (migrateAccount(username, realname, authHash, ip, lastLogin)) {
                        count++;
                    }
                }
            }
        } catch (Exception e) {
            logger.severe("AuthMe SQLite migration failed: " + e.getMessage());
            e.printStackTrace();
        }
        return count;
    }

    /**
     * Migrate from AuthMe MySQL database.
     */
    public int migrateFromMySQL(String url, String user, String password, String tableName) {
        int count = 0;
        logger.info("Migrating from AuthMe MySQL: " + url);

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            try (Connection src = DriverManager.getConnection(url, user, password)) {
                String sql = "SELECT username, realname, password, ip, lastlogin, regdate, email"
                        + " FROM " + quote(tableName, false)
                        + " WHERE password IS NOT NULL AND password != ''";
                try (PreparedStatement ps = src.prepareStatement(sql)) {
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        String username = rs.getString("username");
                        String realname = safeString(rs, "realname");
                        String authHash = rs.getString("password");
                        String ip = safeString(rs, "ip");
                        long lastLogin = rs.getLong("lastlogin");

                        if (authHash == null || authHash.isEmpty()) continue;
                        if (migrateAccount(username, realname, authHash, ip, lastLogin)) {
                            count++;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.severe("AuthMe MySQL migration failed: " + e.getMessage());
            e.printStackTrace();
        }
        return count;
    }

    private boolean migrateAccount(String username, String realname, String authHash, String ip, long lastLogin) {
        try {
            String name = (realname != null && !realname.isEmpty()) ? realname : username;

            // Check if already in LuoOS
            FoliaPlayerData existing = storage.load(name);
            if (existing != null && existing.isRegistered()) {
                logger.fine("Skipping " + name + " — already registered in LuoOS");
                return false;
            }

            FoliaPlayerData data = existing != null ? existing : new FoliaPlayerData(name);
            data.username = name;
            if (data.uuid == null) {
                data.uuid = UUID.nameUUIDFromBytes(
                        ("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            // Store AuthMe hash as-is — LuoOS supports it natively
            data.passwordHash = authHash;
            data.isOnlineAccount = false;
            data.registeredTime = lastLogin > 0 ? lastLogin : System.currentTimeMillis();
            data.lastLoginTime = lastLogin > 0 ? lastLogin : System.currentTimeMillis();
            if (ip != null && !ip.isEmpty() && !"127.0.0.1".equals(ip)) {
                data.lastIp = ip;
            }

            storage.save(data);
            logger.info("Migrated: " + name + " (hash: " + authHash.substring(0, Math.min(8, authHash.length())) + "...)");
            return true;
        } catch (Exception e) {
            logger.warning("Failed to migrate account " + username + ": " + e.getMessage());
            return false;
        }
    }

    private static String safeString(ResultSet rs, String column) throws SQLException {
        String val = rs.getString(column);
        return (val != null) ? val : "";
    }

    private static String quote(String name, boolean isSqlite) {
        // Sanitize: only allow alphanumeric and underscore
        if (!name.matches("^[a-zA-Z0-9_]+$")) {
            name = "authme"; // fallback to default
        }
        return isSqlite ? "\"" + name + "\"" : "`" + name + "`";
    }
}
