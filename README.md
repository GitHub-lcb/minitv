<div align="center">

<h1>MiniTV</h1>

<p><strong>开源的短剧分发平台</strong> —— 内容方入驻上传、平台审核分发、分集解锁与结算，一套后端撑起官网 / H5 / App / 后台。</p>

<p>React 18 · TypeScript · FastAPI · PostgreSQL · Redis · Flutter · Bunny Stream · Stripe</p>

</div>

---

> **短剧出海，全链路交付**
>
> 收款通道 · 短剧制作 · 系统定制 · 广告投放 —— 需要哪一段就聊哪一段，[扫码加微信](#商务合作)。
>
> 线上在跑：[官网](https://minitv.us) · [H5](https://h5.minitv.us) · [API](https://api.minitv.us)

---

## 在线演示

| 端 | 地址 | 说明 |
| --- | --- | --- |
| 🖥 官网（营销站） | <https://minitv.us> | 产品介绍、免费试看、套餐展示 |
| 📱 H5 Web App | <https://h5.minitv.us> | 完整播放、金币、签到、评论，移动端优先 |
| 🔐 管理后台 | <https://admin.minitv.us> | 内容 / 运营 / 财务后台，需登录 |
| ⚙️ API | <https://api.minitv.us> | 接口服务，文档见 `/docs` |

> 演示站由维护者提供，仅用于体验产品；请勿用于压测或爬取。

## 产品演示

<table>
<tr>
<td width="100%">
<img src="docs/screenshots/web-home.png" alt="MiniTV 官网首屏">
<br><sub><b>官网</b> —— 首屏讲清价值主张，右侧真机模型预演产品界面，主 CTA 直达免费试看。</sub>
</td>
</tr>
</table>

<table>
<tr>
<td width="33%" align="center">
<img src="docs/screenshots/h5-home.jpg" alt="H5 首页">
<br><sub><b>H5 · 首页</b><br>Discover / Ranking / Free 多频道，主推位轮播</sub>
</td>
<td width="33%" align="center">
<img src="docs/screenshots/h5-shows.jpg" alt="H5 频道页">
<br><sub><b>H5 · 新剧</b><br>最新上架一屏尽览，三列纵向浏览</sub>
</td>
<td width="33%" align="center">
<img src="docs/screenshots/app-episodes.jpg" alt="App 选集面板">
<br><sub><b>App · 选集</b><br>已看 / 在追 / 加锁三态一目了然</sub>
</td>
</tr>
</table>

<table>
<tr>
<td width="50%">
<img src="docs/screenshots/admin-dashboard.png" alt="后台数据看板">
<br><sub><b>后台 · 数据看板</b> —— 活跃、播放、完播、营收按日聚合，全站累计与待办一屏掌握。</sub>
</td>
<td width="50%">
<img src="docs/screenshots/admin-rewards.png" alt="后台金币与任务">
<br><sub><b>后台 · 金币与任务</b> —— 7 天签到阶梯与任务激励可在线上直接调参，次日生效、全程留痕。</sub>
</td>
</tr>
</table>

## 功能

**内容与播放**

- 剧集 / 分集 / 标签 / 首页楼层可视化编排，支持定时上架与批量上传
- Bunny Stream 转码 + HLS 签名播放，多清晰度切换、断点续播
- 分集解锁：免费集数、金币单价、会员权益三级控制

**变现与增长**

- Stripe Checkout 会员，支持「自动续费订阅」与「一次性时长包」两种计费模式，后台一键切换
- 金币账本（append-only 流水 + 物化余额），所有消耗走幂等接口，杜绝重复扣费
- 7 天签到阶梯、每日任务、绑定奖励，奖励配置后台可调、按生效日归档

**运营与协作**

- 分集评论 + 异步 AI 审核（OpenAI 兼容接口，密钥在后台加密存储）
- 上剧方（supplier）独立角色：自助建剧、上传、提交审核，只读对账单
- 管理后台 RBAC（运营 / 财务 / 管理员）、API Key + CLI、全量审计日志
- 数据看板、财务订单、月度结算单

## 技术栈

| 层 | 选型 |
| --- | --- |
| 后端 | Java 21 · Spring Boot 3.5 · MyBatis · Flyway |
| 数据 | MySQL 8.4 · Redis 7 |
| 前端 | React 18 · TypeScript 5 · Vite 5 · Ant Design 5 |
| App | Flutter（Dart SDK 3.13+，iOS / Android） |
| 媒体 | Bunny Stream（HLS 转码）· Bunny Storage + Optimizer（封面 / 字幕） |
| 支付 | Stripe Checkout + Webhook |
| 邮件 | Resend |
| 部署 | Cloudflare R2 托管静态站 · 单台 Linux 跑 API |

## 目录结构

```
.
├── server/             Java 后端（Spring Boot 3 + MyBatis + Flyway + MySQL）
│   ├── src/main/java/us/minitv/    account / content / playback / wallet /
│   │                               billing / social / recommend / ops / partner
│   └── src/main/resources/db/migration/   Flyway MySQL schema
├── backend/            原 Python FastAPI 实现（只读对照，不再部署）
├── admin/              管理后台（React + Ant Design）
├── web/                官网营销站（React，含独立隐私政策页）
├── h5/                 移动端 Web App（React）
├── app/                Flutter App（iOS / Android）
├── cli/                单文件 CLI（纯标准库，Python 3.8+）
├── scripts/            数据导入、发布、运维脚本
├── docs/               架构 / 运营 / 验收文档
├── deployments/        发布记录（每次发布自动生成 JSON）
└── docker-compose.yml  本地 MySQL 8 + Redis 7
```

## 快速开始

### 前置要求

- JDK **21**（`java -version`）
- Maven **3.9+**
- **MySQL 8**（本机已安装可直接用；Windows 服务名通常是 `MySQL80`）
- **Redis 7**（本机已安装可直接用；或 Docker）
- 可选：Flutter 3.x（只在跑 App 时需要）

> 不想动本机数据库的话，也可以用 `docker compose up -d` 起一套 MySQL `:13306` / Redis `:16379`，见第 2 步的备选说明。

### 1. 拉代码并准备环境变量

```bash
git clone https://github.com/xuliang2024/minitv.git
cd minitv
cp .env.example .env
```

`.env.example` 默认连本机 MySQL `127.0.0.1:3306` 和 Redis `127.0.0.1:6379`，并默认 `BUNNY_MODE=fake`（播放/封面走本地桩实现）—— **不填任何外部密钥也能把接口和后台跑通**。

### 2. 初始化数据库（本机已装 MySQL 的情况）

Windows PowerShell（会提示输入 MySQL `root` 密码，密码不会被保存）：

```powershell
.\scripts\init_mysql.ps1
```

脚本会创建 `minitv` 库、`minitv / minitv_local` 本地账号，并打开 Flyway 建触发器所需的权限。Linux / macOS 手动执行：

```bash
mysql -u root -p < scripts/init_mysql.sql
```

确认 `.env` 中：

```dotenv
DATABASE_URL=mysql://minitv:minitv_local@127.0.0.1:3306/minitv
REDIS_URL=redis://127.0.0.1:6379/0
```

如果要用 Docker 的 MySQL/Redis（不改本机数据库）：

```bash
docker compose up -d          # MySQL :13306 / Redis :16379（带密码）
```

然后把 `.env` 改成：

```dotenv
DATABASE_URL=mysql://minitv:minitv_local@127.0.0.1:13306/minitv
REDIS_URL=redis://:minitv_local@127.0.0.1:16379/0
```

### 3. 启动后端（首次启动 Flyway 自动建表）

```bash
cd server
mvn spring-boot:run
```

首次启动会在 `minitv` 库里自动建好 47 张表、2 个状态机触发器和 4 个生成列。健康检查：

```bash
curl http://127.0.0.1:18110/health
# {"status":"ok","env":"local","mysql":"8.0.x","redis":"..."}
```

> 生产形态：`mvn -DskipTests package` 后 `java -jar target/minitv-server-2.0.0.jar`。
> 后端只需启动一次；改代码后 Spring Boot 重启即可（Flyway 不会重复建表）。

### 4. 创建第一个后台账号

后端第一次启动、表建好之后执行一次（`admin_users` 为空时才创建，可安全重复运行）。命令本身不占 18110 端口，但会和正在运行的后端共用 Maven 的 `target/`，所以建议先停掉正在运行的后端再执行：

```powershell
cd server
$env:SEED_ADMIN_PASSWORD = "换成你自己的口令"; $env:SEED_ADMIN_RUN = "1"
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.main.web-application-type=none"
```

Linux / macOS：

```bash
cd server
SEED_ADMIN_PASSWORD='换成你自己的口令' SEED_ADMIN_RUN=1 \
  mvn spring-boot:run -Dspring-boot.run.arguments="--spring.main.web-application-type=none"
```

不带 `SEED_ADMIN_PASSWORD` 时会随机生成一个并打印一次。默认用户名 `admin`。

### 5. 启动三个前端（各开一个终端）

首次需要 `npm install`，之后只需 `npm run dev`：

```bash
cd admin && npm install && npm run dev     # 管理后台 http://localhost:5173
cd web   && npm install && npm run dev     # 官网     http://localhost:5174
cd h5    && npm install && npm run dev     # H5       http://localhost:5175
```

Vite 已配好代理，`/v1` 与 `/admin/v1` 自动转发到 `127.0.0.1:18110`，无需额外配置跨域。

打开 <http://localhost:5173>，用第 4 步创建的 `admin` 账号登录。

> Windows 下也可以一条命令拉起 4 个窗口（后端 + 三个前端）：`.\scripts\start-dev.ps1`；
> 只启动后端用 `-NoFrontends`，只启动前端用 `-NoBackend`。

### 6. 手机浏览器访问本地 H5（可选）

按场景选一种：

**A. 同一 Wi-Fi / 手机热点（最简单）**

```powershell
cd h5
$env:VITE_DEV_HOST = "0.0.0.0"     # 或不设变量，用 npm run dev -- --host
npm run dev
```

电脑 `ipconfig` 查 IPv4（例如 `192.168.1.5`），手机浏览器打开 `http://192.168.1.5:5175/`。
第一次监听时 Windows 防火墙会弹窗，记得允许 Node.js。手机热点方案：电脑连上手机热点后，同样访问电脑在热点网段的 IP。

**B. 手机移动网络 + 临时公网隧道（不在同一网络）**

以 Cloudflare 快速隧道为例：

```powershell
winget install --id Cloudflare.cloudflared
# 另开一个终端，记下输出里的 https://xxx.trycloudflare.com
cloudflared tunnel --url http://127.0.0.1:5175

cd h5
$env:VITE_ALLOWED_HOSTS = ".trycloudflare.com"    # 允许隧道域名（点开头=所有子域）
npm run dev
```

手机打开隧道地址即可。ngrok / localtunnel 同理：`ngrok http 5175`（`VITE_ALLOWED_HOSTS=.ngrok-free.app`）、`npx localtunnel --port 5175`（`.loca.lt`）。

**C. Tailscale（私网，不需要暴露公网）**

电脑和手机都装并登录 Tailscale → 电脑用 `VITE_DEV_HOST=0.0.0.0` 启动 H5 → 手机打开 `http://100.x.x.x:5175/`。

**Android USB（免网络）**：手机开启 USB 调试，电脑执行 `adb reverse tcp:5175 tcp:5175`，手机浏览器打开 `http://localhost:5175/`。

说明：

- `APP_ENV=local` 时后端会自动允许 localhost / 私网 IP / 常见隧道域名的 CORS 来源，不用改代码；生产环境仍使用显式白名单。
- H5 的 API 走 Vite 代理到电脑上的 `18110`，后端不需要单独暴露公网。
- 视频 HLS 直连 Bunny CDN，手机网络需要能访问 `*.b-cdn.net`。
- ⚠️ 隧道会把 dev server 临时暴露到公网（包含 `/v1` 代理），测完即关；不要用这种方式暴露管理后台。

### 本地端口一览

| 服务 | 本机安装（默认） | Docker 备选 |
| --- | --- | --- |
| API | <http://127.0.0.1:18110> | 同左 |
| 管理后台 | <http://localhost:5173> | 同左 |
| 官网 | <http://localhost:5174> | 同左 |
| H5 | <http://localhost:5175> | 同左 |
| MySQL | `127.0.0.1:3306` | `127.0.0.1:13306` |
| Redis | `127.0.0.1:6379` | `127.0.0.1:16379`（带密码） |

### 环境自检

```bash
python3 scripts/check_local_env.py
```

检查 `.env`、MySQL、Redis 与 `/health`；Flutter / Android 工具链单独报告，不影响后端启动。

### 常见问题

- **后台登录 503「会话存储不可用」/ Redis 报 `MISCONF`**：本机 Redis 无法写 RDB（目录权限或磁盘问题）时，写命令会被禁用。修复 `dir` 权限，或另起一个可写实例：`redis-server --port 16379 --requirepass minitv_local --appendonly no --dir <可写目录>`，再把 `.env` 的 `REDIS_URL` 改为 `redis://:minitv_local@127.0.0.1:16379/0`。
- **上剧方剧集点「通过并上架」报 409 `not_approved`**：上剧方内容必须先在「待审核」通过审核。如果「待审核」里没有这部剧，说明它被改到了「审核中」但还没送审：先点「退回草稿」，再点「提交审核」（会自动生成待审核记录），审核通过后即可上架。
- **打开前端报 `ERR_CONNECTION_REFUSED`**：Vite 在 Windows / Node 18+ 下通常只监听 `localhost`（IPv6 `::1`），请用 <http://localhost:5173> 而不是 `127.0.0.1`；需要局域网访问时在 `vite.config.ts` 的 `server.host` 里显式设置 `0.0.0.0`。
- **Windows 找不到 `mysql` 命令**：直接跑 `.\scripts\init_mysql.ps1`（默认用 `C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe`），或加参数 `-MysqlExe "你的 mysql.exe 路径"`。
- **MySQL80 服务没起来**：以管理员身份执行 `Start-Service MySQL80`，或 `Get-Service MySQL80` 查看状态。
- **端口被占用**：API 端口在 `.env` 的 `API_PORT`；前端端口在各自的 `vite.config.ts`；MySQL/Redis 端口在 `DATABASE_URL` / `REDIS_URL`。
- **想清空本地数据重来**：`DROP DATABASE minitv;`，重新执行第 2 步初始化，再启动后端（Flyway 会重新建表）。
- **MySQL 重启后 Flyway 报触发器权限错误**：`SET GLOBAL log_bin_trust_function_creators = 1` 是运行时变量，重启会失效；在 `my.ini` 的 `[mysqld]` 下加一行 `log_bin_trust_function_creators=1`，或重新执行 `scripts/init_mysql.sql`。
## 配置真实图片与视频（Bunny）

> 完整操作手册（控制台点击路径、凭据对照表、验证命令、排错与安全清单）：[docs/bunny-setup.md](docs/bunny-setup.md)。

默认 `BUNNY_MODE=fake`：不联网、封面上传返回 503、视频走本地假的 TUS 接收端，适合不依赖外网的本地联调。
要让「封面/字幕上传、视频直传、签名播放」走真实云端，需要 Bunny.net 账号并把下面这些凭据填进 `.env`。

### 1. Bunny 后台准备（一次性）

| 资源 | 创建位置 | 需要记下的值 | 对应 `.env` |
| --- | --- | --- | --- |
| Stream 视频库 | Bunny → Stream → Add Library | Library ID、Library API Key | `BUNNY_STREAM_LIBRARY_ID` / `BUNNY_STREAM_API_KEY` |
| Stream 的 Pull Zone | 建库时自动生成（库的 CDN 设置里） | hostname（如 `vz-xxxx.b-cdn.net`）、Token Key | `BUNNY_STREAM_CDN_HOST` / `BUNNY_STREAM_TOKEN_KEY` |
| Storage Zone（封面/字幕） | Bunny → Storage → Add Storage Zone（建议主区 New York，可加 LA 复制） | Zone 名、Storage Zone Password、上传域名（如 `ny.storage.bunnycdn.com`） | `BUNNY_STORAGE_ZONE` / `BUNNY_STORAGE_PASSWORD` / `BUNNY_STORAGE_HOST` |
| Storage 的 Pull Zone（图片 CDN） | Storage Zone 关联的 Pull Zone，或单独新建 Storage 类型 Pull Zone | hostname（如 `minitv-dev-images.b-cdn.net`） | `BUNNY_IMAGE_CDN_HOST` |
| 账号 API Key | Bunny → Account Settings → API Key | key | `BUNNY_API_KEY`（仅用于清缓存） |

具体在控制台哪里拿值：

- **Storage Zone 凭据**：打开刚建的 Storage Zone → **Access** → **API / HTTP**，复制 Zone 名、Password、Hostname。
- **图片 Pull Zone**：可以在 Storage Zone 页的 **Connected Pull Zones** 里 Add，也可以走左侧 **CDN** → **Add Pull Zone**（Origin Type 选 Storage Zone）；hostname 在 Pull Zone 的 **General** 页。
- **Stream 凭据**：**Stream** → 打开视频库 → **API** 页，复制 Library ID 和 API Key；库的 Pull Zone hostname 在 API 页的 **Pull Zone → Manage**。
- **Stream Token Key**：视频库 → **Security** 页，开启 **Embed view token authentication**，复制 **Token authentication key**；同时确认 **CDN token authentication** 已开启（同一个 key 同时签播放地址和预览 embed）。
- **Account API Key**：左侧 **Account → API key**，只用于 purge，权限较大，务必只放服务端。

两个关键开关：

- **视频 Pull Zone**：必须开启 **Token Authentication**，把 Token Key 填到 `BUNNY_STREAM_TOKEN_KEY`；否则 `/v1/playback` 签出的 HLS 地址无法通过鉴权。
- **图片 Pull Zone**：**不要**开启 Token Authentication。封面在库里是公开 URL（`cover_url`），一旦图片 Pull Zone 开鉴权，封面会 403。字幕的播放 URL 虽然带签名参数，但在未开鉴权的 Pull Zone 上会被忽略；`BUNNY_IMAGE_TOKEN_KEY` 填任意非空串即可（只有上传了字幕才会用到）。
- 开发 / 生产必须使用不同的 Stream Library、Storage Zone、Pull Zone；开发密钥不要进生产 `.env`。

### 2. 填写 `.env` 并重启后端

```dotenv
BUNNY_MODE=real
BUNNY_API_KEY=账号 API Key

BUNNY_STORAGE_ZONE=minitv-dev
BUNNY_STORAGE_HOST=ny.storage.bunnycdn.com
BUNNY_STORAGE_PASSWORD=Storage Zone Password

BUNNY_IMAGE_CDN_HOST=minitv-dev-images.b-cdn.net
BUNNY_IMAGE_TOKEN_KEY=任意非空串（或图片 Pull Zone 的 Token Key）

BUNNY_STREAM_LIBRARY_ID=123456
BUNNY_STREAM_API_KEY=Library API Key
BUNNY_STREAM_CDN_HOST=vz-xxxx.b-cdn.net
BUNNY_STREAM_TOKEN_KEY=视频 Pull Zone 的 Token Key
```

Java 服务在启动时读取 `.env`，改完需要重启：

```bash
cd server
mvn spring-boot:run
```

缺少某个变量时，相关接口会返回明确的 503，例如
`{"detail":"BUNNY_MODE=real but BUNNY_STREAM_LIBRARY_ID is not configured"}`，按提示补齐即可。

### 3. 在后台走一遍

1. **封面**：建剧后上传封面（jpg / png / webp，≤ 5MB）。文件写入 Storage 的 `covers/{drama_id}/cover.{ext}`，`cover_url` 变成 `https://{BUNNY_IMAGE_CDN_HOST}/covers/...`。
2. **视频**：新建分集 →「上传视频」→ 浏览器先调 `upload-session`（服务端在 Bunny 创建视频对象并返回 TUS 地址与签名头）→ 浏览器直传 Bunny → 自动转码。视频二进制不经过 API 服务器。
3. **转码完成**：`BUNNY_MODE=real` 时后端每 120 秒自动对账一次 Bunny 状态；也可以在后台点「同步状态」。分集进入「审核中」后按正常流程审核、发布。
4. **字幕**（可选）：上传 `.vtt` / `.srt`（≤ 2MB），存到 Storage 的 `subs/{episode_id}/`，播放接口返回带签名参数的 URL。
5. **播放**：公开端 `POST /v1/playback` 通过权限判断后签发 **5 分钟** HLS 地址：
   `https://{BUNNY_STREAM_CDN_HOST}/bcdn_token=...&token_path=.../{video_id}/playlist.m3u8`。
   后台分集「预览」用的是 Bunny Player 临时签名链接，不会暴露未发布分集的直链。

### 4. 转码回调（可选）

默认不需要配回调：后端对账轮询 + 后台「同步状态」已经能完成 `transcoding → in_review`。

如果配置 Bunny Stream Webhook 指向 `https://<你的 API 域名>/v1/bunny/callback`，请求必须带
`X-Bunny-Signature = HMAC-SHA256(BUNNY_CALLBACK_SECRET, 原始请求体)`。Bunny 原生 Webhook 不发送这个头，直接指向会返回 401；需要自己加一层中继签名，或继续使用对账/手动同步。

### 5. 不接 Bunny 的替代方案

- **只要封面临时可用**：不配 Storage，建剧后在后台/`PATCH /admin/v1/dramas/{id}` 里把 `cover_url` 填成任意外部图片 URL。
- **视频**：`episodes` 只保存 `bunny_video_id`，没有"外部视频 URL"字段。不接 Bunny 就只能保持 `BUNNY_MODE=fake`（地址是假的，播放器不会真的出画面）；要接别家 HLS 需要改播放模块代码。
- **批量导入**：`python3 scripts/import_hotlist.py` 走后台 API + Bunny PUT 批量导入；单集也可用 CLI `minitv episode upload <id> ./ep01.mp4`。

### 6. 常见排错

| 现象 | 原因 |
| --- | --- |
| 封面 403 / 不显示 | 图片 Pull Zone 开了 Token Authentication；关掉，或用外部图片 URL |
| 播放 403 | 视频 Pull Zone 未开 Token Authentication，或 `BUNNY_STREAM_TOKEN_KEY` 填错 |
| 上传返回 401 / 502 | `BUNNY_STREAM_API_KEY` 与 Library 不匹配，或用了账号 API Key 代替 Library API Key |
| 上传接口 503 | `BUNNY_MODE=real` 但对应变量为空；按 `detail` 里的变量名补齐并重启 |
| 字幕播放 500 | 有 `subtitle_url` 但 `BUNNY_IMAGE_TOKEN_KEY` 为空 |
## 运行 App

```bash
cd app
flutter pub get

# iOS 模拟器（默认就连本机 API）
flutter run

# Android 模拟器 / 真机需要显式指定 API 地址
flutter run --dart-define=API_BASE=http://10.0.2.2:18110
```

## 命令行工具

`cli/minitv` 是单文件、零依赖的 CLI（Python 3.8+，当前 0.4.0），覆盖剧集 / 分集 / 标签 / 分组 / 排期 / 上传 / 上剧方等 **57 条命令**。

```bash
curl -fsSL https://minitv.us/cli/install.sh | sh
minitv config set-key                      # 交互式写入 API Key（不落 shell 历史）
minitv drama list --status published
minitv episode upload 12345 ./ep01.mp4
minitv doctor                              # 自检：连通 / 身份 / 权限
```

完整命令见 [`docs/cli-reference.md`](docs/cli-reference.md)。

## 发布

线上发布统一走一个入口，内含共享锁与自动版本号：

```bash
python3 scripts/release.py status          # 先看锁和线上版本
python3 scripts/release.py r2   --dist web/dist --source web
python3 scripts/release.py run  --target server -- ./scripts/deploy_server.sh
```

版本形如 `v2026.10.29.1`，由脚本在锁内生成并打 tag；回滚同样走脚本。细节见 [`docs/release-process.md`](docs/release-process.md)。

> 这些日期构建号属于**本地部署记账**，权威记录是 `deployments/releases/*.json`（含版本号与提交 SHA）。它们不推送到远端——公开仓库只保留语义化版本 tag，当前为 [`v1.0.0`](https://github.com/xuliang2024/minitv/releases/tag/v1.0.0)。

## 文档

| 文档 | 内容 |
| --- | --- |
| [`docs/technical-architecture.md`](docs/technical-architecture.md) | 技术拓扑、密钥边界、部署架构 |
| [`docs/release-process.md`](docs/release-process.md) | 发布 / 回滚流程与故障处理 |
| [`docs/membership-coins-unlock.md`](docs/membership-coins-unlock.md) | 会员、金币、解锁的账务设计 |
| [`docs/comment-architecture.md`](docs/comment-architecture.md) | 评论与异步审核链路 |
| [`docs/supplier-role-plan.md`](docs/supplier-role-plan.md) | 上剧方角色的权限与结算设计 |
| [`docs/cli-reference.md`](docs/cli-reference.md) | CLI 全部命令 |

## 商务合作

**我们提供短剧出海的全链路解决方案** —— 从系统到变现一次打通，欢迎咨询。

| 服务 | 我们做什么 |
| --- | --- |
| 💳 **收款通道** | 海外收款落地：Stripe 订阅 + 金币解锁双模式，多币种定价、退款与对账已跑通 |
| 🎬 **短剧制作** | 剧本、拍摄、剪辑、字幕与多语言本地化，直接交付可上线的竖屏片源 |
| 🧩 **系统定制** | 本仓库源码私有化部署、品牌换皮、功能二开，含 iOS / Android App 打包上架 |
| 📣 **广告投放** | TikTok / Meta 素材制作与投放代运营，按 ROI 逐步放量 |

> 自建站、代制剧、代投放，哪一项都能单独合作；已有系统只缺支付或买量，也可以只补那一段。

<p align="center"><img src="docs/assets/wechat.png" width="260" alt="扫码加微信，咨询短剧出海全链路解决方案"></p>

<p align="center"><strong>扫码加微信，备注「MiniTV」，聊聊你的出海项目</strong></p>

遇到 Bug 或功能建议，也可以直接开 [Issue](https://github.com/xuliang2024/minitv/issues)。

## 许可证

本项目基于 [MIT License](LICENSE) 开源，© 2026 MiniTV。

可自由用于商业项目，保留版权声明即可。如需改为 Apache-2.0（附带专利授权条款），替换 `LICENSE` 文件并同步本节即可。
