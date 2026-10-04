# 单机部署与联调

这份指南适用于一台 Linux 主机上运行一个 Java 进程，并连接 MySQL 8、Redis、腾讯云 COS；AI 为按需开启的外部服务。当前已通过 64 项隔离测试并生成可执行 JAR，失败、错误及跳过均为 0；已检查私密配置未入包。仓库提供部署模板，但真实依赖和公网访问尚需按本指南验收。所有命令均为示例，请在测试环境先演练。

## 1. 构建可交付文件

开发机或 CI 使用 JDK 17、Maven 3.8+：

```bash
mvn --batch-mode --no-transfer-progress clean verify
jar tf target/Star-pic-backend-0.0.1-SNAPSHOT.jar
```

交付文件为 `target/Star-pic-backend-0.0.1-SNAPSHOT.jar`，应包含 `BOOT-INF/classes/`，且不包含真实的 `application-local.yml`、`application-prod.yml`。Maven 明确排除这些文件；`clean` 也清除上次构建留下的资源副本。仓库提供 `deploy/github-actions-ci.yml.example` 模板，启用后 GitHub Actions 运行同样的隔离验证、检查 JAR 内容并保存报告/JAR，不自动部署，也不需要云服务密钥。当前推送令牌缺少 `workflow` 权限，GitHub 拒绝直接上传工作流；因此先提交模板，尚未启用远端 CI。获得工作流写入权限后，将模板复制为 `.github/workflows/ci.yml` 并提交。当前测试与 JAR 检查结果来自本地验证。

## 2. 准备数据库与外部服务

- **新库**：使用 `SQL/create_table.sql` 初始化。先核对脚本中的库名 `star_pic`；修改实例/库名时同时调整 `DB_URL`。
- **已有库**：备份并记录迁移执行情况，按顺序执行 `SQL/migrations` 中未应用的 SQL。`001_picture_original_key.sql` 补充原图 Key；`002_out_painting_task.sql` 补充扩图任务表。不要把初始化脚本当作已有数据库的升级工具，也不要自动对当前开发库运行迁移。
- 给应用单独的数据库账号，只授予目标库所需的查询/写入权限。初始化和迁移用另一个运维账号执行，应用启动不会自动创建业务表。
- Redis 只对受控网络开放，设置密码并验证 `DB/namespace` 与环境隔离。Redis 在此项目同时保存登录 Session 和公共列表缓存；缓存可回退数据库，登录 Session 不能假装不受故障影响。
- COS 启用图片处理并确认应用具备上传、查询、删除、处理衍生图和设置对象 ACL 的权限。`COS_HOST` 应是当前桶的 HTTPS 对象访问域名，或已正确回源该桶的公共 CDN 域名；`objectUrl` 使用该域名加 Key 生成对象地址。私有签名 URL 由 SDK 返回 COS 源站地址，绕过公共 CDN。CDN 配套配置仍需在真实环境验收，不代表已经开通。

COS 桶的默认读权限应为私有，避免桶策略/CDN 绕过对象 ACL。`public/` 下的原图、WebP 和缩略图由应用设置公共读，源文件属于可公开资源；`space/` 下的原图及衍生图必须保持私有，API 完成归属校验后生成短期签名 URL，当前有效期 10 分钟。签名到期不会收回已经下载的文件。存量对象不会自动修改 ACL，历史公开私有图需在云控制台独立排查；使用 CDN 时还需确认缓存、回源和鉴权策略不会公开私有对象。

## 3. 设置外置配置

将 `.env.example` 复制为 `/etc/star-picture/star-picture.env`，填写真实值。systemd 的 `EnvironmentFile` 读取该文件；Spring Boot 自身不会自动加载 `.env`。文件不要放进 Git、JAR、CI artifact 或前端代码。

