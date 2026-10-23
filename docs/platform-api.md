# 业务平台 API

Base：`http://127.0.0.1:8087/platform/api`。与旧 `/api/**` 不同，这里使用 Session Cookie 和 CSRF，不接受 X-API-Key 作为个人账号。

当前工作台的站点、分析、导出请求由 `PlatformClimateController`（Spring MVC）接收，调用 `PlatformQueries`（Service），经统一 `QueryCache` 与 `PlatformClimateMapper.xml` 取数。接口路径、登录方式和 JSON 契约不变；不再使用 JdbcTemplate 作为气象查询数据访问层。详见 [统一架构](unified-architecture.md)。

## 认证流程

1. `GET /csrf`，获得 `{headerName,token}` 并保留服务端设置的 Cookie。
2. `POST /login`，Content-Type 为 `application/x-www-form-urlencoded`，提交 username/password，并带上返回的 CSRF 请求头与 Cookie。
3. 登录成功 200，Session ID 由安全框架更新；再次 `GET /csrf` 获取登录后 Token。
4. 后续请求带 Cookie；POST/PATCH 等状态修改还须带 CSRF 头。
5. `POST /logout` 注销会话，同样需要 CSRF。

没有认证的读取返回 401；缺少/错误 CSRF 的状态修改返回 403。禁用账号的业务访问返回 401。没有权限访问某个数据集及其任务/导出时返回 404；有读取但没有写入权限的修改返回 403。

用户名需为 3–40 位小写 ASCII，以字母开头，允许数字、`.`、`_`、`-`。密码至少 12 字符，最多 72 UTF-8 字节。5 次连续失败锁定 5 分钟。没有公众注册或密码重置 API。

## 路由

以下路径均相对 Base。除 csrf/login 外，均要求已登录且账号启用。

| 方法 | 路径 | 权限与用途 |
| --- | --- | --- |
| GET | `/csrf` | 获取 CSRF Token，可匿名 |
| POST | `/login` | 账号密码表单登录，可匿名但要 CSRF |
| POST | `/logout` | 注销会话 |
| GET | `/me` | 当前账号 id/username/displayName/role/enabled，不返回密码摘要 |
| GET | `/admin/users` | ADMIN；最多 200 条账号 |
| POST | `/admin/users` | ADMIN；创建账号，201 |
| PATCH | `/admin/users/{id}/enabled` | ADMIN；启用/停用，不能停用自己 |
| GET | `/datasets` | 最多 100 个可访问数据集 |
| POST | `/datasets` | ADMIN/RESEARCHER；创建，201 |
| GET | `/datasets/{id}` | 数据集详情、最多 100 个结果版本、共享成员 |
| POST | `/datasets/{id}/clone` | 仅复制只读示例为当前用户的私人数据集，201 |
| POST | `/datasets/{id}/members` | 所有者/ADMIN；READ/WRITE/REMOVE |
| GET | `/datasets/{id}/files` | 数据集读取权限；最多 100 个文件记录 |
| POST | `/datasets/{id}/files` | 数据集写权限；multipart file，202 |
| GET | `/datasets/{id}/stations` | 数据集读取权限；最多 200 个当前原始数据站点 |
| GET | `/datasets/{id}/analysis` | 数据集读取权限；版本化结果查询 |
| POST | `/datasets/{id}/analyze` | 数据集写权限；创建计算任务，202 |
| POST | `/datasets/{id}/exports` | 数据集读取权限；创建固定版本导出任务，202 |
| GET | `/tasks` | 当前可访问任务，支持 datasetId/limit/offset |
| GET | `/tasks/{uuid}` | 当前有数据集读取权限才能查看；完成导出附 export 元数据 |
| POST | `/tasks/{uuid}/retry` | 仅 FAILED/INTERRUPTED，新 ID；再次检查操作权限，202 |
| GET | `/exports` | 最多 100 条可访问导出，支持 datasetId |
| GET | `/exports/{uuid}/download` | 再次校验权限与期限，返回 CSV；过期/文件缺失返回 410 |
| GET | `/template.csv` | 下载空白标准化字段模板 |
| GET | `/audit` | 最多 100 条可访问操作记录，支持 datasetId |

## 业务请求示例

创建账号（ADMIN）：

```json
{"username":"researcher01","displayName":"研究人员一","password":"自行生成的私密密码，至少12字符","role":"RESEARCHER"}
```

