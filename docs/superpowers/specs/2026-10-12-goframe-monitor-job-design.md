# 批次 15：GoFrame 移植定时任务调度 `/monitor/job`（含调度引擎 + 任务执行）

## Summary

把 Java RuoYi 的定时任务模块（`ruoyi-quartz`，`SysJobController` 8 端点 + Quartz 调度 + 反射执行）移植到
GoFrame 后端（`ruoyi-goframe`），**真调度**：GoFrame 原生 `gcron` 承接调度与生命周期，用**编译期固定任务注册表**
替代 Spring bean 反射，新增一段自写 `cronutil` 补齐 `gcron` 缺失的「下次触发时间」与 cron 合法性校验。
调度器随进程启动即从 `sys_job` 全量加载并注册（对齐 Java `@PostConstruct init`），由 `config` 开关控制（默认开）。
前端 RuoYi-Vue3 的「定时任务」页不改即可对接（含逐行的 `nextValidTime`）。

契约基准（只读实过来的源）：`SysJobController.java`、`SysJobServiceImpl.java`、`ScheduleUtils.java`、
`JobInvokeUtil.java`、`AbstractQuartzJob.java`、`SysJob.java`（@Excel 8 列）、`SysJobMapper.xml`、
`ScheduleConstants.java`、`Constants.java`（`JOB_WHITELIST_STR={com.ruoyi.quartz.task}`、`JOB_ERROR_STR`、
`LOOKUP_RMI/LDAP/LDAPS`、`HTTP/HTTPS`、`SUCCESS="0"/FAIL="1"`）；`OperLogCleanTask`/`LogininforCleanTask`
两个真实任务 bean；种子 `insert into sys_job values(4, '操作日志定期清理','SYSTEM','operLogCleanTask.clean(\'90\')',
'0 0 3 * * ?','3','1','1','admin',sysdate(),'',null,'…分批5000条删除')`。

---

## 决策与取舍（已获用户批准）

- **执行方式**：真调度 + `config` 开关**默认开**（Go 视为主后端）。若与 Java 后端同时在线会双触发，本批按
  「Go 唯一后端」语义接受，开关保留以便需要时关闭。
- **调度引擎**：GoFrame 原生 `gcron`（零新依赖）。否决 `robfig/cron`（`Next` 虽公开，但无按名暂停/恢复、
  无「立即跑一次」、`?` 需预处理、且新增依赖，管理面贴合度差）。
- **执行落点**：编译期任务注册表 `{beanName.method → Go handler}`，**不做任意类实例化/反射**，天然杜绝 RCE，
  且忠实于「白名单包只有两个清理任务」的现实。未命中注册表的目标在保存期即被拒（等价 Java 白名单）。

### 有意的契约偏差（3 条，须实现与验收时知悉）

1. **列表默认排序**：Java `SysJobMapper.selectJobList` 无 `ORDER BY`（InnoDB 实际按 PK 返回）；Go 为分页稳定显式
   `order by job_id asc`。观测行为一致。
2. **misfire 策略列**：内存引擎无持久化 trigger，`misfire_policy`（0/1/2/3）仅原样存储/导出，不参与运行期补偿；
   停机期间错过的调度重启后不补跑（与 `gcron` 语义一致，近似 Java `DO_NOTHING`/`FireAndProceed` 单发）。
3. **cron 专有 token**：`cronutil` 与 `gcron` 均不支持 Quartz 的 `L W #`；含这些 token 的 cron 在保存期被
   `IsValid` 拒。本 fork 的两个真实任务与种子数据均不触碰，影响面为零。

---

## Key Changes（按子系统分组）

### 1. 数据模型 `internal/model/entity.go`

新增 `SysJob`，列严格对齐 `selectJobVo`/`SysJobResult`（13 列）：

