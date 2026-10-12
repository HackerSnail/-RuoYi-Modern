# 批次 16：GoFrame 移植代码生成器 `/tool/gen`（元数据 CRUD + Velocity 引擎逐字节生成 + 落盘）

## Summary

把 Java RuoYi 的代码生成模块（`ruoyi-generator`，`GenController` 13 端点 + `gen_table/gen_table_column` 元数据 +
`information_schema` 内省 + Velocity 模板产出 Java/Vue/TS/SQL + ZIP 下载 + 自定义路径落盘）移植到 GoFrame 后端
（`ruoyi-goframe`）。**逐字节完全忠实**：原样复用同一批 19 个 `.vm` 模板文件（拷进 Go embed，模板源不动），在其上
实现一个 **Velocity 子集解释器**，逐字节由构造保证，并用 **Apache Velocity 生成的金标准（oracle）** 逐字节 diff 收口。
`genCode`（写本地磁盘）亦忠实移植。前端 RuoYi-Vue3 的「系统工具 → 生成代码」页不改即可对接。

契约基准（只读实过来的源）：`GenController.java`、`GenTableServiceImpl.java`、`GenUtils.java`、`VelocityUtils.java`、
`GenConfig.java`、`GenConstants.java`、`GenTable.java`、`GenTableColumn.java`、`GenTableEditDTO.java`、
`GenTableMapper.xml`、`GenTableColumnMapper.xml`；模板 `vm/{java,xml,sql,js,ts,vue,vue/v3,vue/v3ts}/*.vm`；
`SqlUtil.filterKeyword`；种子 DDL `sql/ry_20260417.sql` 的 `create table gen_table` / `gen_table_column`（第 677/708 行）。

**无需 DDL 迁移**：`gen_table`/`gen_table_column` 已在共享库脚本内，Java 与 Go 同库直接共用。

---

## 决策与取舍（已获用户批准）

- **生成引擎口径 = 逐字节完全忠实**：用户明确选「逐字节」而非「结构等价」，也否掉了「拆子批先只做 CRUD、生成另议」——
  生成引擎必须做，且输出须与 Java 版**逐字节一致**。
- **落盘 `genCode` = 忠实移植写盘**：`genCode/{tableName}` 按 `gen_table.gen_path`（默认 `/` 回退到进程工作目录 `/src`）
  把 Java + mapper.xml 写到本地磁盘（对齐 Java `generatorCode`），受 `gen.allowOverwrite` 门控（关则拒绝，文案逐字对齐）。
- **实现路线 A**：复用 `.vm` + Go 版 Velocity 子集解释器 + Java oracle 金标准。否决 B（把 `.vm` 重写成 Go
  `text/template`——破坏同源保证、空白/方法调用/`#{}` 语义差异使逐字节更难）与 C（无成熟 Go-Velocity 库）。
- **createTable 的 Druid 缺位**：Go 侧无 Druid SQL parser。改「`filterKeyword` 等价 + 按 `;` 切分并仅接受**单条
  CREATE TABLE** 的轻量校验 + 原样执行该语句」，**行为契约对齐、实现不同**（详见 16-A 第 5、5b 节，spec 内如实标注差异）。

### 子批分解（每批一提交，沿用既有纪律）

- **16-A 元数据/CRUD 全链路（不含生成）**：`gen:` 配置块、`GenTable/GenTableColumn` 实体与 `GenTableEditDTO` 校验、
  `GenConstants` 常量、`GenUtils`（类名/包名/模块/业务名/功能名 + 列类型/控件/查询方式推导）、两枚 mapper（gen_table /
  gen_table_column CRUD + `information_schema` 内省，逐条对齐 XML）、service（selectGenTableList / dbList /
  selectDbTableListByNames / column / getInfo / selectGenTableAll / importGenTable / updateGenTable /
  deleteGenTableByIds / createTable / checkTableExist / synchDb）、controller 9 端点（list / {tableId} / db/list /
  column/{tableId} / importTable / createTable / PUT / remove / synchDb）+ 权限 `tool:gen:*`。做完 `/tool/gen` 页面
  浏览/导入/建表/配置/同步全可用。
- **16-B Velocity 引擎 + 上下文装配 + 预览/下载（逐字节）**：Go 版 Velocity 解释器（词法/表达式/指令/空白语义）+
  oracle 夹具 + `prepareContext/getTemplateList/getFileName/getImportList/getDicts/colSpan/expandColumn` 移植 +
  `previewCode`（返回**有序** `模板路径→内容` map）+ `downloadCode` 单表/批量（ZIP 条目名=`getFileName`、
  `index-bak.ts` 跨表聚合、批量上限 50、ZIP 写失败不吞）。端点：`preview/{tableId}`、`download/{tableName}`、
  `batchGenCode`。
