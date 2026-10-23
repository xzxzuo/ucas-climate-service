# 业务平台实际验证记录

## 最新：2026-10-22 统一 Spring MVC / MyBatis 主链路

对应架构见 [unified-architecture.md](unified-architecture.md)，可编辑图源见 [architecture.mmd](architecture.mmd)。本轮没有读取 45 GB 原文件，没有清空已有业务数据，也没有发布外部站点。

实际变更：新增 `PlatformClimateController`、`PlatformQuery`、`PlatformClimateMapper` 及 XML；`PlatformQueries` 不再直接操作 JdbcTemplate 或拼接 SQL。站点、版本、五类结果查询和异步导出取数通过 MyBatis；缓存复用统一 QueryCache。用户/任务管理和批量计算仍可使用 JdbcTemplate，不声称全平台都已改为 MyBatis。

### 构建与真实调用链测试

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 CLIMATE_IT=true mvn -B -ntp clean verify
```

2026-10-22 23:01:04 +02:00 完成，**54 tests，0 failures，0 errors，0 skipped**，BUILD SUCCESS。日志 `.runtime/logs/mybatis-unification-tests.log`；JaCoCo 行覆盖 742/820（90.49%），分支 392/603（65.01%）。

其中平台真实 MySQL / HTTP 集成测试从 13 增为 16 个，缓存单元测试从 1 增为 3 个。新增覆盖：

- 安装测试专用 MyBatis Executor 插件，记录真实执行的 MappedStatement，而非 mock Mapper；站点/版本列表和五类查询均验证对应执行。
- 五类查询校验分页、count、绑定的数据集/版本，校验逐日 HTD 和地区中位数等已知值；类似 SQL 的地区输入作为绑定值匹配空结果。
- 第二次热缓存命中不执行结果表 `selectPage/count`，但仍做授权与版本检查；撤销共享后返回 404，不能利用热缓存越权。
- 五类异步 CSV 导出均执行同一个 Mapper `selectPage`，并可实际下载；原有固定旧版本导出测试继续通过。
- 缓存按数据集/版本/分页参数隔离，保留高精度 BigDecimal；读写 Redis 失败都回源，禁用缓存不访问 Redis 或旧全局版本表。

此轮测试发现并修复地区逐日 SQL 中 `maxValue` 别名与 MySQL 保留字的冲突。集成测试仅清理名为 `climate_rebuild_test` 的测试数据库，测试准备代码会先校验 `_test` 后缀。

### 新镜像真实业务验证

只执行 `docker compose --env-file .runtime/auth.env up -d --build --no-deps app` 替换 app，保留原 MySQL/Redis 和私有文件卷。随后运行：

```bash
python3 scripts/platform.py verify
python3 scripts/verify_live.py --report .runtime/mybatis-legacy-oracle.json
```

- 2026-10-22T21:03:06Z，平台 **36 项检查全部通过**，报告 `.runtime/platform-verification.json`，日志 `.runtime/logs/mybatis-platform-live.log`。与旧版 27 项相比，新增站点列表核对、另外四类分页结果以及对应四类 CSV 的筛选范围/行数/版本核对。
- 使用已有的 26,004 行小样本，真实上传两份独立数据集、导入、计算、查询与导出；新建验证数据集为 7、8，带有明确的端到端验证标签。没有删除历史用户或数据集。
- 所有旧接口公式核对仍 PASS，**11,895 项断言通过**；样本观测 26,004 行，逐日结果 4,185 行。独立 Decimal oracle 覆盖旧 API、本次数值缓存改造和结果一致性。
- 替换镜像后、此次验证尚未覆盖原报告前，已执行 `persistence-check`：2026-10-19 保存的数据集/任务/私有 CSV 仍可读取。新验证报告还记录了四份额外导出，后续 `persistence-check` 会检查全部五类导出文件。
- 随后再次 `docker compose ... restart app`，等待 health 正常后重跑 `persistence-check`，实际读取新验证的数据集、八个成功任务和全部五类私有 CSV 均 PASS。日志 `.runtime/logs/mybatis-persistence.log`。Session 不跨重启保留，页面需要重新登录。

旧平台缓存 Key 不再读取，会按 TTL 自然过期；未执行 Redis 全库清空。V1/V2/V3 和两份公式 SQL 未改动，无需重建数据库。默认只有本机访问，不代表完成了 45 GB 容量、并发或生产安全验收。图中的“重点模块”用于复盘，不构成当年全部由本人负责的证明。

---

以下为 **2026-10-19 业务平台首版的历史验证记录**，保留当时的测试数量与标识，不代表最新构建数量。

日期：2026-10-19。本文件记录当时真正执行的验证，不是待办清单。没有读取 45 GB 原文件，也没有对外发布站点。

## 1. 干净构建与测试

执行：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 CLIMATE_IT=true mvn -B -ntp clean verify
```

最终 BUILD SUCCESS，49 个测试全部通过：0 failures、0 errors、0 skipped。构建日志 `.runtime/logs/final-platform-tests.log`。

- 原服务 33 个测试继续通过，覆盖计算、认证、缓存、增量回滚与真正的旧版 Spring 定时触发。
- 新平台 13 个真实 MySQL / HTTP 集成测试通过：Session/CSRF、角色、数据集所有权与授权撤销、跨数据集同站同日隔离、文件去重、导入整批回滚、失败重算保留旧结果、原公式一致性、固定版本导出、下载权限/期限、禁用账号、重启恢复、输入校验、统一收件箱、轮转防饥饿。
- 新平台 3 个单元测试通过：CSV 公式前缀防护、私有路径校验、BCrypt 字节长度限制。

