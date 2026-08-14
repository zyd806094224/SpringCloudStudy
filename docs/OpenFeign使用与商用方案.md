# OpenFeign 使用与商用方案

> 本文回答三个问题：OpenFeign 怎么集成（结合本项目现有代码）、为什么这么设计、以及公司里实际怎么商用（含 feign 包、私服、版本治理、真实场景）。

## 一、OpenFeign 解决什么问题

### 1.1 微服务间调用的演进

```
第一阶段：HttpClient/RestTemplate 手拼
    restTemplate.getForObject("http://10.2.3.4:9000/product/" + id, Product.class)
    问题：IP 写死、URL 拼字符串、参数靠手工拼、返回值手工反序列化、没有统一超时/日志

第二阶段：RestTemplate + @LoadBalanced
    restTemplate.getForObject("http://service-product/product/" + id, Product.class)
    好处：服务名替代 IP，带负载均衡
    问题：依然是"拼 URL"思维，接口长什么样全靠看文档

第三阶段：OpenFeign 声明式调用（本项目建设）
    @Autowired
    private ProductFeignClient productFeignClient;
    Product product = productFeignClient.getProductById(1L);   // 像调本地方法
```

### 1.2 核心思想

**把"调一个 HTTP 接口"变成"实现一个 Java 接口"**：使用方只声明接口（方法签名 + MVC 注解），OpenFeign 在运行时用动态代理生成实现——拼 URL、序列化、发请求、反序列化、返回结果全部自动完成。

**接口定义即文档、即契约**。这一句话是理解后面"feign 包"模式的钥匙。

## 二、本项目如何集成（现状代码讲解）

项目里 `service-order`（订单，端口 8000）通过 Feign 调用 `service-product`（商品，端口 9000），完整链路如下：

```
OrderServiceImpl.createOrder()
        │ @Autowired 注入
        ▼
ProductFeignClient（接口，动态代理）─────────────┐
        │ ① 按方法上的注解生成请求               │
        │    GET http://service-product/product/1│
        ▼                                       │
Nacos 服务发现：service-product → [192.168.x.x:9000] 实例列表
        ▼
LoadBalancer：从实例列表选一个（轮询/随机）
        ▼
Sentinel 熔断降级检查 ──调用失败/超时──► ProductFeignClientFallback（返回兜底数据）
        ▼
service-product 的 ProductController.getProduct()（Redisson 分布式锁 + 扣库存）
        ▼
返回 Product JSON → Feign 自动反序列化成 Product 对象
```

### 2.1 集成四要素

**① 依赖与开启**（`services/pom.xml` 已引入 starter，服务共同继承）：

```java
// ServiceOrderApplication.java
@EnableFeignClients      // 开启 Feign 客户端扫描，为标注 @FeignClient 的接口生成代理 bean
@EnableDiscoveryClient   // 开启服务发现
@SpringBootApplication
public class ServiceOrderApplication { ... }
```

**② 声明客户端**（`services/service-order/.../feign/ProductFeignClient.java`）：

```java
@FeignClient(value = "service-product",                    // 目标服务名（Nacos 注册名）
             fallback = ProductFeignClientFallback.class)  // 熔断降级类
public interface ProductFeignClient {
    @GetMapping("/product/{id}")                           // 契约：路径、参数、返回值
    Product getProductById(@PathVariable("id") Long id);
}
```

**③ 降级兜底**（`feign/fallback/ProductFeignClientFallback.java`）：

```java
@Slf4j
@Component
public class ProductFeignClientFallback implements ProductFeignClient {
    @Override
    public Product getProductById(Long id) {
        log.info("Fallback...");
        Product product = new Product();
        product.setId(id);
        product.setProductName("未知商品");   // 兜底数据：别让下游故障拖垮我的下单主链路
        product.setPrice(new BigDecimal("0"));
        product.setNum(0);
        return product;
    }
}
```

**④ 配置**（`application-feign.yaml`，通过 `spring.profiles.include: feign` 激活）：

```yaml
spring:
  cloud:
    openfeign:
      client:
        config:
          default:                    # 全局默认：连 1s / 读 2s
            logger-level: full
            connect-timeout: 1000
            read-timeout: 2000
          service-product:            # 按服务名覆盖：商品接口较慢（有 2s 模拟慢调用+锁），放宽到 60s
            connect-timeout: 3000
            read-timeout: 60000

feign:
  sentinel:
    enabled: true                     # 开启熔断，fallback 才生效

logging:
  level:
    com.example.serviceorder.feign.ProductFeignClient: DEBUG   # Feign 日志要 DEBUG 级别才输出
```

## 三、为什么这么设计（每一层的原因）

