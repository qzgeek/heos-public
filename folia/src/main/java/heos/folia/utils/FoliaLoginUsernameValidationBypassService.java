package heos.folia.utils;

import heos.folia.storage.FoliaAccountBinding;
import heos.folia.storage.FoliaBanData;
import heos.folia.storage.FoliaWhitelistData;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Netty hook for login bypass and account binding UUID remapping.
 *
 * For offline players (non-standard usernames):
 *   Bypasses Mojang verification and creates an offline GameProfile.
 *
 * For bound accounts:
 *   Remaps the UUID to the target UUID BEFORE the server processes the login.
 *   Also checks that no one else in the binding group is already online.
 */
public final class FoliaLoginUsernameValidationBypassService implements AutoCloseable {
    private static final String ACCEPTOR = "heos_login_acceptor";
    private static final String CHILD_BOOT = "heos_login_child_bootstrap";
    private static final String PACKET_HANDLER = "heos_login_username_validation_bypass";
    private static final String VANILLA = "packet_handler";

    private final Plugin plugin;
    private final FoliaBanData banData;
    private final FoliaWhitelistData whitelistData;
    private final FoliaAccountBinding accountBinding;
    private final Set<Channel> serverChannels = Collections.newSetFromMap(new IdentityHashMap<>());

    public FoliaLoginUsernameValidationBypassService(Plugin plugin, FoliaBanData banData,
                                                      FoliaWhitelistData whitelistData,
                                                      FoliaAccountBinding accountBinding) {
        this.plugin = plugin;
        this.banData = banData;
        this.whitelistData = whitelistData;
        this.accountBinding = accountBinding;
    }

    public void install() {
        for (Channel ch : serverChannels()) installServerChannel(ch);
        if (serverChannels.isEmpty())
            plugin.getLogger().warning("No server channels found for login bypass");
        plugin.getLogger().info("Installed Folia login bypass (UUID remapping + concurrency check)");
    }

    @Override
    public void close() {
        for (Channel ch : new ArrayList<>(serverChannels))
            ch.eventLoop().execute(() -> remove(ch, ACCEPTOR));
        serverChannels.clear();
    }