```go
type SysJob struct {
    JobId          int64       `json:"jobId"          orm:"job_id"`
    JobName        string      `json:"jobName"        orm:"job_name"`
    JobGroup       string      `json:"jobGroup"       orm:"job_group"`
    InvokeTarget   string      `json:"invokeTarget"   orm:"invoke_target"`
    CronExpression string      `json:"cronExpression" orm:"cron_expression"`
    MisfirePolicy  string      `json:"misfirePolicy"  orm:"misfire_policy"`
    Concurrent     string      `json:"concurrent"`
    Status         string      `json:"status"`
    CreateBy       string      `json:"createBy"       orm:"create_by"`
    CreateTime     *gtime.Time `json:"createTime"     orm:"create_time"`
    UpdateBy       string      `json:"updateBy"       orm:"update_by"`
    UpdateTime     *gtime.Time `json:"updateTime"     orm:"update_time"`
    Remark         string      `json:"remark"`
    // 非持久化响应字段：对齐 Java SysJob.getNextValidTime()（瞬态，依 cron 实时推算）。
    NextValidTime  *gtime.Time `json:"nextValidTime"  orm:"-"`
}
```

- `MisfirePolicy` 默认值 `"0"`（对齐 Java 字段初始化），由插入前的业务逻辑保证。
- `NextValidTime` 由 service 在 list/getInfo 返回前逐条计算填充；空/非法 cron → `nil`（JSON `null`），
  不阻断整列表（对齐 Java getter 的 try/catch 兜底）。
- 时间序列化沿用本仓既有 `*gtime.Time` 约定（与 operlog/logininfor 一致，不改）。

### 2. 服务层 `internal/service/job.go`（新建）

对齐 `SysJobServiceImpl` + `SysJobMapper.xml`：

- `const selectJobVo = "select job_id, job_name, job_group, invoke_target, cron_expression, misfire_policy, " +
  "concurrent, status, create_by, create_time, update_by, update_time, remark from sys_job"`
- `const jobDeleteMaxBatch = 500`（对齐 `MAX_DELETE_BATCH`）
- `JobListFilter{ JobName, JobGroup, Status, InvokeTarget string }`
- `buildJobListSQL(f)`：照 `<where>` — jobName/invokeTarget `like concat('%',?,'%')`，jobGroup/status `= ?`（字符串
  presence 区分未提交与空串）。
- `SelectJobListPaged(ctx, f, p)`：`QueryPaged(..., "job_id asc")`。
- `SelectJobListLimited(ctx, f, maxRows)`：列表 SQL + `order by job_id asc limit ?`（传 `maxRows+1`），export 护栏。
- `SelectJobById(ctx, jobId) *model.SysJob`：`GetOne`；未命中回 `nil`。
- `SelectJobAll(ctx) []model.SysJob`：`selectJobVo` 全量（启动加载）。
- `InsertJob(ctx, *model.SysJob) (int64, error)`：动态列仅非空、`create_time=now`、`useGeneratedKeys` 语义取回自增
  jobId 回填 `JobId`。**入库前 status 置暂停 `"1"`**（对齐 `insertJob`：`job.setStatus(PAUSE)`）；若
  `MisfirePolicy` 空则补 `"0"`、`Concurrent` 空则补 `"0"`（对齐 Java 字段默认 `MISFIRE_DEFAULT`/允许并发，避免
  动态插入漏列致 DB null）。
- `UpdateJob(ctx, *model.SysJob) (int, error)`：动态 SET 仅非空；`status` 以「字段是否出现」判 `!=null`（保留 `'0'`）；
  `update_time` 强制刷新。
- `DeleteJobById(ctx, jobId) (int, error)`：物理 `delete from sys_job where job_id=?`。
- `DeleteJobByIds(ctx, ids []int64) (int, error)`（编排整体对齐 Java `deleteJobByIds`→`deleteJob`）：
  空→`error("请选择要删除的任务")`；`>500`→`error("单次最多删除 500 条任务，当前 %d 条")`；否则逐条
  `SelectJobById`，不存在则跳过（避免陈旧 ID），存在则先 `DeleteJobById`（DB），`rows>0` 再
  `unregisterJob(jobId)`（摘调度器，对齐 Java「先删库、成功才动调度器」），累加 affected 返回。
  （调度摘除与删库同在本 service 层收口，controller `remove` 不再跨层编排。）
