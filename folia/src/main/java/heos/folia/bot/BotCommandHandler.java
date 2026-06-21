package heos.folia.bot;

import heos.folia.storage.FoliaStorage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;

/**
 * QQ group command handler — ported from whitelist/command_handler.py + motd/main.py.
 * Commands: 白名单 <ID>, 删除 <ID>, 查询白名单, 服务器还活着吗, 封禁/解禁 (admin)
 */
public class BotCommandHandler {
    private final Logger logger;
    private final BotDb botDb;
    private final FoliaStorage storage;
    private final BotStatusService statusService;
    private final int maxPerQq;
    private final Pattern idPattern;
    private final long[] allowedGroups;

    // Rate limiting: groupId -> timestamps
    private final Map<Long, long[]> rateMap = new ConcurrentHashMap<>();
    private final int rateMax;
    private final long rateWindowMs;

    // Command trigger for status
    private final String statusTrigger;

    // Patterns
    private static final Pattern APPLY = Pattern.compile("^(申请|白名单|申请白名单)\\s+(\\S+)$");
    private static final Pattern DELETE = Pattern.compile("^(删除|删除白名单)\\s+(\\S+)$");
    private static final Pattern QUERY = Pattern.compile("^查询白名单$");
    private static final Pattern QUERY_OTHER = Pattern.compile("^查询白名单\\s+(.+)$");
    private static final Pattern HELP = Pattern.compile("^(help|帮助|命令|菜单|HELP|Help)$");
    private static final Pattern BAN_CMD = Pattern.compile("^(封禁|拉黑|黑名单|添加黑名单)\\s*(.*)$");
    private static final Pattern UNBAN_CMD = Pattern.compile("^(解禁|解封|删除黑名单|移除黑名单|移出黑名单)\\s*(.*)$");

    public BotCommandHandler(Logger logger, BotDb botDb, FoliaStorage storage, BotStatusService statusService,
                             int maxPerQq, String allowedIdChars, int maxIdLength,
                             long[] allowedGroups, String statusTrigger, int rateMax, int rateWindowSec) {
        this.logger = logger;
        this.botDb = botDb;
        this.storage = storage;
        this.statusService = statusService;
        this.maxPerQq = maxPerQq;
        this.idPattern = Pattern.compile("^[" + allowedIdChars + "]{1," + maxIdLength + "}$");
        this.allowedGroups = allowedGroups;
        this.statusTrigger = statusTrigger;
        this.rateMax = rateMax;
        this.rateWindowMs = rateWindowSec * 1000L;
    }

    public void handle(OneBotEvent event) {
        if (!"message".equals(event.postType()) || !"group".equals(event.messageType())) return;

        long groupId = event.groupId();
        if (!isAllowed(groupId)) return;

        long qq = event.userId();
        String text = event.rawMessage().trim();
        String role = event.senderRole();
        boolean isAdmin = "admin".equals(role) || "owner".equals(role);

        logger.info("[BotHandler] QQ" + qq + " group=" + groupId + " role=" + role + ": " + text);

        // Status query (no rate limit for admins)
        if (text.equals(statusTrigger)) {
            if (!isAdmin && !checkRate(groupId)) {
                event.reply("请求过于频繁，请稍后再试～");
                return;
            }
            handleStatus(groupId, event);
            return;
        }

        // Admin commands
        if (isAdmin) {
            if (handleBan(qq, text, event)) return;
            if (handleUnban(qq, text, event)) return;
            if (handleAdminDelete(qq, text, event)) return;
        }

        // Blacklist check
        if (botDb.isBlacklisted(qq) && (APPLY.matcher(text).matches() || DELETE.matcher(text).matches())) {
            event.reactDeny();
            return;
        }

        // Regular commands
        Matcher m = APPLY.matcher(text);
        if (m.matches()) { handleApply(qq, m.group(2), event); return; }

        m = DELETE.matcher(text);
        if (m.matches()) { handleSelfDelete(qq, m.group(2), event); return; }

        if (QUERY.matcher(text).matches()) { handleQuery(qq, event); return; }
        Matcher qo = QUERY_OTHER.matcher(text);
        if (qo.matches()) { handleQueryOther(qq, qo.group(1), event); return; }
        if (HELP.matcher(text).matches()) { handleHelp(event); return; }

        // Unknown command — silently ignore for now
        // The original projects also don't respond to non-matching messages
    }

    private boolean isAllowed(long groupId) {
        if (allowedGroups.length == 0) return true;
        for (long g : allowedGroups) if (g == groupId) return true;
        return false;
    }

