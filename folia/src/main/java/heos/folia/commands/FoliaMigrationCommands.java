package heos.folia.commands;

import heos.folia.storage.FoliaBanData;
import heos.folia.utils.FoliaNameResolver;
import heos.folia.storage.FoliaPlayerData;
import heos.folia.storage.FoliaStorage;
import heos.folia.utils.FoliaDisconnects;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FoliaMigrationCommands {
    private static final long CONFIRM_TIMEOUT_MILLIS = 60_000L;
    private static final int MAX_MIGRATION_BAN_SECONDS = 30;
    private static final PlayerFileType[] PLAYER_FILE_TYPES = {
            new PlayerFileType("playerdata", ".dat"),
            new PlayerFileType("playerdata", ".dat_old"),
            new PlayerFileType("stats", ".json"),
            new PlayerFileType("advancements", ".json")
    };

    private final Plugin plugin;
    private final FoliaStorage storage;
    private final FoliaBanData banData;
    private final FoliaNameResolver nameResolver;
    private final Map<UUID, PendingMigration> pendingMigrations = new ConcurrentHashMap<>();

    public FoliaMigrationCommands(Plugin plugin, FoliaStorage storage, FoliaBanData banData, FoliaNameResolver nameResolver) {
        this.plugin = plugin;
        this.storage = storage;
        this.banData = banData;
        this.nameResolver = nameResolver;
    }

    public boolean onHeosSubcommand(CommandSender sender, String[] args) {
        if (args[0].equalsIgnoreCase("migrate")) {
            return prepare(sender, args);
        }
        if (args[0].equalsIgnoreCase("confirm-click")) {
            return confirm(sender, args);
        }
        return false;
    }

    private boolean prepare(CommandSender sender, String[] args) {
        if (!plugin.getConfig().getBoolean("enablePlayerDataMigration", false)) {
            sender.sendMessage(ChatColor.RED + "Player data migration is disabled in config.yml");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Migration must be confirmed by clicking the chat button in game.");
            return true;
        }
        if (args.length != 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /heos migrate <sourcePlayer> <targetPlayer>");
            return true;
        }
        String source = args[1];
        String target = args[2];
        if (source.equalsIgnoreCase(target)) {
            sender.sendMessage(ChatColor.RED + "Cannot migrate data to the same player");
            return true;
        }

        String token = UUID.randomUUID().toString();
        pendingMigrations.put(player.getUniqueId(), new PendingMigration(source, target, token, System.currentTimeMillis()));
        sender.sendMessage(ChatColor.YELLOW + "Migration prepared: " + source + " -> " + target);
        sender.sendMessage(ChatColor.YELLOW + "Click the confirmation button within 60 seconds to execute it.");
        sendConfirmationButton(player, token);
        return true;
    }

    private boolean confirm(CommandSender sender, String[] args) {
        if (!plugin.getConfig().getBoolean("enablePlayerDataMigration", false)) {
            sender.sendMessage(ChatColor.RED + "Player data migration is disabled in config.yml");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Migration must be confirmed in game.");
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage(ChatColor.RED + "Invalid migration confirmation");
            return true;
        }
        PendingMigration migration = pendingMigrations.get(player.getUniqueId());
        if (migration == null) {
            sender.sendMessage(ChatColor.RED + "No pending migration. Use /heos migrate <source> <target> first.");
            return true;
        }
        if (!migration.token.equals(args[1])) {
            sender.sendMessage(ChatColor.RED + "Invalid migration confirmation. Please click the latest confirmation button.");
            return true;
        }
        if (System.currentTimeMillis() - migration.createdAt > CONFIRM_TIMEOUT_MILLIS) {
            pendingMigrations.remove(player.getUniqueId());
            sender.sendMessage(ChatColor.RED + "Pending migration expired. Use /heos migrate <source> <target> again.");
            return true;
        }
        pendingMigrations.remove(player.getUniqueId());
        execute(sender, migration);
        return true;
    }

    private void execute(CommandSender sender, PendingMigration migration) {
        Player sourceOnline = Bukkit.getPlayerExact(migration.sourceUsername);
        Player targetOnline = Bukkit.getPlayerExact(migration.targetUsername);
        if (sourceOnline != null) {
            FoliaDisconnects.disconnect(sourceOnline, "Your data is being migrated to another account. Please log in again later", "HEOS_MIGRATION_SOURCE");
        }
        if (targetOnline != null) {
            FoliaDisconnects.disconnect(targetOnline, "Data is being migrated to your account. Please log in again later", "HEOS_MIGRATION_TARGET");
        }

        // Collect all UUIDs associated with the source name
        Set<UUID> sourceUuids = collectPlayerUuids(migration.sourceUsername);
        UUID targetUuid = resolvePlayerUuid(migration.targetUsername);

        // Try to resolve the target's existing UUID from storage
        List<FoliaPlayerData> targetDataList = storage.loadAllByName(migration.targetUsername);
        UUID existingTargetUuid = null;
        for (FoliaPlayerData d : targetDataList) {
            if (d.uuid != null) {
                existingTargetUuid = d.uuid;
                break;
            }
        }
        if (existingTargetUuid != null) {
            targetUuid = existingTargetUuid;
        }

        Path worldDir = primaryWorldPath();
        int copied = copyPlayerFiles(worldDir, sourceUuids, targetUuid);

        // Migrate HEOS data: find source data by name, copy to target UUID
        List<FoliaPlayerData> sourceDataList = storage.loadAllByName(migration.sourceUsername);
        for (FoliaPlayerData sourceData : sourceDataList) {
            if (sourceData.isRegistered()) {
                FoliaPlayerData targetData = storage.load(targetUuid);
                if (targetData == null) {
                    targetData = new FoliaPlayerData(migration.targetUsername, targetUuid, sourceData.isOnlineAccount);
                }
                targetData.username = migration.targetUsername;
                targetData.uuid = targetUuid;
                targetData.passwordHash = sourceData.passwordHash;
                targetData.lastIp = sourceData.lastIp;
                targetData.isOnlineAccount = sourceData.isOnlineAccount;
                targetData.registeredTime = sourceData.registeredTime;
                targetData.lastLoginTime = System.currentTimeMillis();
                storage.save(targetData);
                copied++;
            }
        }

        if (copied == 0) {
            sender.sendMessage(ChatColor.RED + "No data found to migrate");
            return;
        }

        int deleted = clearSourceData(worldDir, migration.sourceUsername, sourceUuids);
        int banSeconds = Math.min(MAX_MIGRATION_BAN_SECONDS, Math.max(1, plugin.getConfig().getInt("migrationBanSeconds", 30)));
        long banExpiry = System.currentTimeMillis() + banSeconds * 1000L;
        UUID sourceUuid = sourceUuids.isEmpty() ? null : sourceUuids.iterator().next();
        banData.addPlayerBan(migration.sourceUsername, sourceUuid, "Data migration in progress", banExpiry, sender.getName());

        sender.sendMessage(ChatColor.GRAY + "=================================");
        sender.sendMessage(ChatColor.GREEN + "Data migration complete");
        sender.sendMessage(ChatColor.GRAY + "Source player: " + migration.sourceUsername + (sourceUuid != null ? " (" + sourceUuid + ")" : ""));
        sender.sendMessage(ChatColor.GRAY + "Target player: " + migration.targetUsername + " (" + targetUuid + ")");
        sender.sendMessage(ChatColor.GRAY + "Migrated entries: " + copied);
        sender.sendMessage(ChatColor.GRAY + "Cleaned source entries: " + deleted);
        sender.sendMessage(ChatColor.GRAY + "Source player temporarily banned for " + banSeconds + " seconds.");
        sender.sendMessage(ChatColor.GRAY + "=================================");
    }

    private void sendConfirmationButton(Player player, String token) {
        TextComponent button = new TextComponent("[Confirm Migration]");
        button.setColor(net.md_5.bungee.api.ChatColor.GREEN);
        button.setBold(true);
        button.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/heos confirm-click " + token));
        player.spigot().sendMessage(button);
    }

    private Set<UUID> collectPlayerUuids(String username) {
        Set<UUID> uuids = new LinkedHashSet<>();
        Player online = Bukkit.getPlayerExact(username);
        if (online != null) {
            uuids.add(online.getUniqueId());
        }
        // Also collect UUIDs from storage
        List<FoliaPlayerData> dataList = storage.loadAllByName(username);
        for (FoliaPlayerData data : dataList) {
            if (data.uuid != null) {
                uuids.add(data.uuid);
            }
        }
        uuids.add(resolvePlayerUuid(username));
        return uuids;
    }

    private static UUID resolvePlayerUuid(String username) {
        @SuppressWarnings("deprecation")
        OfflinePlayer player = Bukkit.getOfflinePlayer(username);
        return player.getUniqueId();
    }

    private static Path primaryWorldPath() {
        World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (world == null) {
            return Path.of("world");
        }
        return world.getWorldFolder().toPath();
    }

    private static int copyPlayerFiles(Path worldDir, Set<UUID> sourceUuids, UUID targetUuid) {
        int copied = 0;
        for (PlayerFileType fileType : PLAYER_FILE_TYPES) {
            Path target = fileType.path(worldDir, targetUuid);
            for (UUID sourceUuid : sourceUuids) {
                if (copyIfExists(fileType.path(worldDir, sourceUuid), target)) {
                    copied++;
                    break;
                }
            }
        }
        return copied;
    }

    private int clearSourceData(Path worldDir, String sourceUsername, Set<UUID> sourceUuids) {
        int deleted = 0;
        for (UUID sourceUuid : sourceUuids) {
            for (PlayerFileType fileType : PLAYER_FILE_TYPES) {
                if (deleteIfExists(fileType.path(worldDir, sourceUuid))) {
                    deleted++;
                }
            }
        }
        // Delete all storage entries for this username
        List<FoliaPlayerData> sourceDataList = storage.loadAllByName(sourceUsername);
        for (FoliaPlayerData data : sourceDataList) {
            if (data.uuid != null && storage.delete(data.uuid)) {
                deleted++;
            }
        }
        return deleted;
    }

    private static boolean copyIfExists(Path from, Path to) {
        if (!Files.exists(from)) {
            return false;
        }
        try {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean deleteIfExists(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException ignored) {
            return false;
        }
    }

    private record PendingMigration(String sourceUsername, String targetUsername, String token, long createdAt) {
    }

    private record PlayerFileType(String directory, String suffix) {
        Path path(Path worldDir, UUID uuid) {
            return worldDir.resolve(directory).resolve(uuid + suffix);
        }
    }
}
