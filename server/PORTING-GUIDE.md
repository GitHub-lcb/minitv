# MiniTV Java 重写移植规范（并行 Agent 必读）

本仓库把 `backend/`（Python FastAPI）重写为 `server/`（Java 21 + Spring Boot 3.5 + MyBatis + MySQL 8）。
**Python 代码是唯一行为标准**：输出字段、状态码、错误文案、Redis key、副作用都必须对齐；不要"优化"业务逻辑。

## 1. 目录与所有权

```
server/
├── pom.xml                     # 不要改
├── src/main/resources/
│   ├── application.yml         # 不要改
│   ├── db/migration/V1..V3     # Flyway 基线，不要改
│   └── mapper/*.xml            # 每张表的 MyBatis XML；文件名加你的模块前缀
└── src/main/java/us/minitv/
    ├── common/  config/  health/  security/      # 基座，只读
    ├── <module>/row/*.java     # 全表 Row 类（自动生成），只读
    ├── account/mapper/UserMapper.java 等少量共享 mapper：只读
    └── <你的包名>/**           # 只有你可以写
```

- 只允许修改/新增**分配给自己的包**和 `resources/mapper/<你的前缀>*.xml`。
- 需要别的模块的表时：**在你自己的包里再写一个 mapper**（可以重复写同样的 SQL），不要改别人的文件。
- 不要运行 `mvn`、不要启动服务、不要动 MySQL 数据。类型检查用：

```powershell
& server\tools\check-package.ps1 -Path server\src\main\java\us\minitv\<你的包>
```

（它用 `target/classes` 里的基座类 + `deps-cp.txt` 做 javac 编译检查，不写 `target/`。）

## 2. 已提供的基座（直接用，勿改）

| 类 | 用途 |
|---|---|
| `common.ApiException` | 抛 HTTP 错误。`new ApiException(status, detail)`；detail 可以是 String 或 `Map.of("code","message")`；`ApiException.unprocessable(...)` 等快捷方法 |
| `common.GlobalExceptionHandler` | 统一输出 `{"detail": ...}`，与 FastAPI 一致 |
| `common.Json` | JSON 列读写：`Json.write(obj)` / `Json.readMap(raw)` / `Json.read(raw, TypeReference, fallback)` |
| `common.Times` | `Times.now()`（UTC LocalDateTime）、`Times.laToday()`、`Times.dayStartUtc(day)`、`Times.businessDay(dt)` |
| `security.PasswordHasher` | 与 Python 完全兼容的 `pbkdf2_sha256$120000$salt$hash`；`verify()`；`sha256Hex()` |
| `security.Tokens` | `urlSafe(32)`（= Python token_urlsafe(32)）、`hex(n)` |
| `security.RedisStore` | get/set/setIfAbsent/delete/increment/expire/scan/flushDb |
| `security.SessionService` | C 端会话 `minitv_user_session:` 30 天；后台会话 `minitv_admin_session:` 8 小时；`createUserSession/resolveUserId/createAdminSession/resolveAdmin/logoutAdmin/revokeAdminSessions` |
| `security.ApiKeyService` | API Key 解析、scope 校验、生成、`normaliseScopes`、`keyState` |
| `security.CurrentUser` / `CurrentStaff` / `CurrentPartner` | 控制器参数注解，自动注入 `UserRow` / `StaffContext` / `PartnerContext` |
| `*/row/*Row` | 47 张表的行对象（Lombok `@Data`，列名下划线转驼峰由 MyBatis 完成；JSON 列是 String，时间列是 LocalDateTime，日期列 LocalDate） |

注意：`UserRow` 在 `us.minitv.account.row`，`AdminUserRow`/`AdminApiKeyRow`/`SupplierRow` 在 `us.minitv.ops.row`，其余按模块分。

## 3. 控制器/服务/Mapper 约定

