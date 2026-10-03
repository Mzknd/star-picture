# Star Picture

基于 Spring Boot、MySQL、Redis 和腾讯云 COS 的图库后端学习项目。

## 项目来源

本项目根据程序员鱼皮的智能云图库教学项目跟做，参考：
本地参考项目 `yu-picture-master`，教程主页：https://www.codefather.cn/vip 。保留来源说明，不宣称教学设计为原创。
后续在自己的代码上修正权限、配额和缓存问题，并补齐简历范围内的 AI 扩图。

## 初始版本状态

已有用户登录、公共图库、私有空间、两种上传方式、COS 图片处理、批量抓取与编辑、检索和两级缓存代码。
本次初始提交保存现有实现，将真实配置排除出版本管理，并移除管理员创建账号的硬编码初始密码（该接口需设置 `INITIAL_USER_PASSWORD`）。
权限边界、私有列表、并发配额、替换统计、文件清理和 AI 扩图仍待后续提交完善。
此版本尚未通过完整运行验证，不应直接开放公网。

## 开发环境

- JDK 17、Maven 3.8+、MySQL 8、Redis。
- 初始化数据库：执行 `SQL/create_table.sql`（初始脚本包含追加列操作，请勿重复执行）。
- 配置数据库和 Redis；配置 COS 桶、地域、密钥及可访问的图片域名。
- 公共配置通过环境变量读取：`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`REDIS_HOST`、`REDIS_PASSWORD`、`COS_HOST`、`COS_SECRET_ID`、`COS_SECRET_KEY`、`COS_REGION`、`COS_BUCKET`。
- 已有 `application-local.yml` 只保留在本地，使用 `local` profile 启动，禁止提交真实配置。
- 接口前缀为 `/api`，开发接口文档地址为 `/api/doc.html`。

## 开发边界

优先完成简历中的公共/私有图库、上传优化、批量管理、多维检索、多级缓存、事务配额、注解鉴权及 AI 扩图。
暂不扩展团队空间、WebSocket 协同、分库分表或微服务。
所有性能提升数据需在本项目实测后才能写入简历。
