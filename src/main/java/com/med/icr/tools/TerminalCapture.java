package com.med.icr.tools;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 真实终端回显位图化：读取实际执行 --cli 子命令时落盘的标准输出，
 * 逐行绘制成等宽终端风格 PNG（截图说明里标注来源，不用设计稿冒充）。
 */
public final class TerminalCapture {
    private TerminalCapture() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法: TerminalCapture <真实回显文本文件> <输出 png> [标题]");
            return;
        }
        Path input = Path.of(args[0]);
        Path out = Path.of(args[1]);
        String title = args.length > 2 ? args[2] : "真实终端回显";
        List<String> lines = Files.readAllLines(input, StandardCharsets.UTF_8);
        int width = 1100;
        int lineHeight = 24;
        int header = 56;
        int height = header + (lines.size() + 2) * lineHeight + 20;
        BufferedImage image = new BufferedImage(width, Math.max(height, 160), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0xF5, 0xF6, 0xF8));
        g.fillRect(0, 0, width, image.getHeight());
        g.setColor(new Color(0x1F, 0x4E, 0x79));
        g.fillRect(0, 0, width, header);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Menlo", Font.BOLD, 16));
        g.drawString(title, 20, 34);
        g.setColor(new Color(0x22, 0x22, 0x22));
        g.setFont(new Font("Menlo", Font.PLAIN, 14));
        int y = header + lineHeight;
        for (String line : lines) {
            String safe = line.length() > 120 ? line.substring(0, 120) : line;
            g.drawString(safe, 20, y);
            y += lineHeight;
        }
        g.dispose();
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        ImageIO.write(image, "png", out.toFile());
        System.out.println("终端回显截图已写入 " + out);
    }
}
