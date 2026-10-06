# 一个开源框架的"屎山"清理实录：以 RuoYi-Vue 为手术台的 8 天重构日志

## 写在前面

RuoYi-Vue，国内 Java 圈最流行的后台管理脚手架之一。22k+ star，几乎每个 Java 后端开发都 fork 过它、跑过它、甚至拿它交过课设。

它开箱即用、模块划分清晰、文档齐全——但它经不起细看。

资源关闭用 Java 6 时代的 `try-finally`；菜单排序、部门排序、字典删除全是循环里逐条 UPDATE/SELECT 制造的 N+1；JWT 是自制 Base64 HMAC；密钥硬编码 26 字节明文；测试文件夹——空的，压根没有 `src/test`。

我拿它做了一次完整的"屎山清理"手术：八天，从搭测试安全网、到 IO 流现代化、到 JWT 库迁移、到 N+1 批量改造、到 Mapper SQL 集成测试床、到远端环境的端到端验证。每一步有设计、有取舍、有踩坑。

先声明一个定位：**这是一次人机协作的实践。** AI 负责代码探索（grep 定位所有流操作、列出 N+1 候选点）、样板生成（测试骨架、XML 映射模板）、API 细节查询（jjwt 源码确认密钥长度约束）。我负责：手术切口选择（先动哪里）、方案拍板（改到什么程度就停）、语义判断（哪些改动会改变运行时行为）、验收标准（全绿才算过）。下文如实呈现这些分工点。

---

## 01 搭安全网——33 个测试用例

### 为什么不补全量测试

RuoYi 测试覆盖率为零。如果目标是"给一个中型项目补全测试"，那至少需要两到三周，而且 ROI 递减——大量 getter/setter 和 CRUD 的测试只是数字游戏。

我的策略叫 **Risk-first**：只覆盖后续重构可能弄断的路径。三类：

1. **认证命脉链** — `login → UserDetailsServiceImpl → SysPasswordService → TokenService.createToken → Redis → JwtAuthenticationFilter → /getInfo`。改坏 = 用户无法登录。
2. **数据完整性约束** — 密码加密不可逆、Token 签名可验、密钥长度合规。
3. **被改对象的对照面** — FileUtils、ImageUtils、HttpUtils——如果 TWR 改造让行为变了，测试必须报警。

最终 33 个测试用例，分布在 `ruoyi-common`（6 个，资源处理类）和 `ruoyi-system`（9 个，Service 层契约测试）加认证链集成测试等。

### 踩的第一个坑：AsyncManager 静态初始化器

写认证链测试时碰到一个硬钉子：`SysLoginService` 内部调 `AsyncManager.me().execute(AsyncFactory.recordLogininfor(...))`，而 `AsyncManager` 的 `static {}` 初始化器里做了 `SpringUtils.getBean("ryAsync")`——纯 Mockito 单测没有 beanFactory，直接 NPE。

AI 第一反应是 `mockStatic(AsyncManager.class)`。但这没用：`AsyncManager.me()` 的 `static <clinit>` 在类加载时就执行，早于 mockito 的 interceptor 注册。

我的判断：**绕过静态初始化器，注入一个假 beanFactory 给 SpringUtils**：

```java
@BeforeEach
void injectBeanFactory() {
    BeanFactory bf = mock(BeanFactory.class);
    when(bf.getBean("ryAsync")).thenReturn(new ThreadPoolTaskExecutor());
    ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", bf);
}
```

不需要 mock AsyncManager 的行为——只要 `me()` 不炸，后续链就能跑通。

### 认证链的"命脉"测试长什么样

核心用例：用户密码错误 → 不产生 token。

```java
@Test
void loginFailsWithWrongPassword() {
    SysUser user = new SysUser();
    user.setUserName("admin");
    user.setPassword("$2a$10$realHash");
    when(userService.selectUserByUserName("admin")).thenReturn(user);
    when(passwordService.matches(any(), eq("wrongPwd"))).thenReturn(false);

    assertThrows(UserPasswordNotMatchException.class,
        () -> loginService.login("admin", "wrongPwd", "code", "uuid"));
}
```

