package heos.folia.bot;

import com.google.gson.JsonObject;
import org.java_websocket.WebSocket;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Per-connection state for OneBot WebSocket clients. */
public class OneBotSession {
    public final WebSocket conn;
    public final String clientId;
    final ConcurrentHashMap<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();

    public OneBotSession(WebSocket conn, String clientId) {
        this.conn = conn;
        this.clientId = clientId;
    }

    public void resolvePending(String echo, JsonObject response) {
        CompletableFuture<JsonObject> future = pending.remove(echo);
        if (future != null) {
            future.complete(response);
        }
    }
}