| 设计 | 不这么做的后果 | 原因 |
|---|---|---|
| **声明式接口** | 拼字符串 URL，改个参数要全文搜索 | 编译期类型检查，IDE 直接跳转"接口定义即文档" |
| **服务名 + Nacos** | IP 写死，扩容/迁移/容器化全废 | 实例上下线自动感知，调用方零修改 |
| **LoadBalancer** | 只会打到一个实例，它挂了全挂 | 客户端负载均衡，多实例分摊流量 |
| **显式超时配置** | 用默认值（读超时 60s），慢接口拖死线程池 | 下游卡住时快速失败，把线程还回去 |
| **Sentinel fallback** | 商品服务一抖，订单服务线程堆积，故障沿着调用链向上传导（雪崩） | 失败切兜底逻辑，把故障隔离在下游 |
| **按服务区分超时** | 一个慢接口逼着全局放大超时，其他接口失去保护 | 每个下游特性不同，粒度到服务级 |

**一句话：Feign 的价值 = 声明式（好写）+ 服务发现（好找）+ 负载均衡（好扩）+ 超时熔断（好死，死得快才活得久）。**

## 四、Feign 包（API 模块）模式——契约共享

### 4.1 手抄接口的问题（本项目现状即对照案例）

`service-order` 的 `ProductFeignClient` 是**使用方手抄**的契约。单个调用方没问题，但调用方一多：

```
service-product 改了 /product/{id} 的返回结构
        ↓
N 个使用方手抄的接口全部毫不知情 —— 编译全绿
        ↓
运行时反序列化丢字段 / 404，逐个排查、逐个修复、逐个背锅
```

### 4.2 打 feign 包是什么

**服务方把 Feign 接口 + DTO 抽成独立 Maven 模块（如 `service-product-api`），构建成 jar 发布；使用方引依赖即可获得整套契约，无需手写接口定义。**

先分清两种完全不同的 jar（本机实测对比）：

| 对比项 | 可执行服务包（fat jar） | Feign API 契约包 |
|---|---|---|
| 用途 | 独立部署运行 | 给其他服务引入调用 |
| 内容 | Controller/Service 实现 + 全部依赖 | **只有 interface 声明 + DTO，零实现** |
| 大小 | 本项目 service-product 实测 **139MB** | 典型 **几 KB~几十 KB**（本项目实验值 3.1KB） |
| 打包方式 | spring-boot repackage | 普通类库 jar（须跳过 repackage） |

```
product-api-1.0.0.jar (3.1KB)
├── com/example/productapi/ProductApi.class         ← 契约：@GetMapping("/product/{id}")
├── com/example/productapi/ProductApiClient.class   ← @FeignClient("service-product")
└── (DTO：Product，或依赖公共 model 包)
```

### 4.3 服务方怎么打

```java
// 契约接口：只声明"接口长什么样"，无任何实现
public interface ProductApi {
    @GetMapping("/product/{id}")
    Product getProductById(@PathVariable("id") Long id);
}

// 开箱即用的客户端：随包一起发布；故意不声明 fallback（降级因使用方而异，留给他们定义）
@FeignClient(contextId = "productApiClient", value = "service-product")
public interface ProductApiClient extends ProductApi {
}
```

api 模块 pom 的两个关键点：

```xml
<!-- 注解只借 openfeign 用，optional 不把 feign 版本强加给使用方 -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
    <optional>true</optional>
</dependency>

<build>
    <plugins>
        <!-- 类库 jar 无主类，必须跳过 spring-boot repackage，否则 mvn package 报
             Unable to find main class（本项目根 pom 的该插件会被所有子模块继承） -->
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
            <configuration><skip>true</skip></configuration>
        </plugin>
    </plugins>
</build>
```

更进一步的规范做法：服务方 Controller 直接 `implements ProductApi`，实现类不再写 `@GetMapping`——**提供方实现和 Feign 契约在代码层面绑定，改契约必须先改接口，一眼可见**。

### 4.4 使用方两种接入方式

**方式一：直接用包内客户端（零代码接入）**

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>product-api</artifactId>
    <version>1.0.0</version>
</dependency>
```

```java
// 注意：@EnableFeignClients 默认只扫描本应用所在包及子包，
// jar 里的 com.example.productapi 扫不到，必须显式指定，否则 NoSuchBeanDefinitionException
@EnableFeignClients(clients = ProductApiClient.class)
@SpringBootApplication
public class ConsumerApplication { ... }

@Autowired
private ProductApiClient productApiClient;   // 注入即用，一个注解都不用写
```

**方式二：本地继承契约 + 自定义降级（需要熔断/拦截器/定制超时时）**

```java
// 使用方本地：契约（路径/参数/DTO）仍来自 api 包，只追加自己的策略
@FeignClient(contextId = "productClient", value = "service-product",
             fallback = ProductFeignClientFallback.class)   // 配合 feign.sentinel.enabled=true