| 配置 | 用途与限制 |
| --- | --- |
| `DB_URL/DB_USERNAME/DB_PASSWORD` | 独立目标库连接；建议使用最小权限账号 |
| `REDIS_HOST/REDIS_PORT/REDIS_PASSWORD/REDIS_DATABASE` | Redis Session 与列表缓存 |
| `COS_HOST/COS_REGION/COS_BUCKET/COS_SECRET_ID/COS_SECRET_KEY` | COS 对象域名与服务端凭据；密钥仅存在服务器配置中 |
| `CORS_ALLOWED_ORIGINS` | 逗号分隔的完整前端来源，如 `https://gallery.example.com`；不能有通配符、路径或尾部 `/` |
| `PICTURE_UPLOAD_ALLOWED_HOSTS` | URL 下载的精确主机白名单，逗号分隔；填写可信图片来源及 AI 实际返回的结果下载域名，不能只填 COS 源域名 |
| `PICTURE_UPLOAD_REQUIRE_ALLOWLIST` | 公共默认 `false`，部署示例设置 `true`；此时白名单为空会直接拒绝 URL 下载，不会请求远端 |
| `INITIAL_USER_PASSWORD` | 仅管理员创建账号接口需要；至少 8 位，最多 72 个 UTF-8 字节；留空时该接口拒绝创建 |
| `SERVER_SERVLET_SESSION_COOKIE_SECURE` | 浏览器通过 HTTPS 访问 API 时设为 `true`，本地 HTTP 开发为 `false` |
| `SERVER_SERVLET_SESSION_COOKIE_HTTP_ONLY/SAME_SITE` | 示例为 `true/lax`，优先让前端与 API 使用同一站点 |
| `SPRING_SESSION_REDIS_NAMESPACE` | 区分开发/测试/部署环境的 Session，示例 `star-picture:session` |
| `AI_OUT_PAINTING_ENABLED` | 默认 `false`；在部署环境完成小额联调后再开启 |
| `AI_OUT_PAINTING_ALLOWED_USER_IDS` | 默认空，只允许管理员创建付费任务；逗号分隔明确授权的普通账号 ID，查询和保存仍要求任务本人 |
| `DASHSCOPE_API_KEY/DASHSCOPE_WORKSPACE_ID` | AI 服务端密钥与北京地域工作空间 |
| `AI_OUT_PAINTING_DAILY_LIMIT/ACTIVE_LIMIT/POLL_INTERVAL_SECONDS` | 默认 `5/2/5`，用于本地额度与查询节流，不能替代供应商账单监控 |

`PICTURE_UPLOAD_REQUIRE_ALLOWLIST=false` 且主机列表留空时仅做 HTTP(S)、端口和解析地址检查，并非关闭 URL 上传。部署示例要求白名单非空；替换示例主机并增加实际 AI 结果下载域名后，才能保存对应结果。不能为 AI 单独绕开安全下载策略。部署环境还应使用网络出口策略阻断内网/元数据网段。当前校验与实际下载分别解析 DNS，尚未实现连接时地址绑定；仅靠应用校验不能宣称彻底解决 DNS 重绑定。URL 下载禁用重定向，HEAD 不支持时退回有大小限制的 GET。

如必须使用 YAML，可将 `application-prod.yml` 放到 `/etc/star-picture/`，并在 Java 参数追加 `--spring.profiles.active=prod --spring.config.additional-location=file:/etc/star-picture/`；该目录中的真实配置也不应提交。

## 4. systemd 启动

示例使用专用无登录用户，主机需已安装 Java 17，`/usr/bin/java` 指向正确版本：

```bash
sudo useradd --system --shell /usr/sbin/nologin starpicture
sudo install -d -o root -g starpicture -m 750 /opt/star-picture /etc/star-picture
sudo install -o root -g starpicture -m 640 target/Star-pic-backend-0.0.1-SNAPSHOT.jar /opt/star-picture/app.jar
sudo install -o root -g root -m 600 .env.example /etc/star-picture/star-picture.env
sudoedit /etc/star-picture/star-picture.env
sudo install -o root -g root -m 644 deploy/star-picture.service /etc/systemd/system/star-picture.service
sudo systemctl daemon-reload
sudo systemctl enable --now star-picture
sudo systemctl status star-picture
sudo journalctl -u star-picture -n 100 --no-pager
```

