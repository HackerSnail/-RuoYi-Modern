# 回收站（Recycle Bin）功能设计

- 日期：2026-10-08
- 路线：Architectural（新功能，无现成流程可改）
- 覆盖实体：用户 `sys_user` / 角色 `sys_role` / 部门 `sys_dept`（三类统一抽象）
- 状态：设计已与用户逐节确认（§1–11），待用户 review 本 spec 通过后进入 writing-plans

## 1. 目标与背景

若依对 用户/角色/部门 采用软删除（`del_flag='2'`），但后台没有任何"查看已删除记录 / 恢复 / 彻底删除"的入口——数据删了就在 UI 上消失、无法找回。本功能新增一个**回收站**，让管理员可以：列出软删记录、将其**恢复**、或**物理彻底删除**，并支持批量操作。

### 关键现状（已实测，作为设计地基）
- 三张表软删完全对称：均为 `update ... set del_flag='2'`；所有列表/详情/唯一性查询一律带 `del_flag='0'` 过滤。
- 软删时**已物理清空关联表**：
  - `deleteUserById(s)` 先 `deleteUserRoleByUserId`/`deleteUserRole` + `deleteUserPost`/`deleteUserPostByUserId`（清 `sys_user_role`、`sys_user_post`），再置 `del_flag='2'`。
  - `deleteRoleById(s)` 先清 `sys_role_menu`、`sys_role_dept`，再软删。
  - `deleteDeptById` 仅置 `del_flag='2'`（部门无关联表，父子靠 `parent_id`/`ancestors`）。
- 超管保护：`checkUserAllowed`（命中 `isAdmin()` 即抛 `ServiceException("不允许操作超级管理员用户")`）；数据范围保护：`checkUserDataScope`（非超管才校验）；`deleteUserByIds` 逐项跑这两条防越权。
- 既有软删**不写时间戳**（`update` 不动 `update_time`），故无"删除时间"数据源。

### 已锁定的全局决策
1. **范围**：用户/角色/部门三类，统一抽象（单一 controller/service，按 `type` 分派）。
2. **恢复保真度**：**只恢复实体壳**——不改删除流程，关联维持现状物理清空；恢复 = `del_flag` 由 `'2'` 翻回 `'0'`，之后由管理员重新分配角色/权限/菜单/下级。
3. **操作集**：列表 / 恢复（单+批）/ 彻底删除（单+批），批量返回逐项结果。
4. **不安全态**：恢复做**严格校验**——身份冲突与悬空引用一律**拒绝该项**（不自动改归属）。
5. **彻底删除**需**二次确认**；批处理用**每项独立事务 + 部分成功**语义。
6. **权限粒度**：细粒度（per-type + per-action）。
7. **删除时间**：不做（不引入 `del_time` 列、不改软删），列入不在范围。

## 2. API 端点面

单一控制器 `SysRecycleBinController`，用路径变量 `{type}` 分派三类；`type ∈ {user, role, dept}`，由枚举 `RecycleBinType` 承载元数据（表名/展示名/主键字段/唯一性校验器），controller/service 保持类型无关。

| 操作 | 方法 & 路径 | 说明 |
|---|---|---|
| 列出回收站项 | `GET /system/recyclebin/{type}/list` | 分页，仅 `del_flag='2'`，数据范围过滤 |
| 恢复（单/批） | `PUT /system/recyclebin/{type}/restore` | body `ids[]`；严格校验后翻 `del_flag='0'` |
| 彻底删除（单/批） | `DELETE /system/recyclebin/{type}/purge` | body `ids[] + confirm=true`；物理删除 |

- 委托到 `IRecycleBinService`，内部按 `type` 复用各实体既有 Mapper（新增方法见 §3）。
- 不在 controller 写 type 的业务 if-else；分派靠 `RecycleBinType` + 各 Mapper。
- 入口对 `type` 做枚举白名单校验，非法值直接拒（防动态权限串注入，见 §12）。

## 3. 数据模型 / Mapper 变更

**零 schema 变更**——`del_flag` 及各实体 `delFlag` 字段均已存在，"只恢复实体壳"无需新列。

每张表对称新增三条 Mapper 方法：
- `selectDeletedXxxList(Xxx)`：`where del_flag = '2'` + 与原列表相同的可选过滤条件 + `${params.dataScope}`（供回收站分页）。
- `restoreById(Long)` / `restoreByIds(Long[])`：`update ... set del_flag='0' where {id}=? and del_flag='2'`（带 `del_flag='2'` 前置条件，幂等且防误翻活跃记录）。
- `deleteByIdPhysically(Long)` / `deleteByIdsPhysically(Long[])`：**真·`delete from ...`**（仓内首次出现物理删除）。