- 导出 8 列 `jobExportColumns()`（严格按 `SysJob` @Excel 声明序）：
  1. 任务序号 `Kind:ExcelKindNumeric` → JobId
  2. 任务名称 → JobName
  3. 任务组名 → JobGroup
  4. 调用目标字符串 → InvokeTarget
  5. 执行表达式 → CronExpression
  6. 计划策略 `Converter:"0=默认,1=立即触发执行,2=触发一次执行,3=不触发立即执行"` → MisfirePolicy
  7. 并发执行 `Converter:"0=允许,1=禁止"` → Concurrent
  8. 任务状态 `Converter:"0=正常,1=暂停"` → Status
  - `colJob(fn func(*model.SysJob) any) func(any) any` 适配器；`ExportJob(w, list)` → `WriteExcel(w, "定时任务", cols, rows)`
    （sheet 名对齐 Java `util.exportExcel(...,"定时任务")`）。
- `validateJob(ctx, *model.SysJob) error`：见第 6 节。
- `nextValidTimeOf(cron string) *gtime.Time`：调 `cronutil.Next`，非法/空回 nil；list/getInfo 统一经此填 `NextValidTime`。

**`internal/service/joblog.go` 补**：`InsertJobLog(ctx, *model.SysJobLog) error` —— `INSERT INTO sys_job_log
(job_name, job_group, invoke_target, job_message, status, exception_info, start_time, end_time, create_time)
VALUES(...)`，供调度执行写日志（对齐 `addJobLog`）。

**`internal/service/operlog.go` 补**：`CleanOperLogBefore(ctx, days int) (int, error)`。
**`internal/service/logininfor.go` 补**：`CleanLogininforBefore(ctx, days int) (int, error)`。
- 二者：`DELETE FROM sys_oper_log / sys_logininfor WHERE create_time < DATE_SUB(NOW(), INTERVAL ? DAY) LIMIT 5000`
  循环分批删至影响行数为 0，累计返回删除行数（对齐 Java 备注「分批 5000 条删除」）。

### 3. 调度子系统 `internal/service/scheduler.go`（新建）

- 进程级单例：`var cronObj = gcron.New()` + `map[int64]*gcron.Entry`（jobId→entry）+ `sync.Mutex`。
- entry 名 `"job_"+strconv.FormatInt(jobId,10)`（唯一，类比 `TASK_CLASS_NAME+jobId`）。
- `StartScheduler(ctx)`：
  - 读 `g.Cfg().MustGet(ctx,"job.scheduler.enabled", true).Bool()`；`false` 直接返回。
  - 对齐 Java `init()`：先 `unregisterAll()` 清空内存表，再 `SelectJobAll` 逐条 `registerJob(ctx, job)`；
    `job.Concurrent=="1"`（禁止并发）→ `AddSingleton`，`=="0"`→`Add`；注册后若 `job.Status!="0"`（非正常）
    → `cronObj.Stop(name)`（保持注册但不触发）。读 `invokeTarget` 注册表失败（未命中）的记录跳过并 `g.Log().Warningf`
    （不阻断启动；本 fork 理论不存在）。
- `StopScheduler()`：`cronObj.StopGracefully()`（等在途任务）。
- `registerJob(ctx, job) error`：解析 `invokeTarget` 取 `bean.method`，查注册表；构造 `gcron` job 闭包 =
  `executeJob(ctx, job)`；按 `concurrent` 选 `Add/AddSingleton`（名=entry 名）；登记到 map。若已存在同名先 `Remove`。
- `unregisterJob(jobId)`：`cronObj.Remove(name)` + 从 map 删。
- `pauseJob/resumeJob(jobId)`：`cronObj.Stop/Start(name)`（entry 存在即生效；不存在→false）。
- `triggerOnce(ctx, job) bool`：仅当 `jobId` 在注册表内（≈Java `checkExists`）时，在 goroutine 里执行
  `executeJob(ctx, jobFromDb)` 一次（不动原 cron 计划），返回 true；否则 false。
