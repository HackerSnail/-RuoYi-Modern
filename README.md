# RuoYi-Modern

<p align="center">
	<strong>基于若依重构的现代化后端脚手架，架构更清晰、易维护。</strong><br>
	<em>Modern backend scaffold refactored from RuoYi with a cleaner, more maintainable architecture.</em>
</p>

> 本项目基于 [若依 RuoYi](https://gitee.com/y_project/RuoYi-Vue) 重构，感谢原作者的开源贡献。
> 与上游的差异集中在 **安全加固** 与 **技术债清理** 两条主线，并新增 **GoFrame 版 Go 后端** 验证轻量化方案——不是简单的 fork 搬运。

## 与原版若依的区别

- **前端**：沿用若依 Vue3 版，仅做接口适配以兼容重构后的后端 API。
- **后端**：单仓三工程并列（`ruoyi-springboot` / `ruoyi-goframe` / `ruoyi-vue3`），是本仓库架构重构的核心。
- **安全加固**：真实 HTTP 状态码收口、XSS 净化、账号昵称等敏感字符拦截、会话令牌脱敏、执行点越权白名单、SSRF 多层防护等系统性加固。
- **技术债清理**：日期统一 `java.time`、字段注入改构造器注入、废弃 static 全局可变配置、分页 `ThreadLocal` 泄漏兜底、异常精确分档等。
- **Go 版本采用 GoFrame 重写**：对 Java 后端逐模块忠实移植，作为轻量化方案验证（详见 `ruoyi-goframe/`）。

## 本仓库特性：安全加固 & 技术债清理

本仓库在上游 RuoYi 基础上累计 **230+ 次提交**，围绕**安全加固**与**技术债清理**做了系统性重构。

### 🔒 安全加固

- **真实 HTTP 状态码**：全站统一——认证失败 401 / 权限不足 403 / 业务失败 400，前端按状态码收口错误文案，避免"HTTP 200 里塞错误"导致前端各接口各自判断、反复踩坑。
- **会话与令牌**：在线用户 `tokenId` 改为服务端 HMAC 单向派生的脱敏标识（即便被截取也无法仿造出可通过验签的令牌，比单纯再加密一层更强）；改密强制下线全部会话，权限变更即时失效在线缓存。
- **防注入 / RCE**：Quartz 任务执行白名单（边界感知）、`@DataScope` SQL 标识符白名单、代码生成建表白名单——直接封死执行点越权。
- **防 XSS**：公告富文本经 jsoup 白名单净化闭合存储型 XSS；`XssFilter` 纳入 GET 参数清洗，**弃用有结构性缺陷的正则式 `HTMLFilter`**（正则过滤对嵌套 / 编码绕过本质不可靠，属"假防 XSS"，很多项目至今仍在此坑里）。
- **防 SSRF**：`HttpUtils` 迁移 HttpClient5，host / 协议 / 端口白名单 + IPv4-mapped / Teredo / 6to4 内嵌 IPv4 还原（防止用变形地址绕过黑名单）；文件上传做魔数校验 + 路径穿越护栏。
- **缓存与密钥**：移除 fastjson2 `autoType` 反序列化（收敛任意类实例化风险）；Redis `KEYS` 全量改 `SCAN` + 分批 `UNLINK`（避免大 key 阻塞主线程）；Token / 签名密钥 final 化，日志字段 PII 值级掩码脱敏。

### 🧹 技术债清理（重构前后对比 · 俗称"拆屎山"）

- **依赖瘦身**：移除 yauaa、commons-lang3（异常 / 日期）、手写 Base64、springfox 死映射——收窄传递依赖与安全面；日期全面 `Date` → `java.time` 并固定东八区，根除时区漂移（不少线上事故正源于此）。
- **注入与惯用法**：`@Autowired` 字段注入全量改为构造器注入——可测试性与依赖清晰度直接上一台阶；`instanceof` 模式匹配、统一 K&R 大括号与 `final` 工具类。
- **死代码 / 全局态**：清理演示 Bean 与无用方法，废弃的 `static` 全局可变配置改为实例 Bean——`static` 可变态是并发与单测的噩梦；缓存 demo 统一门控到 `demo.cache.enabled`。
- **不可变与并发**：关键 DTO 改不可变并补值语义 `equals/hashCode`；`AsyncManager` 改 Spring Bean、优雅关停合并进 `@PreDestroy`；分页 `ThreadLocal` 泄漏兜底清理（踩过连接池复用脏分页的坑才知道必须做）。
- **健壮收口**：唯一性 / 存在性回查 / 批量原子性下沉 Service；`GlobalExceptionHandler` 精确分档，业务异常不再伪装成系统故障，对排查效率提升很大。

## 仓库结构（三工程并列）

本仓库为**单 git 仓、三工程并列**的 monorepo，前端与两套后端同仓协作：

| 目录 | 说明 | 技术栈 |
| :--- | :--- | :--- |
| `ruoyi-springboot/` | Java 后端（上游 RuoYi，安全加固版） | Spring Boot + Spring Security + MyBatis + Redis + JWT |
| `ruoyi-goframe/` | Go 后端（对 `ruoyi-springboot` 的逐模块移植，同库同契约） | GoFrame v2 + MySQL + Redis |
| `ruoyi-vue3/` | 前端 | Vue 3 + Element Plus + Vite + Pinia |

仓库根另保留**跨工程共享**内容：`sql/`（建表 + 补丁脚本，两套后端共用同一 schema）、`testdata/`（代码生成器逐字节金标准语料，Java oracle 与 Go 测试共用）、`docs/`（审计/设计/计划文档）、`README.md`、`LICENSE`、`.github/`。

两套后端**读同一个 MySQL（`ry-vue`）+ 同一个 Redis**，返回体 `{code,msg,data}` 与真实 HTTP 状态码契约完全一致，前端 `ruoyi-vue3` 不改动即可分别对接。

## 使用方式

> 前置：JDK 17+、Maven 3.6+、Node 16+、Go（版本见 `ruoyi-goframe/go.mod`），以及可连通的 MySQL（含 `ry-vue` 库）与 Redis；建库脚本见仓库根 `sql/`。

### Java 后端 `ruoyi-springboot/`

```bash
# 构建（在仓库根执行）
mvn -f ruoyi-springboot/pom.xml clean package -DskipTests
# 运行（先改 ruoyi-springboot/ruoyi-admin/src/main/resources/application*.yml 的库/Redis 连接）
ruoyi-springboot\ry.bat          # 或 java -jar ruoyi-springboot/ruoyi-admin/target/ruoyi-admin.jar
```

### Go 后端 `ruoyi-goframe/`

```bash
cd ruoyi-goframe
go build ./... && go test ./internal/...   # 自检（含与 Java oracle 的逐字节金标准比对）
go run .                                    # 或 gf run main.go（需 GoFrame CLI）
```

配置见 `ruoyi-goframe/config.yaml`：监听 `:8080`、`context-path=/`，含库/Redis/Token 密钥等；生产可用环境变量 `MYSQL_LINK` 覆盖数据源、`SERVER_ADDRESS` 覆盖监听地址（便于与 Java 并存调试）。

### 前端 `ruoyi-vue3/`

```bash
cd ruoyi-vue3
npm install
npm run dev          # Vite 开发服务器；/dev-api 代理到后端 :8080（Java 或 Go 皆可）
```

## 关于上游（若依 RuoYi）

本项目重构自 **若依 RuoYi v3.9.2**——一套全部开源、可免费用于个人及企业的快速开发平台。再次感谢原作者的开源贡献，以下为上游原始介绍与资料。

<p align="center">
	<img alt="logo" src="https://oscimg.oschina.net/oscnet/up-d3d0a9303e11d522a06cd263f3079027715.png">
</p>
<p align="center">
	<a href="https://gitee.com/y_project/RuoYi-Vue/stargazers"><img src="https://gitee.com/y_project/RuoYi-Vue/badge/star.svg?theme=dark"></a>
	<a href="https://gitee.com/y_project/RuoYi-Vue"><img src="https://img.shields.io/badge/RuoYi-v3.9.2-brightgreen.svg"></a>
	<a href="https://gitee.com/y_project/RuoYi-Vue/blob/master/LICENSE"><img src="https://img.shields.io/github/license/mashape/apistatus.svg"></a>
</p>

### 平台简介

* 前端采用 Vue、Element（提供 Vue2/Vue3/TS 多版本）。
* 后端采用 Spring Boot、Spring Security、Redis & Jwt。
* 基于 Jwt 的 Token 认证，支持多终端（Web、App、小程序）无缝接入。
* 支持加载动态权限菜单，多方式轻松权限控制，细粒度到按钮级别。
* 内置强大代码生成器，一键生成前后端代码（java、vue、xml、sql），支持 CRUD 下载。
* 阿里云折扣场：[点我进入](http://aly.ruoyi.vip)，腾讯云秒杀场：[点我进入](http://txy.ruoyi.vip)&nbsp;&nbsp;

### 版本分支

RuoYi-Vue 后端项目提供 Spring Boot 2.x / 3.x / 4.x 多版本分支的并行维护

| 名称              | 说明                      | 地址                                                    |
| :---------------- | :------------------------ | :------------------------------------------------------ |
| master 默认分支   | Spring Boot 4.x (JDK 17+) | https://gitee.com/y_project/RuoYi-Vue                   |
| springboot3 分支  | Spring Boot 3.x (JDK 17+) | https://gitee.com/y_project/RuoYi-Vue/tree/springboot3  |
| springboot2 分支  | Spring Boot 2.x (JDK 8+)  | https://gitee.com/y_project/RuoYi-Vue/tree/springboot2  |  

RuoYi-Vue 前端项目提供 Vue 2.x / 3.x / JavaScript / TypeScript 版本，均可混用搭配

| 项目名称      | **RuoYi-Vue2** | **RuoYi-Vue3** | **RuoYi-Vue3-TypeScript**   |
| :---          | :---           | :---           | :---                        |
| **前端框架**  | Vue 2         | Vue 3          | Vue 3                       |
| **脚本语言**  | JavaScript    | JavaScript     | TypeScript                  |
| **构建工具**  | Vue CLI       | Vite           | Vite                        |
| **UI 组件库** | Element UI    | Element Plus   | Element Plus                |
| **状态管理**  | Vuex          | Pinia          | Pinia                       |
| **路由管理**  | Vue Router 3  | Vue Router 4   | Vue Router 4                |
| **核心特点**  | 1. 技术栈经典稳定<br>2. 社区资料丰富<br>3. 当前维护重心已转移 | 1. 现代前端技术栈<br>2. 开发体验与性能更优<br>3. 官方主推的活跃版本 | 1. 类型加持，减少沟通成本<br>2. 开发时有提示，效率更高<br>3. 多人协作企业级开发项目 |
| **仓库地址**  | [RuoYi-Vue2](https://gitcode.com/yangzongzhuan/RuoYi-Vue2) | [RuoYi-Vue3](https://gitcode.com/yangzongzhuan/RuoYi-Vue3) | [RuoYi-Vue3-TypeScript](https://gitcode.com/yangzongzhuan/RuoYi-Vue3/tree/typescript) |

### 内置功能

1.  用户管理：用户是系统操作者，该功能主要完成系统用户配置。
2.  部门管理：配置系统组织机构（公司、部门、小组），树结构展现支持数据权限。
3.  岗位管理：配置系统用户所属担任职务。
4.  菜单管理：配置系统菜单，操作权限，按钮权限标识等。
5.  角色管理：角色菜单权限分配、设置角色按机构进行数据范围权限划分。
6.  字典管理：对系统中经常使用的一些较为固定的数据进行维护。
7.  参数管理：对系统动态配置常用参数。
8.  通知公告：系统通知公告信息发布维护。
9.  操作日志：系统正常操作日志记录和查询；系统异常信息日志记录和查询。
10. 登录日志：系统登录日志记录查询包含登录异常。
11. 在线用户：当前系统中活跃用户状态监控。
12. 定时任务：在线（添加、修改、删除)任务调度包含执行结果日志。
13. 代码生成：前后端代码的生成（java、html、xml、sql）支持CRUD下载 。
14. 系统接口：根据业务代码自动生成相关的api接口文档。
15. 服务监控：监视当前系统CPU、内存、磁盘、堆栈等相关信息。
16. 缓存监控：对系统的缓存信息查询，命令统计等。
17. 在线构建器：拖动表单元素生成相应的HTML代码。
18. 连接池监视：监视当前系统数据库连接池状态，可进行分析SQL找出系统性能瓶颈。

### 在线体验

- admin/admin123  
- 陆陆续续收到一些打赏，为了更好的体验已用于演示服务器升级。谢谢各位小伙伴。

演示地址：http://vue.ruoyi.vip  
文档地址：http://doc.ruoyi.vip

### 演示图

<table>
    <tr>
        <td><img src="https://oscimg.oschina.net/oscnet/cd1f90be5f2684f4560c9519c0f2a232ee8.jpg"/></td>
        <td><img src="https://oscimg.oschina.net/oscnet/1cbcf0e6f257c7d3a063c0e3f2ff989e4b3.jpg"/></td>
    </tr>
    <tr>
        <td><img src="https://oscimg.oschina.net/oscnet/up-8074972883b5ba0622e13246738ebba237a.png"/></td>
        <td><img src="https://oscimg.oschina.net/oscnet/up-9f88719cdfca9af2e58b352a20e23d43b12.png"/></td>
    </tr>
    <tr>
        <td><img src="https://oscimg.oschina.net/oscnet/up-39bf2584ec3a529b0d5a3b70d15c9b37646.png"/></td>
        <td><img src="https://oscimg.oschina.net/oscnet/up-936ec82d1f4872e1bc980927654b6007307.png"/></td>
    </tr>
	<tr>
        <td><img src="https://oscimg.oschina.net/oscnet/up-b2d62ceb95d2dd9b3fbe157bb70d26001e9.png"/></td>
        <td><img src="https://oscimg.oschina.net/oscnet/up-d67451d308b7a79ad6819723396f7c3d77a.png"/></td>
    </tr>	 
    <tr>
        <td><img src="https://oscimg.oschina.net/oscnet/5e8c387724954459291aafd5eb52b456f53.jpg"/></td>
        <td><img src="https://oscimg.oschina.net/oscnet/644e78da53c2e92a95dfda4f76e6d117c4b.jpg"/></td>
    </tr>
	<tr>
        <td><img src="https://oscimg.oschina.net/oscnet/up-8370a0d02977eebf6dbf854c8450293c937.png"/></td>
        <td><img src="https://oscimg.oschina.net/oscnet/up-49003ed83f60f633e7153609a53a2b644f7.png"/></td>
    </tr>
	<tr>
        <td><img src="https://oscimg.oschina.net/oscnet/up-d4fe726319ece268d4746602c39cffc0621.png"/></td>
        <td><img src="https://oscimg.oschina.net/oscnet/up-c195234bbcd30be6927f037a6755e6ab69c.png"/></td>
    </tr>
    <tr>
        <td><img src="https://oscimg.oschina.net/oscnet/b6115bc8c31de52951982e509930b20684a.jpg"/></td>
        <td><img src="https://oscimg.oschina.net/oscnet/up-5e4daac0bb59612c5038448acbcef235e3a.png"/></td>
    </tr>
</table>