    private boolean checkRate(long groupId) {
        long now = System.currentTimeMillis();
        long[] times = rateMap.computeIfAbsent(groupId, k -> new long[0]);
        // Prune old timestamps
        int valid = 0;
        for (long t : times) if (now - t < rateWindowMs) valid++;
        if (valid >= rateMax) return false;
        // Add new timestamp
        long[] newTimes = new long[valid + 1];
        int idx = 0;
        for (long t : times) if (now - t < rateWindowMs) newTimes[idx++] = t;
        newTimes[idx] = now;
        rateMap.put(groupId, newTimes);
        return true;
    }

    // ---- Status query ----
    private void handleStatus(long groupId, OneBotEvent event) {
        event.reply("正在查询服务器状态，请稍候...");
        new Thread(() -> {
            try {
                BotStatusService.ServerStatus info = statusService.ping();
                // Get system stats from the running server
                Runtime rt = Runtime.getRuntime();
                double mem = (rt.totalMemory() - rt.freeMemory()) * 100.0 / rt.maxMemory();
                double cpu = -1; // Can't easily get JVM CPU from plugin

                String text = statusService.formatStatusText(info, cpu >= 0 ? cpu : null, mem);
                event.reply(text);
            } catch (Exception e) {
                event.reply("查询失败: " + e.getMessage());
            }
        }, "LuoOS-Status").start();
    }

    // ---- Whitelist commands ----
    private void handleApply(long qq, String playerId, OneBotEvent event) {
        if (!idPattern.matcher(playerId).matches()) { event.react(false); return; }
        if (botDb.hasWhitelist(qq, playerId)) { event.react(false); return; }
        int count = botDb.getWhitelistCount(qq);
        if (count >= maxPerQq) { event.react(false); return; }

        String uuid = null;
        var data = storage.load(playerId);
        if (data != null) uuid = data.uuid.toString();
        botDb.addWhitelist(qq, playerId, uuid);
        // Also add to MC server whitelist
        org.bukkit.Bukkit.getScheduler().runTask(org.bukkit.Bukkit.getPluginManager().getPlugin("luoos"),
                () -> {
                    org.bukkit.OfflinePlayer op = org.bukkit.Bukkit.getOfflinePlayer(playerId);
                    if (op != null) op.setWhitelisted(true);
                });

        event.react(true);
        logger.info("[BotHandler] QQ" + qq + " applied whitelist: " + playerId);
    }

    private void handleSelfDelete(long qq, String playerId, OneBotEvent event) {
        if (!botDb.hasWhitelist(qq, playerId)) { event.react(false); return; }
        botDb.removeWhitelist(qq, playerId);
        // Also remove from MC server whitelist
        org.bukkit.Bukkit.getScheduler().runTask(org.bukkit.Bukkit.getPluginManager().getPlugin("luoos"),
                () -> {
                    org.bukkit.OfflinePlayer op = org.bukkit.Bukkit.getOfflinePlayer(playerId);
                    if (op != null) op.setWhitelisted(false);
                });
        event.react(true);
    }

    private void handleQuery(long qq, OneBotEvent event) {
        var players = botDb.getWhitelist(qq);
        int count = players.size();
        if (players.isEmpty()) {
            event.replyAt("你还没有添加白名单 (0/" + maxPerQq + ")");
        } else {
            StringBuilder sb = new StringBuilder("你的白名单 (" + count + "/" + maxPerQq + "):\n");
            for (int i = 0; i < players.size(); i++)
                sb.append(i + 1).append(". ").append(players.get(i)).append("\n");
            event.replyAt(sb.toString());
        }
    }

    // ---- Admin: ban/unban/delete ----
    private boolean handleBan(long adminQq, String text, OneBotEvent event) {
        Matcher m = BAN_CMD.matcher(text);
        if (!m.matches()) return false;
        long targetQq = extractTargetQq(text, event);
        if (targetQq == 0) { event.react(false); return true; }
        String dur = parseDuration(text);
        botDb.blacklist(targetQq, dur.equals("permanent") ? null : parseDurationSeconds(dur), "QQ ban");
        event.react(true);
        return true;
    }

    private boolean handleUnban(long adminQq, String text, OneBotEvent event) {
        Matcher m = UNBAN_CMD.matcher(text);
        if (!m.matches()) return false;
        long targetQq = extractTargetQq(text, event);
        if (targetQq == 0) { event.react(false); return true; }
        botDb.unblacklist(targetQq);
        event.react(true);
        return true;
    }

