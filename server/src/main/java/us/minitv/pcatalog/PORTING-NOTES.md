# public-catalog 移植说明（Python `modules/content/public_router.py` + `public_flow.py` 配置面 + `modules/recommend`）

包：`us.minitv.pcatalog`（controller / service / dto / mapper / support）
行为标准：`backend/` Python 代码（只读，未修改）。

## 已完成接口（11/11）

| # | 接口 | 实现位置 | 要点 |
|---|---|---|---|
| 1 | `GET /v1/preview` | `PCatalogPublicController` → `PCatalogContentService.preview()` | 每分类前 7；`limit=7`；`All shows` 伪分类 id=0；分类为启用 tag（category asc, id asc，main 在前）；卡片按 `play_count` 降序、recency 破平（稳定排序） |
| 2 | `GET /v1/preview/dramas/{drama_id}` | 同上 `previewDrama` | 404 文案 `drama not found` / `no published episodes` / `no free episodes`；只统计 `status=published` 且有 `bunny_video_id` 的集 |
| 3 | `GET /v1/home` | 同上 `home()` | 已发布剧按 `updated_at DESC`，字段同 `_drama_out` |
| 4 | `GET /v1/dramas/{drama_id}` | 同上 `dramaDetail` | 非 published → 404 `drama not found` |
| 5 | `GET /v1/search` | 同上 `search(q)` | `q` 必填；标题 LIKE，`updated_at DESC`，limit 20；空串 422（FastAPI 同形 detail 数组） |
| 6 | `GET /v1/feed` | `PCatalogFeedController` → `PCatalogFeedService` + `PCatalogSignals` + `PCatalogRanking` + `PCatalogFeedCursor` | 完整复刻 recall/打分/多样性/游标/Redis 计划与曝光；错误 400 `bad feed cursor signature`、`malformed feed cursor` |
| 7 | `GET /v1/home/groups` | `PCatalogFlowController` → `PCatalogConfigService.homeGroups()` | active 分组 + online banners；未发布剧跳过；banner 缺剧时 `title/cover_url=null`，`headline` 回退 `""` |
| 8 | `GET /v1/search/trending` | 同上 `trending()` | `enabled=1` 按 position，返回 `{"items":[term...]}` |
| 9 | `GET /v1/config` | 同上 `config(locale)` | 启用 tabs 按 position；`locale=zh` 且无中文时回退英文并写一条 `audit_logs`（`copy_fallback`，payload `{"key":...}`），请求结束一次提交 |
| 10 | `GET /v1/marketing` | 同上 `marketing()` | stats/testimonials/stores/trending 前 6/`billing.list_current_plans`（含最新版本、`benefits` 原样 JSON 对象）；`billing_settings` 缺失时插入 `id=1, billing_mode='one_time'`（同 Python 副作用） |
| 11 | `GET /v1/legal/{slug}` | 同上 `legal(slug)` | 缺失 404 `not found` |

无鉴权接口（全部公开）；feed 用 `@CurrentUser(required=false)` 做可选登录，游客走 `X-Device-Id`。
Redis key 逐字一致：`feed:plan:{session_id}`、`feed:imp:{viewer}`（viewer = `u{user_id}` 或 `d{device|anon}`），TTL 均为 7 天；游标签名密钥 `FEED_CURSOR_KEY`（默认 `local-feed-cursor`，Spring `@Value` 读取，故 `.env` 也可生效）。

## 数据库/Mapper

