# CLI 运维通道参考

> 主入口是桌面窗口（`mvn -q javafx:run`）；CLI 只用于排障、批处理与无人值守复核，与桌面共用同一业务层。
> 全量参数以本文件为准，README 只留速查。

## 调用形态

```bash
java -jar target/interface-credential-rotation.jar --cli <子命令> [参数]
# 等价的类路径调用（离线机器手工拼装时用）
java -cp "target/interface-credential-rotation.jar:target/dependency/*" com.med.icr.Main --cli <子命令> [参数]
```

jar 清单已写入 `Class-Path: dependency/…`，所以 **`target/dependency/` 必须与 jar 一起拷贝**，
只拷单个 jar 会在加载 Jackson 时失败。桌面入口不用这条命令，走 `mvn -q javafx:run` 或 `jpackage` 产物。

## 运维子命令

| 子命令 | 作用 | 关键参数 | 是否外发请求 |
|---|---|---|---|
| `sync` | 同步凭据台账与失败事件流，回放入站管线并输出结论卡 | `--input`（默认 `samples/failures-4w.jsonl`） | 配置台账端点时读台账；否则读本地样例 |
| `scan` | 到期扫描，输出「今天先换哪张」清单 | `--warn-days`（默认 7）、`--as-of <ISO8601>`、`--export <csv>` | 配置台账端点时 GET 台账 |
| `probe` | 真实连通性自检（GET /health 与 TLS 握手语义） | `--endpoint` | 有端点时发真实 GET；只读，不写 |
| `ticket` | 人工确认后派发处置单 | `--confirm`（不带则拒绝外发）、`--credential-id`、`--window-start` | 仅带 `--confirm` 且台账已配置时 POST |
| `doctor` | 打印 11 项配置齐全性；可选只读可达性 | `--check-reachability` | 默认**不发任何网络请求**，加参数才做只读探测 |
| `audit` | 导出审计与模型调用元数据 | `--from`、`--to`、`--export <csv>` | 只读本地状态库 |
| `attribution` | 读本地 JSONL 样例，离线复核根因结论卡 | `--input`（默认 `samples/failures-sample.jsonl`） | 纯本地，不发请求 |

另有 `--help`：打印入口说明与全部子命令名，不加载 JavaFX 运行时。

## 典型运维用法

```bash
# 早班核对配置：不触网、不回显任何密钥
java -jar target/interface-credential-rotation.jar --cli doctor

# 换证季固定口径复跑（把系统时间固定到样例基准）
java -jar target/interface-credential-rotation.jar --cli scan --as-of 2026-10-12T00:00:00Z --warn-days 7 --export docs/expiry-list.csv

# 回放 4 周离线样例，看入队 / 拒收 / 结论卡
java -jar target/interface-credential-rotation.jar --cli sync --input samples/failures-4w.jsonl

# 未带确认参数的派单一律被拒绝
java -jar target/interface-credential-rotation.jar --cli ticket
```

## 退出码语义

| 退出码 | 含义 |
|---|---|
| 0 | 命令成功；`probe` 探测未通过也返回 0（结论已结构化输出，交由人工处置） |
| 1 | 命令执行异常或外发写回失败（本地状态保持未派发，可重试） |
| 2 | 参数错误：未知子命令，或 `ticket` 缺少 `--confirm` |

## 打包（院内拷贝即用）

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
mvn -q dependency:go-offline           # 有网机器上先跑一次，离线机器可整目录拷 ~/.m2
mvn -q test                            # 发布前跑全量测试
mvn -q -DskipTests package             # 产出 jar + target/dependency/
sh scripts/package.sh mac             # macOS 产出 dist/*.app
# Windows 侧：同命令在 Windows + JDK 17 上执行，把 --type 改为 msi 或 exe（需 WiX）
```

院内安装按四步走：

1. 把 `target/interface-credential-rotation.jar` 与整个 `target/dependency/` 拷到同一目录，`samples/` 一并带上。
2. 复制 `.env.example` 为 `.env`，填院内真实地址与令牌，由 shell 或系统服务导出为环境变量；**不要提交 `.env`**。
3. `java -jar interface-credential-rotation.jar --cli doctor` 核对 11 项配置齐全性（默认不触网）。
4. `mvn -q javafx:run` 启动桌面常驻端；无人值守复核用 `--cli scan` / `--cli audit`。

`target/`、`dist/`、`.localstate/`、`logs/` 与 `.env` 均由 `.gitignore` 排除；发布仓库只带源码与文档。
