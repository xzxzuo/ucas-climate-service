# API：认证、参数和增量管理

基础地址 `http://127.0.0.1:8087`。所有 `/api/**` 业务路由要求一个 `X-API-Key` 请求头。**不再支持匿名查询或 X-Admin-Key**。本机密钥在 `.runtime/auth.env`，不应复制进本文档。

## 1. 认证与权限

```bash
set -a
source .runtime/auth.env
set +a
```

- CLIMATE_READ_KEY → READER，可查询和导出。
- CLIMATE_ADMIN_KEY → ADMIN + READER，可管理和查询。
- 没有/错误密钥：401；认证有效但角色不足：403。
- API Key 不是 JWT，不是个人账户，没有用户级数据隔离或自动到期。
- `/`、`/index.html`、`/actuator/health` 可公开 GET。
- 页面填密钥后连接；导出走携带头的 fetch，不使用 URL 传密钥。

## 2. 业务接口

| 方法及路径 | 参数 | 说明 |
| --- | --- | --- |
| GET /api/meta | 无 | 计算版本、计数、基准窗口、单位和分位数口径 |
| GET /api/stations | 无 | 最多 200 个站点及日期覆盖范围 |
| GET /api/stations/{station}/thresholds | station 为 11 位数字 | 五阈值及各变量有效样本数；无基准返回 404 |
| GET /api/stations/{station}/indicators | from,to,limit,offset | `[from,to)`；limit=100，范围 1～500；offset=0，最大 100000 |
| GET /api/stations/{station}/annual | year | 年份 1993～2100，13 指标的非零/有效天数 |
| GET /api/aggregates/daily | date,level,metric，可选地区字段 | 七项幅度的地区日均值/最小/最大/中位数 |
| GET /api/aggregates/annual | year,level,metric，可选地区字段 | 各站年度非零天数的地区统计 |
| GET /api/exports/daily.csv | station,from,to | 最多 20000 行，500 行分页写出 |
| GET /api/exports/thresholds.csv | 无 | 最多 500 个站点 |
| GET /api/exports/station-annual.csv | station,year | 站点年度 13 指标 |
| GET /api/exports/region-daily.csv | 同地区日接口 | 最多 500 行 |
| GET /api/exports/region-annual.csv | 同地区年接口 | 最多 500 行 |

level 为 COUNTRY/PROVINCE/CITY/DISTRICT。地区参数 country/province/city/district 最大各 96 字符，使用绑定值，不是 SQL 片段。区县统计仅为中国生成。地区接口最多 500 行，暂未实现大规模地区分页。

日幅度指标：LTD/HTD/HR/EHR/LH/HTD1/CTD1；年统计另外支持 FOG/RAIN/SNOW/HAIL/THUNDER/TORNADO。

```bash
curl -sS -H "X-API-Key: $CLIMATE_READ_KEY" http://127.0.0.1:8087/api/meta
curl -i -H "X-API-Key: $CLIMATE_READ_KEY" \
  http://127.0.0.1:8087/api/stations/57328099999/thresholds
curl -sS -H "X-API-Key: $CLIMATE_READ_KEY" \
  'http://127.0.0.1:8087/api/stations/57328099999/indicators?from=1993-01-01&to=1994-01-01&limit=10&offset=0'
curl -f -H "X-API-Key: $CLIMATE_READ_KEY" -o climate.csv \
  'http://127.0.0.1:8087/api/exports/daily.csv?station=57328099999&from=1993-01-01&to=1994-01-01'
```

## 3. 管理员接口

| 方法及路径 | 参数 | 说明 |
| --- | --- | --- |
| POST /api/admin/rebuild | 无 | 对数据库已有观测全量重算；不接收文件路径 |
| GET /api/admin/cache | 无 | 命中/未命中/故障次数、当前缓存版本 |
| GET /api/admin/incremental/status | 无 | 是否自动启用、运行状态、目录、预算、lastRun |
| GET /api/admin/incremental/jobs | limit=20,offset=0 | limit 1～100，offset 0～10000；按完成时间与 ID 倒序 |
| POST /api/admin/incremental/run | retryFailed=false | 同步扫描一轮；true 时允许重试扫描到的失败文件 |

```bash
curl -sS -H "X-API-Key: $CLIMATE_ADMIN_KEY" \
  http://127.0.0.1:8087/api/admin/incremental/status
curl -sS -X POST -H "X-API-Key: $CLIMATE_ADMIN_KEY" \
  http://127.0.0.1:8087/api/admin/incremental/run
curl -sS -H "X-API-Key: $CLIMATE_ADMIN_KEY" \
  'http://127.0.0.1:8087/api/admin/incremental/jobs?limit=20&offset=0'
```

管理员手动扫描在自动开关关闭时仍可用。retryFailed 只作用于本轮发现的文件，不能凭历史 ID 找回已经被移走的文件内容。

## 4. 增量响应

下面是响应形状示意，标识符、计数和时间以实际为准：

```json
{
  "trigger": "MANUAL",
  "busy": false,
  "completedAt": "2026-10-17T10:00:00Z",
  "files": [
    {
      "fileName": "batch-001.csv",
      "status": "SUCCESS",
      "sha256": "64位内容摘要",
      "importedRows": 31,
      "revision": 5,
      "errorCode": null
    }
  ],
  "error": null
}
```

- HTTP 200 表示管理请求已得到处理，不代表全部文件成功；检查 files[].status、busy 和 error。
- SUCCESS：已原子提交；SKIPPED：相同内容已成功；DEFERRED：太新/退避/自动上限。
- FAILED：文件事务失败；REJECTED：文件或快照前置校验未通过。
- busy=true：本实例已有运行，本次不再执行。
- importedRows 为解析并 upsert 的记录数，**不是新 INSERT 行数**。
- lastRun 在内存，重启后清空；文件历史在 MySQL 中保留。
- jobs 是 JDBC 审计行列表，字段为 snake_case，例如 file_name、imported_rows、affected_stations、error_code、next_retry_at。
- 正常提交的 SUCCESS/FAILED 才是终态；RUNNING 是业务事务中的未提交中间状态，不是持久化调度租约。

## 5. 缓存与错误

阈值和地区汇总返回 X-Cache：

- HIT：命中 Redis。
- MISS：未命中后查库。
- UNAVAILABLE：Redis 异常，已回源数据库。
- BYPASS：缓存关闭。

TTL 1800 秒。键包含计算版本；事务提交后才切换进程内版本。当前要求单实例写入，不允许另开 CLI 导入后假定旧实例的缓存会自动获知新版本。

错误 JSON 包含 error/message/requestId，响应头携带 X-Request-Id。常见状态码：400 参数错误、401 未认证、403 无权限、404 无资源/基准、503 数据库不可用、500 非预期错误。鉴权失败与角色不足不重定向到登录页面。

完整事务、调度、文件投递和限制见根目录 [README](../README.md)。