- 控制器：`@RestController`，路径必须以完整前缀开头（如 `/v1/auth/email/code`、`/admin/v1/...`、`/partner/v1/...`），方法参数用 `@PathVariable`/`@RequestParam`/`@RequestBody`。
- 请求体：写 `record XxxIn(...)`；Pydantic 的 `Field(min_length=..., ge=..., le=...)` 用 Bean Validation 或手写判断（长度/范围不合法时 FastAPI 返回 422；用 `throw ApiException.unprocessable("...")` 或 `@Valid`）。未知字段默认忽略（Jackson 已配置）。
- 返回体：直接返回 `Map<String,Object>` / `record` / `List`，键名必须与 Python `dict` 完全一致（包括 `null` 字段——Jackson 默认保留 null）。**不要包装成 `{code,data}`。**
- 时间：直接把 `LocalDateTime` 放进响应体（全局序列化成 `2026-10-14T12:00:00+00:00`）。如果 Python 里是 `datetime.isoformat()` 的字符串，也返回 LocalDateTime 即可。日期用 `LocalDate`。
- 服务层：业务逻辑放 `service`，用 `@Service`；写操作的类/方法加 `@Transactional`（等价 Python 的 `session.commit()`）。
- Mapper：`@Mapper` 接口；简单 SQL 用 `@Select/@Insert/@Update/@Delete` 注解，复杂 SQL 写 XML 到 `resources/mapper/<前缀>_<表>.xml`。MyBatis 已开 `map-underscore-to-camel-case`。
- 分页：Python 用 `limit`/`offset` 查询参数时，SQL 用 `LIMIT #{limit} OFFSET #{offset}`（MySQL 语法；参数名两边保持一致）。
- 唯一约束冲突：捕捉 `org.springframework.dao.DuplicateKeyException`，翻译成 Python 里对应的 409/400 文案。
- Python 的 `session.get(X, id)` → `mapper.findById(id)`；`select(...).where(...)` → 自己写 SQL。

## 4. MySQL 注意

- 不支持 PostgreSQL 的 `FILTER (WHERE ...)`：改成 `SUM(CASE WHEN ... THEN 1 ELSE 0 END)` / `COUNT(CASE WHEN ... THEN 1 END)`。
- 不支持 `timezone()`：按天分桶在 Java 里用 `Times.businessDay()`；或在 SQL 里用 `DATE(created_at)`（UTC）配合 `Times.dayStartUtc` 的边界条件（照搬 Python 的窗口过滤）。
- JSON 列：写入时传 `Json.write(...)` 得到的 String；读取时 `Json.read(...)`。
- 布尔列是 TINYINT，MyBatis 映射 Boolean 即可。
- 表名/列名有保留字时用反引号。
- 不要用 PostgreSQL 的 `RETURNING`；插入后用 `SELECT LAST_INSERT_ID()`（MyBatis `useGeneratedKeys="true" keyProperty="id"`）。

## 5. 鉴权写法

```java
@GetMapping("/v1/me")
public Map<String,Object> me(@CurrentUser UserRow user) { ... }        // 游客=null，required 默认 true 为 401

@PostMapping("/admin/v1/dramas")
public Map<String,Object> create(@CurrentStaff StaffContext staff, @RequestBody DramaIn body) {
    staff.user().getId(); staff.key(); // key 非空表示 API Key 调用（写入 api_key_events 由过滤器统一处理）
}

@GetMapping("/partner/v1/dramas")
public Map<String,Object> list(@CurrentPartner PartnerContext partner) { partner.supplierId(); }
```

- `@CurrentStaff` 已实现 `require_admin` 的语义：缺头/无效会话/API Key scope/角色 = `staff_only`，**partner 角色会被 403**。
- 控制器内部仍需按原 Python 做细分角色检查（如 `require_admin_role` = role 必须 `admin`，`require_content_writer` = role ∈ {ops, admin}）；用 `staff.user().getRole()` 判断并抛 403，文案照抄 Python。
- `@CurrentPartner` 已实现上剧方会话/密钥、`partner_only`、`supplier_disabled`；`@CurrentPartner(apiKeyAllowed = false)` 对应"只能用会话调用"。

## 6. Redis key（必须逐字一致）

- 用户会话：`minitv_user_session:<token>`（已有）
- 后台会话：`minitv_admin_session:<token>`（已有）
- 校验码限流：照抄 `account/service.py`（`minitv_code_rate:` 等）
- 其他 Redis key 照抄 Python 源文件里的字符串。

## 7. 输出与验收

- 每完成一个接口，勾掉 `server/PORTING-ENDPOINTS.md` 里对应行（可选）。
- 在你包里的 `PORTING-NOTES.md` 写：已完成接口清单、已知差异、未实现项（如有）。
- 最后跑 `check-package.ps1`，必须输出 `JAVAC_OK`。
- 不要修改 `server/PORTING-ENDPOINTS.md` 的开头统计。

## 8. 并行冲突防护（重要）

多个 Agent 同时写代码，Spring 默认按"类名首字母小写"生成 Bean 名。**所有 `@RestController` / `@Service` / `@Component` 类和 `@Mapper` 接口的类名必须带模块前缀**，例如：

- `PAccountAuthController` / `PAccountAuthService` / `PAccountUserMapper`
- `PCommercePlaybackController`
- `AdminCoreStaffService`
- `AdminFlowContentEpisodeController`
- `PartnerDramaController`

Mapper XML 文件名同样加前缀：`<prefix>_<table>.xml`，`namespace` 用你包里的 mapper 全限定名。

除此之外：不要 `@ComponentScan`、不要定义 `@Configuration`（复用基座配置），不要创建与基座同名的类。
