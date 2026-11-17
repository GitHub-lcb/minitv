# public-commerce 模块移植说明（Java / Spring Boot 3 + MyBatis）

行为标准：`backend/app/modules/public_flow.py`（13 个端点）及其调用的
`playback/service.py`、`wallet/service.py`、`billing/service.py`、
`content/upload_service.py`、`common/bunny.py`。
包：`us.minitv.pcommerce`；Mapper XML：`resources/mapper/pcommerce_*.xml`。

## 一、已完成接口（13/13）

| # | 方法 | 路径 | 实现位置 |
|---|---|---|---|
| 1 | POST | `/v1/playback` | `PCommercePlaybackController#authorize` → `PCommercePlaybackService#authorize` |
| 2 | POST | `/v1/playback/start` | `PCommercePlaybackController#start` → `PCommercePlaybackService#recordEvent` |
| 3 | POST | `/v1/playback/progress` | 同上（kind=progress） |
| 4 | POST | `/v1/playback/complete` | 同上（kind=complete） |
| 5 | POST | `/v1/unlock` | `PCommerceWalletController#unlock` → `PCommerceWalletService#unlockEpisode` |
| 6 | GET | `/v1/plans` | `PCommerceBillingController#plans` → `PCommerceBillingService#listCurrentPlans/billingMode` |
| 7 | GET | `/v1/me/orders` | `PCommerceBillingController#orders` → `PCommerceBillingService#listUserOrders` |
| 8 | POST | `/v1/billing/webhook` | `PCommerceBillingController#webhook` → `PCommerceBillingService#applyWebhook` |
| 9 | POST | `/v1/billing/cancel` | `PCommerceBillingController#cancel` → `PCommerceBillingService#cancelSubscription` |
| 10 | POST | `/v1/billing/checkout` | `PCommerceBillingController#checkout` → `PCommerceBillingService#createCheckoutSession` |
| 11 | POST | `/v1/billing/stripe/webhook` | `PCommerceBillingController#stripeWebhook` → `PCommerceBillingService#applyStripeEvent` |
| 12 | GET | `/v1/dramas/{drama_id}/play` | `PCommercePlaybackController#dramaPlay` → `PCommercePlaybackService#dramaPlay` |
| 13 | POST | `/v1/bunny/callback` | `PCommerceBunnyController#callback` → `PCommerceBunnyCallbackService#applyCallback` |

对齐要点（逐项照抄 Python，未做“优化”）：

- 响应键名/嵌套结构/null 字段全部一致；错误 body 统一 `{"detail": ...}`
  （字符串或 `{code,message}`，402 为 `{"need","have"}` 对象）。
- 播放鉴权：free（episode_number ≤ free_episode_count）/ 会员（`access_until` = max(订阅期末, pass_end) 且 > now）/ coin entitlement 三选一；
  `not_available` → 404，`region_blocked` / `locked` → 200 + `allowed:false`；locked 携带
  `unlock:{coin_price, membership_available:true}`。
- 播放令牌：HMAC-SHA256 `path|expires|gen`，TTL 300s，URL 为
  `{PLAYBACK_CDN_BASE}{path}?token=&expires=&gen=`；fake 模式视频 `PLAYBACK_TOKEN_KEY`、
  字幕 `IMAGE_TOKEN_KEY`、gen 取 Redis `playback_gen:{episode_id}`；real 模式走 Bunny
  Advanced Token（`HS256-<base64url>`）与 `/bcdn_token=...` HLS 目录令牌。
- `renditions` 阶梯 240/360/480/720/1080/1440/2160（短边像素；`MEMBER_ONLY_1080` 生效）。
- `record_event`：写 `playback_events`；`start` 额外 `dramas.play_count + 1`（并 update `updated_at`）；
  登录用户 upsert `playback_progress`。
- `/unlock` 顺序：幂等回放 → episode/price 校验（404/409）→ 会员优先（status=subscription）
  → 已有 entitlement（already_unlocked）→ `SELECT ... FOR UPDATE` 扣币 + `coin_ledger` + entitlement
  + `idempotency_records`（key = `u{user_id}:sha256(key)`，recorded payload 含 `replayed:false`）。
- `/plans`：active plans + 各自最新 plan_version；`billing_settings` 懒创建默认 `one_time`。
- `/me/orders`：只返回 paid/refunded，`created_at desc, id desc`，多取 1 行算 `has_more`。
- 支付：Stripe Checkout（`/v1/checkout/sessions`）、价格补齐（`/prices`）、cancel
  （`cancel_at_period_end`）、Stripe-Signature（t/v1、300s 容差）与 `customer.subscription.*`、
  `checkout.session.*`（payment=时段包叠加 / 订阅）全量快照式处理；晚到事件不复活已撤销会员。