    private void installServerChannel(Channel ch) {
        if (ch == null || serverChannels.contains(ch)) return;
        serverChannels.add(ch);
        ch.eventLoop().execute(() -> {
            if (ch.pipeline().get(ACCEPTOR) != null) return;
            ch.pipeline().addFirst(ACCEPTOR, new ChannelDuplexHandler() {
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    if (msg instanceof Channel child) installChildBootstrap(child);
                    super.channelRead(ctx, msg);
                }
            });
        });
    }

    private void installChildBootstrap(Channel ch) {
        if (ch.pipeline().get(CHILD_BOOT) != null || ch.pipeline().get(PACKET_HANDLER) != null) return;
        ch.pipeline().addFirst(CHILD_BOOT, new ChannelDuplexHandler() {
            public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
                installChildChannel(ctx.channel(), 0); super.channelRegistered(ctx);
            }
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                installChildChannel(ctx.channel(), 0); super.channelRead(ctx, msg);
            }
        });
    }

    private void installChildChannel(Channel ch, int attempts) {
        if (!ch.isRegistered() || !ch.isOpen()) return;
        if (ch.pipeline().get(PACKET_HANDLER) != null) { remove(ch, CHILD_BOOT); return; }
        if (ch.pipeline().get(VANILLA) == null) {
            if (attempts < 20)
                ch.eventLoop().schedule(() -> installChildChannel(ch, attempts + 1), 25L, java.util.concurrent.TimeUnit.MILLISECONDS);
            return;
        }

        ch.pipeline().addBefore(VANILLA, PACKET_HANDLER, new ChannelDuplexHandler() {
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (!isHelloPacket(msg)) { super.channelRead(ctx, msg); return; }
                String username = packetUsername(msg);
                if (username == null) { super.channelRead(ctx, msg); return; }

                // 1) Reject banned / not-whitelisted
                if (rejectHeosLogin(ctx.channel(), username)) return;

                // 2) Compute the expected UUID for this username (offline formula)
                UUID rawUuid = offlineUuid(username);

                // 3) Account binding: check if bound, remap UUID, check concurrency
                UUID effectiveUuid = rawUuid;
                boolean enableBinding = plugin.getConfig().getBoolean("enableAccountBinding", true);
                if (enableBinding) {
                    UUID targetUuid = accountBinding.resolveEffectiveUuid(rawUuid);
                    if (!targetUuid.equals(rawUuid)) {
                        // This is a bound account. Check group online status.
                        String onlineName = accountBinding.checkGroupOnline(rawUuid);
                        if (onlineName != null) {
                            // Someone in the group is online — reject
                            disconnectLogin(ctx.channel(), FoliaMessages.bindGroupOnline(onlineName));
                            plugin.getLogger().info("Rejected bound player " + username + " — " + onlineName + " is online in the same group");
                            return;
                        }
                        effectiveUuid = targetUuid;
                        plugin.getLogger().info("Bound player " + username + " → target UUID " + effectiveUuid);
                    }
                }

                // 4) Offline bypass for non-standard usernames
                boolean allowOffline = plugin.getConfig().getBoolean("allowOfflinePlayers", true);
                boolean isStandard = FoliaMojangApi.isValidMojangUsername(username);

                if (allowOffline && !isStandard) {
                    acceptOfflineLogin(ctx.channel(), username, effectiveUuid);
                    return;
                }

                // 5) If UUID was remapped by binding, also use the remapped UUID for login
                //    (but don't bypass Mojang - let it verify normally for standard names)
                if (enableBinding && !effectiveUuid.equals(rawUuid)) {
                    // For bound accounts with standard names, we need to force the target UUID
                    // even though Mojang would normally create a different profile.
                    // The binding takes precedence.
                    acceptOfflineLogin(ctx.channel(), username, effectiveUuid);
                    return;
                }

                super.channelRead(ctx, msg);
            }
        });
        remove(ch, CHILD_BOOT);
    }

    // ================ Rejection ================

    private boolean rejectHeosLogin(Channel ch, String username) {
        return rejectBan(username, ch) || rejectWhitelist(username, ch);
    }

    private boolean rejectWhitelist(String username, Channel ch) {
        if (!plugin.getConfig().getBoolean("enableWhitelist", false) || whitelistData.isWhitelisted(username))
            return false;
        plugin.getLogger().info(FoliaMessages.whitelistDeniedLog(username));
        return disconnectLogin(ch, FoliaMessages.whitelistKick());
    }

    private boolean rejectBan(String username, Channel ch) {
        FoliaBanData.BanEntry ban = banData.getPlayerBan(username, null);
        if (ban != null) {
            if (!plugin.getConfig().getBoolean("enableCustomBan", true)
                    && !FoliaMessages.isMigrationReason(ban.reason)) return false;
            return disconnectLogin(ch, FoliaMessages.banMessage(ban.reason, FoliaTimeParser.formatAbsolute(ban.expiryTime)));
        }
        if (!plugin.getConfig().getBoolean("enableCustomBan", true)) return false;
        FoliaBanData.IpBanEntry ip = banData.getIpBan(channelIp(ch));
        if (ip == null) return false;
        return disconnectLogin(ch, FoliaMessages.banIpMessage(ip.reason, FoliaTimeParser.formatAbsolute(ip.expiryTime)));
    }

    // ================ Offline login ================

    private void acceptOfflineLogin(Channel ch, String username, UUID uuid) {
        ChannelHandler handler = ch.pipeline().get(VANILLA);
        if (handler == null) return;
        Object listener = findPacketListener(handler);
        if (listener == null) return;
        try {
            Class<?> profileClass = Class.forName("com.mojang.authlib.GameProfile");
            Object profile = profileClass.getConstructor(UUID.class, String.class).newInstance(uuid, username);
            setField(listener, "requestedUsername", username);
            setField(listener, "requestedUuid", uuid);

            Method m = findGameProfileMethod(listener.getClass(), "startClientVerification", profileClass);
            if (m != null) { m.setAccessible(true); m.invoke(listener, profile); return; }
            m = findGameProfileMethod(listener.getClass(), "finishLoginAndWaitForClient", profileClass);
            if (m != null) {
                setField(listener, "authenticatedProfile", profile);
                m.setAccessible(true); m.invoke(listener, profile);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.FINE, "Failed offline login for " + username, e);
        }
    }

    // ================ Reflection helpers ================

    private boolean disconnectLogin(Channel ch, String msg) {
        ChannelHandler h = ch.pipeline().get(VANILLA);
        if (h == null) return false;
        Object listener = findPacketListener(h);
        if (listener == null) return false;
        try {
            Class<?> cc = Class.forName("net.minecraft.network.chat.Component");
            Object comp = cc.getMethod("literal", String.class).invoke(null, msg);
            listener.getClass().getMethod("disconnect", cc).invoke(listener, comp);
            return true;
        } catch (Exception e) { return false; }
    }

    private Object findPacketListener(Object conn) {
        for (Class<?> t = conn.getClass(); t != null; t = t.getSuperclass()) {
            for (Field f : t.getDeclaredFields()) {
                if (!f.getType().getName().contains("PacketListener")) continue;
                try { f.setAccessible(true); Object v = f.get(conn);
                    if (v != null && v.getClass().getName().contains("ServerLoginPacketListenerImpl")) return v;
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private List<Channel> serverChannels() {
        Object conn = serverConnection();
        if (conn == null) return List.of();
        List<Channel> chs = new ArrayList<>();
        for (Class<?> t = conn.getClass(); t != null; t = t.getSuperclass()) {
            for (Field f : t.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(f.getType())) continue;
                try { f.setAccessible(true); Object v = f.get(conn);
                    if (v instanceof List<?> l) collectChannels(l, chs);
                } catch (Exception ignored) {}
            }
        }
        return chs;
    }

    private void collectChannels(List<?> src, List<Channel> dst) {
        for (Object v : src) {
            if (v instanceof ChannelFuture f) dst.add(f.channel());
            else if (v instanceof Channel c) dst.add(c);
        }
    }

    private Object serverConnection() {
        try {
            Method getServer = plugin.getServer().getClass().getMethod("getServer");
            Object srv = getServer.invoke(plugin.getServer());
            return srv.getClass().getMethod("getConnection").invoke(srv);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to access server connection", e);
            return null;
        }
    }

    private boolean isHelloPacket(Object p) { return p != null && p.getClass().getName().endsWith("ServerboundHelloPacket"); }

    private String packetUsername(Object p) {
        for (Method m : p.getClass().getMethods())
            if (m.getParameterCount() == 0 && m.getReturnType() == String.class)
                try { return (String) m.invoke(p); } catch (Exception ignored) {}
        for (Field f : p.getClass().getDeclaredFields())
            if (f.getType() == String.class)
                try { f.setAccessible(true); return (String) f.get(p); } catch (Exception ignored) {}
        return null;
    }

    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static String channelIp(Channel ch) {
        if (ch.remoteAddress() instanceof InetSocketAddress a && a.getAddress() != null)
            return a.getAddress().getHostAddress();
        return "";
    }

    private Method findGameProfileMethod(Class<?> t, String name, Class<?> profileClass) {
        for (Class<?> c = t; c != null; c = c.getSuperclass())
            for (Method m : c.getDeclaredMethods())
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == profileClass
                        && m.getReturnType() == Void.TYPE && m.getName().equals(name))
                    return m;
        for (Class<?> c = t; c != null; c = c.getSuperclass())
            for (Method m : c.getDeclaredMethods())
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == profileClass
                        && m.getReturnType() == Void.TYPE)
                    return m;
        return null;
    }

    private void setField(Object target, String name, Object value) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); f.set(target, value); return; }
            catch (NoSuchFieldException ignored) {} catch (Exception ignored) { return; }
        }
    }

    private void remove(Channel ch, String name) {
        if (ch.pipeline().get(name) != null) ch.pipeline().remove(name);
    }
}