约束：遵循本仓"Mapper XML 每条 SQL 必须注释"规范。

冲突检测复用既有 `checkUserNameUnique`/`checkPhoneUnique`/`checkEmailUnique`、`checkRoleKeyUnique`、`checkDeptNameUnique`——它们只比对 `del_flag='0'`，恰用于判断"恢复后是否与现存活跃记录撞名"。

## 4. 恢复的严格校验

恢复前置：目标项必须 `del_flag='2'`。逐项校验，任一不过则**该项失败**（不影响批内其他项，见 §6）：

- **身份冲突**：翻回 `'0'` 前跑对应唯一性校验器查活跃记录。用户查 `user_name`/`phonenumber`/`email`；角色查 `role_key`（及 `role_name`）；部门查同父 `dept_name`。命中 → 拒绝，原因"存在同名活跃 XX"。
- **悬空引用**：
  - 恢复用户：其 `dept_id` 指向的部门若仍 `del_flag='2'`/不存在 → **拒绝**，提示"请先恢复所属部门"。
  - 恢复部门：其父部门若仍 `del_flag='2'` → **拒绝**，提示"请先恢复上级部门"（自上而下恢复）。
  - 不做"自动把 `dept_id` 置空/挂根"（会造出无归属数据，明确排除）。
- 校验结论逐条写入 §5 结果 DTO。

## 5. 批量结果 DTO

`restore`/`purge` 均接受 `ids[]`，统一返回 `RecycleBinOpResult`：

```
RecycleBinOpResult {
    int total;
    int successCount;
    int failCount;
    List<Item> items;   // Item { Long id; String name; Status status; String reason; }
    enum Status { SUCCESS, FAILED }
}
```

失败项带可读原因（冲突 / 悬空 / 缺确认 / 越权 / 护栏拒绝），前端据此逐项提示。

## 6. 彻底删除二次确认 + 事务与部分成功

- **二次确认**：`purge` 必须带 `confirm=true`，否则整请求拒绝（防不可逆误删）；前端弹确认框（契约见 §10）。
- **事务粒度**：批内**每个 id 独立事务**（`REQUIRES_NEW`），逐个成功/失败并收集到 `RecycleBinOpResult`；一个坏项不回滚其余。`restore`/`purge` 同此策略。
- **关联清理**：软删时关联已清空，`purge` 只需删主表行，无关联残留需处理（部门无关联表）。
- **孤儿引用（已知副作用）**：物理 `purge` 用户/部门后，`sys_notice_read`、登录日志等**非关联表**仍留旧 `id`（无 FK，历史数据悬空）。不清理，仅文档告知（§11）。

## 7. 权限种子与数据权限

**细粒度权限位（决策 C=①）**：
- 查看位：`system:recyclebin:list`（一个）。
- 动作位：`system:{user|role|dept}:restore`、`system:{user|role|dept}:purge`（各类型独立，共 6 个）。
- 理由：`purge` 物理不可逆，独立授权位使"能恢复"≠"能永久销毁"，符合最小权限。
- controller：`@PreAuthorize("@ss.hasPermi('system:'+type+':'+action)")`（list 用 `system:recyclebin:list`）。

**数据权限**：
- `{type}/list` 复用对应 `@DataScope`（`selectDeletedXxxList` 带 `${params.dataScope}`），部门管理员只见自身范围。
- `restore`/`purge` **逐项**调 `checkUserDataScope`（对齐 `deleteUserByIds` 现状），杜绝跨范围恢复/销毁。

**种子数据**：`sys_menu` 按钮级插入 + 超管角色 `sys_role_menu` 关联；幂等写法须规避已知坑（`INSERT...SELECT` 派生表多未别名同名列会报 `Duplicate column name`，需显式别名）。

## 8. 安全护栏

- **超管**：`restore`/`purge` 用户时先过 `checkUserAllowed`，`admin`（userId=1）永不被动。
- **自删锁死**（新增，强于现状）：`purge` 禁止销毁当前登录用户自己（目标 id ≠ 当前 `userId`），防自我锁死且不可恢复。
- **部门 purge 阻断**：部门若有**活跃下级**（`hasChildByDeptId`，`del_flag='0'`）或**在职用户**（`checkDeptExistUser`）→ 拒绝物理删，防造出无主孤儿；其软删子项交由 §4 悬空校验兜。
- **不可逆留痕**：`purge` 挂 `@Log(businessType=DELETE)`；`restore` 亦为敏感写操作，全程审计。

