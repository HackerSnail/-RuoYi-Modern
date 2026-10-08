# 回收站（Recycle Bin）实现计划

- 日期：2026-10-08
- 对应 spec：[2026-10-08-recycle-bin-design.md](../specs/2026-10-08-recycle-bin-design.md)
- 范围：本仓（RuoYi-Vue 后端多模块）**后端实现 + 种子 SQL + 测试**；前端 Vue 工程不在本仓，spec §10 契约作为交付说明，前端页面由独立 UI 仓实现。
- 说明：`writing-plans` 技能未安装，本计划按其同等规格手工产出——任务小步、依赖有序、每步可验证、决策完备。

## 全局约定（每个任务都隐含遵守）
- Java 大括号一律 K&R(1TBS)；成员变量显式 `private`。
- Mapper XML **每条 SQL 必须带注释**（`<!-- -->` 或同行说明其用途与 `del_flag` 语义）。
- 中文注释高密度、无噪音（不写复述代码的废话注释）。
- 验证兜底：每阶段结束跑 `mvn -q -pl <改动模块> -am compile`；涉及单测跑 `mvn -q -pl ruoyi-system test`（集成测按 tag 单独跑，默认不执行）。
- 提交：每完成一个可编译的最小里程碑，按"改完验证通过即 commit+push"入库；逐文件显式 `git add`，中文信息走 UTF-8 文件 + `git commit -F`。

## 已确认的地基事实（实现直接引用，勿再臆测）
- `SysUserMapper`：`selectUserList(SysUser)` / `deleteUserById(Long)` / `deleteUserByIds(Long[])` / `checkUserNameUnique|checkPhoneUnique|checkEmailUnique(String)`（返回实体，非 null 即撞名）。
- `SysRoleMapper`：`selectRoleList(SysRole)` / `deleteRoleById(Long)` / `deleteRoleByIds(Long[])` / `checkRoleNameUnique(String)` / `checkRoleKeyUnique(String)`。
- `SysDeptMapper`：`selectDeptList(SysDept)` / `deleteDeptById(Long)` / `hasChildByDeptId(Long)` / `checkDeptExistUser(Long)` / `checkDeptNameUnique(deptName,parentId)`。
- 数据权限：`@DataScope(deptAlias="d", userAlias="u")` 标在 **ServiceImpl 方法**上，对应 mapper SQL 内出现 `${params.dataScope}` 且表别名为 `d`/`u`。
- 既有软删 SQL：`update ... set del_flag='2'`；所有列表/详情/唯一性查询带 `del_flag='0'`。

---

## 阶段一：无行为变更的基础设施（先编译通过，不接任何调用）

### T1.1 `RecycleBinType` 枚举（common）
- 新增 `ruoyi-common/src/main/java/com/ruoyi/common/enums/RecycleBinType.java`。
- 仅承载**纯元数据**：`code`（`user|role|dept`，URL/权限用）、`displayName`（"用户/角色/部门"）、`pkField`（`user_id|role_id|dept_id`）、`permPrefix`（`system:user` 等）。
- 提供 `static RecycleBinType fromCode(String)`：非法 code 抛 `IllegalArgumentException`（controller 入口白名单靠它）。
- **不含**任何对 system Mapper 的引用（避免 common 反向依赖 system）。分派在 T2 的 ServiceImpl 里做。
- 验证：`mvn -q -pl ruoyi-common compile`。

### T1.2 `RecycleBinOpResult` DTO（system）
- 新增 `ruoyi-system/src/main/java/com/ruoyi/system/domain/dto/RecycleBinOpResult.java`。
- 字段：`int total` / `int successCount` / `int failCount` / `List<Item> items`。
- 内部静态类 `Item { Long id; String name; Status status; String reason; }`；枚举 `Status { SUCCESS, FAILED }`。
- 提供便捷构造/累加方法（`addSuccess(id,name)`、`addFailure(id,name,reason)` 并同步计数），供 Service 逐项填充。
- 全 `private` + getter/setter（K&R）。
- 验证：`mvn -q -pl ruoyi-system compile`。

> **里程碑 M1**：T1.1+T1.2 编译通过 → commit（`feat(recyclebin): 新增 RecycleBinType 枚举与 RecycleBinOpResult DTO 基础设施`）。

---

## 阶段二：Mapper 层（每表对称新增，带 SQL 注释）

> 恢复与彻底删除都需要**先按 id 读到那条 `del_flag='2'` 的记录**（拿它的 `user_name`/`dept_id`/`parent_id`/`role_key` 做校验），故每表在 spec §3 三条之外**再加一条按 id 取回收站记录**的查询——这是对 §3"三条"的落地细化，不改变设计意图。

