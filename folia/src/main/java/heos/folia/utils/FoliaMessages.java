package heos.folia.utils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.plugin.Plugin;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

public final class FoliaMessages {
    private static final String DEFAULT_LANGUAGE = "zh_cn";
    private static final Gson GSON = new Gson();
    private static Map<String, String> FALLBACK = Collections.emptyMap();
    private static Plugin plugin;

    private FoliaMessages() {
    }

    public static void init(Plugin instance) {
        plugin = instance;
        FALLBACK = loadLanguage("en_us");
    }

    private static Map<String, String> loadLanguage(String language) {
        try {
            var stream = FoliaMessages.class.getResourceAsStream("/data/heos/lang/" + language.toLowerCase(Locale.ENGLISH) + ".json");
            if (stream == null) {
                return Collections.emptyMap();
            }
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                Map<String, String> map = GSON.fromJson(reader, new TypeToken<Map<String, String>>() {}.getType());
                return map == null ? Collections.emptyMap() : map;
            }
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    public static String translate(String key) {
        String language = currentLanguage();
        Map<String, String> current = loadLanguage(language);
        if (current.containsKey(key)) {
            return current.get(key);
        }
        return FALLBACK.getOrDefault(key, key);
    }

    public static String authPromptLogin() {
        return translate("text.heos.loginInputHint");
    }

    public static String authPromptRegister() {
        return translate("text.heos.registerInputHint");
    }

    public static String offlineNameHint() {
        if (isChinese()) {
            return "\u00a76\u7528\u6237\u540d\u65e0\u6548\n\u5141\u8bb8\u7684\u683c\u5f0f\n" + allowedUsernamePattern();
        }
        return translate("text.heos.disallowedUsername").formatted(allowedUsernamePattern());
    }

    public static String invalidOfflineNameLog() {
        if (isChinese()) {
            return "\u00a76\u7528\u6237\u540d\u65e0\u6548\uff0c\u5141\u8bb8\u7684\u683c\u5f0f\uff1a" + allowedUsernamePattern();
        }
        return translate("text.heos.disallowedUsername").formatted(allowedUsernamePattern());
    }

    public static String loginTimeout() {
        return translate("text.heos.timeExpired");
    }

    public static String premiumWelcome() {
        return translate("text.heos.onlinePlayerLogin");
    }

    public static String authServiceUnavailable() {
        return translate("text.heos.authServiceUnavailable");
    }

    public static String loginInputHint() {
        return translate("text.heos.loginInputHint");
    }

    public static String registerInputHint() {
        return translate("text.heos.registerRequired");
    }

    public static String alreadyLoggedIn() {
        return translate("text.heos.alreadyAuthenticated");
    }

    public static String premiumNoLogin() {
        return translate("text.heos.onlinePlayerLogin");
    }

    public static String premiumNoRegister() {
        return translate("text.heos.onlinePlayerLogin");
    }

    public static String notRegistered() {
        return translate("text.heos.userNotRegistered");
    }

    public static String alreadyRegistered() {
        return translate("text.heos.alreadyRegistered");
    }

    public static String loginSuccess() {
        return translate("text.heos.successfullyAuthenticated");
    }

    public static String wrongPassword() {
        return translate("text.heos.wrongPassword");
    }

    public static String passwordTooShort() {
        return translate("text.heos.minPasswordChars");
    }

    public static String passwordTooLong() {
        return translate("text.heos.maxPasswordChars");
    }

    public static String passwordMismatch() {
        return translate("text.heos.matchPassword");
    }

    public static String registerFailed() {
        return translate("text.heos.registerRequired");
    }

    public static String registerSuccess() {
        return translate("text.heos.registerSuccess");
    }

    public static String keepPasswordSafe() {
        return translate("text.heos.keepPasswordSafe");
    }

    public static boolean isMigrationReason(String reason) {
        if (reason == null) {
            return false;
        }
        String normalized = reason.toLowerCase();
        return normalized.contains("data migration in progress")
                || normalized.contains("migration in progress");
    }

    public static String whitelistKick() {
        return translate("text.heos.whitelistKick");
    }

    public static String loginFailureLock(long seconds) {
        if (isChinese()) {
            return "\u00a7c\u767b\u5f55\u5931\u8d25\u6b21\u6570\u8fc7\u591a\n\u8bf7\u7a0d\u540e\u91cd\u8bd5\n" + seconds + " s";
        }
        return translate("text.heos.loginFailureLock").formatted(seconds);
    }

    public static String whitelistDeniedLog(String username) {
        return translate("text.heos.whitelistDeniedLog").formatted(username);
    }

    public static String banMessage(String reason, String expiry) {
        if (isChinese()) {
            return "\u4f60\u5df2\u88ab\u5c01\u7981\n" + reason + "\n" + expiry;
        }
        return translate("text.heos.banMessage").formatted(reason, expiry);
    }

    public static String banIpMessage(String reason, String expiry) {
        if (isChinese()) {
            return "\u4f60\u7684 IP \u5df2\u88ab\u5c01\u7981\n" + reason + "\n" + expiry;
        }
        return translate("text.heos.banIpMessage").formatted(reason, expiry);
    }

    public static String migrationBanAttemptLog(String username) {
        return translate("text.heos.playerAlreadyOnline").formatted(username);
    }

    // === Name conflict prefixes ===
    public static String namePrefixOnline() {
        return translate("text.heos.namePrefixOnline");
    }

    public static String namePrefixOffline() {
        return translate("text.heos.namePrefixOffline");
    }

    // === Account binding messages ===
    public static String bindOldNameRequired() {
        return translate("text.heos.bindOldNameRequired");
    }

    public static String bindNewNameRequired() {
        return translate("text.heos.bindNewNameRequired");
    }

    public static String bindAlreadyBound() {
        return translate("text.heos.bindAlreadyBound");
    }

    public static String bindPendingExists() {
        return translate("text.heos.bindPendingExists");
    }

    public static String bindRequestFailed() {
        return translate("text.heos.bindRequestFailed");
    }

    public static String bindRequestSent(String oldName) {
        return translate("text.heos.bindRequestSent").formatted(oldName);
    }

    public static String bindNoPendingRequest(String newName) {
        return translate("text.heos.bindNoPendingRequest").formatted(newName);
    }

    public static String bindAccepted(String newName) {
        return translate("text.heos.bindAccepted").formatted(newName);
    }

    public static String bindDenied(String newName) {
        return translate("text.heos.bindDenied").formatted(newName);
    }

    public static String bindNotPending() {
        return translate("text.heos.bindNotPending");
    }

    public static String bindRevoked(long id) {
        return translate("text.heos.bindRevoked").formatted(id);
    }

    public static String bindUsageRequest() {
        return translate("text.heos.bindUsageRequest");
    }

    public static String bindUsageAccept() {
        return translate("text.heos.bindUsageAccept");
    }

    public static String bindUsageDeny() {
        return translate("text.heos.bindUsageDeny");
    }

    public static String bindUsageList() {
        return translate("text.heos.bindUsageList");
    }

    public static String bindUsageRevoke() {
        return translate("text.heos.bindUsageRevoke");
    }

    public static String bindNoPendingForYou() {
        return translate("text.heos.bindNoPendingForYou");
    }

    public static String bindInfo(int id, String oldName, String newName, String status, String created) {
        return translate("text.heos.bindInfo").formatted(id, oldName, newName, status, created);
    }

    public static String bindHeader() {
        return translate("text.heos.bindHeader");
    }

    // === Name ambiguity resolution ===
    public static String nameAmbiguous(String name) {
        return translate("text.heos.nameAmbiguous").formatted(name);
    }

    public static String nameAmbiguousHint() {
        return translate("text.heos.nameAmbiguousHint");
    }

    // === Additional binding messages ===
    public static String bindSelfTarget() {
        return translate("text.heos.bindSelfTarget");
    }

    public static String bindIsTarget() {
        return translate("text.heos.bindIsTarget");
    }

    public static String bindTargetIsBound() {
        return translate("text.heos.bindTargetIsBound");
    }

    public static String bindGroupOnline(String playerName) {
        return translate("text.heos.bindGroupOnline").formatted(playerName);
    }

    private static String allowedUsernamePattern() {
        if (plugin != null && plugin.getConfig().getBoolean("allowMoreOfflineUsernameCharacters", true)) {
            return translate("text.heos.usernamePatternExtended");
        }
        return translate("text.heos.usernamePatternSimple");
    }

    private static String currentLanguage() {
        return plugin == null ? DEFAULT_LANGUAGE : plugin.getConfig().getString("language", DEFAULT_LANGUAGE);
    }

    private static boolean isChinese() {
        return "zh_cn".equalsIgnoreCase(currentLanguage());
    }
}
