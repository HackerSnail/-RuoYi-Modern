# system 模块横向审计红线清单

> 用途：`Sys*ServiceImpl` 逐个 code review 时的统一对照表。历轮 review 反复命中同样五类问题，
> 收敛为一份可勾选红线清单，后续每审一个服务类就逐条打勾，避免遗漏与重复讨论。
>
> 图例：✅ 已核实并落地 ｜ ⚠️ 部分/纵深 ｜ ➖ 不适用 ｜ ⬜ 待审

## 五条红线

| # | 红线 | 判定要诀（避免误判） |
|---|------|----------------------|
| R1 | **唯一性落地**：Service 增改路径必须强制校验唯一性，且 DB 唯一索引兜底 TOCTOU | Controller 前置校验 + Service 方法唯一调用方即该 Controller 时，"形同虚设/绕过前端直调"多为夸大——Service 收口属**纵深防御**非补现网漏洞。软删表只能加非唯一辅助索引；物理删表可加硬 UNIQUE 但须核对应用查重 SQL 语义（是否过滤 status/del_flag）与索引一致 |
| R2 | **回查判空**：`selectXxxById` 回查结果取字段前必须判空 | 本仓 `selectXxxById` 的 XML 多不过滤 del_flag（物理删表），或 ID 可被并发删除 → null 解引用即 500。反复中招：`updateConfig`/`deleteDictDataByIds`/`updateDictType`。判空后抛 `ServiceException` 明确文案 |
| R3 | **事务边界**：批量写/删加 `@Transactional`，中途失败不得"部分生效" | 用"整批预校验（收集）→ 统一执行"两趟；任一元素非法即抛异常回滚整批 |
| R4 | **缓存时序**：Redis 写/清必须发生在 DB 事务提交之后 | 提交前刷缓存、随后回滚 → 缓存与 DB 长期不一致。落 `TransactionSynchronization.afterCommit`，无活动事务时内联兜底 |
| R5 | **数据权限收口**：读写命门须经 `@DataScope`/`checkXxxDataScope` | `@DataScope` 是 Spring AOP，只有经代理调用（`getAopProxy(this).method`）才生效。判"裸奔"前先 grep 唯一调用方是否已在校验；对照兄弟方法找漏校验的直接越权点 |

## 逐类对照

| 服务类 | R1 唯一性 | R2 判空 | R3 事务 | R4 缓存时序 | R5 数据权限 | commit |
|--------|-----------|---------|---------|-------------|-------------|--------|
| SysConfigServiceImpl | ✅ Service 收口 + DB `uk_config_key`（无软删可硬 UNIQUE） | ✅ `updateConfig` temp 判空 | ✅ `deleteConfigByIds` 两趟预校验 | ✅ 空值哨兵短 TTL | ➖ 全局配置 | `562e2587` |
| SysPostServiceImpl | ✅ | ✅ | ✅ | ➖ 无缓存 | ⚠️ | `4021cf91` |
| RecycleBinServiceImpl | ➖ | ✅ | ✅ | ➖ 本仓无实体/部门树 Redis 缓存（一致性主张前提不成立） | ✅ `@DataScope` 经 getAopProxy 真生效 | `460611cf` |
| SysDictDataServiceImpl | ✅ Service 收口 + DB `uk_dict_type_value`（物理删表） | ✅ `deleteDictDataByIds` data 判空 | ✅ `@Transactional` 两趟 | ✅ 删空 removeDictCache + 分段锁串行化刷新 | ➖ 全局配置 | `8b5b9a3c` |
| SysDictTypeServiceImpl | ✅ insert/update 加 `checkDictTypeUnique` 收口（DB 已有 `unique(dict_type)`） | ✅ `updateDictType` oldDict 判空 | ✅ 批量删除事务（前轮） | ✅ update/delete 缓存移 afterCommit；启动加 SET NX 集群锁 | ➖ 全局配置 | 本轮 |
| SysRoleServiceImpl | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ `checkRoleDataScope`（deptTree 越权点见 SysDept 轮 #5 已补） | 待审 |
| SysUserServiceImpl | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | 待审 |

## 复用性纠正（历轮已证伪、勿再当作缺陷投递）

- "唯一性检查形同虚设/可绕过前端直调"：当 Service 方法唯一调用方是**已做校验的 Controller** 时属夸大。
- "缓存一致性脏范围"：数据权限每请求现算、无进程内/Redis 权限缓存时，主张前提不成立。
- `checkDictValueUnique` 跨类型误判：其 XML 已 `dict_type=? and dict_value=?` 联合。
- 字典"删空写空列表缓存致前端下拉变空/语义不一致"：读侧 `selectDictDataByType` 用 `isNotEmpty` 守卫，空列表当未命中回源，正确。
- `selectDictLabel` 走缓存、`selectDictTypeById` Controller 取字段 NPE：前者全仓无调用方（死码），后者 getInfo 仅 `success()` 包裹不解引用，均不成立。