- **16-C 落盘 genCode + 收尾**：`generatorCode` 写盘（跳过清单逐字对齐、`getGenPath`、`allowOverwrite` 门控、
  `assertSafeSegment` 路径穿越防御），端点 `genCode/{tableName}`；全量回归 + 集成 + 文档。

> 依赖关系：16-B 需要 16-A 的实体/常量/GenUtils/service；16-C 需要 16-B 的引擎与 `getFileName`。三批顺序落地。

---

## Key Changes（按子系统分组）

### 1. 数据模型 `internal/model/entity.go`（追加）

两张表都是 `gen_table`/`gen_table_column` 的**全列映射**（不像 SysJob 只映射 vo 子集），因为编辑/导入/生成都要读写全部列。
`columns`/`subTable`/`pkColumn`/`view`/`treeCode`… 为 Java 侧瞬态字段，Go 侧用 `orm:"-"` 承载、按需由 service 装配。

```go
// GenTable 对齐 gen_table 全列 + 瞬态装配字段（Java GenTable extends BaseEntity）。
type GenTable struct {
    TableId        int64           `json:"tableId"        orm:"table_id"`
    TableName      string          `json:"tableName"      orm:"table_name"`
    TableComment   string          `json:"tableComment"   orm:"table_comment"`
    SubTableName   string          `json:"subTableName"   orm:"sub_table_name"`
    SubTableFkName string          `json:"subTableFkName" orm:"sub_table_fk_name"`
    ClassName      string          `json:"className"      orm:"class_name"`
    TplCategory    string          `json:"tplCategory"    orm:"tpl_category"`
    TplWebType     string          `json:"tplWebType"     orm:"tpl_web_type"`
    PackageName    string          `json:"packageName"    orm:"package_name"`
    ModuleName     string          `json:"moduleName"     orm:"module_name"`
    BusinessName   string          `json:"businessName"   orm:"business_name"`
    FunctionName   string          `json:"functionName"   orm:"function_name"`
    FunctionAuthor string          `json:"functionAuthor" orm:"function_author"`
    FormColNum     *int            `json:"formColNum"     orm:"form_col_num"` // 可空：指针区分「未选」与 0
    GenType        string          `json:"genType"        orm:"gen_type"`
    GenPath        string          `json:"genPath"        orm:"gen_path"`
    Options        string          `json:"options"        orm:"options"`
    CreateBy       string          `json:"createBy"       orm:"create_by"`
    CreateTime     *gtime.Time     `json:"createTime"     orm:"create_time"`
    UpdateBy       string          `json:"updateBy"       orm:"update_by"`
    UpdateTime     *gtime.Time     `json:"updateTime"     orm:"update_time"`
    Remark         string          `json:"remark"         orm:"remark"`
    // 瞬态（非持久化，orm:"-"）：由 LEFT JOIN / service 装配，供生成上下文与前端回显。
    Columns      []*GenTableColumn `json:"columns"      orm:"-"`
    PkColumn     *GenTableColumn   `json:"pkColumn"     orm:"-"`
    SubTable     *GenTable         `json:"subTable"     orm:"-"`
    TreeCode     string            `json:"treeCode"     orm:"-"`
    TreeParentCode string          `json:"treeParentCode" orm:"-"`
    TreeName     string            `json:"treeName"     orm:"-"`
    ParentMenuId int64             `json:"parentMenuId" orm:"-"`
    ParentMenuName string          `json:"parentMenuName" orm:"-"`
    View         bool              `json:"view"         orm:"-"`
    Params       map[string]any    `json:"params"       orm:"-"` // BaseEntity.params：编辑期前端传入，updateGenTable 序列化成 options
}
```

