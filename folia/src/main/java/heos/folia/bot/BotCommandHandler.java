package heos.folia.bot;

import heos.folia.storage.FoliaStorage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;

/**
 * Handles QQ group messages from OneBot, dispatching whitelist/blacklist/status commands.
 * Integrated with LuoOS shared database for QQ-MC account linking (whitelist = binding).
 */
public class BotCommandHandler {
    private final Logger logger;
    private final BotDb botDb;
    private final FoliaStorage storage;
    private final int maxPerQq;
    private final Pattern idPattern;
    private final long[] allowedGroups;

    // Command patterns
    private static final Pattern APPLY = Pattern.compile("^(申请|白名单|申请白名单)\\s+(\\S+)$");
    private static final Pattern DELETE = Pattern.compile("^(删除|删除白名单)\\s+(\\S+)$");
    private static final Pattern QUERY = Pattern.compile("^查询白名单$");
    private static final Pattern BAN_CMD = Pattern.compile("^(封禁|拉黑|黑名单|添加黑名单)\\s*(\\S*)$");
    private static final Pattern UNBAN_CMD = Pattern.compile("^(解禁|解封|删除黑名单|移除黑名单|移出黑名单)\\s*(\\S*)$");

    public BotCommandHandler(Logger logger, BotDb botDb, FoliaStorage storage,
                             int maxPerQq, String allowedIdChars, int maxIdLength,
                             long[] allowedGroups) {
        this.logger = logger;
        this.botDb = botDb;
        this.storage = storage;
        this.maxPerQq = maxPerQq;
        this.idPattern = Pattern.compile("^[" + allowedIdChars + "]{1," + maxIdLength + "}$");
        this.allowedGroups = allowedGroups;
    }

    public void handle(OneBotEvent event) {
        if (!"message".equals(event.postType()) || !"group".equals(event.messageType())) return;

        long groupId = event.groupId();
        if (!isAllowed(groupId)) return;

        long qq = event.userId();
        String text = event.rawMessage().trim();
        String role = event.senderRole();
        boolean isAdmin = "admin".equals(role) || "owner".equals(role);

        logger.info("[Bot] QQ" + qq + " in group " + groupId + ": " + text);

        // Admin commands
        if (isAdmin) {
            if (handleBan(qq, text, event)) return;
            if (handleUnban(qq, text, event)) return;
            if (handleAdminDelete(qq, text, event)) return;
        }

        // Blacklist check for apply/delete
        if (botDb.isBlacklisted(qq) && (APPLY.matcher(text).matches() || DELETE.matcher(text).matches())) {
            event.react(false);
            return;
        }

        // Regular commands
        Matcher m = APPLY.matcher(text);
        if (m.matches()) { handleApply(qq, m.group(2), event); return; }

        m = DELETE.matcher(text);
        if (m.matches()) { handleSelfDelete(qq, m.group(2), event); return; }

        if (QUERY.matcher(text).matches()) { handleQuery(qq, event); return; }
    }

    private boolean isAllowed(long groupId) {
        if (allowedGroups.length == 0) return true;
        for (long g : allowedGroups) if (g == groupId) return true;
        return false;
    }

    private void handleApply(long qq, String playerId, OneBotEvent event) {
        if (!idPattern.matcher(playerId).matches()) {
            event.react(false);
            return;
        }
        if (botDb.hasWhitelist(qq, playerId)) {
            event.react(false);
            return;
        }
        int count = botDb.getWhitelistCount(qq);
        if (count >= maxPerQq) {
            event.react(false);
            return;
        }
        // Add to QQ whitelist (also serves as QQ-MC binding)
        String uuid = storage.load(playerId) != null ? storage.load(playerId).uuid.toString() : null;
        botDb.addWhitelist(qq, playerId, uuid);

        // Execute RCON whitelist add via storage (the plugin has RCON access through Bukkit)
        // For now, the whitelist data is written to DB. The sync to MC server happens
        // via the LuoOS whitelist system or RCON.

        event.react(true);
        logger.info("[Bot] QQ" + qq + " applied whitelist for " + playerId);
    }

    private void handleSelfDelete(long qq, String playerId, OneBotEvent event) {
        if (!botDb.hasWhitelist(qq, playerId)) {
            event.react(false);
            return;
        }
        botDb.removeWhitelist(qq, playerId);
        event.react(true);
        logger.info("[Bot] QQ" + qq + " removed whitelist for " + playerId);
    }

    private void handleQuery(long qq, OneBotEvent event) {
        var players = botDb.getWhitelist(qq);
        int count = players.size();
        if (players.isEmpty()) {
            event.replyAt("你还没有添加白名单 (0/" + maxPerQq + ")");
        } else {
            StringBuilder sb = new StringBuilder("你的白名单 (" + count + "/" + maxPerQq + "):\n");
            for (int i = 0; i < players.size(); i++) {
                sb.append(i + 1).append(". ").append(players.get(i)).append("\n");
            }
            event.replyAt(sb.toString());
        }
    }

    // ---- Admin commands ----
    private boolean handleBan(long adminQq, String text, OneBotEvent event) {
        Matcher m = BAN_CMD.matcher(text);
        if (!m.matches()) return false;
        long targetQq = extractQq(text, event);
        if (targetQq == 0) { event.react(false); return true; }
        // Parse duration (default permanent)
        String duration = parseDuration(text);
        botDb.blacklist(targetQq, duration.equals("permanent") ? null : parseDurationSeconds(duration), "Admin ban");
        event.react(true);
        return true;
    }

    private boolean handleUnban(long adminQq, String text, OneBotEvent event) {
        Matcher m = UNBAN_CMD.matcher(text);
        if (!m.matches()) return false;
        long targetQq = extractQq(text, event);
        if (targetQq == 0) { event.react(false); return true; }
        botDb.unblacklist(targetQq);
        event.react(true);
        return true;
    }

    private boolean handleAdminDelete(long adminQq, String text, OneBotEvent event) {
        // Admin delete: "删除 <@someone>" or "删除 <qq> <player>"
        long targetQq = extractQq(text, event);
        if (targetQq == 0) return false;
        String playerId = extractPlayer(text);
        botDb.removeWhitelist(targetQq, playerId);
        event.react(true);
        return true;
    }

    // ---- Helpers ----
    private long extractQq(String text, OneBotEvent event) {
        // Try @ mention first
        if (event.raw.has("message")) {
            var arr = event.raw.getAsJsonArray("message");
            for (var seg : arr) {
                var s = seg.getAsJsonObject();
                if ("at".equals(s.get("type").getAsString())) {
                    return s.getAsJsonObject("data").get("qq").getAsLong();
                }
            }
        }
        // Try numeric in text
        Matcher m = Pattern.compile("\\b(\\d{5,})\\b").matcher(text);
        if (m.find()) return Long.parseLong(m.group(1));
        return 0;
    }

    private String extractPlayer(String text) {
        // Get the last word after removing command prefix and QQ mentions
        String cleaned = text.replaceFirst("^(删除|删除白名单)\\s*", "").trim();
        String[] parts = cleaned.split("\\s+");
        for (String p : parts) {
            if (p.matches("^[a-zA-Z0-9_-]+$") && !p.matches("\\d{5,}")) return p;
        }
        return null;
    }

    private String parseDuration(String text) {
        Matcher m = Pattern.compile("(\\d+[yMwdhms]|永久|永封|forever)").matcher(text);
        if (m.find()) return m.group(1);
        return "permanent";
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