这些不是"覆盖率游戏"，而是"如果你改坏了 TokenService 的签名逻辑，这个测试会告诉你"。

---

## 02 finally-close → try-with-resources

### 为什么"老式但正确"是个坏信号

RuoYi 的 `HttpUtils.sendGet` 长这样：

```java
BufferedReader in = null;
try {
    in = new BufferedReader(new InputStreamReader(
            connection.getInputStream(), contentType));
    // read...
} catch (IOException e) { ... }
finally {
    try { if (in != null) in.close(); } catch (IOException e) { }
}
```

功能正确，但有三个问题：

1. **样板噪音**：真正的逻辑只有两行，但 60% 是 null-check + close + catch。
2. **异常吞并**：如果 try 里抛了一个异常、close 又抛一个，finally 里把 close 的吞了——主异常保留是对的，但这种模式让人误以为"所有 close 异常都不重要"。
3. **不支持 Suppressed 链**：TWR 在 close 异常时会 addSuppressed，语义更丰富。

### 全量扫描 + 判定表

AI grep 了三个类里所有涉及 `InputStream`/`OutputStream`/`BufferedReader`/`PrintWriter` 的方法，列出一张判定表，我逐个拍板：

| 方法 | 判定 | 理由 |
|---|---|---|
| FileUtils.writeBytes（×2） | 可安全转 TWR | 输入流本地开；输出流由调用方传入，保留其关闭语义 |
| ImageUtils.getImage | 可安全转 TWR | 单 InputStream |
| ImageUtils.readFile | 需重构 TWR 嵌套 | if/else 两分支各自开流，互斥 |
| HttpUtils.sendGet | 可安全转 TWR | BufferedReader 单流 |
| HttpUtils.sendPost | 需谨慎：写→读顺序 | PrintWriter out 和 BufferedReader in 各独立 TWR |
| HttpUtils.sendSSLPost | 上一轮已 TWR | 不动 |

### sendPost：顺序陷阱

原代码在 finally 里先关 out 再关 in。TWR 如果写成 `try (out; in)`，关闭序是逆声明序——in 先关、out 后关——语义反了。

我的方案：**两个流各用独立 TWR 块**，保持先写完 flush 再 read：

```java
try (PrintWriter out = new PrintWriter(conn.getOutputStream())) {
    out.print(params);
    out.flush();
}
// out 已关闭 → request body 结束
try (BufferedReader in = new BufferedReader(
        new InputStreamReader(conn.getInputStream(), contentType))) {
    // read response line by line
}
```

### POI close 语义差异

RuoYi ExcelUtil 用 `IOUtils.close(workbook, sheet)`（Apache POI 版，吞异常）。TWR 里 `workbook.close()` 的 IOException **会抛出**。

我拍板接受这个差异：导出场景下，`workbook.write(response.getOutputStream())` 已经把数据全部刷到客户端。后续 close 失败通常是清理 temp file——对用户透明。让异常更透明反而更好。

### 结果

6 个站点全部 TWR 化，33 用例仍全绿。`mvn test` BUILD SUCCESS。

---

## 03 JWT 迁移 + 敏感配置清理

原来的 Token 生成是：自拼 `Base64(header).Base64(payload).HMAC-SHA256(secret, message)`——不是标准库、密钥 26 字节明文、没有 algorithm 协商。

换成 `jjwt 0.13.0`（api + impl + jackson 三件套）：

```java
Key key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
String token = Jwts.builder()
    .claims(claims)
    .subject(loginUser.getUsername())
    .issuedAt(now)
    .expiration(exp)
    .signWith(key, Jwts.SIG.HS512)
    .compact();
```

**运行时约束**：jjwt 0.13.0 对 HS512 要求密钥 **>= 64 bytes**（RFC 7518 §3.2）。RuoYi 原来的 26 字符密钥直接触发 `WeakKeyException`。这不是编译期能发现的——只有运行时 sign 的那一刻才炸。