public interface ProductFeignClient extends ProductApi {
}
```

本项目 `service-order` 的 Sentinel fallback 用法即对应此方式。注意：**即使写了本地客户端，也只写"一个空接口+注解"，方法签名、路径、DTO 全部来自 jar**——契约维护权依然在服务方。

| | 方式一：包内客户端 | 方式二：本地 extends |
|---|---|---|
| 代码量 | 零 | 一个空接口 + fallback 类 |
| 降级/拦截器 | yaml 配置 | 注解自由指定 |
| 适用 | 快速接入、无特殊要求 | 需要熔断降级、定制化 |

### 4.5 本质与收益

**把"接口长什么样"的维护权，从 N 个使用方手里收回到 1 个服务方手里，用 Maven 版本机制代替人肉同步。**

- 契约变更 → 服务方升 api 包版本 → 使用方升版本 → **不兼容处编译期立刻报错**（对比手抄方式的运行时才炸）
- DTO 是同一个 class 文件，天然一致，不存在两家反序列化结果不同
- 版本可追溯：谁在用哪个版本的契约，pom 里一目了然

## 五、公司实际商用流程与场景详解

### 5.1 发布链路：私服是标准做法

服务方和使用方在公司里是**不同代码仓库、不同团队**，需要私服（Nexus / Artifactory，或 GitLab/云效内置制品库）中转：

```
商品团队（服务方）                          订单团队（使用方）
1. 接口定义放 xxx-api 独立模块              4. pom 引依赖 / 升版本号
2. 契约变更 → 升版本 1.0.0 → 1.1.0         5. 不兼容变更编译期报错，找服务方对齐
3. mvn deploy（通常由 CI 流水线执行）
        └──────────► 公司私服 Nexus ◄──────────┘
                     company-releases   ← 正式版（不可变）
                     company-snapshots  ← 联调版（可更新）
```

服务方只需三处配置：

```xml
<!-- ① api 模块 pom：发布地址 -->
<distributionManagement>
    <repository>
        <id>company-releases</id>
        <url>https://nexus.公司域名.com/repository/company-releases/</url>
    </repository>
    <snapshotRepository>
        <id>company-snapshots</id>
        <url>https://nexus.公司域名.com/repository/company-snapshots/</url>
    </snapshotRepository>
</distributionManagement>
```

```xml
<!-- ② settings.xml：账号（id 必须与 pom 中一致） -->
<server>
    <id>company-releases</id>
    <username>zhaoyudong</username>
    <password>***</password>
