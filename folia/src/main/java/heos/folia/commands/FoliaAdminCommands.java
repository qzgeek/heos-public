package heos.folia.commands;

import heos.folia.utils.FoliaPasswordHasher;
import heos.folia.utils.FoliaNameResolver;
import heos.folia.storage.FoliaPlayerData;
import heos.folia.storage.FoliaStorage;
import heos.folia.storage.FoliaWhitelistData;
import heos.folia.event.FoliaAuthService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.io.File;
import java.util.UUID;

public final class FoliaAdminCommands implements CommandExecutor, TabCompleter {
    private final FoliaStorage storage;
    private final FoliaWhitelistData whitelistData;
    private final FoliaMigrationCommands migrationCommands;
    private final org.bukkit.plugin.Plugin plugin;
    private final FoliaAuthService authService;
    private final FoliaBanCommands banCommands;
    private final FoliaBindCommands bindCommands;
    private final FoliaNameResolver nameResolver;

    public FoliaAdminCommands(org.bukkit.plugin.Plugin plugin, FoliaStorage storage,
                              FoliaWhitelistData whitelistData,
                              FoliaMigrationCommands migrationCommands,
                              FoliaAuthService authService,
                              FoliaBanCommands banCommands,
                              FoliaBindCommands bindCommands) {
        this.plugin = plugin;
        this.storage = storage;
        this.whitelistData = whitelistData;
        this.migrationCommands = migrationCommands;
        this.authService = authService;
        this.banCommands = banCommands;
        this.bindCommands = bindCommands;
        this.nameResolver = authService.getNameResolver();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Refresh command suggestions on every use (catches mid-session OP changes)
        if (sender instanceof Player player) {
            player.updateCommands();
        }
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("login") || sub.equals("register") || sub.equals("changepassword")) {
            return auth(sender, args);
        }

        if (sub.equals("bind")) {
            return bindCommands.onCommand(sender, command, label, shiftArgs(args));
        }

        if (!sender.hasPermission("luoos.admin")) {
            // Non-admin trying admin command or unknown subcommand — show help
            showHelp(sender);
            return true;
        }