```go
// GenTableColumn 对齐 gen_table_column 全列。isXxx 存 "0"/"1" 字符串（Java COLUMN_FLAG_YES="1"）。
type GenTableColumn struct {
    ColumnId      int64       `json:"columnId"      orm:"column_id"`
    TableId       int64       `json:"tableId"       orm:"table_id"`
    ColumnName    string      `json:"columnName"    orm:"column_name"`
    ColumnComment string      `json:"columnComment" orm:"column_comment"`
    ColumnType    string      `json:"columnType"    orm:"column_type"`
    JavaType      string      `json:"javaType"      orm:"java_type"`
    JavaField     string      `json:"javaField"     orm:"java_field"`
    IsPk          string      `json:"isPk"          orm:"is_pk"`
    IsIncrement   string      `json:"isIncrement"   orm:"is_increment"`
    IsRequired    string      `json:"isRequired"    orm:"is_required"`
    IsInsert      string      `json:"isInsert"      orm:"is_insert"`
    IsEdit        string      `json:"isEdit"        orm:"is_edit"`
    IsList        string      `json:"isList"        orm:"is_list"`
    IsQuery       string      `json:"isQuery"       orm:"is_query"`
    QueryType     string      `json:"queryType"     orm:"query_type"`
    HtmlType      string      `json:"htmlType"      orm:"html_type"`
    DictType      string      `json:"dictType"      orm:"dict_type"`
    Sort          *int        `json:"sort"          orm:"sort"`
    CreateBy      string      `json:"createBy"      orm:"create_by"`
    CreateTime    *gtime.Time `json:"createTime"    orm:"create_time"`
    UpdateBy      string      `json:"updateBy"      orm:"update_by"`
    UpdateTime    *gtime.Time `json:"updateTime"    orm:"update_time"`
    TableName     string      `json:"-"             orm:"table_name"` // 仅批量反读 information_schema 时作分组键（Java @JsonIgnore transient）
}
```

- 布尔派生方法（对齐 Java `isPk()/isList()/…`）：Go 侧写成 `func (c *GenTableColumn) IsPkFlag() bool { return c.IsPk=="1" }`
  等，供生成上下文与模板取用；`isSuperColumn`/`isUsableColumn`/`readConverterExp`/`capJavaField` 同样按 Java 逻辑实现。
- `GenTable.isSub()/isTree()/isCrud()/isSuperColumn(javaField)` 依 `TplCategory` + `GenConstants.BASE_ENTITY/TREE_ENTITY`。
- `FormColNum *int`：DB 可空，`getColSpan(nil)`→"24"（对齐 Java 判 null 防 NPE）。

### 2. 常量 `internal/service/genconstants.go`（新建）

逐字移植 `GenConstants`：`TPL_CRUD/TREE/SUB`、`TREE_CODE/PARENT_CODE/NAME`、`PARENT_MENU_ID/NAME`、`GEN_VIEW`、
`COLUMNTYPE_STR/TEXT/TIME/NUMBER`（各切片）、`COLUMNNAME_NOT_EDIT/LIST/QUERY`、`BASE_ENTITY`、`TREE_ENTITY`、
`HTML_*`（input/textarea/select/radio/checkbox/datetime/imageUpload/fileUpload/editor）、`TYPE_STRING/INTEGER/LONG/
DOUBLE/BIGDECIMAL/DATE`、`QUERY_LIKE/EQ`、`REQUIRE="1"`、`COLUMN_FLAG_YES="1"`。值必须与 Java 完全一致。

### 3. 工具 `internal/service/genuitls.go`（新建，对齐 `GenUtils`）

- `InitTable(t *model.GenTable, operName string)`：读 `gen:` 配置（`packageName/author/autoRemovePre/tablePrefix`）→
  `ConvertClassName`（去前缀 + `convertToCamelCase`）/`GetModuleName`（包名末段）/`GetBusinessName`（表名末 `_` 段）/
  `ReplaceText`（`RegExUtils.replaceAll(comment,"(?:表|若依)","")`）→ 填 className/packageName/moduleName/businessName/
  functionName/functionAuthor/createBy。
- `InitColumnField(col *model.GenTableColumn, t *model.GenTable)`：`getDbType`（截 `(` 前）→按 COLUMNTYPE_* 定
  javaType/htmlType；数字型解析 `(len)`/`(p,s)`（`Integer.parseInt` 失败降级 Long，对齐 try/catch）；`getColumnLength>=500`
  或 text → textarea；`javaField=toCamelCase(columnName)`；isInsert 恒 "1"；按 `COLUMNNAME_NOT_*` + isPk 定
  isEdit/isList/isQuery；列名后缀推导 queryType(LIKE)/htmlType(status→radio、type/sex→select、image→imageUpload、
  file→fileUpload、content→editor)。逐分支对齐 Java。
- `GetDbType/GetColumnLength/ArraysContains/GetModuleName/GetBusinessName/ReplaceFirst/ReplaceText` 纯函数。

### 4. Mapper（新建 `internal/service/gentable.go` + `gentablecolumn.go` 内的查询函数，对齐两枚 XML）

**用 GoFrame `g.DB()` 原生 SQL，逐条对齐 XML 语义**（`select database()` 限定当前库；LIKE 前缀转义 `qrtz\_%`/`gen\_%`）：

- `SelectGenTableList(ctx, f, p)`：`selectGenTableVo` + `<where>`（lower(table_name/table_comment) like、
  create_time 的 `date_format` beginTime/endTime 范围）；分页。
