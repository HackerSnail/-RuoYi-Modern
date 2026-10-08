# 回收站（Recycle Bin）交付说明

- 日期：2026-10-08
- 对应 spec：[2026-10-08-recycle-bin-design.md](2026-10-08-recycle-bin-design.md)
- 对应计划：[2026-10-08-recycle-bin-implementation.md](../plans/2026-10-08-recycle-bin-implementation.md)
- 定位：后端实现 + 种子 SQL + 测试已全部落地；本文是给 UI 仓/运维的交接说明，并如实记录实现相对 spec 的偏差。

## 1. 里程碑与落点（提交谱系）

| 提交 | 内容 |
|---|---|
| `f57f7518` | 设计 spec |
| `d6c2eb29` | 实现计划（六阶段） |
| `1c8e0b65` | 阶段一 `RecycleBinType` 枚举 + `RecycleBinOpResult` DTO |
| `e6c25d68` | 阶段二 user/role/dept 三表 Mapper（列表/按id取/恢复/物理删）+ XML |
| `f6a6cc42` | 阶段三 Service（类型分派、软删口径数据权限、逐项部分成功） |
| `275f0c8a` | 阶段四 `SysRecycleBinController` + 程序化鉴权 + 菜单种子 SQL |
| `dbd6a4d7` | 阶段五 枚举白名单单测 + Service 逐项语义单测（默认 `mvn test` 全绿） |
| `3b38db90` | 阶段五 Mapper 集成测（`@Tag("integration")`，需集成库执行） |

交付文件：`sql/recyclebin_menu_20261008.sql`（权限种子）。

## 2. 前端 API 契约（照 spec §10）

`{type}` 仅取 `user | role | dept`，非法值返回业务错误（非 500）。

| 端点 | 请求 | 响应 |
|---|---|---|
| `GET /system/recyclebin/{type}/list` | query：`pageNum`/`pageSize` + 可选 `keyword` | `TableDataInfo`（`total` + `rows[]`，含实体既有列） |
| `PUT /system/recyclebin/{type}/restore` | body：`{ "ids": [...] }` | `AjaxResult{ data: RecycleBinOpResult }` |
| `DELETE /system/recyclebin/{type}/purge` | body：`{ "ids": [...], "confirm": true }` | `AjaxResult{ data: RecycleBinOpResult }` |

`RecycleBinOpResult`：`total / successCount / failCount / items[]`，`Item { id, name, status(SUCCESS|FAILED), reason }`。

- **部分成功恒 HTTP 200**：成败逐项体现在 `items`，前端按 `status/reason` 渲染；仅鉴权失败(403)、缺 `confirm`、非法 `type` 走错误响应。
- `keyword` 落到各实体名称字段（用户 `user_name` / 角色 `role_name` / 部门 `dept_name`）做模糊过滤。
- 列表**不含"删除时间"**（无 `del_time` 数据源），按 `createTime` 排序。

## 3. 部署步骤（务必先备份）

1. **备份数据库**——`purge` 物理不可逆，上线前对 `sys_user`/`sys_role`/`sys_dept` 做快照。
2. **执行种子 SQL** `sql/recyclebin_menu_20261008.sql`：向 `sys_menu` 插入 1 查看位 + 6 动作位、并向超管角色关联。脚本幂等（`SELECT ... FROM dual WHERE NOT EXISTS` 依 `perms` 去重，`menu_id` 走自增避开既有 100/1000 段），可安全重复执行。
3. **重启/刷新后台**使菜单生效；非超管角色在"角色管理"按需勾选新权限位。
4. **回滚**：撤销权限可按 `perms` 删除对应 `sys_menu` 行及 `sys_role_menu` 关联；后端代码回退到 `d6c2eb29`（spec/plan 之前）即整体移除功能。数据层零 schema 变更，无需结构回滚。

> 种子脚本本会话未在真实库执行（不擅自对库动 DDL/DML），需在具备 MySQL 的环境手动跑两遍确认幂等。

## 4. 运维/使用提示

- **恢复只还实体壳**：软删时角色/岗位/菜单等关联已被物理清空，恢复仅把 `del_flag` 翻回 `'0'`，**不会还原授权**。恢复用户后需重新分配角色、恢复部门后需重新挂载下级/权限——UI 与操作手册须明示，避免误以为"恢复即复原"。
- **恢复是自上而下的**：用户所属部门、部门的上级若仍在回收站，恢复会被拒（提示"请先恢复所属/上级部门"），需先恢复父级。
- **不可逆护栏**：`purge` 需 `confirm=true` + 独立 `system:{type}:purge` 权限 + `@Log` 审计三重把关；"能恢复"不等于"能永久销毁"。
- **已知残留**：物理删用户/部门后，`sys_notice_read`、登录日志等非关联表仍留旧 id（无外键、历史悬空），不清理（spec §11）。

## 5. 实现相对 spec 的偏差（均为落地细化，不改变设计意图）

1. **鉴权方式**（spec §7 写 `@PreAuthorize("@ss.hasPermi('system:'+type+':'+action)")`）：`@PreAuthorize` 表达式必须是编译期常量，无法随运行时 `{type}` 拼接。改为**方法体内程序化鉴权**——先 `RecycleBinType.fromCode(type)` 白名单校验，再取枚举派生的固定权限串调 `PermissionService.hasPermi` 断言，不过则抛 `AccessDeniedException`(403)。等价满足"白名单先于权限串、杜绝注入"（spec §12 风险 4）。
2. **事务/部分成功**（spec §6 初稿设想每项 `REQUIRES_NEW` 独立 bean）：实现发现恢复/销毁各只有一条状态变更语句（`update`/`delete`），单语句天然原子，故不开外层事务、逐项 try/catch 即达"部分成功"，无需 `REQUIRES_NEW` 与额外组件。spec §6 已同步修订。
3. **数据权限**（spec §7）：既有 `checkUserDataScope` 底层查询一律带 `del_flag='0'`，对回收站的 `del_flag='2'` 记录恒查空会 100% 误拒非超管。改用"软删口径"：以同样带 `@DataScope` 的 `selectDeletedXxxList` 按目标 id 过滤判可见性（超管经切面短路必命中）。spec §7 已同步修订。
4. **Mapper 每表 4 法而非 3**（spec §3）：额外新增 `selectDeletedXxxById`——恢复/销毁前需先按主键读到那条 `del_flag='2'` 记录取其名称/归属做校验。批量 `...ByIds` 变体因服务层逐项循环而未落地（无调用方即不写死代码）。

## 6. 测试现状

- **单测（默认 `mvn test` 全绿，不打库）**：`RecycleBinTypeTest` 4/4、`RecycleBinServiceImplTest` 11/11。覆盖枚举白名单、恢复只还壳、身份冲突、悬空部门、软删口径越权、缺 `confirm` 整请求拒、自删锁死、部门在职用户阻断、批量部分成功计数。
- **Mapper 集成测（`RecycleBinMapperIntegrationTest`）**：锁定 `del_flag='2'` 前置、恢复幂等不误翻活跃行、物理删真删三条 SQL 语义。`@Tag("integration")`，连共享测试库事务回滚；**本会话仅验证 `test-compile` 通过，跑绿需在具备集成库的环境执行 `mvn test -P integration`**。