- `executeJob(ctx, job)`（真正执行体，对齐 `AbstractQuartzJob`）：
  - `start := gtime.Now()`；`defer recover()`。
  - 取 `handler, ok := lookupTask(job.InvokeTarget)`；无 handler → 记为失败（`status=1`+异常信息）。
  - 命中则 `err := handler(ctx, args)`；panic/err → `status=1`、`exception_info`（`ExceptionUtil` 等价：取 err/panic
    文本截断 2000）。
  - `end := gtime.Now()`；`ms := end.Sub(start)`（毫秒）；
    `job_message = job.JobName + " 总共耗时：" + ms + "毫秒"`（逐字对齐）。
  - 组装 `model.SysJobLog{JobName,JobGroup,InvokeTarget,JobMessage,Status,ExceptionInfo,StartTime,EndTime}` →
    `InsertJobLog`；落库失败仅记日志不外抛（对齐 `after` 全程兜底）。
- 所有调度器动作「先落库成功、再同步调度器」，与 Java 各 `@Transactional` 方法顺序一致（见第 7 节 controller 编排）。

### 4. 任务注册表 `internal/service/jobtask.go`（新建）

- `type taskHandler func(ctx context.Context, args []string) error`
- `var taskRegistry = map[string]taskHandler{ "operLogCleanTask.clean": ..., "logininforCleanTask.clean": ... }`
  （key = `beanName.methodName`，即白名单落地）。
- `lookupTask(invokeTarget string) (taskHandler, []string, bool)`：复刻 `JobInvokeUtil` 的解析——
  - `getBeanName` = `substringBefore(target,"(")` 再 `substringBeforeLast(".",)`；
  - `getMethodName` = `substringBefore(target,"(")` 再 `substringAfterLast(".",)`；
  - `getMethodParams` = `substringBetweenLast("(",")")`；空则无参；否则按逗号分割（引号 `'`/`"` 内逗号不拆），
    每项去首尾空白、去包裹引号后的**原始字面量**放进 `args []string`（类型判定交给 handler）。
  - key=`beanName+"."+methodName`，命中返回 handler + args。
- handler 实现：
  - `operLogCleanTask.clean`：`args` 空 → 取 `g.Cfg()...("ruoyi.operLogRetentionDays")`；否则 `strconv.Atoi(args[0])`
    （去引号已在解析阶段完成）。`days<=0` → `error("操作日志保留天数必须大于 0，当前值：%d")`。调
    `CleanOperLogBefore`。
  - `logininforCleanTask.clean`：空 → 默认 `180`；同解析；`days<=0` → `error("登录日志保留天数必须大于 0，当前值：%d")`；
    调 `CleanLogininforBefore`。
  - 解析不出整数 → `error("...保留天数参数不合法：%s")`（对齐 Java `parseDays`）。

### 5. `internal/service/cronutil.go`（新建，纯函数）

- `Parse(pattern string) (*Spec, error)`：6 段（秒 分 时 日 月 周）。逐段：
  - 段字符集与 `gcron` 一致：允许 `* ? , - /` 及月/周英文名（`JAN..DEC`/`SUN..SAT`）。
  - `?` 仅在日/周段合法，等价 `*`（匹配全部）。
  - 值域：秒 0-59、分 0-59、时 0-23、日 1-31、月 1-12、周 0-6；越界或含 `L W #` → 返回 error。
  - 生成各段 `map[int]struct{}`；`Spec` 保存。
- `IsValid(pattern string) bool`：`Parse` 无 error 即真（供 `validateJob`）。
- `Next(pattern string, from *gtime.Time) *gtime.Time`：解析后从 `from+1s`（截到秒）起，按
  月→(日 AND 周)→时→分→秒 逐级向前搜索（复刻 `gcron.Next` 的 AND 语义：日与周都必须命中；5 年上限内找不到回 nil）。
- 单测覆盖：`0 0 3 * * ?` → 次日 03:00:00；`?`≡`*`；`L`/`W`/`#`/越界 → `IsValid=false`；`0 15 10 ? * MON-FRI` 语义。

> 注：`cronutil` 的校验/推算口径必须与 `gcron` 运行期解析一致（同源子集），否则「保存期放行、运行期报错」。

### 6. 安全 `validateJob`（逐字对齐 `ScheduleUtils.validateJob`）

写库（add/edit）前统一收口，任一失败 → `error`（controller 转 `api.Err`），文案前缀
`定时任务'<jobName>'保存失败`，顺序完全照 Java：

