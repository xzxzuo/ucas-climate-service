# 2026-10-23 业务代码清理

目标：只保留 ClimateLab platform 业务，消除“新平台 + 旧气象查询系统”并行维护；不删除现有业务数据，不改变已执行迁移。

## 删除的重复/退役实现

- 旧 `ClimateController`、`IncrementalController` 及 `/api/**` 路由。
- 旧 `ClimateMapper`/XML、`ClimateQueryService`、`ExportService`；查询和导出统一使用平台 Mapper。
- `ApiKeyFilter` 和第二套 `SecurityConfig`；平台认证错误抽为普通 `SecurityErrors`，现在只有一条 Session/CSRF 安全链。
- 旧增量的 Properties、Scheduler、Service、FileProcessor、JobStore；只保留平台收件箱和持久化任务 Worker。
- 旧 `static/index.html` 及登录页/侧栏上的退役页面链接。
- 旧访问/导出/投递演示脚本：`verify_access.py`、`export_demo.py`、`publish_incremental_demo.py`。
- 与已删除功能一一对应的旧 API Key/旧增量测试；原计算断言迁移到初始化集成测试，而不是把计算验证一起丢掉。
- QueryCache 的旧全局 revision、JdbcTemplate、旧 API 缓存入口与无人读取的计数器；仅保留数据集/结果版本缓存。
- Docker、application.yml、原生启动中的旧密钥和旧增量配置要求。

## 保留、收拢或迁移的内容

- 平台站点、五类查询、导出仍是 `Spring MVC → Service → MyBatis XML → MySQL`，支持原有权限、缓存、版本与事务。
- 初始化仍确实需要原始表与原公式：四个必要类归入 `bootstrap/SampleLoadRunner`、`SampleLoader`、`SampleImporter`、`SampleAnalysis`。删去仅旧增量需要的按站点重算、导入站点集合、全局缓存版本回调。
- `FileSnapshot` 由旧增量包移到 `io/`；上传校验仍复用 `data/Observation`。
- `@EnableScheduling` 放在主应用上，避免平台定时器暗中依赖已删除的旧调度配置。
- `verify_live.py` 的独立 Decimal 计算保留，HTTP 数据源改为平台 Session API；默认只读，可显式 `--export` 创建一次异步验证导出。
- `local_auth.py` 新文件只生成管理员密码；旧私有文件原样保留已有 API Key 以便回滚，应用不读取、不传入 Docker。增加了临时文件回归测试，验证幂等、兼容与不覆盖凭据。
- README 改为单平台使用说明；旧说明移到 `docs/archive/` 并标记“历史、不可作为当前操作命令”。

## 没有删除什么

没有清空 MySQL、Redis 或平台文件卷；没有删除用户、数据集、既有任务、导出、种子样本或 45 GB 原数据。旧 `data/incremental/` 内的投递文件保留，只停止扫描。

V1/V2/V3 迁移与两份计算 SQL 的内容均保留；V2 废弃的历史任务表可继续存在，不因清代码而 DROP。未更改实习简历或把配套能力写成本人原有职责。

## 恢复与验证

清理前 Git 基线为 `19085522cf545bacc2babef79a41db1839e3f8d2`。已删除的跟踪代码可从该提交读取或恢复；本次没有提交、重置或清理 Git 工作区。历史文档还保存在 `docs/archive/`。

执行全量测试而非仅编译，包含新平台 MyBatis 实际执行、权限撤销、固定版本导出、原公式/回滚和真正的 Spring 收件箱/Worker 自动触发。旧入口即使传 X-API-Key 也没有处理器，GET 返回 404。最新数量、Docker 实测与公式断言见 [platform-verification.md](platform-verification.md)。

代码清理不等于生产安全审计或容量验证，仍是单实例内部平台。