该配置用 JVM 临时目录处理上传临时文件，通过 `PrivateTmp` 与其他进程隔离。后台 COS 清理使用 4 个核心线程、最多 8 个线程、100 个队列槽；队列满时调用线程执行任务，因此高压力下请求延迟可能增加。停止时最多等待 30 秒完成后台任务，systemd 的停止宽限为 45 秒。它是有界的尽力补偿，不是持久任务队列；硬停或主机故障仍可能留下孤儿对象，需靠清理日志与定期核对补偿。

只开放反向代理的 HTTPS 入口，应用端口限制在受控网络；MySQL/Redis 不对公网暴露。反向代理保留 `/api` 前缀和 Cookie，前端请求携带凭据。优先使用同一站点的前端/API；若必须跨站部署，需独立验证 `SameSite=None; Secure` 和浏览器的第三方 Cookie 策略。CORS 是浏览器来源约束，不是登录鉴权，也不提供 CSRF Token；不可信前端来源不能加入白名单，额外的跨站防护仍需根据开放方式验证。开发文档入口及 API 描述文件应限制为管理员/内网可访问。

## 5. 真实环境验收

以下内容属于目标环境联调，隔离测试通过不代表已经通过这些检查：

| 验收 | 方法与预期 |
| --- | --- |
| 登录与密码 | 新注册保存 BCrypt；旧 MD5 账号首次正确登录后升级，错误密码不写入；登录前后 Session ID 变化；前端 Cookie/跨域行为符合配置 |
| 公共/私有权限 | 两个普通账号分别创建私有空间；互相不能查看、替换或批量编辑；未审核公共图不能被匿名列表/详情返回 |
| 上传与对象权限 | 验证正常 JPG/PNG/WebP、伪造扩展名、超大文件和无 Content-Length 的下载；核对公共/私有原图与衍生图 ACL；未签名私有地址应无法直接读取 |
| 配额与并发 | 在真实 MySQL 上并发上传/替换/删除，核对行锁、计数/大小不超额、失败回滚、历史超额空间可逐步删除释放 |
| 审核与替换 | 请求带上审核页面中的 `expectedUrl`；源图替换后旧页面审核请求应失败，重新加载后才能审核 |
| 缓存与 Redis | 比较缓存命中/回源结果；上传/审核/批量修改后提交事务并失效；断开并恢复 Redis，确认降级与恢复以及 Session 故障表现；测试重启和版本键丢失场景 |
| AI | 先检查供应商账号额度/费用；开启后仅用少量测试图；创建需管理员或被明确授权的用户 ID，检查未授权账号拒绝创建、查询/失败/过期、他人任务禁止访问、每日/活动额度、私有源图读取、重复保存及配额不足 |
| 关闭与恢复 | 正常停止观察清理任务，重启检查数据库/Session；模拟 COS 失败与进程退出，核对清理日志及遗留对象 |

缓存采取最终一致性。正常写入在事务提交后更换公共缓存版本，Redis 故障时回退查询并记录本进程恢复标记；如果故障期间进程退出，未传播的失效可能延续到旧条目 TTL 到期；L1 为 2 分钟，L2 为随机 5～10 分钟。在 L2 到期前回填的本地副本还可能再延续 2 分钟，常规完成时序下约 12 分钟；这不是硬实时上限。不能把本地恢复标记说成持久保证，也不能称为强一致性或零陈旧。

## 6. 发布与回退

发布前备份目标数据库、记录当前 JAR 与迁移版本、确认新旧版本的表结构兼容。停止服务后替换 `/opt/star-picture/app.jar`，再启动并检查登录、上传和业务日志。失败时恢复上一份兼容 JAR；数据库结构或数据更改不能通过替换 JAR 自动回退，需按备份和兼容性单独处理。

此项目尚没有 HTTP 健康检查端点、持久补偿队列或实际性能基线。CI 只证明隔离测试与构建结果；真实云服务费用、运维和可用性需要在部署环境继续观察。