- 旧 webhook：`X-Pay-Signature` = HMAC-SHA256(PAY_WEBHOOK_SECRET, body)，按 txn_id 幂等。
- Bunny 回调：`X-Bunny-Signature` = HMAC(body)；坏签名先写
  `audit_logs(bunny_callback_rejected)` 再 401；unknown video/episode → 400；已完成回放
  `replayed:true`；transcoding → in_review / transcode_failed；real 模式补 `duration_seconds`。
- Bunny 原生回调形态 `{"VideoGuid","Status":0..5}`：<4 忽略并回显，≥5 failed，否则 finished。

## 二、文件清单

- `support/`：`PCommerceCrypto`（HMAC/hex/quote/base64url）、`PCommerceEnv`（.env/OS 环境变量）、
  `PCommerceBunny`（fake/real 网关、签名、Stream API）、`PCommerceStripeClient`（REST + 验签）、
  `PCommerceRequests`（FastAPI 风格 422、JSON 取值）、`PCommerceUploadRejected`。
- `dto/`：`PCommerceEpisodePosition`、`PCommerceEpisodeCount`、`PCommerceOrderView`。
- `mapper/`：`PCommercePlaybackMapper`、`PCommerceWalletMapper`、`PCommerceBillingMapper`、
  `PCommerceContentMapper`、`PCommerceSocialMapper`（复杂聚合用 XML）、`PCommerceUploadMapper`、
  `PCommerceIdempotencyMapper`、`PCommerceAuditMapper`。
- `service/`：`PCommercePlaybackService`、`PCommerceWalletService`、`PCommerceBillingService`、
  `PCommerceBunnyCallbackService`、`PCommerceContentService`（公开可见性读路径）。
- `controller/`：`PCommercePlaybackController`、`PCommerceWalletController`、
  `PCommerceBillingController`、`PCommerceBunnyController`。
- `resources/mapper/pcommerce_social.xml`：按集分组的 approved / 本人 pending 评论数聚合。

未依赖其它 Agent 尚未完成的包：跨模块表（episodes/dramas/tags/comments/reactions/media_uploads）
都在本包 mapper 中自带 SQL。仅复用基座只读类：`ApiException/Json/Times`、
`CurrentUser/SessionService/RedisStore/Tokens`、`*/row/*Row`。

## 三、已知差异（与 Python 的少量非契约差异）

1. 请求体“缺必填字段”用 FastAPI 形状的 422（`{type:missing,loc,msg}`）；字段**类型错误**
   由 Jackson 变成基础异常，body 仍是 422 但 `detail` 数组形态与 Pydantic 略有不同。
2. `Set.of(...).contains` 等 Java 细节不影响契约；真实 Stripe/Bunny 调用改用
   `java.net.http.HttpClient`（超时/重试/错误文案照抄：502 `stripe rejected the request: …`、
   `stripe is unreachable`、503 `… is not configured`）。
3. 事务边界：Python 每个 service 函数末尾单次 `commit()`，Java 用 `@Transactional` 聚合；
   `createCheckoutSession` 保持 Python 的“价格补齐/订单分别提交”语义（未整体包事务）。
4. `/v1/billing/webhook` 非法 JSON 仍返回 500（Python `json.loads` 未被 `BillingError` 捕获）；
   Stripe/Bunny webhook 非法 JSON 分别是 400 `invalid payload` / 400 `json required`。
5. `plan_versions` 按 `stripe_price_id` 反查取 `LIMIT 1`（Python `scalar()` 取首行；重复价 id 的
   退化场景行为等价）。
6. 时间统一 UTC `LocalDateTime`（库列为无时区 DATETIME，连接 `time_zone='+00:00'`），
   JSON 序列化 `...+00:00`，与 Python aware-datetime `isoformat()` 一致。
7. 并发撞唯一键（order_no / idempotency_key / entitlement / coin_ledger.biz_key）直接冒泡为 500，
   与 Python `IntegrityError` 未捕获的行为一致，未额外翻译成 409。
8. `PORTING-ENDPOINTS.md` 的勾选未改：多 Agent 并行会冲突，改由 Lead 统一合并统计。

## 四、未实现 / 占位

- 本包职责内 13 个端点全部实现，无占位。
- 本包范围外（属其它 Agent）：`/v1/me`、history/favorites 等 public_flow 其余端点、
  admin 侧计划/会员管理、上传会话（`/admin/v1/episodes/{id}/upload-session`）等未在本包实现；
  本模块只消费其写入的数据（media_uploads/episodes 状态）。
- 真实模式 Bunny `create_stream_video` / TUS 授权仅在“上传会话”端点使用，不属本包接口，
  故本包只实现 `sign_stream_playback` / `sign_image_url` / `stream_video` 所需的网关能力。
