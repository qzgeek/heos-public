package heos.folia.bot;

import com.google.gson.JsonObject;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Per-connection state for OneBot WebSocket clients. */
public class OneBotSession {
    public final Channel channel;
    public final String clientId;
    final ConcurrentHashMap<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();

    public OneBotSession(Channel channel) {
        this.channel = channel;
        InetSocketAddress addr = (InetSocketAddress) channel.remoteAddress();
        this.clientId = addr.getAddress().getHostAddress() + ":" + addr.getPort();
    }

    public void resolvePending(String echo, JsonObject response) {
        CompletableFuture<JsonObject> future = pending.remove(echo);
        if (future != null) {
            future.complete(response);
        }
    }
}

/** Wrapper for incoming OneBot events with convenience API call methods. */
public class OneBotEvent {
    public final JsonObject raw;
    public final String clientId;
    private final OneBotServer server;

    public OneBotEvent(JsonObject raw, String clientId, OneBotServer server) {
        this.raw = raw;
        this.clientId = clientId;
        this.server = server;
    }

    // ---- Accessors ----
    public String postType() { return str("post_type"); }
    public String messageType() { return str("message_type"); }
    public long groupId() { return raw.has("group_id") ? raw.get("group_id").getAsLong() : 0; }
    public long userId() { return raw.has("user_id") ? raw.get("user_id").getAsLong() : 0; }
    public long messageId() { return raw.has("message_id") ? raw.get("message_id").getAsLong() : 0; }
    public String rawMessage() { return str("raw_message"); }
    public JsonObject sender() { return raw.has("sender") ? raw.getAsJsonObject("sender") : null; }
    public String senderRole() {
        JsonObject s = sender();
        return s != null && s.has("role") ? s.get("role").getAsString() : "member";
    }

    private String str(String key) {
        return raw.has(key) && !raw.get(key).isJsonNull() ? raw.get(key).getAsString() : "";
    }

    // ---- API calls ----
    public void reply(String message) {
        if (messageType().equals("group")) {
            callApi("send_group_msg", "{\"group_id\":" + groupId() + ",\"message\":[{\"type\":\"text\",\"data\":{\"text\":\""
                    + escape(message) + "\"}}]}");
        }
    }

    public void replyPrivate(String message) {
        callApi("send_private_msg", "{\"user_id\":" + userId() + ",\"message\":[{\"type\":\"text\",\"data\":{\"text\":\""
                + escape(message) + "\"}}]}");
    }

    public void replyAt(String message) {
        if (messageType().equals("group")) {
            callApi("send_group_msg", "{\"group_id\":" + groupId()
                    + ",\"message\":[{\"type\":\"at\",\"data\":{\"qq\":\"" + userId() + "\"}},"
                    + "{\"type\":\"text\",\"data\":{\"text\":\"" + escape(message) + "\"}}]}");
        } else {
            reply(message);
        }
    }

    public void react(boolean success) {
        int emoji = success ? 38 : 67;
        callApi("set_msg_emoji_like", "{\"message_id\":" + messageId() + ",\"emoji_id\":" + emoji + ",\"set\":true}");
    }

    /** Call OneBot API action */
    public CompletableFuture<JsonObject> callApiAsync(String action, String params) {
        OneBotSession session = server.sessions.get(clientId);
        if (session == null) return CompletableFuture.failedFuture(new RuntimeException("No session"));

        String echo = String.valueOf(System.nanoTime());
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        session.pending.put(echo, future);

        String request = "{\"action\":\"" + action + "\",\"params\":" + params + ",\"echo\":\"" + echo + "\"}";
        server.send(clientId, request);

        return future.completeOnTimeout(null, 10, TimeUnit.SECONDS);
    }

    public void callApi(String action, String params) {
        callApiAsync(action, params).exceptionally(e -> null);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