        return switch (sub) {
            case "resetpassword" -> resetPassword(sender, args);
            case "info" -> info(sender, args);
            case "whitelist" -> whitelist(sender, args);
            case "migrate", "confirm-click" -> migrationCommands.onHeosSubcommand(sender, args);
            case "migrate-authme" -> migrateAuthMe(sender, args);
            case "migrate-authme-tsv" -> migrateAuthMeTsv(sender, args);
            case "reload" -> reload(sender, args);
            case "ban", "ban-ip", "unban", "unban-ip", "banlist" -> banCommands.onSubcommand(sender, sub, shiftArgs(args));
            default -> { showHelp(sender); yield true; }
        };
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== LuoOS v0.06 ===");
        sender.sendMessage(ChatColor.WHITE + "/los login <密码>" + ChatColor.GRAY + " - 登录");
        sender.sendMessage(ChatColor.WHITE + "/los register <密码> <确认>" + ChatColor.GRAY + " - 注册");
        sender.sendMessage(ChatColor.WHITE + "/los changepassword <旧> <新>" + ChatColor.GRAY + " - 改密");
        sender.sendMessage(ChatColor.WHITE + "/los bind" + ChatColor.GRAY + " - 账号绑定管理");
        if (sender.hasPermission("luoos.admin")) {
            sender.sendMessage(ChatColor.GRAY + "--- 管理命令 ---");
            sender.sendMessage(ChatColor.WHITE + "/los info <玩家>" + ChatColor.GRAY + " - 查看信息");
            sender.sendMessage(ChatColor.WHITE + "/los resetpassword <玩家> <密码>" + ChatColor.GRAY + " - 重置密码");
            sender.sendMessage(ChatColor.WHITE + "/los whitelist add/remove/list" + ChatColor.GRAY + " - 白名单");
            sender.sendMessage(ChatColor.WHITE + "/los migrate <源> <目标>" + ChatColor.GRAY + " - 数据迁移");
            sender.sendMessage(ChatColor.WHITE + "/los migrate-authme <路径>" + ChatColor.GRAY + " - AuthMe迁移");
            sender.sendMessage(ChatColor.WHITE + "/los reload" + ChatColor.GRAY + " - 重载配置");
            sender.sendMessage(ChatColor.WHITE + "/ban /ban-ip /unban /banlist" + ChatColor.GRAY + " - 封禁管理");
        }
    }

    private boolean auth(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by a player");
            return true;
        }

        String sub = args[0].toLowerCase();
        if (sub.equals("login")) {
            if (args.length != 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /los login <password>");
                return true;
            }
            authService.login(player, args[1]);
            return true;
        }
        if (sub.equals("register")) {
            if (args.length != 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /los register <password> <confirmPassword>");
                return true;
            }
            authService.register(player, args[1], args[2]);
            return true;
        }
        if (sub.equals("changepassword")) {
            if (args.length != 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /los changepassword <oldPassword> <newPassword>");
                return true;
            }
            authService.changePassword(player, args[1], args[2]);
            return true;
        }
        return false;
    }

    private static String[] shiftArgs(String[] args) {
        if (args.length <= 1) {
            return new String[0];
        }
        String[] shifted = new String[args.length - 1];
        System.arraycopy(args, 1, shifted, 0, shifted.length);
        return shifted;
    }

    private FoliaPlayerData resolvePlayer(String nameArg, CommandSender sender) {
        // Try prefixed name first
        FoliaPlayerData prefixed = nameResolver.findByPrefixedName(nameArg);
        if (prefixed != null) {
            return prefixed;
        }

        // Load by plain name
        List<FoliaPlayerData> all = storage.loadAllByName(nameArg);
        if (all.size() > 1) {
            // Ambiguous — show hint
            sender.sendMessage(ChatColor.RED + ambiguousNameMsg(nameArg));
            sender.sendMessage(ChatColor.YELLOW + heos.folia.utils.FoliaMessages.nameAmbiguousHint());
            for (FoliaPlayerData data : all) {
                nameResolver.resolve(data);
                sender.sendMessage(ChatColor.GRAY + "  - " + data.effectiveDisplayName()
                        + " (" + (data.isOnlineAccount ? "premium" : "offline") + ") "
                        + (data.uuid != null ? data.uuid.toString().substring(0, 8) : "?"));
            }
            return null;
        }
        if (all.size() == 1) {
            nameResolver.resolve(all.get(0));
            return all.get(0);
        }
        return null;
    }

    private boolean resetPassword(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /los resetpassword <player> <newPassword>");
            return true;
        }
        FoliaPlayerData data = resolvePlayer(args[1], sender);
        if (data == null) {
            sender.sendMessage(ChatColor.RED + "Player " + args[1] + " not found or name is ambiguous.");
            return true;
        }
        if (!data.isRegistered()) {
            sender.sendMessage(ChatColor.RED + "Player " + data.effectiveDisplayName() + " is not registered");
            return true;
        }
        String password = args[2];
        data.passwordHash = FoliaPasswordHasher.hashPassword(password);
        storage.save(data);
        sender.sendMessage(ChatColor.GREEN + "Reset password for player " + data.effectiveDisplayName());

        Player online = Bukkit.getPlayer(data.uuid);
        if (online != null) {
            online.sendMessage(ChatColor.YELLOW + "Your password was reset by an administrator");
            online.sendMessage(ChatColor.YELLOW + "New password: " + password);
            online.sendMessage(ChatColor.YELLOW + "Please use /changepassword soon");
        }
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /los info <player>");
            return true;
        }
        FoliaPlayerData data = resolvePlayer(args[1], sender);
        if (data == null) {
            sender.sendMessage(ChatColor.RED + "Player " + args[1] + " not found or name is ambiguous.");
            return true;
        }
        if (!data.isRegistered()) {
            sender.sendMessage(ChatColor.RED + "Player " + data.effectiveDisplayName() + " is not registered");
            return true;
        }
        sender.sendMessage(ChatColor.GRAY + "=================================");
        sender.sendMessage(ChatColor.YELLOW + "Player info: " + data.effectiveDisplayName());
        if (data.hasNameConflict) {
            sender.sendMessage(ChatColor.GRAY + "Original name: " + data.username);
        }
        sender.sendMessage(ChatColor.GRAY + "UUID: " + (data.uuid == null ? "unknown" : data.uuid));
        sender.sendMessage(ChatColor.GRAY + "Last IP: " + (data.lastIp == null || data.lastIp.isBlank() ? "unknown" : data.lastIp));
        sender.sendMessage(ChatColor.GRAY + "Registered at: " + (data.registeredTime > 0L ? new Date(data.registeredTime) : "unknown"));
        sender.sendMessage(ChatColor.GRAY + "Last login: " + (data.lastLoginTime > 0L ? new Date(data.lastLoginTime) : "unknown"));
        sender.sendMessage(ChatColor.GRAY + "Account type: " + (data.isOnlineAccount ? "premium" : "offline"));
        sender.sendMessage(ChatColor.GRAY + "=================================");
        return true;
    }

    private boolean whitelist(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /los whitelist <add|remove|list> [player]");
            return true;
        }
        switch (args[1].toLowerCase()) {
            case "add" -> {
                if (args.length != 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /los whitelist add <player>");
                    return true;
                }
                // Try resolve to UUID for precise whitelisting
                FoliaPlayerData data = resolvePlayer(args[2], sender);
                if (data != null && data.uuid != null) {
                    if (whitelistData.add(data.uuid)) {
                        sender.sendMessage(ChatColor.GREEN + "Added " + data.effectiveDisplayName() + " (UUID) to whitelist");
                        return true;
                    }
                }
                if (whitelistData.add(args[2])) {
                    sender.sendMessage(ChatColor.GREEN + "Added " + args[2] + " to whitelist");
                } else {
                    sender.sendMessage(ChatColor.RED + "Player is already in whitelist: " + args[2]);
                }
                return true;
            }
            case "remove" -> {
                if (args.length != 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /los whitelist remove <player>");
                    return true;
                }
                FoliaPlayerData data = resolvePlayer(args[2], sender);
                boolean removed = false;
                if (data != null && data.uuid != null) {
                    removed = whitelistData.removeByUuid(data.uuid);
                }
                if (!removed) {
                    removed = whitelistData.remove(args[2]);
                }
                if (removed) {
                    sender.sendMessage(ChatColor.GREEN + "Removed " + args[2] + " from whitelist");
                } else {
                    sender.sendMessage(ChatColor.RED + "Player is not in whitelist: " + args[2]);
                }
                return true;
            }
            case "list" -> {
                sender.sendMessage(ChatColor.YELLOW + "Whitelist size: " + whitelistData.usernames.size()
                        + (whitelistData.uuids != null ? " + " + whitelistData.uuids.size() + " UUIDs" : ""));
                if (!whitelistData.usernames.isEmpty()) {
                    sender.sendMessage(ChatColor.GRAY + "Names: " + String.join(", ", whitelistData.usernames));
                }
                if (whitelistData.uuids != null && !whitelistData.uuids.isEmpty()) {
                    List<String> shortUuids = new ArrayList<>();
                    for (String id : whitelistData.uuids) {
                        shortUuids.add(id.substring(0, Math.min(id.length(), 8)));
                    }
                    sender.sendMessage(ChatColor.GRAY + "UUIDs: " + String.join(", ", shortUuids));
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            subs.add("login"); subs.add("register"); subs.add("changepassword");
            subs.add("bind");
            if (sender.hasPermission("luoos.admin")) {
                subs.add("ban"); subs.add("ban-ip"); subs.add("unban"); subs.add("unban-ip");
                subs.add("banlist"); subs.add("resetpassword"); subs.add("info");
                subs.add("whitelist"); subs.add("migrate"); subs.add("migrate-authme"); subs.add("migrate-authme-tsv"); subs.add("reload");
            }
            return filter(subs, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("whitelist")) {
            if (!sender.hasPermission("luoos.admin")) {
                return Collections.emptyList();
            }
            return filter(List.of("add", "remove", "list"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("bind")) {
            return bindCommands.onTabComplete(sender, command, alias, shiftArgs(args));
        }
        if ((args.length == 2 && (args[0].equalsIgnoreCase("resetpassword") || args[0].equalsIgnoreCase("info")))
                || (args.length == 3 && args[0].equalsIgnoreCase("whitelist") && !args[1].equalsIgnoreCase("list"))) {
            if (!sender.hasPermission("luoos.admin")) {
                return Collections.emptyList();
            }
            String prefix = args[args.length - 1].toLowerCase();
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(prefix)) {
                    names.add(player.getName());
                }
            }
            return names;
        }
        return Collections.emptyList();
    }

    private boolean migrateAuthMe(CommandSender sender, String[] args) {
        String authMeDir = args.length >= 2 ? args[1] : "plugins/AuthMe";
        File dir = new File(authMeDir);
        if (!dir.exists() || !new File(dir, "config.yml").exists()) {
            sender.sendMessage(ChatColor.RED + "AuthMe config not found at " + dir.getAbsolutePath());
            sender.sendMessage(ChatColor.GRAY + "Usage: /los migrate-authme [path-to-AuthMe-plugin-dir]");
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "正在从 " + authMeDir + " 迁移 AuthMe 数据...");
        try {
            heos.folia.utils.AuthMeMigrator migrator = new heos.folia.utils.AuthMeMigrator(
                    java.util.logging.Logger.getLogger("LuoOS-AuthMe"), authService.getStorage());
            int count = migrator.migrate(authMeDir);
            sender.sendMessage(ChatColor.GREEN + "迁移完成！共迁移 " + count + " 个账号。");
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "迁移失败: " + e.getMessage());
        }
        return true;
    }

    private boolean migrateAuthMeTsv(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /los migrate-authme-tsv <tsv-file>");
            return true;
        }
        String path = args[1];
        sender.sendMessage(ChatColor.YELLOW + "正在从 " + path + " 导入 AuthMe TSV 数据...");
        try {
            heos.folia.utils.AuthMeMigrator migrator = new heos.folia.utils.AuthMeMigrator(
                    java.util.logging.Logger.getLogger("LuoOS-AuthMe"), authService.getStorage());
            int count = migrator.migrateFromTsv(path);
            sender.sendMessage(ChatColor.GREEN + "导入完成！共迁移 " + count + " 个账号。");
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "导入失败: " + e.getMessage());
        }
        return true;
    }

    private boolean reload(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /los reload");
            return true;
        }
        plugin.reloadConfig();
        sender.sendMessage(ChatColor.GREEN + "Heos config reloaded");
        return true;
    }

    private static List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.startsWith(lower)) {
                result.add(value);
            }
        }
        return result;
    }

    private static String ambiguousNameMsg(String name) {
        return heos.folia.utils.FoliaMessages.nameAmbiguous(name);
    }
}
