package heos.folia.bot;

import com.google.gson.*;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/**
 * Minecraft server pinger and status formatter.
 * Ported from the original motd/motd.py.
 */
public class BotStatusService {
    private final Logger logger;
    private final String host;
    private final int port;
    private final String displayName;
    private final String description;

    public BotStatusService(Logger logger, String host, int port, String displayName, String description) {
        this.logger = logger;
        this.host = host;
        this.port = port;
        this.displayName = displayName;
        this.description = description;
    }

    record ServerStatus(boolean online, String version, int onlinePlayers, int maxPlayers,
                        String motd, List<String> playerNames, long latency, String error) {}

    public ServerStatus ping() {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(5000);

            // Handshake packet
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            writeVarInt(handshake, 0x00); // packet ID
            writeVarInt(handshake, -1);   // protocol version (-1 = auto)
            writeString(handshake, host);
            handshake.write(port >> 8); handshake.write(port & 0xFF);
            writeVarInt(handshake, 1);    // next state: status

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeVarInt(out, handshake.size());
            out.write(handshake.toByteArray());

            // Request packet
            ByteArrayOutputStream req = new ByteArrayOutputStream();
            writeVarInt(req, 1); // size
            req.write(0x00);     // packet ID

            out.write(req.toByteArray());
            socket.getOutputStream().write(out.toByteArray());
            socket.getOutputStream().flush();

            // Read response
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int len = readVarInt(in);
            int packetId = readVarInt(in);
            String json = readString(in);
            socket.close();

            long latency = System.currentTimeMillis() - start;
            return parseResponse(json, latency);
        } catch (Exception e) {
            return new ServerStatus(false, "", 0, 0, "", List.of(), -1, e.getMessage());
        }
    }

    private ServerStatus parseResponse(String jsonStr, long latency) {
        try {
            JsonObject o = JsonParser.parseString(jsonStr).getAsJsonObject();
            JsonObject ver = o.getAsJsonObject("version");
            JsonObject players = o.getAsJsonObject("players");
            JsonObject desc = o.getAsJsonObject("description");

            String motd = parseMotd(desc);
            int online = players.get("online").getAsInt();
            int max = players.get("max").getAsInt();

            List<String> names = new ArrayList<>();
            if (players.has("sample")) {
                for (JsonElement e : players.getAsJsonArray("sample")) {
                    names.add(e.getAsJsonObject().get("name").getAsString());
                }
            }

            return new ServerStatus(true, ver.get("name").getAsString(),
                    online, max, motd, names, latency, "");
        } catch (Exception e) {
            return new ServerStatus(false, "", 0, 0, "", List.of(), -1, "Parse error: " + e.getMessage());
        }
    }

    private String parseMotd(JsonObject desc) {
        if (desc.has("text")) return cleanColor(desc.get("text").getAsString());
        if (desc.has("extra")) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement e : desc.getAsJsonArray("extra")) {
                if (e.isJsonPrimitive()) sb.append(e.getAsString());
                else if (e.isJsonObject() && e.getAsJsonObject().has("text"))
                    sb.append(e.getAsJsonObject().get("text").getAsString());
            }
            return cleanColor(sb.toString());
        }
        return "";
    }

    private String cleanColor(String s) {
        return s.replaceAll("§[0-9a-fk-or]", "").replaceAll("\\\\u00a7[0-9a-fk-or]", "");
    }

    public String formatStatusText(ServerStatus info, Double cpu, Double mem) {
        if (!info.online) return "❌ 服务器离线: " + (info.error.isEmpty() ? "未知原因" : info.error);

        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(displayName).append(" ===\n");
        sb.append("🎮 版本: ").append(info.version).append("\n");
        sb.append("👥 在线: ").append(info.onlinePlayers).append("/").append(info.maxPlayers).append("\n");
        sb.append("⏱ 延迟: ").append(info.latency).append("ms\n");
        if (!info.motd.isEmpty()) sb.append("📝 MOTD: ").append(info.motd).append("\n");
        if (cpu != null) sb.append("💻 CPU: ").append(String.format("%.1f%%", cpu)).append("\n");
        if (mem != null) sb.append("🧠 内存: ").append(String.format("%.1f%%", mem)).append("\n");
        if (!info.playerNames.isEmpty()) {
            sb.append("👤 在线玩家: ");
            int show = Math.min(info.playerNames.size(), 15);
            for (int i = 0; i < show; i++) {
                sb.append(info.playerNames.get(i));
                if (i < show - 1) sb.append(", ");
            }
            if (info.playerNames.size() > 15) sb.append(" ...等").append(info.playerNames.size()).append("人");
            sb.append("\n");
        }
        sb.append("\nWrite by 黔中极客 / LuoOS");
        return sb.toString();
    }


    public byte[] renderLocal() {
        Runtime rt = Runtime.getRuntime();
        double mem = (rt.totalMemory() - rt.freeMemory()) * 100.0 / rt.maxMemory();
        double cpu = -1;
        String ver = "1.21.11";

        BotCardRenderer renderer = new BotCardRenderer(1500, 700);

        BufferedImage icon = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D ig = icon.createGraphics();
        ig.setColor(Color.decode("#2C3E50")); ig.fillRect(0, 0, 64, 64);
        ig.setColor(Color.decode("#5D6D7E")); ig.fillOval(4, 4, 56, 56);
        ig.dispose();

        java.util.List<String> bottom = java.util.List.of(
                "查询时间：" + java.time.LocalDateTime.now().toString().replace("T", " ").substring(0, 19),
                "Write by 黔中极客 / LuoOS");

        BufferedImage background = null;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("background.png")) {
            if (is != null) background = ImageIO.read(is);
        } catch (Exception ignored) {}

        BufferedImage card = renderer.render(displayName, icon, "127.0.0.1:25565",
                0, ver, description, description, 0, 20, bottom, cpu, mem, background, java.util.List.of());

        try { return renderer.toPngBytes(card); }
        catch (Exception e) { logger.warning("Card render failed: " + e.getMessage()); return null; }
    }

    // VarInt encoding (from Minecraft protocol)
    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int result = 0, shift = 0;
        byte b;
        do {
            b = in.readByte();
            result |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    private static void writeString(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.write(b, 0, b.length);
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = readVarInt(in);
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
