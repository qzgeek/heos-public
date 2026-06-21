package heos.folia.bot;

import com.google.gson.JsonObject;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class OneBotServer extends WebSocketServer {
    private final Logger logger;
    private final String accessToken;
    private Consumer<OneBotEvent> eventHandler;
    final Map<String, OneBotSession> sessions = new ConcurrentHashMap<>();

    public OneBotServer(Logger logger, String host, int port, String accessToken) {
        super(new InetSocketAddress(host, port));
        this.logger = logger;
        this.accessToken = accessToken;
        setReuseAddr(true);
    }

    public void setEventHandler(Consumer<OneBotEvent> h) { this.eventHandler = h; }

    @Override
    public void onStart() {
        logger.info("[LuoOS-Bot] WebSocket server started on ws://" + getAddress().getHostName() + ":" + getPort());
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        if (accessToken != null && !accessToken.isEmpty()) {
            String tok = handshake.getFieldValue("Authorization");
            if (tok != null && tok.startsWith("Bearer ")) tok = tok.substring(7).trim();
            if (!accessToken.equals(tok)) {
                String q = handshake.getResourceDescriptor();
                if (q != null && q.contains("access_token=")) {
                    tok = q.substring(q.indexOf("access_token=") + 13);
                    if (tok.contains("&")) tok = tok.substring(0, tok.indexOf("&"));
                }
                if (!accessToken.equals(tok)) { conn.close(1008, "Token fail"); return; }
            }
        }
        String cid = conn.getRemoteSocketAddress().toString().replace("/", "");
        sessions.put(cid, new OneBotSession(conn, cid));
        logger.info("[Bot] Connected: " + cid);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        OneBotSession s = sessions.values().stream().filter(ss -> ss.conn == conn).findFirst().orElse(null);
        if (s != null) { sessions.remove(s.clientId); logger.info("[Bot] Disconnected: " + s.clientId); }
    }

    @Override
    public void onMessage(WebSocket conn, String text) {
        if (text == null || text.isBlank() || text.trim().equals("null")) return;
        OneBotSession s = findSession(conn);
        if (s == null) return;

        try {
            logger.info("[Bot] RX: " + (text.length() > 200 ? text.substring(0, 200) + "..." : text));
            JsonObject json = new com.google.gson.Gson().fromJson(text, JsonObject.class);
            if (json == null) return;

            if (json.has("meta_event_type") && "heartbeat".equals(
                    json.getAsJsonPrimitive("meta_event_type").getAsString())) {
                // Heartbeat — client tells us it is alive. No response needed.
                return;
            }
            if (json.has("meta_event_type")) {
                // Other meta events (lifecycle, etc.) — acknowledge silently
                return;
            }
            if (json.has("echo") && json.has("status")) {
                s.resolvePending(json.get("echo").getAsString(), json);
                return;
            }
            if (json.has("action")) {
                logger.info("[Bot] API action: " + json.get("action").getAsString());
                handleApiAction(s.clientId, json);
                return;
            }
            if (json.has("post_type") && eventHandler != null) {
                eventHandler.accept(new OneBotEvent(json, s.clientId, this));
            }
        } catch (com.google.gson.JsonParseException ignored) {
        } catch (Exception e) {
            // Ignore parse errors on non-JSON messages
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {}
    @Override
    public void onError(WebSocket conn, Exception ex) {
        logger.warning("[Bot] Error: " + ex.getMessage());
    }

    private void handleApiAction(String clientId, JsonObject json) {
        String action = json.get("action").getAsString();
        JsonObject resp = new JsonObject(); resp.addProperty("status","ok"); resp.addProperty("retcode",0);
        if (json.has("echo") && !json.get("echo").isJsonNull()) resp.add("echo",json.get("echo"));

        switch (action) {
            case "get_login_info" -> {
                JsonObject d = new JsonObject(); d.addProperty("user_id",10000); d.addProperty("nickname","LuoOS Bot"); resp.add("data",d);
            }
            case "can_send_image", "can_send_record" -> resp.addProperty("data", true);
            case "get_status" -> {
                JsonObject d = new JsonObject(); d.addProperty("online",true); d.addProperty("good",true); resp.add("data",d);
            }
            case "get_version_info" -> {
                JsonObject d = new JsonObject(); d.addProperty("app_name","LuoOS"); d.addProperty("app_version","0.06"); d.addProperty("protocol_version","v11"); resp.add("data",d);
            }
            default -> { resp.addProperty("status","failed"); resp.addProperty("retcode",1404); resp.addProperty("msg","unsupported: "+action); }
        }
        send(clientId, new com.google.gson.Gson().toJson(resp));
    }

    public void send(String clientId, String json) {
        OneBotSession s = sessions.get(clientId);
        if (s != null && s.conn.isOpen()) s.conn.send(json);
    }

    private OneBotSession findSession(WebSocket conn) {
        for (OneBotSession s : sessions.values()) if (s.conn == conn) return s;
        return null;
    }

    public void startServer() {
        try { start(); } catch (Exception e) { logger.severe("[Bot] WS start failed: "+e.getMessage()); }
    }

    public void stopServer() {
        try { stop(1000); } catch (Exception ignored) {}
    }
}