1. cron 空 → `"Cron表达式不能为空"`
2. invokeTarget 空 → `"调用目标字符串不能为空"`
3. `!cronutil.IsValid(cron)` → `"Cron表达式不正确"`
4. 含 `rmi:`（忽略大小写）→ `"目标字符串不允许'rmi'调用"`
5. 含 `ldap:`/`ldaps:` → `"目标字符串不允许'ldap(s)'调用"`
6. 含 `http://`/`https://` → `"目标字符串不允许'http(s)'调用"`
7. 命中 `JOB_ERROR_STR` 黑名单（`java.net.URL`/`javax.naming.InitialContext`/`org.yaml.snakeyaml`/
   `org.springframework`/`org.apache`/`com.ruoyi.common.utils.file`/`com.ruoyi.config`/`com.ruoyi.generator`）→
   `"目标字符串存在违规"`
8. `invokeTarget` 的 `beanName.methodName` **不在 `taskRegistry`** → `"目标字符串不在白名单内"`

> 第 8 条以「注册表命中」实现 Java `whiteList`（本 fork 白名单包 `com.ruoyi.quartz.task` 实际仅两个 bean，
> 注册表即其编译期等价）。

### 7. 控制层 `internal/controller/job.go` + 路由 `internal/router/router.go`（8 端点）

逐字对齐 `SysJobController`。`@RequestBody` 端点一律 `r.Parse`；GET 列表过滤用 `r.Get`。

| 端点 | 方法/路径 | 权限 | 行为（Service/调度编排） |
|---|---|---|---|
| list | GET `/monitor/job/list` | `monitor:job:list` | 过滤+`SelectJobListPaged`；每行 `nextValidTimeOf(cron)`；`DataTable` |
| export | POST `/monitor/job/export` | `monitor:job:export` | 同过滤 + `GetExportMaxRows` + `exportOverLimit` + `ExportJob`+`writeXlsx`；@Log 定时任务/BtExport |
| getInfo | GET `/monitor/job/{jobId}` | `monitor:job:query` | `SelectJobById`；`api.OkData(job)`（含 nextValidTime；Java `success()` 不因 nil 报错，nil 也回 data=null） |
| add | POST `/monitor/job` | `monitor:job:add` | `createBy=当前用户`（不信前端）→`validateJob`→`InsertJob`（默认暂停）→rows>0 后 `registerJob`（暂停则随后 `Stop`）→`toAjax`；@Log 定时任务/BtInsert |
| edit | PUT `/monitor/job` | `monitor:job:edit` | `updateBy=当前用户`→`validateJob`→取旧记录（拿 jobGroup）→`UpdateJob`→rows>0 后 `unregister`+`register`（重建，保留原状态）→`toAjax`；@Log 定时任务/BtUpdate |
| changeStatus | PUT `/monitor/job/changeStatus` | `monitor:job:changeStatus` | 取 `jobId`→库基准记录；nil→`api.Err("任务不存在："+id)`；`status` 空→`api.Err("状态不能为空")`；`status==NORMAL("0")`→DB `updateJob(status=0)`+`resumeJob`；`==PAUSE("1")`→`updateJob(status=1)`+`pauseJob`；`toAjax`；@Log 定时任务/BtUpdate |
| run | PUT `/monitor/job/run` | `monitor:job:changeStatus` | 仅取 `jobId`（+jobGroup 定位）；`triggerOnce`（内部按库记录执行一次）；true→`api.Ok()`；false→`api.Err("任务不存在或已过期！")`；@Log 定时任务/BtUpdate |
| remove | DELETE `/monitor/job/{jobIds}` | `monitor:job:remove` | `splitCommaInts`→`DeleteJobByIds`（service 内完成空/超 500 校验与逐条「先删库再摘调度」）→`toAjax(rows)`；@Log 定时任务/BtDelete |

- changeStatus/run 保留 `SaveRequest=true`（Java `@Log` 默认存参，无明文密码等敏感项），走便捷 `OpLog(...)`。
- 路由：静态段 `list`/`export`/`changeStatus`/`run` 先于动态 `{jobId}`/`{jobIds}` 注册（GF 字面量优先）。
- `getInfo` 的 `{jobId}` 与 `remove` 的 `{jobIds}` 都是单段路径但语义不同（GET vs DELETE），HTTP 方法足以区分。