## 9. 测试策略

- **单测**（`RecycleBinType` 元数据映射、冲突/悬空判定，mock `check*Unique`）：
  - 恢复成功且只还壳（关联不还原）；身份冲突拒绝；父部门未复拒绝；`purge` 缺 `confirm` 拒绝；自删拒绝；超管拒绝；批量部分成功（一坏项不回滚其余）。
- **Mapper 集成测**（依"mapper 集成测试实践规范"）：`selectDeletedXxxList` 仅返回 `del_flag='2'`；`restoreById` 带 `del_flag='2'` 前置、对活跃行不误翻；`deleteByIdPhysically` 真删。
- **分组**：集成测试按"集成测试分组与执行策略"打 tag（依赖远程共享环境），默认 `mvn test` 不跑。

## 10. 前端 API 契约

| 端点 | 请求 | 响应 |
|---|---|---|
| `GET /system/recyclebin/{type}/list` | `pageNum/pageSize` + 各类型过滤字段 | `TableDataInfo`（`total` + `rows[]`，含 `id`/名称/`createBy`/`createTime` 等既有列） |
| `PUT /system/recyclebin/{type}/restore` | `{ "ids": [...] }` | `AjaxResult{ data: RecycleBinOpResult }` |
| `DELETE /system/recyclebin/{type}/purge` | `{ "ids": [...], "confirm": true }` | `AjaxResult{ data: RecycleBinOpResult }` |

- **部分成功用 HTTP 200 承载**：即使有失败项也回 200，成败在 `items` 逐项体现；仅鉴权失败(403)/缺 `confirm`/非法 `type` 走错误路径。
- 回收站列表**不含"删除时间"**（无 `del_time` 数据源），按 `createTime` 排序。
- `type` 白名单：仅 `user|role|dept`。

## 11. 明确不在范围（YAGNI 边界）

- 关联不重建（只还实体壳，恢复后需重授权，前端须明示）。
- 无自动过期清理（不做"超 N 天自动 purge"，无限期保留至手动清除）。
- 无删除时间戳（不引入 `del_time`、不改软删）。
- 不做孤儿引用治理（`purge` 遗留的 `sys_notice_read`/日志旧 `id` 不清理）。
- 不做跨类型统一聚合视图（前端分页签各自请求）。

## 12. 风险

1. **部分成功不可回滚**：独立事务下批处理中途失败 → 已处理项不回退（`purge` 尤甚）。缓解：`RecycleBinOpResult` 逐项如实回报 + 前端明确展示。
2. **物理删除不可逆**：三重缓解（细粒度 `purge` 权限 + `confirm` + `@Log` 审计）；仍建议生产前 DB 备份。
3. **越权**：忘记对 `restore`/`purge` 逐项 `checkUserDataScope`/`checkUserAllowed` → 跨范围/动超管。列为测试必覆盖。
4. **动态权限串注入**：`system:{type}:purge` 拼接前必须白名单校验 `type`。
5. **种子脚本幂等**：`sys_menu`/`sys_role_menu` 重复执行会炸（含 `Duplicate column name` 坑，需显式别名）。
6. **"只还壳"认知差**：恢复后账号无角色权限，前端文案 + 文档强调需重授权，避免误以为"恢复即复原"。

## 13. 预计落点文件（实现顺序由 writing-plans 细化）

- 新增 `ruoyi-common/.../enums/RecycleBinType.java`（仅承载纯元数据：表名/展示名/主键字段/权限前缀；`type`→各 Mapper 的实际分派在 `RecycleBinServiceImpl` 内完成，避免 common 反向依赖 system）
- 新增 `ruoyi-system/.../service/IRecycleBinService.java` + `.../service/impl/RecycleBinServiceImpl.java`
- 新增 `ruoyi-system/.../domain/dto/RecycleBinOpResult.java`
- 扩充 `SysUserMapper`/`SysRoleMapper`/`SysDeptMapper` 及其 XML（各三条新方法，带 SQL 注释）
- 新增 `ruoyi-admin/.../web/controller/system/SysRecycleBinController.java`
- 种子 SQL：`sys_menu` + `sys_role_menu` 幂等插入（随功能 SQL 脚本交付）
- 单测 + Mapper 集成测（按分组 tag）
