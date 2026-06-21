package heos.folia.bot;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.font.TextAttribute;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.AttributedString;
import java.util.*;
import java.util.List;

/**
 * Minecraft server status card renderer — ported from motd/status_card.py.
 * Uses Java Graphics2D instead of PIL.
 */
public class BotCardRenderer {
    private final int cw, ch;
    private final Map<String, Color> theme;
    private final Map<String, Double> threshold;
    private Font titleFont, normalFont, motdFont, bottomFont;
    private static final int ICON_W = 190, ICON_H = 190, ICON_R = 24;
    private static final int LINE_GAP = 52, BAR_H = 75, BASE_LINES = 7;

    // MC color codes
    private static final Map<Character, Color> MC_COLORS = Map.ofEntries(
            Map.entry('0', Color.decode("#000000")), Map.entry('1', Color.decode("#0000AA")),
            Map.entry('2', Color.decode("#00AA00")), Map.entry('3', Color.decode("#00AAAA")),
            Map.entry('4', Color.decode("#AA0000")), Map.entry('5', Color.decode("#AA00AA")),
            Map.entry('6', Color.decode("#FFAA00")), Map.entry('7', Color.decode("#AAAAAA")),
            Map.entry('8', Color.decode("#555555")), Map.entry('9', Color.decode("#5555FF")),
            Map.entry('a', Color.decode("#55FF55")), Map.entry('b', Color.decode("#55FFFF")),
            Map.entry('c', Color.decode("#FF5555")), Map.entry('d', Color.decode("#FF55FF")),
            Map.entry('e', Color.decode("#FFFF55")), Map.entry('f', Color.decode("#FFFFFF")),
            Map.entry('r', Color.decode("#FFFFFF"))
    );

    private static final Map<String, Color> DEFAULT_THEME = Map.of(
            "main_bg_mask", new Color(0x0F, 0x25, 0x40, 204),
            "bottom_bar_bg", new Color(0x05, 0x12, 0x25, 224),
            "text_main", Color.decode("#D0E0FF"), "text_sub", Color.decode("#B0C4DE"),
            "text_bottom", Color.decode("#607090"), "success", Color.decode("#55FF55"),
            "warning", Color.decode("#FFFF55"), "danger", Color.decode("#FF5555")
    );
    private static final Map<String, Double> DEFAULT_THRESHOLD = Map.of(
            "cpu_warning", 50.0, "cpu_danger", 80.0,
            "mem_warning", 50.0, "mem_danger", 80.0
    );

    public BotCardRenderer(int w, int h) {
        this.cw = w; this.ch = h;
        this.theme = DEFAULT_THEME; this.threshold = DEFAULT_THRESHOLD;
        initFonts();
    }

    private void initFonts() {
        Font base = null;
        // Try embedded font from plugin jar
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("LXGWWenKaiMono-Medium.ttf")) {
            if (is != null) {
                base = Font.createFont(Font.TRUETYPE_FONT, is).deriveFont(36f);
            }
        } catch (Exception ignored) {}
        // Fallback: system fonts
        if (base == null) {
            String[] candidates = {"/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
                    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"};
            for (String c : candidates) {
                try { base = Font.createFont(Font.TRUETYPE_FONT, new java.io.File(c)).deriveFont(36f); break; }
                catch (Exception ignored) {}
            }
        }
        if (base == null) base = new Font("SansSerif", Font.PLAIN, 36);
        titleFont = base.deriveFont(48f);
        normalFont = base.deriveFont(36f);
        motdFont = base.deriveFont(40f);
        bottomFont = base.deriveFont(24f);
    }