我的处理：把 `token.secret` 改成环境变量占位 `${TOKEN_SECRET:...}`，默认值用满足 64 字节的随机串；真实部署通过环境变量注入。密钥不进 git。

---

## 04 N+1 批量改造——真正改头换面的手术

### 问题定位

AI 在 Service 层 grep 到四处"循环内逐条 DB 操作"：

| 方法 | 模式 | 问题 |
|---|---|---|
| updateMenuSort | for → updateMenuSort | N 次 UPDATE |
| updateDeptSort | for → updateDeptSort | N 次 UPDATE |
| deleteDictTypeByIds | for → selectById + check + delete | **3N 次** DB |
| importUser | for → selectConfigByKey | 每行重复读配置 |

### CASE WHEN 批量 UPDATE：menu 和 dept

经典写法——把 N 条 UPDATE 合成 1 条 SQL，用 `CASE id WHEN x THEN y` 做逐行赋值：

```xml
<update id="updateMenuSortBatch" parameterType="java.util.List">
    update sys_menu
    set order_num = case menu_id
        <foreach collection="list" item="item">
            when #{item.menuId} then #{item.orderNum}
        </foreach>
        else order_num
    end
    where menu_id in
        <foreach collection="list" item="item" open="(" separator="," close=")">
            #{item.menuId}
        </foreach>
</update>
```

Service 层改动：

```java
List<SysMenu> list = new ArrayList<>();
for (int i = 0; i < menuIds.length; i++) {
    SysMenu m = new SysMenu();
    m.setMenuId(Convert.toLong(menuIds[i]));
    m.setOrderNum(Convert.toInt(orderNums[i]));
    list.add(m);
}
if (!list.isEmpty()) menuMapper.updateMenuSortBatch(list);
```

**行为等价**：相同输入 → 相同 DB 结果。执行路径从 N 次往返变 1 次。

dept 的 `updateDeptSortBatch` 完全对称。

### deleteDictTypeByIds：3N → 3 + 改行为

原代码有个**隐藏 bug**：循环里"发现已分配就抛异常"，但前面已经删掉的记录不会回滚——**部分删除**隐患。

我的方案：**改为全原子**。

```java
@Transactional
public void deleteDictTypeByIds(Long[] dictIds) {
    if (dictIds == null || dictIds.length == 0) return;

    // 1 次批量回查
    List<SysDictType> types = dictTypeMapper.selectDictTypeByIds(dictIds);
    List<String> names = types.stream()
        .map(SysDictType::getDictType).collect(Collectors.toList());

    // 1 次批量筛"已被 sys_dict_data 占用"的类型
    List<String> assigned = names.isEmpty() ? List.of()
        : dictDataMapper.selectAssignedDictTypes(names);

    // 任一被占用 → 整体抛异常，不删任何一条
    for (SysDictType dt : types) {
        if (assigned.contains(dt.getDictType()))
            throw new ServiceException(dt.getDictName() + "已分配,不能删除");
    }

    // 1 次批量删除
    dictTypeMapper.deleteDictTypeByIds(dictIds);
    // 清缓存
    types.forEach(dt -> DictUtils.removeDictCache(dt.getDictType()));
}
```

**DB 往返 3N → 3。行为变更**：原代码可能部分删除，现在要么全成功要么全不动。我确认这是改进并明确接受了。

### importUser：只消灭确定的浪费

循环里唯一真正的冗余是每行都去 sys_config 表查一次 initPassword。提到循环外：

```java
String initPassword = configService.selectConfigByKey("sys.user.initPassword");
for (...) {
    user.setPassword(SecurityUtils.encryptPassword(initPassword));
    // 逐行校验、逐行插入/更新
}
```

为什么不把整个 import 改成 batch insert？因为逐行有不同分支：新/旧用户判断、逐行校验错误收集、成功/失败计数——硬塞成 batch SQL 会把可读性砸烂。

**原则：消灭确定的浪费，但不制造不确定的复杂度。**

