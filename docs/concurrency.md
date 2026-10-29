# 多人并行任务执行

多个用户可以同时提交任务。Web 请求只负责权限、校验、文件暂存和事务入队；一个轻量调度器从 MySQL 认领任务，交给真正的后台执行线程。默认有两个写执行线程和一个独立导出线程。

## 执行规则

| 场景 | 行为 |
| --- | --- |
| 不同数据集的 IMPORT / ANALYZE | 最多同时执行 2 个，可配置 |
| 同一数据集的 IMPORT / ANALYZE | 按 `created_at,id` 顺序互斥，后续任务继续 QUEUED |
| 固定结果版本的 EXPORT | 独立执行池，默认同时执行 1 个；可以与同数据集计算同时进行 |
| 两个写线程都忙 | 其他写任务留在 MySQL；仍可认领导出 |
| 队首数据集繁忙 | 跳过其后续写任务，继续寻找其他可运行数据集或导出 |
| 等待和运行任务合计达到 100 | 提交返回 503；全局配额检查仍由 queue_guard 短事务保护 |

全局不承诺严格 FIFO：独立数据集可越过忙碌的数据集，不同任务也可以乱序完成。同一数据集的写入顺序以已提交任务的数据库排序为准，不以鼠标点击时刻为准。EXPORT 固定的是已发布结果，不等待尚未发布的写任务。

没有无界内存队列。两个 `ThreadPoolExecutor` 使用 `SynchronousQueue` 和拒绝策略，调度器只认领有执行容量的任务。线程切换或关闭导致拒绝时，任务恢复 QUEUED、清空 started_at，下一轮再试。提交成功只表示任务已持久化，不表示执行完成。

## 数据集与版本保护

调度器用进程内 map 记录正在写入的数据集，避免用线程和数据库连接排队等同一数据集。V4 新增 `platform_dataset_write_guard`，为已有数据集补齐锁行；新建数据集与示例初始化也在同一事务中创建锁行。

`PlatformProcessor.process()` 使用 `READ_COMMITTED` 事务。IMPORT/ANALYZE 在读写观测、修订和版本前，对该数据集的独立锁行执行 `SELECT ... FOR UPDATE`。这也保护绕过调度器直接调用处理器的代码路径；第二个写入者取得锁后可以读到上一事务提交的最新版本，而不是旧的 REPEATABLE READ 快照。

锁的是专用子表，不是 `platform_dataset` 父行，避免长时间阻塞新上传、任务入队的外键检查。导出不取写锁，仍经过账号、数据集权限、版本检查和同一个 MyBatis Mapper，始终使用提交时固定的不可变结果版本。

计算结果、`platform_analysis_run`、active/next version 和 SUCCESS 在业务事务中一起提交。失败回滚仍保留上次成功版本；失败状态另开事务记录，最后释放执行槽和本地数据集占用。进度使用 REQUIRES_NEW，只代表尝试处理进度，不代表外层事务已经提交。

## 配置与升级

```text
CLIMATE_PLATFORM_WRITE_THREADS=2
CLIMATE_PLATFORM_EXPORT_THREADS=1
DB_POOL_SIZE=10
```

写线程和导出线程各允许 1–8。启动校验连接池至少为 `2 * (writeThreads + exportThreads) + 2`，为并行任务、独立进度事务和请求预留连接。增加线程不等于提高吞吐，应观察数据库、CPU、内存与任务耗时后调节。

1. 正常停止已有 app，使旧单线程任务完成或在重启时被标记 INTERRUPTED。
2. 使用新代码重建并启动单个 app。Flyway 执行 V4，不修改 V1–V3，不清空数据。
3. 登录后同时向两个独立数据集提交计算，在任务中心可看到多个 RUNNING。线程日志以 `climate-write-*`、`climate-export-*` 区分。
4. 向同一数据集连续提交两个写任务，第二个保持 QUEUED，直到第一个提交/回滚。

Docker Compose 已传递这三个环境变量。仅调整配置后重新创建 app 即可；不要删除 MySQL 或 platform-data 卷。设置 write-threads=1 可以降低写并行度，导出仍保持独立。

## 调度、关闭与边界

任务调度器和收件箱分别使用 `platformTaskScheduler`、`platformInboxScheduler`，各自只有一个轻量调度线程。计算和导出在执行池上运行，调度线程不执行长任务。

关闭时先停止认领，最多等待 30 秒让在途任务结束，再中断未结束线程。文件锁保留至本地在途任务退出，防止旧线程尚未结束便让同目录新 Worker 接管。未领取的 QUEUED 保留；重启仍把遗留 RUNNING 标记 INTERRUPTED，用户手动重试并保留旧历史。

这是单应用实例内的并行执行，不是多实例消费协议。不要运行两份 app 连接同一业务库；本地文件锁不能约束不同目录/机器，启动恢复也没有分布式 owner/lease。文件与数据库不构成同一事务，仍需备份、文件对账与清理。没有生产 QPS、全量数据容量或严格 exactly-once 承诺。

## 验证

设置 `CLIMATE_IT=true`，连接独立的 `_test` MySQL 数据库和专用 Redis，再执行 `mvn -B -ntp clean verify`。默认未开启集成测试时，不应把跳过说成完整验收。

`PlatformConcurrencyIntegrationTest` 通过真实账号 Session、HTTP、MySQL 和处理器验证：两位科研人员的计算事务在不同线程同时到达屏障；同数据集顺序和忙队首跳过；写池满时固定版本导出成功；直接并发调用处理器的数据库写锁；计算期间新上传不被父行锁阻塞；失败和拒绝释放容量；并发提交不超过 100；关闭保留等待任务。

`PlatformSchedulingIntegrationTest` 另验证真实 Spring 定时器自动并发计算，不通过测试直接调用 dispatch/runOnce。屏障只协调真实进度写入的时机，实际导入、计算 SQL、版本提交、导出和下载都不使用假实现。