    public BufferedImage render(String serverName, BufferedImage icon, String address,
                                int ping, String version, String motd, String intro,
                                int online, int max, List<String> bottomLines,
                                double cpu, double mem, BufferedImage background,
                                List<String> playerLines) {
        cpu = Math.max(0, Math.min(100, cpu));
        mem = Math.max(0, Math.min(100, mem));
        if (playerLines == null) playerLines = List.of();

        // Scale and crop background
        BufferedImage bg = scaleCrop(background, cw, ch);
        BufferedImage canvas = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.drawImage(bg, 0, 0, null);

        // Main area mask
        int mainH = ch - BAR_H;
        g.setColor(theme.get("main_bg_mask"));
        g.fillRect(0, 0, cw, mainH);

        // Icon
        int iconX = 80, iconY = (mainH - ICON_H) / 2;
        if (icon != null) {
            BufferedImage scaled = new BufferedImage(ICON_W, ICON_H, BufferedImage.TYPE_INT_ARGB);
            Graphics2D ig = scaled.createGraphics();
            ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ig.setClip(new RoundRectangle2D.Float(0, 0, ICON_W, ICON_H, ICON_R, ICON_R));
            ig.drawImage(icon, 0, 0, ICON_W, ICON_H, null);
            ig.dispose();
            g.drawImage(scaled, iconX, iconY, null);
        }

        // Text area
        int totalLines = BASE_LINES + playerLines.size();
        int textStartY = iconY + ICON_H / 2 - (totalLines * LINE_GAP) / 2;
        int textX = iconX + ICON_W + 45;
        int y = textStartY;

        // Line 1: Server name
        drawText(g, serverName, textX, y, titleFont, theme.get("text_main")); y += LINE_GAP;

        // Line 2: IP (no external ping needed since bot runs on same machine)
        drawText(g, "IP：" + address, textX, y, normalFont, theme.get("text_sub")); y += LINE_GAP;

        // Line 3: Version
        drawText(g, "版本: " + version, textX, y, normalFont, theme.get("text_sub")); y += LINE_GAP;

        // Line 4: MOTD with colors
        drawMcText(g, motd, textX, y, motdFont); y += LINE_GAP;

        // Line 5: Intro
        drawText(g, intro, textX, y, normalFont, theme.get("text_main")); y += LINE_GAP;

        // Line 6: Players
        drawText(g, "在线人数: " + online + "/" + max, textX, y, normalFont, theme.get("text_sub")); y += LINE_GAP;

        // Line 7: CPU + MEM
        Color cpuC = metricColor(cpu, "cpu"), memC = metricColor(mem, "mem");
        String cpuLabel = "CPU：", memLabel = "内存：";
        int cx = textX;
        drawText(g, cpuLabel, cx, y, normalFont, theme.get("text_sub"));
        cx += g.getFontMetrics(normalFont).stringWidth(cpuLabel);
        drawText(g, String.format("%.1f%%", cpu), cx, y, normalFont, cpuC);
        cx += g.getFontMetrics(normalFont).stringWidth(String.format("%.1f%%", cpu));
        drawText(g, "  -  ", cx, y, normalFont, theme.get("text_sub"));
        cx += g.getFontMetrics(normalFont).stringWidth("  -  ");
        drawText(g, memLabel, cx, y, normalFont, theme.get("text_sub"));
        cx += g.getFontMetrics(normalFont).stringWidth(memLabel);
        drawText(g, String.format("%.1f%%", mem), cx, y, normalFont, memC);
        y += LINE_GAP;

        // Player lines
        for (String pl : playerLines) {
            drawText(g, pl, textX, y, normalFont, theme.get("text_sub"));
            y += LINE_GAP;
        }

        // Bottom bar
        g.setColor(theme.get("bottom_bar_bg"));
        g.fillRect(0, ch - BAR_H, cw, BAR_H);
        int bottomY = ch - BAR_H + 12;
        for (String bl : bottomLines) {
            int bw = g.getFontMetrics(bottomFont).stringWidth(bl);
            drawText(g, bl, (cw - bw) / 2, bottomY, bottomFont, theme.get("text_bottom"));
            bottomY += 28;
        }

        g.dispose();
        return canvas;
    }

    public byte[] toPngBytes(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        return baos.toByteArray();
    }

    // --- Helpers ---
    private void drawText(Graphics2D g, String text, int x, int y, Font font, Color color) {
        g.setFont(font); g.setColor(color);
        g.drawString(text, x, y + font.getSize());
    }

    private void drawMcText(Graphics2D g, String text, int x, int y, Font font) {
        List<ColoredSegment> segs = parseMcColors(text);
        int cx = x;
        g.setFont(font);
        for (ColoredSegment s : segs) {
            g.setColor(s.color);
            g.drawString(s.text, cx, y + font.getSize());
            cx += g.getFontMetrics().stringWidth(s.text);
        }
    }

    private List<ColoredSegment> parseMcColors(String text) {
        List<ColoredSegment> segs = new ArrayList<>();
        if (text == null || text.isEmpty()) { segs.add(new ColoredSegment("", MC_COLORS.get('f'))); return segs; }
        StringBuilder buf = new StringBuilder();
        Color cur = MC_COLORS.get('f');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                if (buf.length() > 0) { segs.add(new ColoredSegment(buf.toString(), cur)); buf.setLength(0); }
                Color nc = MC_COLORS.get(Character.toLowerCase(text.charAt(i + 1)));
                if (nc != null) cur = nc;
                i++;
            } else { buf.append(c); }
        }
        if (buf.length() > 0) segs.add(new ColoredSegment(buf.toString(), cur));
        return segs;
    }

    private Color pingColor(int ping) {
        if (ping <= 60) return theme.get("success");
        if (ping <= 500) return theme.get("warning");
        return theme.get("danger");
    }

    private Color metricColor(double val, String type) {
        if ("cpu".equals(type)) {
            if (val >= threshold.get("cpu_danger")) return theme.get("danger");
            if (val >= threshold.get("cpu_warning")) return theme.get("warning");
        } else {
            if (val >= threshold.get("mem_danger")) return theme.get("danger");
            if (val >= threshold.get("mem_warning")) return theme.get("warning");
        }
        return theme.get("success");
    }

    private static BufferedImage scaleCrop(BufferedImage img, int w, int h) {
        if (img == null) {
            BufferedImage bg = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = bg.createGraphics();
            g.setColor(Color.decode("#1A252F")); g.fillRect(0, 0, w, h); g.dispose();
            return bg;
        }
        double br = (double) img.getWidth() / img.getHeight();
        double cr = (double) w / h;
        int nw, nh;
        if (br > cr) { nh = h; nw = (int)(nh * br); }
        else { nw = w; nh = (int)(nw / br); }
        BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, nw, nh, null); g.dispose();
        BufferedImage crop = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        g = crop.createGraphics();
        g.drawImage(scaled, -(nw - w) / 2, -(nh - h) / 2, null); g.dispose();
        return crop;
    }

    private record ColoredSegment(String text, Color color) {}
}