- `SelectDbTableList(ctx, f, p)`：`information_schema.tables` where `table_schema=(select database())` AND
  `table_name NOT LIKE 'qrtz\_%' AND NOT LIKE 'gen\_%' AND table_name NOT IN (select table_name from gen_table)` +
  同过滤 + `order by create_time desc`；分页。仅回 table_name/table_comment/create_time/update_time。
- `SelectDbTableListByNames(ctx, names)`：同上按 `table_name in (...)` 批量（导入预览）。
- `SelectGenTableById(ctx, id)`：主表 LEFT JOIN `gen_table_column` 按 `c.sort` 装配 columns（一次取回一表多列）；
  未命中→nil（service 转「生成表不存在，ID：x」）。
- `SelectGenTableByName(ctx, name)` / `SelectGenTableAll(ctx)`：同 XML（All 不含 gen_type/gen_path，忠实照 XML）。
- `InsertGenTable` / `UpdateGenTable` / `DeleteGenTableByIds`：动态列仅非空、`create_time/update_time=sysdate()`、
  回写自增 `table_id`。update 的 `<set>` 判空口径逐字对齐（`tableName/subTableName/...` 判 null 允许置空串、
  其余判非空）。
- `SelectGenTableColumnListByTableId(ctx, tableId)`：`where table_id=? order by sort`。
- `SelectDbTableColumnsByName(ctx, name)` / `SelectDbTableColumnsByNames(ctx, names)`：`information_schema.columns`
  用 CASE 推 `is_required/is_pk/is_increment`、`ordinal_position as sort`、带 `table_name` 分组键；names 空→`1=0`。
- `InsertGenTableColumn` / `BatchInsertGenTableColumn` / `UpdateGenTableColumn` / `DeleteGenTableColumnByTableIds` /
  `DeleteGenTableColumns`：动态列/多值 INSERT/`<set>` 判 null 保留 '0'/空集合守卫 `1=0`，逐字对齐。
- `CreateTableDDL(ctx, sql)`：mapper 层 `Exec(ctx, sql)` 原样执行 DDL（由 service `CreateSingleTable` 校验后调用）。
- `CheckTableExist(ctx, name) int`：`select count(1) from information_schema.tables where table_schema=(select database()) and table_name=?`。

### 5. 服务 `internal/service/gentable_service.go`（新建，对齐 `GenTableServiceImpl`）

- `SelectGenTableById`：查库→nil 抛「生成表不存在，ID：x」→`setTableFromOptions`（解析 options JSON 填 treeCode/
  parentMenuId/view 等瞬态）。
- `UpdateGenTable`：`options = json(params)` → 更新主表 → rows>0 且带 columns 时逐列**归属校验**
  （`column.TableId != tableId` 抛「列[x]不属于当前生成表，禁止跨表修改」）→ 更新列。事务。
- `DeleteGenTableByIds`：删主表 + 级联删列。事务。
- `CreateSingleTable(sql)`（service，逐字对齐 Java `GenTableServiceImpl.createTable`）：**忠实复刻 `SqlUtil.filterKeyword`**
  （⚠️ 关键：它先 `replaceAll("\\p{Z}|\\s"
    ,"")` 删净所有空白，再按**字符集** `{\\,|}` split `SQL_REGEX`，用 `indexOfIgnoreCase` 匹配那个「无空白」串——
    故 `"drop "/"insert "/"select "` 等**带尾空格的 token 永不命中**，真正生效子集仅
    `extractvalue|updatexml|sleep|information_schema|+|/*|user()`（及 `%0A`/`\u000B`）；一条正常 `CREATE TABLE` 恒放行。
    **不得「修正」该怪癖**，须原样复刻以保行为一致）；再校验入参恰为**单条** CREATE TABLE（多语句/非建表→抛
    「非法建表语句：仅允许执行单条 CREATE TABLE」）；`Exec(原样sql)`，无 error 即成功。（无 Druid 之 AST 重序列化：Java 靠
    AST 输出语句、Go 靠上述严格前置校验后原样执行，行为目标一致，差异如实注于代码注释。）
- `CheckTableExist(name)`：`count>0`。

### 5b. createTable 建表编排（对齐 Java **controller** `GenController.createTableSave`，非 service）

Go 侧该编排放 controller（忠实于 Java 分层）。**逐字复刻两阶段 + 各文案**：
1. `filterKeyword(sql)`（整体先过一遍；抛错则由兜底 catch 转通用文案）。
2. **切分并收集**：Go 无 Druid AST，改「tokenizer 感知引号/注释的顶层 `;` 切分 → 逐条按归一化前导关键字分类 →
   **仅收集 `CREATE TABLE` 开头的语句**（非建表语句像 Java 一样被静默丢弃）」。收集为空→`error("未解析到有效的 CREATE TABLE 语句")`。
   （切分正确性风险由单测 + 「Java 本就静默丢弃非 CREATE」的行为目标兜住。）