---

## 05 Mapper 集成测试床——让批量 SQL 有自动回归

### 为什么要这层

批量 SQL 写完了、单元测试验证了"service 调了一次 mapper"的契约——但那条 CASE/IN 语句从没在真实 MySQL 上跑过。全靠手工连远端库、`BEGIN → UPDATE → SELECT → ROLLBACK` 验证。这不持续。

### 设计：共享库 + 事务回滚 + 标签隔离

三个核心约束：

1. **不污染共享数据**：`@Transactional` 测试方法结束后自动回滚。fixture 行（`create_by='itest'`）随事务消失。
2. **不拖垮 `mvn test`**：打 `@Tag("integration")`，根 pom surefire 默认 `excludedGroups=integration`——离线也全绿，CI 不依赖外部数据库。
3. **测试自建数据**：不信任 ry-vue 里的种子数据（别人可能正在改），每个用例自己 INSERT fixture、断言、回滚。

### 放哪？@SpringBootTest vs @MybatisTest

我选了 `@SpringBootTest(webEnvironment=MOCK)`，而不是 `@MybatisTest`。

原因很实在：RuoYi 的 MyBatis 用**非标准键**（`mybatis.mapperLocations` 而非标准 `mybatis.mapper-locations`），由自定义 `MyBatisConfig` 手工装配 SqlSessionFactory。`@MybatisTest` 切片只认标准 autoconfigure——它找不到 mapper XML，mapper bean 不会注册。

### 踩的坑：webEnvironment=NONE 启动失败

第一次跑，上下文炸了：

```
No bean named 'requestMappingHandlerMapping' available
```

根因：`PermitAllUrlProperties`（RuoYi framework 的 `@Component`）依赖 `requestMappingHandlerMapping` bean，而这个 bean 只在 Web ApplicationContext 里存在。NONE 不建 MVC → bean 缺失 → SecurityConfig 整条链崩。

修正：改成 `WebEnvironment.MOCK`（给 MVC 基础设施但不绑端口、不起 Tomcat）。

### surefire 配置

根 pom 不继承 `spring-boot-starter-parent`（只 import BOM），所以 surefire 版本得显式钉：

```xml
<properties>
    <maven-surefire.version>3.5.2</maven-surefire.version>
    <test.groups></test.groups>
    <test.excludedGroups>integration</test.excludedGroups>
</properties>

<build><plugins>
    <plugin>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>${maven-surefire.version}</version>
        <configuration>
            <groups>${test.groups}</groups>
            <excludedGroups>${test.excludedGroups}</excludedGroups>
        </configuration>
    </plugin>
</plugins></build>

<profiles>
    <profile>
        <id>integration</id>
        <properties>
            <test.groups>integration</test.groups>
            <test.excludedGroups></test.excludedGroups>
        </properties>
    </profile>
</profiles>
```

运行方式：

- 日常开发/CI：`mvn test` → 只跑单元（16 秒，离线安全）
- 验证 SQL：`mvn test -P integration` → 只跑集成（需远端 MySQL+Redis 在线）

### 测试基类

```java
@Tag("integration")
@ActiveProfiles("druid")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
public abstract class BaseMapperIntegrationTest {
    @Autowired
    protected DataSource dataSource;

    protected JdbcTemplate jdbc;

    @BeforeEach
    void setUpJdbcTemplate() {
        this.jdbc = new JdbcTemplate(dataSource);
    }
}
```

每个测试类继承它，用 `jdbc.update(...)` 插入 fixture、调用 mapper 的批量方法、`jdbc.queryForObject(...)` 回查断言——方法结束，Spring TestContext 自动 rollback。

### 验证结果

- 默认 `mvn test`：BUILD SUCCESS，`ruoyi-admin` 显示 `Tests run: 0`（集成被标签排除），离线安全网纹丝不动。
- `mvn test -P integration`：BUILD SUCCESS，3 个集成用例全绿（上下文 10s 启动）。
- 回滚后查库：四张表 `create_by='itest'` 计数全为 **0**。