### 8. 启动接线 `main.go` + `config.yaml`

- `main.go`：`service.StartServerMonitor(ctx)` 之后加 `service.StartScheduler(ctx)`；`s.Run()` 返回、
  `StopServerMonitor()` 旁加 `service.StopScheduler()`。
- `config.yaml`：新增顶层 `job: scheduler: enabled: true`。

---

## Test Plan

- **`cronutil` 单测**（不依赖服务）：`Parse/IsValid/Next` 覆盖 `0 0 3 * * ?`、`?`≡`*`、`MON-FRI`、`L/W/#`/越界拒绝、
  5 年不可达回 nil。
- **任务注册表单测**：`lookupTask("operLogCleanTask.clean('90')")` → key 命中 + args=["90"]（去引号）；
  `lookupTask("operLogCleanTask.clean(90)")` → args=["90"]；`lookupTask("operLogCleanTask.clean")` → args 空；
  `lookupTask("com.ruoyi.quartz.task.OperLogCleanTask.clean('90')")`（全类名写法）解析 bean/method 正确；
  未注册 bean → ok=false。
- **validateJob 单测**：8 条校验逐分支文案；rmi/ldap/http/黑名单/白名单外各被拒。
- **运行期集成**（隔离端口 :8090，验证码经 DB 临时关）：
  1. 登录取 token。
  2. add：非法 cron → 拒绝文案；未注册 bean（如 `ryTask.demoMethod`）→ "目标字符串不在白名单内"；合法
     `operLogCleanTask.clean('90')` + `0 0 3 * * ?` → 入库默认暂停、调度器注册（stopped）。
  3. changeStatus：status=0 恢复（`resumeJob` 生效）；空 status → "状态不能为空"；不存在 jobId → "任务不存在：<id>"。
  4. run：对已注册任务触发一次 → 稍后读 `sys_job_log` 反校验 `job_message="<jobName> 总共耗时：<ms>毫秒"`、
     `status`、`start/end_time`；未注册 → "任务不存在或已过期！"。
  5. list：total/排序对、每行 `nextValidTime` 非空且为未来 03:00；getInfo：命中含 nextValidTime。
  6. export：HTTP200 + PK 魔数，`readtool` 反读 sheet 名「定时任务」+ 8 列 + converter（计划策略/并发/状态）+ 任务序号 NUMERIC。
  7. remove：单条/多条 → 200；`sys_job` 与调度器同步摘除；>500 → 拒绝文案。
  8. 清理：删测试期间产生的 `sys_job`/`sys_job_log` sentinel，复原共享库（`sys_job` 仅剩种子 id=4 且保持暂停）。
- **还原**：config 端口/开关/上限回提交态；`tmp/` 脚手架 `rmdir`；`go mod tidy && go build && go vet` 绿。

---

## 落地顺序（供 writing-plans 细化）

1. `entity.go` 加 `SysJob`。
2. `cronutil.go` + 单测（先立地基，运行/校验同源）。
3. `jobtask.go`（注册表 + 解析）+ `operlog/logininfor` 的 `Clean*Before`。
4. `job.go` service（CRUD/list/export/validateJob/nextValidTime）+ `joblog.go` 的 `InsertJobLog`。
5. `scheduler.go`（gcron 生命周期 + executeJob）。
6. `controller/job.go` 8 handler。
7. `router.go` +8 路由；`main.go` + `config.yaml` 接线。
8. 编译/vet → 单测 → 运行期集成 → 还原 → commit+push。

## Assumptions

- 本 fork 白名单包 `com.ruoyi.quartz.task` 仅 `operLogCleanTask`/`logininforCleanTask` 两任务；注册表覆盖二者即可，
  不实现历史 `ryTask`（Java 侧已移除）。
- `ruoyi.operLogRetentionDays` 配置项在 Go 侧读得到（若无则默认取合理值，实现时核 `config.yaml`）。
- `*gtime.Time` 的 JSON 序列化格式与已移植的 operlog/logininfor 保持一致，前端已知可用。
