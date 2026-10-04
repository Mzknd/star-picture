# Star Picture

基于 Java 17、Spring Boot 2.7.6、MyBatis-Plus、MySQL、Redis 和腾讯云 COS 的图库后端学习项目，面向实习简历展示与面试复盘。实现公共图库、个人私有空间、图片上传管理和 AI 扩图闭环；部署范围为单机应用。

## 项目来源

基础功能参考程序员鱼皮的智能云图库教学项目，教程入口：[编程导航](https://www.codefather.cn/vip)，本地参考项目为 `yu-picture-master`。保留教学来源，不把参考项目的整体设计宣称为个人原创。此仓库的个人补充集中在权限边界、上传校验、事务配额、缓存失效、密码兼容及 AI 扩图任务管理。

## 实现范围

| 能力 | 当前实现 |
| --- | --- |
| 用户与鉴权 | Session + Redis 登录态、注解/AOP 管理员鉴权；新密码 BCrypt，旧盐值 MD5 账号成功登录时条件升级；登录轮换 Session ID；口令至少 8 位且不超过 72 个 UTF-8 字节 |
| 公共/私有图库 | 公开列表仅展示已审核图片；私有空间查询、详情、修改、批量操作校验归属；审核支持 `expectedUrl` 防止替换后的旧页面提交 |
| 上传与存储 | 本地文件、URL 上传和批量抓取；HEAD 预检、GET 流式大小上限、真实图片解析、像素上限；COS WebP/缩略图独立 Key；记录原图 Key 并提供失败补偿和旧对象清理 |
| 空间配额 | 事务内锁空间/图片、带条件配额更新；替换按大小差额计费；删除释放配额；非空空间禁止直接删除；缩限额不得低于当前使用量 |
| 查询与缓存 | 名称/简介、标签、分类、尺寸及颜色等查询；公共审核列表 Caffeine + Redis 两级缓存、缓存键规范化、TTL 抖动、事务提交后失效与 Redis 故障降级；L1 最多 1000 条/2 分钟，L2 TTL 5～10 分钟；采用最终一致性 |
| AI 扩图 | 默认关闭；本地任务记录、用户额度预留、云端异步提交、查询授权和状态更新、超时处理；结果保存到源图所属空间并复用上传/配额流程；重复保存返回同一图片 |
| 部署与验证 | 可执行 JAR、精确 CORS 来源白名单、COS HTTPS 与超时、有界后台线程池、systemd 示例；提供 GitHub Actions 隔离测试/打包模板，启用后不自动部署 |

本项目不包含团队空间、WebSocket 协同、微服务或分库分表。没有实际压测结果时，不填写性能提升百分比，也不把该项目描述为已经完成生产验收。

## 本地启动

需要 JDK 17、Maven 3.8+、MySQL 8、Redis，以及启用图片处理的 COS 桶。AI 非必需，默认不请求付费服务。

1. **新库**：执行 `SQL/create_table.sql`。**已有库**：先备份，确认现有表结构后按顺序执行 `SQL/migrations` 中未执行的迁移，禁止拿初始化脚本覆盖开发库。
2. 参照 [.env.example](.env.example) 设置环境变量。也可以用被 Git 忽略的 `application-local.yml`，但该文件不会打进 JAR，启动时需按下述命令外置加载。
3. 测试与打包：

   ```bash
   mvn clean verify
   ```

4. 使用环境变量启动：

   ```bash
   java -jar target/Star-pic-backend-0.0.1-SNAPSHOT.jar
   ```

   使用本地外置配置（把路径替换为实际目录）：

   ```bash
   java -jar target/Star-pic-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local --spring.config.additional-location=file:./src/main/resources/
   ```

接口前缀 `/api`，开发文档地址 `http://localhost:8080/api/doc.html`。Spring Boot 不会自动读取 `.env`；Linux 单机示例通过 systemd 的 `EnvironmentFile` 注入变量。前端本地默认允许 `http://localhost:5173`，其他来源需明确配置 `CORS_ALLOWED_ORIGINS`，跨域请求需携带凭据。

## AI 扩图接口

先登录并确认源图片可操作，再调用下面的接口。创建付费任务还需管理员身份，或账号 ID 已列入 `AI_OUT_PAINTING_ALLOWED_USER_IDS`；默认名单为空，仅管理员可创建。`taskId` 是本地任务 ID，不是供应商任务 ID。

```text
POST /api/picture/out_painting/create_task
{"pictureId":123,"parameters":{"xScale":1.5,"yScale":1.5}}

GET /api/picture/out_painting/get_task?taskId=本地任务ID

POST /api/picture/out_painting/save
{"taskId":456,"picName":"扩图结果"}
```

默认每人每天 5 次、最多 2 个活动任务，查询间隔至少 5 秒，未保存任务从创建起 24 小时过期；当天次数按 Asia/Shanghai 自然日统计，提交失败仍消耗次数。已保存任务不因到期重新失效，重复保存返回同一图片（图片删除或权限失效除外）。开启前配置 `DASHSCOPE_API_KEY`、北京地域工作空间 `DASHSCOPE_WORKSPACE_ID` 和 `AI_OUT_PAINTING_ENABLED=true`，需要普通演示账号创建时再明确配置 `AI_OUT_PAINTING_ALLOWED_USER_IDS`，在用户部署环境完成真实服务小额联调；本次未调用真实付费 AI 服务。生产示例要求 URL 下载白名单，需允许可信图片来源及供应商实际返回的 AI 结果域名，不能只填 COS 域名。任务结果 URL 仅在授权用户查询成功任务时返回；它是临时预览地址，点击保存后才成为图库中的持久图片。

## 验证边界

本次 `mvn clean verify` 已通过 64 项隔离测试，失败、错误及跳过均为 0，覆盖权限、事务配额、上传/URL 下载、缓存、密码兼容与 AI 任务；已生成可执行 JAR，并检查确认未打入 `application-local.yml`、`application-prod.yml` 或 `.env`。测试使用 Mock、H2 和本地 HTTP 服务，不依赖真实云服务密钥。H2 的 MySQL 模式用于验证本地事务逻辑，不能代替真实 MySQL 锁、索引和隔离级别验证。

真实 MySQL、Redis 故障恢复、COS 图片处理/ACL/CDN、以及付费 AI 服务尚待目标环境联调。桶须保持私有默认权限，并允许应用为公共原图和衍生图设置公共读；私有空间对象必须私有，已有旧对象的 ACL 不会自动修改。Redis 中断会同时影响登录态与缓存；缓存降级不意味着登录可以脱离 Redis。对象清理是可重试的后台补偿，进程突然退出仍可能留下孤儿对象；没有 `originalKey` 的历史图片无法完整回收历史原图。

CI 模板保存在 `deploy/github-actions-ci.yml.example`。当前 Git 推送令牌没有 `workflow` 权限，直接上传 `.github/workflows/ci.yml` 被 GitHub 拒绝，因此模板尚未启用；获得工作流写入权限后，可将模板复制到该路径再提交。当前 64 项结果来自本地隔离验证，不是远端 Actions 运行结果。

部署步骤和验收清单见 [docs/deployment.md](docs/deployment.md)，简历表述与面试追问见 [docs/interview-guide.md](docs/interview-guide.md)。