    private boolean handleAdminDelete(long adminQq, String text, OneBotEvent event) {
        if (!text.startsWith("删除") && !text.startsWith("删除白名单")) return false;
        long targetQq = extractTargetQq(text, event);
        if (targetQq == 0) return false;
        String playerId = extractPlayer(text);
        if (playerId != null) {
            botDb.removeWhitelist(targetQq, playerId);
        } else {
            // Delete all
            var all = botDb.getWhitelist(targetQq);
            for (String p : all) botDb.removeWhitelist(targetQq, p);
        }
        event.react(true);
        return true;
    }


    // --- Query other (admin, supports @) ---
    private void handleQueryOther(long qq, String target, OneBotEvent event) {
        String role = event.senderRole();
        boolean isAdmin = "admin".equals(role) || "owner".equals(role);
        if (!isAdmin) { event.replyAt("只有管理员可以查询他人白名单"); return; }
        long targetQq = 0;
        try { targetQq = Long.parseLong(target.trim()); } catch (Exception ignored) {}
        if (targetQq == 0) { targetQq = extractTargetQq(target, event); }
        if (targetQq == 0) { event.react(false); return; }
        var players = botDb.getWhitelist(targetQq);
        int cnt = players.size();
        if (players.isEmpty()) {
            event.replyAt("QQ" + targetQq + " 还没有添加白名单");
        } else {
            StringBuilder sb = new StringBuilder("QQ" + targetQq + " 的白名单 (" + cnt + "):\n");
            for (int i = 0; i < players.size(); i++)
                sb.append(i + 1).append(". ").append(players.get(i)).append("\n");
            event.replyAt(sb.toString());
        }
    }

    private void handleHelp(OneBotEvent event) {
        String txt = "LuoOS Bot 命令帮助\n\n"
            + "白名单 <ID>    申请白名单（自动绑定QQ）\n"
            + "申请白名单 <ID>  同上（别名）\n"
            + "删除 <ID>      删除自己的白名单\n"
            + "查询白名单      查看自己的白名单\n"
            + "查询白名单 <QQ/@> 管理员查询他人\n"
            + "服务器还活着吗  查看服务器状态卡片\n"
            + "help/帮助/菜单  显示此帮助\n\n"
            + "——管理员——\n"
            + "封禁 @QQ [时长] 封禁用户\n"
            + "解禁 @QQ      解禁用户\n"
            + "删除 @QQ <ID> 删除指定用户的白名单\n\n"
            + "Write by 黔中极客 / LuoOS Bot v0.06";
        event.reply(txt);
    }

    // ---- Helpers ----
    private long extractTargetQq(String text, OneBotEvent event) {
        // Try @ mention from message array
        if (event.raw.has("message")) {
            var arr = event.raw.getAsJsonArray("message");
            for (var seg : arr) {
                var s = seg.getAsJsonObject();
                if ("at".equals(s.get("type").getAsString())) {
                    try { return s.getAsJsonObject("data").get("qq").getAsLong(); }
                    catch (Exception ignored) {}
                }
            }
        }
        // Try numeric in text
        Matcher m = Pattern.compile("\\b(\\d{5,})\\b").matcher(text);
        if (m.find()) return Long.parseLong(m.group(1));
        return 0;
    }

    private String extractPlayer(String text) {
        String cleaned = text.replaceFirst("^(删除|删除白名单)\\s*", "").trim();
        for (String p : cleaned.split("\\s+")) {
            if (p.matches("^[a-zA-Z0-9_-]+$") && !p.matches("\\d{5,}")) return p;
        }
        return null;
    }

    private String parseDuration(String text) {
        Matcher m = Pattern.compile("(\\d+[yMwdhms]|永久|永封|forever)").matcher(text);
        return m.find() ? m.group(1) : "permanent";
    }

    private Long parseDurationSeconds(String dur) {
        if (dur == null) return null;
        try {
            long total = 0;
            Matcher m = Pattern.compile("(\\d+)([yMwdhms])").matcher(dur);
            while (m.find()) {
                long v = Long.parseLong(m.group(1));
                switch (m.group(2)) {
                    case "y": total += v * 365 * 86400; break;
                    case "M": total += v * 30 * 86400; break;
                    case "w": total += v * 7 * 86400; break;
                    case "d": total += v * 86400; break;
                    case "h": total += v * 3600; break;
                    case "m": total += v * 60; break;
                    case "s": total += v; break;
                }
            }
            return total > 0 ? total : null;
        } catch (Exception e) { return null; }
    }
}
