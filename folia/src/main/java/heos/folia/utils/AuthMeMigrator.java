package heos.folia.utils;

import heos.folia.storage.FoliaPlayerData;
import heos.folia.storage.FoliaStorage;

import java.sql.*;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Migrates player passwords from AuthMe to LuoOS.
 *
 * AuthMe stores passwords in a table named "authme" with columns:
 *   username, password (hashed), ip, lastlogin, email, etc.
 *
 * LuoOS stores player data in the "players" table with encrypted JSON "data" column.
 *
 * Usage: new AuthMeMigrator(logger, storage).migrate(authMeDbPath);
 */
public class AuthMeMigrator {
    private final Logger logger;
    private final FoliaStorage storage;

    public AuthMeMigrator(Logger logger, FoliaStorage storage) {
        this.logger = logger;
        this.storage = storage;
    }

    /**
     * Migrate from AuthMe SQLite database.
     *
     * @param authMeDbPath path to AuthMe's SQLite database (e.g. "plugins/AuthMe/authme.sqlite")
     * @return number of accounts migrated
     */
    public int migrateFromSQLite(String authMeDbPath) {
        int count = 0;
        try (Connection authCon = DriverManager.getConnection("jdbc:sqlite:" + authMeDbPath)) {
            authCon.setAutoCommit(false);
            PreparedStatement ps = authCon.prepareStatement(
                    "SELECT username, password, lastlogin FROM authme WHERE password IS NOT NULL AND password != ''");
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                String username = rs.getString("username");
                String authMeHash = rs.getString("password");
                long lastLogin = rs.getLong("lastlogin");

                if (authMeHash == null || authMeHash.isEmpty()) continue;

                // Check if already exists in LuoOS
                FoliaPlayerData existing = storage.load(username);
                if (existing != null && existing.isRegistered()) {
                    logger.fine("Skipping " + username + " — already registered in LuoOS");
                    continue;
                }

                // Create or update LuoOS player data
                FoliaPlayerData data = existing != null ? existing : new FoliaPlayerData(username);
                data.username = username;
                if (data.uuid == null) {
                    data.uuid = UUID.nameUUIDFromBytes(
                            ("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                data.passwordHash = authMeHash; // AuthMe hash format is compatible
                data.isOnlineAccount = false;
                data.registeredTime = lastLogin > 0 ? lastLogin : System.currentTimeMillis();
                data.lastLoginTime = lastLogin > 0 ? lastLogin : System.currentTimeMillis();

                storage.save(data);
                count++;
                logger.info("Migrated: " + username);
            }

            authCon.close();
        } catch (Exception e) {
            logger.severe("AuthMe migration failed: " + e.getMessage());
        }
        return count;
    }

    /**
     * Migrate from AuthMe MySQL database.
     */
    public int migrateFromMySQL(String url, String user, String password) {
        int count = 0;
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            try (Connection authCon = DriverManager.getConnection(url, user, password)) {
                PreparedStatement ps = authCon.prepareStatement(
                        "SELECT username, password, lastlogin FROM authme WHERE password IS NOT NULL AND password != ''");
                ResultSet rs = ps.executeQuery();

                while (rs.next()) {
                    String username = rs.getString("username");
                    String authMeHash = rs.getString("password");
                    long lastLogin = rs.getLong("lastlogin");

                    if (authMeHash == null || authMeHash.isEmpty()) continue;

                    FoliaPlayerData existing = storage.load(username);
                    if (existing != null && existing.isRegistered()) {
                        logger.fine("Skipping " + username + " — already registered");
                        continue;
                    }

                    FoliaPlayerData data = existing != null ? existing : new FoliaPlayerData(username);
                    data.username = username;
                    if (data.uuid == null) {
                        data.uuid = UUID.nameUUIDFromBytes(
                                ("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    data.passwordHash = authMeHash;
                    data.isOnlineAccount = false;
                    data.registeredTime = lastLogin > 0 ? lastLogin : System.currentTimeMillis();
                    data.lastLoginTime = lastLogin > 0 ? lastLogin : System.currentTimeMillis();

                    storage.save(data);
                    count++;
                    logger.info("Migrated: " + username);
                }
            }
        } catch (Exception e) {
            logger.severe("AuthMe MySQL migration failed: " + e.getMessage());
        }
        return count;
    }
}
