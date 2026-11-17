# 架构与关键不变量

> 本文件是技术细节的唯一落点；README 只保留一页速查与指路链接。

## 主链路（ASCII）

```
ESB 失败事件流 ──GET /api/v1/interface-failures?since=&limit=──▶ 订阅轮询（30s，since 游标落 SQLite）
      │                                                              │
      ▼                                                              ▼
先分派后解析（FailureEventParser）  ──▶  背压队列（offer 拒收不计数 / offerOrReject 拒收并计数）
      │                                                              │
      ▼                                                              ▼
脱敏（Masking） ──▶ 入队计数 / 水位（≥80% 置「即将拒收」） ──▶ 无界归档档（保留期默认 90 天）
      │
      ▼
指纹为主 + 通道号为辅绑定（冲突或缺任一 → unresolved，不产生结论卡）
      │
      ▼
时间窗归并（半开区间 [start, start+5min)） ──▶ 规则基线 / 模型管道（preprocess → model → postprocess）
      │
      ▼
根因结论卡（草稿）─ 人工勾选 + 二次确认 ─▶ 处置单写回 POST /api/v1/credential-tickets
      │                                      （幂等键 = sha256(长度前缀(credential_id) + "|" + ISO8601(windowStart))）
      ▼
厂商换证 ─▶ 真实 TLS / HTTP 探测 ─▶ 结论回写 POST /api/v1/credential-verifications
      │                                      │
      ▼                                      ▼
探测成功 + 写回成功才推进「已验证」     写回失败：票据保持待确认，界面提示可重试
```

## 双状态机与跃迁表

凭据状态：`生效 → 临期 → 已过期 → 待轮换 → 已轮换 → 已验证`

| from | 合法 to | 说明 |
|---|---|---|
| 生效 | 临期、已过期 | 允许跳过临期直接过期 |
| 临期 | 已过期 | |
| 已过期 | 待轮换 | |
| 待轮换 | 已轮换、生效 | 「生效」为撤销轮换 |
| 已轮换 | 已验证 | **唯一入口**：`CredentialStateMachine.transitionToVerified(from, "PROBE_VERIFIED", true)` |
| 已验证 | 无 | 终态 |

非法跃迁抛 `CRED_STATE_ILLEGAL_TRANSITION`，异常带 `from` / `to`。

处置单状态：`待确认 → 已派发 → 已关闭`（另允许「待确认 → 已关闭」撤销未派发单）。非法跃迁抛
`TICKET_STATE_ILLEGAL_TRANSITION`。**模型输出与降级路径一律不推进任何状态**。

## 幂等键公式

```
canonical = len(credential_id) + ":" + credential_id + "|" + ISO8601_UTC(故障窗口起始)
idempotency_key = sha256(canonical)            // 64 位十六进制
```

长度前缀保证 `credential_id` 含分隔符时也不产生歧义；`T7` 的 `IdempotencyKey.compute` 是唯一实现，
`T36` 的处置单写回与 `T31` 的对比基准都引用它。存储层 `ticket.idempotency_key` 为 `PRIMARY KEY`，
冲突时 `INSERT OR IGNORE` 返回既有单号。

## 凭据指纹

```
fingerprint = HMAC-SHA256(FINGERPRINT_KEY, credential_id + "|" + secret).substring(0, 16)
```

密钥来自环境变量且无默认值；只存 16 位十六进制，比较走 `MessageDigest.isEqual` 恒定时间语义。
任何表、日志、导出文件都不写 `secret` 明文。

## 领域不变量清单（`DomainInvariantTest` 穷举）

1. 状态跃迁闭合：6×6 组合要么合法，要么抛带码异常。
2. 幂等键稳定：同一 `(credential_id, windowStart)` 恒等；不同窗口必不同；含分隔符 id 无歧义。
3. 指纹不含明文：同 id 同 secret 恒等；截断长度 16；全库检索明文命中数 0。
4. 到期分级与状态机一致：状态「已过期」必然分级为红；`expires_at` 缺失归「无期限」而非已过期。
5. 处置单幂等键唯一：同窗口 10 次写入后 `ticket` 表只 1 行。

## 模型降级路径

```
ModelGateway.infer(ModelRequest)
  ├─ 5xx / 429 → 指数退避重试 ≤2 次 → 仍失败：MODEL_HTTP_5XX / MODEL_HTTP_429
  ├─ 4xx → MODEL_CONFIG_ERROR（直接降级，不重试）
  ├─ 超时 / 网络不可达 → MODEL_TIMEOUT / MODEL_UNREACHABLE
  └─ 响应不可解析、缺字段、越界、证据引用不存在 → MODEL_BAD_OUTPUT
                    ↓ 全部收敛到
回落规则归因（RootCauseCard.asDegraded）→ 卡片标注「本次未使用模型归因」→ 置信度下调
                    ↓
           状态一律不推进；人工确认闸门仍然必须勾选 + 二次确认
```

## 对接层与替身边界

| 端点 | 方法 | 鉴权位 | 幂等头 |
|---|---|---|---|
| `{ESB_BASE_URL}/api/v1/interface-failures` | GET | `authorization: bearer $ESB_TOKEN` | — |
| `{CREDENTIAL_BASE_URL}/api/v1/integration-credentials` | GET | `authorization: bearer $CREDENTIAL_TOKEN` | — |
| `{CREDENTIAL_BASE_URL}/api/v1/credential-tickets` | POST | `authorization: bearer $CREDENTIAL_TOKEN` | `idempotency-key` |
| `{CREDENTIAL_BASE_URL}/api/v1/credential-verifications` | POST | `authorization: bearer $CREDENTIAL_TOKEN` | — |

生产代码里的 base URL 全部来自环境变量，没有 `localhost` / `127.0.0.1` / `example.com` 默认值；
本地 HTTP 替身只存在于 `src/test`，用于契约测试与错误语义测试，不作为最终对接面。

## 容量与存储演进

- 有界队列扛突发，无界归档档留证；`QueueWatermark` 深度 ≥ 80% 容量时置「即将拒收」。
- 事件归档按 `occurred_at` 保留 90 天（可配），清理条数写审计。
- `since` 游标持久化在 `subscription_cursor` 表，重启不重拉也不漏拉。