创建数据集，或给 clone 请求传入新名称：

```json
{"name":"四川站点极端气候分析","description":"真实数据样本的个人复建实验"}
```

共享授权：

```json
{"username":"researcher02","permission":"READ"}
```

账号启停：`{"enabled":false}`。不能给 VIEWER 授予 WRITE，也不能通过共享 API 修改所有者权限。

上传响应：

```json
{"duplicate":false,"file":{"id":"文件UUID","status":"STAGED","sha256":"内容指纹"},"task":{"id":"任务UUID","task_type":"IMPORT","status":"QUEUED"}}
```

上述示例省略其他元数据。重复文件返回 `duplicate:true` 和最近一条已有任务，不自动重新创建失败任务。竞争重复上传可能返回 409。上传超过 8 MiB 返回 413。

## 查询参数

`GET /datasets/{id}/analysis`：

| kind | 参数 |
| --- | --- |
| THRESHOLDS | 无额外必填参数 |
| DAILY | station 必填，11 位；from 默认 1993-01-01，to 默认 2024-01-01，区间 `[from,to)` |
| STATION_ANNUAL | station 必填；year 默认 1993，允许 1993–2100 |
| REGION_DAILY | date 默认 1993-01-01；level 默认 COUNTRY；metric 默认 HTD |
| REGION_ANNUAL | year 默认 1993；level 默认 COUNTRY；metric 默认 HTD |

通用：version 可选，默认当前成功结果；limit 默认 100，允许 1–500；offset 默认 0，允许 0–1000000。地区查询可额外传 country/province/city/district，均作为 SQL 绑定值，名称最多 96 字符。level 为 COUNTRY/PROVINCE/CITY/DISTRICT；metric 为原有十三指标之一，但地区日结果只存在七类幅度指标。

响应：

```json
{
  "datasetId": 2,
  "resultVersion": 1,
  "parameters": {"kind":"THRESHOLDS"},
  "items": [{"stationCode":"57328099999","temp10":43.9,"temp90":82.4}],
  "total": 3,
  "limit": 100,
  "offset": 0
}
```

示例 items 只保留部分字段。缺失值返回 null。缓存状态通过 X-Cache 返回 MISS/HIT/BYPASS/UNAVAILABLE；逐日明细不缓存。权限检查在缓存读取前，缓存 Key 含数据集 ID、固定结果版本和规范化参数摘要；缓存反序列化保留 BigDecimal，不降为 Double。

没有成功结果返回 409；指定不存在的版本返回 404。查询站点、日期、地区只筛选已有结果，不改变指标公式。

## 任务与导出

计算请求不需要 body。导出请求 body 使用相同查询参数，可显式指定 version：

```json
{"kind":"DAILY","version":"1","station":"57328099999","from":"1993-01-01","to":"1994-01-01"}
```

计算/导出首次接收返回 202 和任务对象；202 是持久化接收，不是计算成功。

任务主要字段：id、dataset_id、created_by、task_type、status、phase、processed_rows、input_revision、result_version、retry_of、错误信息和时间。QUEUED/RUNNING/SUCCESS/FAILED/INTERRUPTED 是执行状态；阶段用于解释处理进展，不是精确百分比。

同一失败任务已有 QUEUED/RUNNING 重试时再提交返回 409；成功或运行中任务不能用 retry API 重试，重新计算请创建新的 ANALYZE 任务。后台任务开始前会再次检查提交者是否启用及是否仍有权限。

导出在接收时绑定一个已存在的成功版本；之后平台发布新版本，不改变此任务的目标。CSV 最大 20,000 行、32 MiB，超过则任务失败，不静默截断。生成文件私有保存，下载资格 7 天；路径中只用服务端 UUID，HTTP 不接受任意服务器文件路径。

## 错误与边界

常见错误 JSON：`{error,message,requestId}`。400 参数/数据格式错误，401 未登录/账号禁用，403 无修改权限或 CSRF，404 不存在/无权资源，409 冲突，410 导出过期/文件缺失，413 文件超限，503 数据库或积压限制。

积压上限 100 包含所有 QUEUED/RUNNING 类型；命中上限不承诺接收。任务和文件持久化不是跨文件系统的原子事务，重启恢复与备份范围见 platform-guide。