3. **防覆盖**：逐条取表名（去反引号）→ `CheckTableExist` 命中→`error("表 '<name>' 已存在，不允许覆盖创建")`。
4. **阶段一**：逐条 `CreateSingleTable(stmt)`，成功则把表名加入 `createdTables`。任一异常→兜底 catch：`createdTables` 非空
   →`error("创建表结构异常，已成功建表 <[..]>，请人工核对")`，空→`error("创建表结构异常")`。
5. `createdTables` 空→`error("创建表结构失败，未成功建立任何表")`。
6. **阶段二**：`SelectDbTableListByNames(createdTables)`→`ImportGenTable(...,tplWebType,当前用户)`；异常→
   `error("表已建成功 <[..]>，但导入生成配置失败，请人工核对后重试")`。
7. 全部成功→`success()`。
- `ImportGenTable(tableList, tplWebType, operName)`：批量反读列按表名分组（N 表 1 次 information_schema）→逐表
  `InitTable`+insert→rows>0 则该表列 `InitColumnField`+`BatchInsert`。异常→「导入失败：x」。事务。
- `SynchDb(tableName)`：查生成表（含列）→实表列（空→「同步数据失败，原表结构不存在」）→逐实表列 `InitColumnField` +
  按 columnName 命中则沿用旧 columnId 转 update（list 保留 dictType/queryType、非主键可编辑保留 isRequired/htmlType），
  否则 insert；旧列不在实表的按 columnId 精删。事务。文案逐字对齐。
- `ValidateEdit(t)`：tree → treeCode/treeParentCode/treeName 非空（否则「树编码字段不能为空」等）；sub →
  subTableName/subTableFkName 非空（否则「关联子表的表名不能为空」「子表关联的外键名不能为空」）。逐字对齐。

### 6. 控制层 `internal/controller/gen.go` + 路由 `internal/router/router.go`

逐字对齐 `GenController`。`@RequestBody` 端点 `r.Parse`；表单/查询参数用 `r.Get`/`r.Query`；路径变量 `{x}`。

| 端点 | 方法/路径 | 权限 | @Log | 行为 |
|---|---|---|---|---|
| genList | GET `/tool/gen/list` | `tool:gen:list` | — | 过滤+分页 → `DataTable` |
| getInfo | GET `/tool/gen/{tableId}` | `tool:gen:query` | — | `{info:表, rows:列, tables:全部表}` 三键 map |
| dataList | GET `/tool/gen/db/list` | `tool:gen:list` | — | `SelectDbTableList` → `DataTable` |
| columnList | GET `/tool/gen/column/{tableId}` | `tool:gen:list` | — | 列 list + `total=len`（**不分页**，对齐 Java 手填 TableDataInfo） |
| importTable | POST `/tool/gen/importTable` | `tool:gen:import` | 代码生成/BtImport | 表单 `tables`(逗号串)+`tplWebType`；空→「请选择要导入的表」；反读实表→ImportGenTable |
| createTable | POST `/tool/gen/createTable` | `tool:gen:create` | 创建表/BtOther | 表单 `sql`+`tplWebType`；两阶段（建表 / 回读导入），文案含「表已建成功 [..]，但导入生成配置失败…」 |
| edit | PUT `/tool/gen` | `tool:gen:edit` | 代码生成/BtUpdate | body `GenTableEditDTO`，逐字段校验（见第 7 节）→copy 回实体→ValidateEdit→UpdateGenTable |
| remove | DELETE `/tool/gen/{tableIds}` | `tool:gen:remove` | 代码生成/BtDelete | 逗号拆 ids→DeleteGenTableByIds |
| preview | GET `/tool/gen/preview/{tableId}` | `tool:gen:preview` | — | `previewCode`→有序 map（16-B） |
| download | GET `/tool/gen/download/{tableName}` | `tool:gen:code` | 代码生成/BtGenCode | `downloadCode(name)`→写 zip（16-B） |
| genCode | GET `/tool/gen/genCode/{tableName}` | `tool:gen:code` | 代码生成/BtGenCode | `allowOverwrite` 关→「【系统预设】不允许生成文件覆盖到本地」；开→generatorCode 写盘（16-C） |
| synchDb | GET `/tool/gen/synchDb/{tableName}` | `tool:gen:edit` | 代码生成/BtUpdate | SynchDb |
| batchGenCode | GET `/tool/gen/batchGenCode?tables=` | `tool:gen:code` | 代码生成/BtGenCode | 逗号拆 names；空→抛「请选择要生成的表」；`downloadCode(names)`→zip |