- 表：dramas、episodes、tags、drama_tags、home_groups、drama_home_groups、banners、channel_tabs、copy_entries、hot_searches、legal_pages、marketing_stats、testimonials、store_links、plans、plan_versions、billing_settings、playback_events、playback_progress、reactions、comments、entitlements、subscriptions、audit_logs。
- 跨模块表都在本包内自建 mapper/SQL（`PCatalogSignalMapper` 等），不依赖其它 Agent 的类；行类直接复用只读的 `*/row/*Row`。
- MySQL 方言：3 处 `count(*) FILTER` 改为 `SUM(CASE WHEN ... THEN 1 ELSE 0 END)`；无 `timezone()`（窗口在 Java 里用 `LocalDateTime.now(UTC) - N days`）；JSON 列按 String 读写。
- XML：`resources/mapper/pcatalog_{drama,episode,tag,homegroup,signal}.xml`；简单查询用注解（ops/billing/audit）。所有 `@Mapper` 接口名与方法名已用 MyBatis `XMLMapperBuilder` + `getBoundSql` 离线验证（40 条语句全部可构建）。

## 已知差异（均为可接受/无对外可见影响）

1. **feed 分数末位 ULP**：JVM `Math.log1p/exp` 与 CPython libm 不同，分值可能差 ~1e-16；排序结果一致（除分数完全并列的极端情况）。用原始 Python `ranking.py`/`signals.py` 对比验证：4 个样例候选分值差 ≤2e-17、排序与 `main_tag`、来源标签完全相同。
2. **Redis 计划 JSON 空白**：Python `json.dumps` 默认 `", "` 分隔，Java 用紧凑 JSON；key 完全相同，值 JSON 语义等价（双向可读）。
3. **曝光计数原子性**：Python 用 pipeline 一次提交；Java 逐条 `HINCRBY` + `EXPIRE`，Redis 异常时可能部分写入（纯缓存，不影响响应）。
4. **feed 可见剧顺序**：Python `visible_dramas` 无 `ORDER BY`，本实现固定 `ORDER BY id`；只在分数完全并列时影响先后。
5. **搜索大小写/重音**：MySQL `utf8mb4_0900_ai_ci` 大小写与重音均不敏感；Postgres `ILIKE` 只忽略大小写，含重音符号的查询可能多命中。
6. **`updated_at` 序列化**：Python `isoformat()` 恒为 `yyyy-MM-ddTHH:mm:ss+00:00`；基座 Jackson 对 `LocalDateTime` 用 `toString()+"+00:00"`，秒为 0 时会丢 `:00`，因此该字段在本模块显式格式化（仅此一个时间字段）。
7. **422 校验错误体**：`q` 空串、`limit` 越界手工构造了与 FastAPI/pydantic 相同的 `{"detail":[{type,loc,msg,input,ctx}]}`；路径参数类型错误等仍由基座 `GlobalExceptionHandler` 生成近似数组。
8. **会员判定异常**：同一用户存在多条 active 订阅时 Python `scalar_one_or_none()` 抛 500，本实现同样抛 `IllegalStateException` → 500 `Internal Server Error`。
9. **Feed 会员/权限**：`@CurrentUser` 只解析 Bearer 会话；游客与登录用户的 `viewer_state`/`unlocked`/`progress` 语义与 Python 一致（会员 = active 订阅且 period_end/pass_end 在未来）。
10. **配置审计事务**：`/v1/config` 的 audit 写入放在同一 `@Transactional` 方法内；Python 是逐条 add 后一次 commit，最终可见结果一致。

未实现/占位项：无。模块内没有需要其它 Agent 补齐的桩代码；未触碰 pom.xml、application.yml、db/migration、common/config/health/security、`*/row/` 与共享 mapper。

## 自测记录

- `server/tools/check-package.ps1 -Path .../pcatalog`：`JAVAC_OK`。
- BLAKE2b-64（feed explore 抖动种子）与 `hashlib.blake2b(data, digest_size=8)` 逐位一致（含空串/跨 128 字节块）。
- 游标编码/解码与 Python `encode_cursor` 字节级一致（3 组样例），Python 可解 Java 游标，异常分支文案一致。
- `ranking.score_candidates/diversify`、`_assign_sources`、`_top`、`round4`、`viewer_key` 与原始 Python 源码对比通过。
- 5 个 mapper XML 通过 `XMLMapperBuilder` 解析；40 条 MappedStatement 的 `getBoundSql` 全部成功（包含 `IN` foreach）。
