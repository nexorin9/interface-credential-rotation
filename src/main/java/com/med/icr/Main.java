package com.med.icr;

import com.med.icr.cli.Cli;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

/**
 * 唯一入口：默认拉起 JavaFX 桌面窗口；--cli &lt;cmd&gt; 进运维通道。
 * CLI 路径不加载任何 JavaFX 类，便于在纯命令行环境跑 doctor / scan。
 */
public final class Main {
    public static final List<String> COMMANDS = List.of(
            "sync", "scan", "probe", "ticket", "doctor", "audit", "attribution");

    private Main() {}

    public static void main(String[] args) {
        if (args.length > 0 && "--cli".equals(args[0])) {
            String[] rest = Arrays.copyOfRange(args, 1, args.length);
            int code = Cli.run(rest, System.out, System.err);
            if (code != 0) System.exit(code);
            return;
        }
        if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            printHelp(System.out);
            return;
        }
        try {
            // 反射加载，保证 CLI 分支不触碰 JavaFX 运行时
            Class<?> app = Class.forName("com.med.icr.ui.DesktopApp");
            app.getMethod("launch", String[].class).invoke(null, (Object) args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("桌面环境不可用，回落到配置自检: " + e.getClass().getSimpleName());
            Cli.run(new String[]{"doctor"}, System.out, System.err);
        }
    }

    public static void printHelp(PrintStream out) {
        out.println("接口账号密钥轮换系统");
        out.println("用法: java -jar interface-credential-rotation.jar [--cli <子命令> [参数]]");
        out.println("默认: 启动桌面窗口（窗口关闭后收进托盘继续订阅）");
        out.println("可用子命令: " + String.join(" / ", COMMANDS));
        out.println("  sync          同步凭据台账与失败事件流");
        out.println("  scan          到期扫描：今天先换哪张证");
        out.println("  probe         真实连通性自检（TLS / HTTP）");
        out.println("  ticket        人工确认后派发处置单（必须带 --confirm）");
        out.println("  doctor        配置齐全性与端点可达性自检");
        out.println("  audit         导出审计记录");
        out.println("  attribution   读本地样例输出根因结论卡");
    }
}