- 路由静态段先于动态：`list`/`db/list`/`column/{id}`/`importTable`/`createTable`/`preview/…`/`download/…`/
  `genCode/…`/`synchDb/…`/`batchGenCode` 均须先于裸 `/{tableId}`（GET）注册；GF 字面量优先，但 `/{tableId}` 与
  `/{tableName}` 动态段易吞静态段，务必**先注册所有静态/多级路径**。`db/list` 是两级、`{tableId}` 是一级，天然不冲突；
  `column/{tableId}`、`preview/{tableId}`、`download/{tableName}`、`genCode/{tableName}`、`synchDb/{tableName}` 均两级。
- 下载/批下响应头逐字对齐 `writeZipToResponse`：`Content-Disposition: attachment; filename="ruoyi.zip"`、
  `Content-Type: application/octet-stream; charset=UTF-8`、`Access-Control-Expose-Headers: Content-Disposition`。

### 7. 编辑校验契约（`@Validated GenTableEditDTO`，Go 侧显式校验收口）

进 service 前在 controller 校验，文案逐字对齐 DTO 注解：`tableName/tableComment/className/packageName/moduleName/
businessName/functionName/functionAuthor` 空→各自「x不能为空」；`tplCategory∉{crud,tree,sub}`→「模板类别非法，应为
crud/tree/sub」；`tplWebType∉{element-ui,element-plus,element-plus-typescript}`→对应文案；`genType∉{0,1}`→
「生成方式非法，应为 0(zip压缩包)/1(自定义路径)」；`columns[].javaField` 空→「Java属性不能为空」。

---

## 16-B 详述：Velocity 子集解释器（逐字节核心）

### 特性面（从 19 个模板实测枚举，须全支持）

- **指令**：`#if / #elseif / #else / #end`、`#foreach( $x in $coll ) ... #end`、`#set( $x = expr )`、`#break`。
  （`#` 后非上述指令名 → 字面输出，覆盖 MyBatis `#{item.$col.javaField}`、`#{$var}`、`## 注释`按需处理——`##` 是行注释须吞，
  `#{`/`#x` 非指令则原样。）
- **引用**：`$ref`、`${ref}`；属性/方法链 `$column.javaField`、`$table.sub`、`$columns.size()`、
  `$column.columnComment.indexOf("（")`、`.substring(0, x)`、`.toUpperCase()`、`.replace("'", "")`、`.split(",")`、
  `.trim()`、`.equals(...)`、`.startsWith/endsWith`。解析口径同 Velocity：先试 `getX()` 再试 `isX()` 再试字段/ map 取值；
  显式方法调用按名映射到 Go shim。
- **foreach 内置**：`$foreach.count`(1 基)、`$foreach.index`(0 基)、`$foreach.hasNext`、`$foreach.first`、`$foreach.last`
  （模板实际用到 count/hasNext，其余一并实现以防遗漏）。
- **表达式**：算术 `+ - * / %`（整型/字符串拼接：`+` 任一操作数为 string 则拼接，对齐 Velocity）、比较 `== != < > <= >=`、
  逻辑 `&& || !`、字面量（双/单引号串、整数、`true/false`、`$null`）、括号。空串比较惯用法 `"" != $x`、`$x != ""`。
- **空白/换行语义（逐字节最大雷区）**：复刻 Velocity——**仅含指令（`#if/#end/#set/#foreach/#break` 等）及其前后空白、
  不含正文的整行，其尾随换行被吞**；正文行内的指令**不吃**换行；`#foreach` 循环体的前后空白按 Velocity「块指令独占行」规则处理。
  该规则用 oracle 逐字节暴露、迭代收敛。

### 方法调用 shim（Go 未反射 Java bean，按枚举实现）

`String`: `indexOf(str[,from])`、`substring(begin[,end])`、`toUpperCase/toLowerCase`、`trim`、`replace(old,new)`、
`split(regex)`、`startsWith/endsWith`、`equals/equalsIgnoreCase`、`length`、`charAt`、`matches`。
`List`: `size`、`isEmpty`、`get(i)`。`Map`: `get/containsKey/keySet/size`。全部按 Java 语义（`substring` 越界抛/
`indexOf` 未命中 -1、`split(" ")` 保留空尾等边角逐条对齐，靠 oracle 验证）。

### 上下文装配（移植 `VelocityUtils`）

