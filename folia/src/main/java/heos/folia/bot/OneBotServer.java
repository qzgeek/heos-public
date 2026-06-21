package heos.folia.bot;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.CharsetUtil;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Embedded OneBot WebSocket server for QQ bot integration.
 * Runs inside the LuoOS Folia plugin, sharing the same database.
 */
public class OneBotServer {
    private final Logger logger;
    private final String host;
    private final int port;
    private final String accessToken;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    /** Active connections: clientId -> session */
    final Map<String, OneBotSession> sessions = new ConcurrentHashMap<>();

    /** Event handler callback (called for incoming OneBot events) */
    private Consumer<OneBotEvent> eventHandler;

    public OneBotServer(Logger logger, String host, int port, String accessToken) {
        this.logger = logger;
        this.host = host;
        this.port = port;
        this.accessToken = accessToken;
    }

    public void setEventHandler(Consumer<OneBotEvent> handler) {
        this.eventHandler = handler;
    }

    public void start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new HttpServerCodec(),
                                    new HttpObjectAggregator(65536),
                                    new WebSocketHandler()
                            );
                        }
                    });
            serverChannel = b.bind(host, port).sync().channel();
            logger.info("[LuoOS-Bot] WebSocket server started on ws://" + host + ":" + port);
        } catch (Exception e) {
            logger.severe("[LuoOS-Bot] Failed to start WebSocket server: " + e.getMessage());
        }
    }

    public void stop() {
        if (serverChannel != null) serverChannel.close();
        if (bossGroup != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        sessions.clear();
        logger.info("[LuoOS-Bot] WebSocket server stopped");
    }

    /** Send raw JSON to a client */
    public void send(String clientId, String json) {
        OneBotSession session = sessions.get(clientId);
        if (session != null && session.channel.isActive()) {
            session.channel.writeAndFlush(new TextWebSocketFrame(json));
        }
    }

    /** Broadcast to all connected clients */
    public void broadcast(String json) {
        sessions.values().forEach(s -> {
            if (s.channel.isActive()) s.channel.writeAndFlush(new TextWebSocketFrame(json));
        });
    }

    private class WebSocketHandler extends SimpleChannelInboundHandler<Object> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof FullHttpRequest httpReq) {
                handleHttpRequest(ctx, httpReq);
            } else if (msg instanceof TextWebSocketFrame textFrame) {
                handleWebSocketFrame(ctx, textFrame);
            }
        }

        private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
            // Token auth
            String token = req.headers().get("Authorization");
            if (token != null && token.startsWith("Bearer ")) {
                token = token.substring(7);
            }
            if (accessToken != null && !accessToken.isEmpty() && !accessToken.equals(token)) {
                // Also check query param
                String uri = req.uri();
                if (uri.contains("access_token=")) {
                    token = uri.substring(uri.indexOf("access_token=") + 13);
                    if (token.contains("&")) token = token.substring(0, token.indexOf("&"));
                }
                if (!accessToken.equals(token)) {
                    ctx.writeAndFlush(new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED));
                    ctx.close();
                    return;
                }
            }

            WebSocketServerHandshakerFactory wsFactory =
                    new WebSocketServerHandshakerFactory(getWebSocketLocation(req), null, false);
            WebSocketServerHandshaker handshaker = wsFactory.newHandshaker(req);
            if (handshaker == null) {
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            } else {
                handshaker.handshake(ctx.channel(), req).addListener(future -> {
                    if (future.isSuccess()) {
                        OneBotSession session = new OneBotSession(ctx.channel());
                        sessions.put(session.clientId, session);
                        logger.info("[LuoOS-Bot] Client connected: " + session.clientId);
                    }
                });
            }
        }

        private void handleWebSocketFrame(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
            String text = frame.text();
            String clientId = findClientId(ctx.channel());
            if (clientId == null) return;

            try {
                com.google.gson.JsonObject json = new com.google.gson.Gson()
                        .fromJson(text, com.google.gson.JsonObject.class);
                if (json == null) return;

                // Check if it's a heartbeat
                if (json.has("meta_event_type") && "heartbeat".equals(json.getAsJsonPrimitive("meta_event_type").getAsString())) {
                    // Respond to heartbeat
                    com.google.gson.JsonObject resp = new com.google.gson.JsonObject();
                    resp.addProperty("status", "ok");
                    resp.addProperty("retcode", 0);
                    if (json.has("echo")) resp.add("echo", json.get("echo"));
                    ctx.writeAndFlush(new TextWebSocketFrame(new com.google.gson.Gson().toJson(resp)));
                    return;
                }

                // Handle API echo responses (resolve pending futures)
                if (json.has("echo") && json.has("status")) {
                    OneBotSession session = sessions.get(clientId);
                    if (session != null) {
                        session.resolvePending(json.get("echo").getAsString(), json);
                    }
                    return;
                }

                // Event dispatch
                if (json.has("post_type") && eventHandler != null) {
                    OneBotEvent event = new OneBotEvent(json, clientId, OneBotServer.this);
                    eventHandler.accept(event);
                }
            } catch (Exception e) {
                logger.warning("[LuoOS-Bot] Failed to parse message: " + e.getMessage());
            }
        }

        private String getWebSocketLocation(FullHttpRequest req) {
            return "ws://" + req.headers().get("Host") + req.uri();
        }

        private String findClientId(Channel channel) {
            for (Map.Entry<String, OneBotSession> e : sessions.entrySet()) {
                if (e.getValue().channel.equals(channel)) return e.getKey();
            }
            return null;
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            String clientId = findClientId(ctx.channel());
            if (clientId != null) {
                sessions.remove(clientId);
                logger.info("[LuoOS-Bot] Client disconnected: " + clientId);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.warning("[LuoOS-Bot] WebSocket error: " + cause.getMessage());
            ctx.close();
        }
    }
}