</server>
```

```bash
# ③ 发布：版本带 -SNAPSHOT 进 snapshots 仓，否则进 releases 仓
mvn clean deploy
```

使用方**无需任何额外配置**——公司 Maven 本来就指向私服，加个坐标就能拉。

### 5.2 版本管理的真实节奏

- **联调期**：服务方发 `1.2.0-SNAPSHOT`，使用方引 SNAPSHOT，每次构建自动拉最新，双方不用频繁打招呼；缺点是"今天还是好的，怎么突然坏了"——所以正式上线前必须切 RELEASE
- **正式版**：合并到 release 分支，CI 自动 deploy；多数公司**禁止本地直发 RELEASE**（必须走流水线留痕、可审计、可回滚）
- **兼容原则**（契约演进纪律）：
  - ✅ 加字段（旧使用方自动忽略新字段）
  - ❌ 删字段 / 改语义 / 改路径 —— 必须升大版本，提前 N 个版本用 `@Deprecated` 标记并在公告里给迁移期限
  - 大厂普遍执行"废弃接口保底 3~6 个月"的缓冲期，到期下线

### 5.3 典型商用场景

**场景 1：电商中台的多使用方共享（api 包最大的价值区）**

商品中心的 `product-api` 被订单、购物车、营销、搜索、比价、App BFF 等 8 个团队引用。商品中心重构库存接口：改 `xxx-api` → 发 2.0.0 → 邮件/IM 通知 → 各团队在自己分支升版本，编译器逐个指出不兼容点。**没有 8 份手抄副本，没有 8 次线上事故。**

**场景 2：BFF 聚合层**

App 端"商品详情页"接口 = 商品信息 + 库存 + 优惠券 + 评价。BFF 服务引 4 个服务的 api 包，每个注入一个 client 并行调用、聚合裁剪后返回。api 包让 BFF 团队无需理解 4 个领域的接口细节——**引包即拿到全部契约**。

**场景 3：跨部门/对外开放调用**

外部合作方要走公司的 OpenAPI 网关鉴权限流，内部跨部门则直接引 api 包 + 网络隔离；有些公司 api 包按业务方发不同版本（白金客户用 2.x 新能力，普通客户 1.x），**同一个服务同时服务多代契约**。

**场景 4：故障隔离与降级（fallback 的真实用法）**

大促前压测发现营销服务过载：营销服务方通知各使用方启用 fallback（优惠券接口降级返回"暂无优惠"而非报错），下单主链路不中断。**降级策略由各使用方按自己的业务定**——这正是 api 包不内置 fallback、把降级留给使用方的原因：同样是查库存失败，下单场景要阻断、购物车场景显示"库存未知"即可。

**场景 5：契约治理平台（大厂进阶）**

- 接口注册：新接口必须在公司接口平台注册才能发布 api 包（防止野接口）
- 契约测试：CI 里跑 Pact/契约测试，服务方改实现忘了改契约、或实现和契约不一致时，流水线直接红
- 自动生成：Swagger/OpenAPI 生成 api 包，或反过来由 api 包生成文档，保证文档永不过期
- 调用链审计：基于 api 包版本 + 链路追踪（SkyWalking 等）统计每个契约版本的真实调用量，作为下线旧版本的依据

**场景 6：什么时候不该用 Feign（同步调用的边界）**

本项目即典型案例：订单创建后的"扣库存"，最终用 RocketMQ 事务消息（createOrderV2/V3）而非 Feign 同步调用——因为下单高峰时商品服务一旦抖动，同步调用会拖着订单服务一起挂，且失败重试容易超卖。**选型原则：强一致、需要实时结果的用 Feign 同步；可异步解耦、要削峰的用 MQ**（详见 [V3 事务消息端到端链路](V3事务消息端到端链路.md)）。

### 5.4 治理手段小结

| 手段 | 解决的问题 |
|---|---|
| api 包 + 私服 + 版本管理 | 契约同步靠人肉 → 靠依赖管理 |
| CI 流水线发包 | 发布留痕、禁止本地直发正式版 |
| 契约测试 | 实现与契约不一致在流水线暴露 |
| Deprecated + 缓冲期公告 | 使用方有迁移窗口，不怕突然下线 |
| Sentinel 熔断 + fallback | 下游故障不向上传导（雪崩隔离） |
| 链路追踪 + 版本调用量统计 | 旧版本该不该下线，数据说话 |

## 六、踩坑与最佳实践

1. **Feign 日志不输出**：`Logger.Level` 配了 full 也没日志？接口的日志级别必须是 `DEBUG`（见 `application-feign.yaml`），且生产环境别开 full（会打完整报文）。
2. **默认超时是坑**：不显式配置时读超时较长（分钟级），下游卡住会拖死调用方线程池；超时要按服务分级配置，慢接口单独放宽。
3. **重试与幂等**：OpenFeign 默认 `Retryer.NEVER_RETRY`。手工开重试时注意——对方接口非幂等（如本项目的扣库存接口）重试可能造成重复扣减，跨服务重试务必先确认幂等性或改用 MQ。
4. **GET 请求别传复杂对象**：`@GetMapping` + POJO 参数会被 Feign 当成请求体处理（GET 带 body），部分服务端直接报错；查询条件要么拆成多个 `@RequestParam`，要么改 POST。
5. **`@EnableFeignClients` 扫描范围**：默认只扫本应用包及子包，jar 包里的客户端要用 `clients = XxxClient.class` 显式注册（见 4.4）。
6. **同名服务多客户端要指定 `contextId`**：否则生成的 bean 名冲突；超时等配置也按 contextId 区分。
7. **库模块打包**：api/model 这类无主类模块必须给 spring-boot-maven-plugin 配 `<skip>true</skip>`，否则 `mvn package` 报 `Unable to find main class`（本工程根 pom 的插件对所有子模块生效，`model` 模块当前即有此隐患，日常用 IDE 启动不受影响，跑 `mvn package` 时会遇到）。
8. **契约里别放实现细节**：api 包的 DTO 只放对外字段，别把内部实体（如带库存、成本价的 Entity）直接暴露——既有安全风险，也让内部重构被迫牵动所有使用方。

## 七、选型总结

| 场景 | 建议 |
|---|---|
| 服务间同步调用 | OpenFeign + Nacos + LoadBalancer（本项目现状） |
| 高危依赖（下游不可用不能影响我） | Feign + Sentinel 熔断 + fallback 兜底 |
| 多调用方 / 跨团队接口 | **api 包模式**：服务方打包容约发私服，使用方引依赖 |
| 联调阶段 | api 包发 SNAPSHOT，随时同步契约 |
| 正式交付 | RELEASE + 语义化版本 + 兼容演进纪律 |
| 异步解耦 / 削峰 / 最终一致 | 不用 Feign，走 MQ（本项目 V2/V3 方案） |
| 对外开放平台 | OpenAPI 网关鉴权限流，或按客户发多版本 api 包 |

**核心收益一句话：声明式让调用好写，服务发现让扩容无感，熔断降级让故障隔离，api 包让契约同步从"人肉+运行时"变成"依赖版本+编译期"。**