### T2.1 `SysUserMapper` + XML
- `selectDeletedUserList(SysUser)`：镜像 `selectUserList` 的可选过滤 + `${params.dataScope}`，把 `del_flag='0'` 改为 `del_flag='2'`。
- `selectDeletedUserById(Long)`：按主键取 `del_flag='2'` 的用户（供恢复校验）。
- `restoreUserById(Long)` / `restoreUserByIds(Long[])`：`update sys_user set del_flag='0' where user_id=? and del_flag='2'`（前置 `del_flag='2'`，幂等防误翻活跃行）。
- `deleteUserByIdPhysically(Long)` / `deleteUserByIdsPhysically(Long[])`：**真 `delete from sys_user where user_id ...`**（仓内首个物理删除；注释写明"回收站彻底删除，不可逆，关联已在软删时清空"）。

### T2.2 `SysRoleMapper` + XML（同 T2.1 对称，主键 `role_id`）
### T2.3 `SysDeptMapper` + XML（同 T2.1 对称，主键 `dept_id`；无关联表）

- 每方法：接口加签名 + 对应 XML 段（`<select>`/`<update>`/`<delete>`），每条 SQL 上注释。
- 验证：`mvn -q -pl ruoyi-system compile`；随后在阶段五的集成测里覆盖。

> **里程碑 M2**：三表 Mapper 全部编译通过 → commit（`feat(recyclebin): 用户/角色/部门 Mapper 新增 回收站列表/按id取/恢复/物理删除 方法`）。

---

## 阶段三：Service 层（类型无关分派 + 严格校验 + 每项独立事务）

### T3.1 `IRecycleBinService` 接口（system.service）
- `TableDataInfo listDeleted(RecycleBinType type, Xxx query)` 或统一入参（分页 + 过滤 Map）——具体签名对齐本仓 `BaseController.startPage()`/`getDataTable()` 惯例。
- `RecycleBinOpResult restore(RecycleBinType type, Long[] ids)`
- `RecycleBinOpResult purge(RecycleBinType type, Long[] confirm, Long[] ids)`（`confirm` 缺失由 controller 或 service 前置拒绝）

### T3.2 `RecycleBinTxService`（**独立 Spring bean，专治自调用失效**）
- 关键决策：批内每项要在**独立事务**里跑，若在 `RecycleBinServiceImpl` 内循环调用同类 `@Transactional` 方法会绕过代理而不生效。故把**单项**逻辑下沉到单独组件：
  - `Item restoreOne(RecycleBinType type, Long id)`：`@Transactional(propagation = REQUIRES_NEW)`。
  - `Item purgeOne(RecycleBinType type, Long id)`：`@Transactional(propagation = REQUIRES_NEW)`。
- `restoreOne` 内部顺序（任一不过即返回 FAILED，不写库）：
  1. 按 id 取 `del_flag='2'` 记录；不存在 → FAILED("记录不在回收站")。
  2. **身份冲突**：按 type 跑 `check*Unique`（user 查 name/phone/email；role 查 roleKey(+roleName)；dept 查同父 deptName），命中活跃记录 → FAILED("存在同名活跃XX")。
  3. **悬空引用**：user 的 `dept_id` 指向部门仍软删/不存在 → FAILED("请先恢复所属部门")；dept 的父仍软删 → FAILED("请先恢复上级部门")。
  4. 通过 → 调 `restoreXxxById`（条件带 `del_flag='2'`），SUCCESS。
- `purgeOne` 内部顺序：
  1. 取记录；不存在 → FAILED。
  2. **护栏**：user→`checkUserAllowed`（admin 拒）+ **自删锁死**（目标 id==当前 userId 拒）；dept→有活跃下级 `hasChildByDeptId` 或在职用户 `checkDeptExistUser` → FAILED("存在活跃下级/在职用户，不可销毁")。
  3. **数据范围**：`checkUserDataScope`（user），越权 → FAILED。
  4. 通过 → `deleteXxxByIdPhysically`，SUCCESS。

### T3.3 `RecycleBinServiceImpl`（system.service.impl）
- `listDeleted`：`startPage()` → 按 type 分派到 `selectDeletedXxxList`；`@DataScope(deptAlias="d", userAlias="u")`（user）/对应别名（role/dept）标在此方法上。
- `restore`/`purge`：**方法本身不开事务**，遍历 `ids`，逐项调用 `RecycleBinTxService.restoreOne/purgeOne`，把返回的 `Item` 累进 `RecycleBinOpResult`（部分成功语义）。
- `purge` 入口前置：`confirm != true` → 整请求抛 `ServiceException`（spec §6）。
- type→Mapper 分派用 `switch(type)`（唯一集中点，其余保持类型无关）。

- 验证：`mvn -q -pl ruoyi-system compile`。