---

## 06 远端环境 + 端到端验证

重构期间把测试数据源切到了远端 `192.168.25.50`（MySQL 8.0.45 + Redis 6 同机）。

流程：

1. 本地 MySQL 客户端连远端 → 建库 `ry-vue`（utf8mb4）→ 导入 `ry_20260417.sql` + `quartz.sql`（31 表，中文无乱码）
2. `mvn -DskipTests package` → `java -jar ruoyi-admin.jar` → 启动 12s（`Started RuoYiApplication`）
3. curl `/captchaImage` → 200
4. 临时关验证码 → `POST /login` 拿 HS512 JWT → Bearer token 调 `/getInfo` → code=200, user=admin, permissions=*:*:*

证明整条链可用：jjwt 签名验签、Redis session、Spring Security filter、MySQL 读写。

---

## 07 反思：AI 辅助开发的边界感

八天做下来，最大的体感：

**AI 是极强的"广度搜索器"和"模式匹配引擎"。** 一个 grep 就扫出 6 处 finally-close、4 处 N+1；一个查询就告诉你 jjwt 0.13.0 源码里 HS512 要求密钥 >=64B；给你写出 CASE WHEN XML 和 ArgumentCaptor 测试骨架——这些"体力活"比人快十倍。

**但以下判断 AI 给不了你（或者给了你也不能直接信）：**

- **"改到什么程度就停"**：importUser 只提了配置读取，没硬批量化。AI 能生成批量方案，但"批量方案会不会让可读性崩、收益是否值得"是人判断。
- **"行为变更接不接受"**：TWR close 异常不吞、deleteDictType 改为原子——AI 明确告诉你这改变了行为，但"改完是不是用户更喜欢的语义"得人拍板。
- **"先动哪里"**：33 个测试覆盖什么、哪些 N+1 先改——手术刀口的规划，AI 不替你定优先级。
- **"设计选型"**：共享 ry-vue + 回滚 vs Testcontainers vs 独立 schema——人做 trade-off。
- **"验收标准"**：回滚后查库确认 residue=0——"多走一步确认真的干净了"的执念是人教给 AI 的。

### 人机协作的正确姿势

1. **我定目标 + 约束**（不污染共享数据、不依赖网络、mvn test 必须离线绿）
2. AI 探索上下文 + 列方案
3. **我做判断 + 拍板**（选哪个方案、接受哪些行为变更）
4. AI 生成代码
5. **我验收 + 追问**（跑不过就停、行为变了就确认）
6. 循环

每一步 AI 加速，但每一步人负责。"AI 写的代码"不代表"不需要人判断的代码质量"。

---

## 数据一览

| 指标 | 改前 | 改后 |
|---|---|---|
| 自动化测试用例 | 0 | 36（33 单元 + 3 集成） |
| 默认 mvn test 耗时 | 无 | ~16s（离线安全） |
| N+1 热点 | 4 处 | 0 处 |
| 字典批量删除 DB 往返 | 3N | 3 |
| finally-close 遗留 | 6 站点 | 0 |
| JWT 库 | 自制 Base64 HMAC | jjwt 0.13.0 HS512 |
| 密钥管理 | 明文 26B 硬编码 | 环境变量占位 >=64B |
| Mapper SQL 回归 | 手工事务脚本 | 自动化集成测试床 |

---

## 最后

这不是一个"把 RuoYi 改成了 DDD 微服务"的故事。没加缓存、没拆服务、没换 ORM。

做的事情很朴素：**把屎山最臭的几个角落翻出来，用最小手术面、带安全网地改掉；然后建一套基础设施让以后改 SQL 不用每次都拿人命去赌。**

"改之前先能测；改之后验证语义没跑偏；行为变更要显式拍板；不过度工程"——这是这次重构里我最想分享的四句话。

如果你的学习项目也想做类似的事，建议是：**别急着加功能。先打开一个 service 类，搜 `for.*Mapper\.`，看看你中了几枪。**
