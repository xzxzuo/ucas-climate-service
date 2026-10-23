# 2026-10-17 实际验证记录

> 历史验证归档：旧业务代码已于 2026-10-23 清理。下列结果不表示当前仍提供这些 API；最新记录见 `docs/platform-verification.md`。

## 最新增强验收：接口鉴权与定时增量

以下为同日后续增强的最新结果；后面的初版记录保留作历史说明，不把早期“仅管理鉴权”当作当前实现。

- Spring Boot 3.5.16 / Spring Security 6.5.11；`CLIMATE_IT=true mvn -B -ntp clean package` 成功，**33 个 Java 测试通过、无跳过**。
- `ObservationTest` 3，`QueryCacheTest` 1，`ClimateIntegrationTest` 13，`FileSnapshotTest` 3，`IncrementalIntegrationTest` 5，`IncrementalServiceTest` 4，`ApiKeyFilterTest` 3，`ScheduledIncrementalIntegrationTest` 1。
- 真实 MySQL/Redis 下验证匿名/错误密钥 401、只读访问管理 403、管理员可访问；重复头拒绝，客户端角色字段不能提权。
- 文件指纹跨改名去重、同名订正、新日期导入、基准修订后历史重算，与全量重算后的地区结果一致。
- 501 条以上输入已经发生 JDBC 批量 flush 后再制造非法行，原始观测/派生结果/revision 仍全部回滚；地理口径冲突也能回滚。
- 失败记录、固定退避、自动最大次数和显式管理员重试通过；目录有界轮转、稳定期、过满拒绝、防重入通过。
- **真实 Spring @Scheduled** 在测试目录收到原子发布的 CSV 后自动导入，后续多轮不重复推进版本，不是只手工调用定时方法。
- 抽样器 4 个测试通过。原始样本独立 Decimal oracle 重新验证 **11,895 项断言通过**，原始观测仍为 26,004 条，日指标 4,185 条。
- 实际 8087 服务已升级，私有密钥文件 `.runtime/auth.env` 权限 600；自动调度已开启，原有样本保持不变。本次增强未重读 45 GB 原文件。
- `docker compose --env-file .runtime/auth.env config --quiet` 通过；Docker daemon 不可用，镜像构建与容器启动仍未实测。
- `python3 scripts/verify_access.py --run-once` 对正式 8087 实例通过 26 项 HTTP 状态/请求 ID 断言，确认自动开关 true、管理员手动空目录扫描正常；未给样本库添加合成观测。
- 五类携带只读密钥的 CSV 下载重新验证通过，输出放在 `.runtime/verification-exports/`，没有覆盖已保留的示例。
- 非 Web `--app.import-file ... --app.exit-after-import` 路径在专用 `_test` 库实测成功并正常退出；没有要求创建 Web 安全过滤链，也不影响正式服务。

## 初版验证记录（下述为增强前的历史结果）

## 环境与范围

本机 Java 17.0.14、Maven 3.9.9、MySQL 8.0.36、Redis 7.4.11。所有数据库均在项目 `.runtime/` 中，使用 13306 / 16379 端口；原始 45 GB CSV 保持原位，只做带读取上限的抽样。Docker Compose 配置通过 `docker compose config -q`；Docker daemon 当前不可用，因此没有把配置校验说成容器运行验证。

真实样本 26,004 条，覆盖 3 站点、20 个基准年份和 4 个分析年份。单次抽样实际读取 50,984,841 字节；为修正缺湿度行的布局判断，抽样执行两次，累计约 102 MB。完整单次元数据见 `data/sample-manifest.json`。

## 构建与测试

`CLIMATE_IT=true mvn -B -ntp package` 成功：**16 个 Java 测试全部通过，无跳过**。

- `ObservationTest` 3 个：站点与天气前导零、NOAA 缺失值、非法湿度/天气编码。
- `QueryCacheTest` 1 个：Redis 连接失败回源。
- `ClimateIntegrationTest` 12 个：真实 MySQL 与 Redis，验证五阈值、日幅度与天气拆分、奇偶中位数、日期分组、缺失值有效天数、重复导入、接口参数与管理访问限制、地区日接口、缓存版本切换、元数据冲突、计算完成后的事务回滚、五类 CSV 导出。

`python3 scripts/test_sampler.py` 的 **4 个测试通过**：双布局、前导零/哨兵缺失值、读取预算、湿度空值时沿用块级布局。

`python3 scripts/verify_live.py` 独立读取规范化小样本，使用 Python Decimal 排序和公式计算预期结果，再调用最终 Java API，**11,893 项断言通过**。覆盖全部站点阈值、每站前 200 个分析日的 13 指标、全部抽样站点年度频次、所有实际地区层级的年度均值/最大/最小/中位数、地区日统计、真实 Redis 命中、参数化查询及导出行数。可机器读取的报告见 `live-verification.json`。

## 实际结果表数量

| 表 | 行数 |
|---|---:|
| raw_observation | 26,004 |
| station_threshold | 3 |
| daily_indicator | 4,185 |
| region_daily_stat | 96,567 |
| station_year_stat | 156 |
| region_year_stat | 520 |

缺失基准而被排除的分析期观测：0。`region_daily_stat` 每行代表一个日期/地区/指标组合，所以它的行数大于日指标表，不代表新增观测。

## Redis 实际故障验证

短暂停止本项目 16379 端口 Redis，调用四川达县阈值接口：响应 **HTTP 200**、`X-Cache: UNAVAILABLE`，返回阈值 `43.9 / 82.4 / 0.83 / 2.09 / 0.5056259581`。随后恢复同一个专用 Redis 实例。正常连续访问可见 `MISS → HIT`。这项验证未停止 MySQL，也未更改原始数据。

## 实际索引执行计划

对站点 `57328099999`、1993 年时间范围查询前 100 行：

- 正常查询：`Index range scan ... using PRIMARY`，按 `(station_code, observation_date)` 范围扫描；返回 100 行，单次观测的算子耗时约 0.124 ms。
- 显式忽略相关索引：`Table scan` 扫描 4,185 行，筛出 365 行并排序，再返回 100 行；单次观测约 1.32 ms。

这是同一小样本的一次实际计划对照，说明索引改变了扫描与排序路径；它不是压测结果，不能当作原实习的提速数据。复现命令在 `scripts/explain.sql`。

## 演示和限制

在页面选择四川达县站点，点击“基准阈值”后，页面显示数据库阈值和 `缓存 HIT`。已保存 `demo.png`。五类示例 CSV 可用 `python3 scripts/export_demo.py` 导出到 `data/examples/`。

当前仅验证抽样规模；没有进行 45 GB 全量导入、全量科研结果验收、多实例运行、生产部署或并发容量压测。分位数选择经验 nearest-rank，与旧草稿的 `PERCENT_RANK` 边界取法有明确差异。全部功能与结论以本次重建版本为准。