`prepareContext(t)`：填入 `tplCategory/tableName/functionName/ClassName/className/moduleName/BusinessName/businessName/
basePackage/packageName/author/colSpan/datetime/pkColumn/importList/permissionPrefix/columns/table/dicts/genView/
parentMenuId`（+ tree/sub 条件变量 `treeCode/treeParentCode/treeName/expandColumn/tree_parent_code/tree_name`、
`subTable/subTableName/subTableFkName/subTableFkClassName/subTableFkclassName/subClassName/subclassName/subImportList`）。
`datetime` 用 `gtime.Now()` 的 `yyyy-MM-dd`（对齐 `DateUtils.getDate()`）。`getTemplateList(t)` 按 tplWebType×tplCategory×
genView 组装**同一顺序**的模板路径列表（`vm/java/domain.java.vm`… + `vm/{js|ts}/api.*` + v3ts 追加 type.ts/index.ts +
vue 主模板 + sub 追加 sub-domain + view 追加 view.vue）。`getFileName(template, t)`：路径穿越 `assertSafeSegment` 白名单
`^[A-Za-z0-9_.]+$` + 拒 `..`（抛「代码生成非法x，疑似路径穿越，已拒绝：value」），再按 Java 的 `template.contains(...)`
分支顺序产出相对路径（sub-domain 先判）。

### preview / download 契约（逐字）

- `previewCode(tableId)`：查表→nil 抛→`setSubTable`+`setPkColumn`→`prepareContext`→遍历 `getTemplateList`
  渲染 → `LinkedHashMap[模板路径]=内容`（**键是模板路径不是文件名、顺序=模板列表顺序**，前端按此回显）。
- `downloadCode(names)`：空→「请选择要生成的表」；`>50`→「单次批量生成不得超过 50 张表，请分批生成」；逐表渲染全部模板，
  `getFileName` 作 ZIP 条目名，`index-bak.ts` 跨表聚合（首表整份、后续仅追加 `export * from` 行），末尾统一把聚合项写入
  zip；ZIP 写失败累积、关闭后抛「部分文件写入ZIP失败：[..]」（不吞）。zip 用 Go `archive/zip`，UTF-8 条目名。
- `setPkColumn`/`setSubTable`/`requireTableByName`/`getImportList`/`getDicts`/`getColSpan`/`getExpandColumn` 逐一对齐 Java。

### Oracle 金标准（验证「逐字节」）

放 `ruoyi-generator/src/test/.../GenGoldenDumpTest.java`（**Java 夹具，不进 Go 提交**，产出物拷入 Go `testdata`）：
用现有 Apache Velocity 依赖，对**覆盖性 fixture 矩阵**渲染每个模板并 dump 到 `testdata/golden/<case>/<template>`。
矩阵维度：`tplCategory∈{crud,tree,sub}` × `tplWebType∈{element-ui,element-plus,element-plus-typescript}` ×
`genView∈{false,true}` × 列类型覆盖（str/text/time/number(int/long/bigdecimal)/含状态/类型/性别/图片/文件/内容后缀/
含全角括号注释/主键非首列/超长≥500）。Go 测试用同 fixture 构造 context、渲染同名 `.vm`，`bytes.Equal` 逐字节断言，
失败打印首个差异偏移。以此把「逐字节」从口号变成可证伪门禁。

---

## 16-C 详述：落盘 genCode

- `generatorCode(tableName)`：查表→装配 sub/pk→`prepareContext`→遍历模板，**跳过清单逐字对齐**
  （`sql.vm/api.js.vm/api.ts.vm/type.ts.vm/index.ts.vm/index.vue.vm/index-tree.vue.vm/view.vue.vm` 不写盘，只渲染
  java+mapper.xml）→`getGenPath(table, template)`→`os.WriteFile`(UTF-8 无 BOM)；写失败→「渲染模板失败，表名：x」。
- `getGenPath`：`genPath=="/"`→`<进程工作目录>/src/<fileName>`（`System.getProperty("user.dir")` 等价取 `os.Getwd`）；
  否则 `genPath/<fileName>`。落盘前对整段做目录穿越防御（叠加 `assertSafeSegment` 已保 fileName 段合法；genPath 来自
  可编辑 gen_table，须拒绝越出白名单根以外的写——实现时以 `filepath.Clean` + 前缀校验收口，行为不低于 Java）。
- `allowOverwrite=false`（controller 层先拦）→「【系统预设】不允许生成文件覆盖到本地」。默认 false（对齐 Java `gen.allowOverwrite: false`）。

---

## 8. 配置 `config.yaml`（追加 `gen:` 顶层块）

```yaml
# 代码生成配置（对齐 GenConfig @ConfigurationProperties(prefix=gen)，Java application.yml 同源）
gen:
  author: "HackerSnail <2306002088@qq.com>"
  packageName: com.ruoyi.system   # 默认生成包路径
  autoRemovePre: false
  tablePrefix: sys_
  allowOverwrite: false           # 是否允许生成文件覆盖到本地（自定义路径）
```