> **里程碑 M3**：Service 层编译通过 → commit（`feat(recyclebin): 回收站 Service——类型分派、恢复严格校验、每项独立事务部分成功`）。

---

## 阶段四：Controller + 权限/审计 + 种子 SQL

### T4.1 `SysRecycleBinController`（ruoyi-admin ...controller.system）
- `@RestController @RequestMapping("/system/recyclebin")`。
- `GET /{type}/list`：`RecycleBinType.fromCode(type)`（非法即拒）→ `@PreAuthorize("@ss.hasPermi('system:recyclebin:list')")` → service.listDeleted。
- `PUT /{type}/restore`：`fromCode` → `@PreAuthorize("@ss.hasPermi('system:'+permPrefix+':restore')")`（用枚举里的 `system:user:restore` 等固定串，**不要把 type 直接拼进 hasPermi 之前未校验**；先 fromCode 白名单，再取枚举常量权限串）。
- `DELETE /{type}/purge`：`fromCode` → `@PreAuthorize(...':purge')`；body 带 `confirm`。
- `restore`/`purge` 均挂 `@Log(title="回收站", businessType=DELETE/其他)`（purge 用 DELETE；restore 选合适 BusinessType）。
- 返回：list→`getDataTable`；restore/purge→`AjaxResult.success(RecycleBinOpResult)`（部分成功走 200）。

### T4.2 权限种子 SQL
- 交付脚本（如 `sql/recyclebin_menu_20261008.sql`）：向 `sys_menu` 插入 1 个查看位 + 6 个动作位的按钮级菜单，并向超管角色 `sys_role_menu` 关联。
- **幂等且避坑**：`INSERT ... SELECT` 派生表里多个未别名同名列会报 `Duplicate column name`——每个 select 列显式别名；或用"先判存在再插"的 `WHERE NOT EXISTS` 包裹。
- 验证：本地库手动跑两遍确认幂等、无重复行。

> **里程碑 M4**：controller+种子编译/自查通过 → commit（`feat(recyclebin): 回收站 Controller、细粒度权限与审计、菜单种子 SQL`）。

---

## 阶段五：测试（TDD 与既有规范对齐）

### T5.1 单元测试（`mvn test` 默认执行，纯 mock 不打库）
- `RecycleBinTypeTest`：`fromCode` 合法/非法。
- `RecycleBinServiceTest`（mock Mapper 与 `check*Unique`、`SecurityUtils` 静态）：
  - 恢复成功只还壳；身份冲突→FAILED；父部门未复→FAILED；purge 缺 confirm→整请求拒；自删→FAILED；admin→FAILED；批量部分成功（一坏项不回滚其余，计数正确）。

### T5.2 Mapper 集成测（打 tag，默认不跑，依"集成测试分组与执行策略"）
- `selectDeletedXxxList` 仅返回 `del_flag='2'`；
- `restoreXxxById` 带 `del_flag='2'` 前置，对活跃行不误翻；
- `deleteXxxByIdPhysically` 真删（删后按 id 查无）。

- 验证：`mvn -q -pl ruoyi-system test`（单测）；集成测按本仓既定 tag 参数单独执行。

> **里程碑 M5**：测试全绿 → commit（`test(recyclebin): 回收站 恢复校验/部分成功 单测 + Mapper 物理删/幂等 集成测`）。

---

## 阶段六：交付说明（非代码）
- 前端契约（spec §10）：三端点、部分成功 200 承载、`type` 白名单、列表按 `createTime` 排序且无"删除时间"。整理给 UI 仓。
- 运行/回滚文档：种子 SQL 执行顺序；"只还壳、恢复后需重授权"的运维提示写入功能说明。

## 风险与例外处理要点（贯穿实现）
1. 自调用事务失效 → 已由 T3.2 独立 bean 化解，勿合并回 Impl。
2. 动态权限串注入 → 必须先 `fromCode` 白名单，再取枚举固定权限常量，绝不裸拼用户输入进 `hasPermi`。
3. 物理删不可逆 → `confirm` + 细粒度 `purge` 权限 + `@Log` 三重护栏，测试必覆盖缺 confirm 路径。
4. 种子幂等 → `Duplicate column name` 坑，列显式别名 + `WHERE NOT EXISTS`。
5. "只还壳"认知差 → 恢复成功项的 `Item` 可附提示文案，前端与文档明示需重授权。

## 完成定义（DoD）
- 全仓 `mvn -q test-compile` 通过；`mvn -q -pl ruoyi-system test` 单测全绿。
- 三端点手工冒烟（list/restore/purge 含批量与失败项）行为符合 spec §2/§4/§5/§6。
- 工作树无脚手架残留；每个里程碑独立可回滚。