JUnit 集成测试全部在名字以 `_test` 结尾的专用数据库运行，测试准备代码会检查数据库名后才删除测试记录。未对正式数据执行测试清空。

最后一次 JaCoCo：行 715/808（88.49%），分支 392/621（63.12%）。报告 `target/site/jacoco/index.html`。这是代码覆盖率，不是生产安全或无故障证明。

## 2. Docker 中的真实业务流程

最终镜像启动后执行：

```bash
python3 scripts/platform.py verify
```

2026-10-19T19:14:42Z 完成，27 项检查全部通过，报告 `.runtime/platform-verification.json`。

验证内容包括：

1. 未登录业务访问被拒绝，Cookie 登录正常，CSRF 保护修改操作。
2. 管理员创建研究人员与查看人员；普通研究人员不能管理账号，查看人员不能创建数据集。
3. 私人数据集对另一用户不可见。
4. 真正上传现有 26,004 行标准化样本；同数据集同内容去重，另一数据集可合法使用同样内容。
5. 两份数据集真实事务导入、独立计数、真实执行指标 SQL，并发布结果 v1。
6. Redis 回填/命中不改变计算数值；READ 共享可查询但不能计算，撤销后不能绕过已热缓存。
7. 真正执行 CSV 导出与新版本计算，导出仍固定选择的 v1，当前结果更新为 v2。
8. 下载有权限检查，另一用户不能直接使用 UUID 下载文件。
9. 错误湿度文件异步失败，观测数、数据修订和已发布结果保持不变；人工重试生成新任务并保留失败历史。
10. 下载等关键动作有审计记录。

最新一次保留的数据集是 4、5；导出文件 ID 为 `aa95af50-07e0-494c-b198-e23bf5fc6a24`。这些标识仅对应本次本机验证，重新运行脚本后会变化。验证账号和数据集均明确命名，不冒充真实科研用户，也未自动删除已有数据。

## 3. 真正的 Spring 定时增量触发

临时设置 `CLIMATE_PLATFORM_INBOX_ENABLED=true`，重建 app 后通过 HTTP 创建“定时增量验证 20261019”（数据集 6）。将已有小样本投递到该数据集收件箱，先写 `.part`，再在容器内原子改名为 `.csv`。

没有手动调用扫描或处理方法，由真实 Spring 定时器自动创建任务：

- 任务：`68e43983-1a71-4ee7-a585-e31037889689`。
- 观察状态：RUNNING → SUCCESS。
- 实际导入：26,004 条观测。
- `AUTO_IMPORT_AS_OWNER` 审计记录存在。
- 报告：`.runtime/platform-inbox-verification.json`。

验证完成后已将收件箱恢复为默认关闭；原投递文件、导入数据和任务历史保留。该入口只自动导入，不自动发布新指标；数据集 6 没有结果版本是预期行为，可以在页面提交计算任务。

## 4. 重建后持久化

应用使用新的最终镜像替换容器，MySQL 和 platform-data 卷均保留。随后执行：

```bash
python3 scripts/platform.py persistence-check
```

验证数据集与原始观测、成功计算任务历史、结果版本，以及**实际私有导出文件内容**仍可读取。不是只检查数据库里存在一个文件路径。

Session 不持久化，容器重启后需要重新登录，这是当前设计，不是数据丢失。

## 5. 原服务回归与公式核对

执行只读独立 Decimal oracle：

```bash
python3 scripts/verify_live.py --report .runtime/platform-legacy-oracle.json
```

结果 PASS，11,895 项断言通过；原样本 26,004 行、逐日结果 4,185 行。包括所有站点阈值、每站最多 200 条逐日记录、年度计数、地区统计、真实 Redis HIT、绑定参数、安全校验和 CSV 行数。

另外与升级前源码归档逐字节比对，V1、V2 和原 `sql/rebuild.sql` 的 SHA-256 一致。V3 只新增平台对象；没有覆盖原查询服务的数据表或更改历史迁移。

## 6. 浏览器实际操作

通过应用内浏览器实际完成：

- 使用本机生成的管理员凭据登录，凭据没有打印进工具结果。
- 打开只读示例，核对三个真实站点、26,004 条观测与 4,185 条逐日结果。
- 切换结果类型，确认只显示相关查询字段，表格显示单位与缺失说明。
- 选择 CARPIQUET 的 1993-07-01 至 1993-07-08，实际查询七条逐日记录并绘制 HTD 图表。
- 原生截图保存在 [platform-analysis.png](platform-analysis.png)，不包含密码。

前端使用 Sites 技能的本地工作流和浏览器验证，仍由 Spring Boot 同源提供静态文件，没有创建、上传或发布外部 Sites。`node --check` 和 Python 脚本语法检查通过。

## 7. 验证限度

没有做 45 GB 全量导入、并发压测、数据库断电、多实例租约、跨机器故障恢复或公开互联网安全验收。没有短信、真实支付、外部模型或云资源调用。

当前为单实例、单 Worker 内部研究平台；文件和数据库不是跨系统原子事务，旧结果和文件还没有自动清理策略。默认仅本机访问，不应当作已经具备生产运维保障的商业多租户平台。