`GenConfig` 读取封装成 `getGenConfig(ctx)`，供 `InitTable`/`ConvertClassName` 与 `genCode` 门控使用。

---

## Test Plan

- **16-A**：
  - 单测：`InitTable`（去前缀/大小写/末段/replaceText 去「表|若依」）、`InitColumnField`（各 COLUMNTYPE 分支、
    数字 `(p,s)` 降级、后缀控件、`COLUMNNAME_NOT_*`+isPk）、`filterKeyword`/单条 CREATE TABLE 校验（多语句/非建表/
    危险词各被拒）、`ValidateEdit`（tree/sub 缺字段文案）、列归属越权被拒。
  - 运行期集成（隔离端口，验证码经 DB 临时关）：db/list→importTable（挑一张真实业务表）→list/column/getInfo 回显→
    edit（改 functionName+列 isQuery，验证 options=json(params)、跨表列被拒）→synchDb（改实表列后同步）→createTable
    （造一张 `itest_gen_x` 新表，二次同名防覆盖被拒）→remove→清理itest 表与生成配置，库回基线。
- **16-B**：
  - oracle 逐字节：上述 fixture 矩阵下每个模板 `bytes.Equal` 全绿（引擎的核心验收）。
  - `getFileName` 单测：各模板→相对路径；含 `..`/非法字符→抛穿越。`getTemplateList` 顺序断言。
  - 运行期：preview/{tableId}→map 键=模板路径且顺序一致、内容与 oracle 同；download→HTTP200+PK 魔数+`readzip`
    反读条目名/内容与 Go 渲染一致；batchGenCode 多表→index-bak.ts 聚合、>50→拒绝文案。
- **16-C**：
  - 落盘：临时 `allowOverwrite=true` 指向 tmp 子目录跑 genCode→断言 java/mapper.xml 落盘、vue/sql **不落盘**；
    `allowOverwrite=false`→拒绝文案；genPath 穿越（如 `../../../../evil`）被拒。跑完删 tmp。
  - 全量：`go mod tidy && go build && go vet && go test ./internal/...`；连真实 DB 端到端；测试数据/captcha/config 复原、
    `tmp/` 脚手架 rmdir、`git status` 干净（oracle 夹具与 go.mod 若因 embed 无新增依赖则不变）。

---

## 落地顺序（供 writing-plans 细化；跨 16-A/B/C 三提交）

**16-A**：1) `genconstants.go` 常量 → 2) entity.go 追加两实体+派生方法 → 3) `gen:` 配置块 + getGenConfig →
4) `genuitls.go` → 5) mapper 查询/CRUD/内省函数 → 6) `gentable_service.go`（含 createTable Go 校验/synchDb/
validateEdit）→ 7) `controller/gen.go` 9 端点 + router + 编辑校验 → 8) 编译/vet/单测/集成 → commit+push。
**16-B**：9) Velocity 解释器（词法/指令/表达式/方法 shim/空白语义）+ 解释器单测 → 10) Java oracle 夹具 + fixture 矩阵 +
生成 `testdata/golden/**` → 11) `velocity_utils.go`（prepareContext/getTemplateList/getFileName/…）→ 12)
gentable_service 补 previewCode/downloadCode/setPk/setSub → 13) controller 补 preview/download/batchGenCode +
zip → 14) 逐字节 golden 测试 + 集成 → commit+push。
**16-C**：15) generatorCode 落盘 + getGenPath + 穿越防御 + allowOverwrite 门控 + controller genCode →
16) 落盘测试 + 全量回归 + 还原 → commit+push。

## Assumptions

- 共享库 `ry-vue` 已含 `gen_table`/`gen_table_column`（脚本有 DDL；实现前用一次性 Go 探针核对其存在与是否已有导入数据可作 fixture，缺则跑脚本内建表段）。
- GoFrame 既有分页/`DataTable`/`api.Ok·Err`/`middleware.Permi·Log·OpLog`/`WriteExcel`/`r.Parse·r.Get·r.Query`/`{x}` 路径参数
  约定与本仓已移植模块一致，直接复用（batch 15 同款）。
- 「逐字节」的正确性以 **Java oracle 为唯一裁判**：凡 golden 与 Go 渲染不一致，一律改 Go 引擎/上下文直至相等，不回退口径。
- 生成物为 Java/Vue 脚手架，本就不编译于 Go 后端；本批忠实复刻其**文本产物**（给人新建工程用），非让 Go 运行它。
- `.vm` 模板文件原样拷贝进 Go embed（内容与 `ruoyi-generator/src/main/resources/vm/**` 逐字节相同），Java 侧模板若后续变更需同步。
