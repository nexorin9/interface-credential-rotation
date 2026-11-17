package com.med.icr.ui;

import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** AWT 托盘常驻：窗口关闭收进托盘继续订阅；不支持时降级为常驻状态条，不抛异常。 */
public final class TrayController {
    private final boolean forceUnsupported;
    private FileChannel lockChannel;
    private FileLock lock;

    public TrayController() { this(false); }
    public TrayController(boolean forceUnsupported) { this.forceUnsupported = forceUnsupported; }

    public boolean supported() {
        if (forceUnsupported) return false;
        try {
            return !GraphicsEnvironment.isHeadless() && SystemTray.isSupported();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    public boolean install(Runnable immediateScan, Runnable openWindow, Runnable exit) {
        if (!supported()) return false;
        try {
            BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            image.getGraphics().setColor(new java.awt.Color(31, 78, 121));
            image.getGraphics().fillRect(0, 0, 16, 16);
            TrayIcon icon = new TrayIcon(image, "接口账号密钥轮换系统");
            java.awt.PopupMenu menu = new java.awt.PopupMenu();
            java.awt.MenuItem scan = new java.awt.MenuItem("立即扫描");
            scan.addActionListener(e -> immediateScan.run());
            java.awt.MenuItem open = new java.awt.MenuItem("打开主窗");
            open.addActionListener(e -> openWindow.run());
            java.awt.MenuItem quit = new java.awt.MenuItem("退出");
            quit.addActionListener(e -> exit.run());
            menu.add(scan); menu.add(open); menu.add(quit);
            icon.setPopupMenu(menu);
            SystemTray.getSystemTray().add(icon);
            return true;
        } catch (RuntimeException | java.awt.AWTException | LinkageError e) {
            return false;
        }
    }

    /** 托盘不支持环境的降级出路：顶部常驻状态条。 */
    public String degradeToStatusBar() {
        return "托盘不可用：已降级为顶部常驻状态条，订阅继续进行";
    }

    /** 单实例：再次启动时激活已有窗口而非新开。 */
    public boolean acquireSingleInstance(Path lockFile) {
        try {
            java.nio.file.Files.createDirectories(lockFile.toAbsolutePath().getParent());
            lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            return lock != null;
        } catch (Exception e) {
            return false;
        }
    }

    public void release() {
        try { if (lock != null) lock.release(); } catch (Exception ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (Exception ignored) { }
    }
}
