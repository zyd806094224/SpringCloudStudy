# Sentinel 网关限流使用与商用方案

> 本文档沉淀本项目 Spring Cloud Gateway + 业务服务双层接入 Sentinel 的完整实践，
> 包括限流方案选型对比、二级限流架构、Nacos 规则持久化、踩坑记录与生产最佳实践。
> 所有代码示例均可在 `gateway` 与 `services/service-order` 模块中找到对应实现。

---

## 一、限流方案选型与对比

开始接入 Sentinel 之前，先回答一个关键问题：**你的场景真的需要 Sentinel 吗？**
限流方案不是越强越好，而是越合适越好。下面这张表是国内主流的四种做法。

### 1.1 四种主流方案对比

| 维度 | Nginx `limit_req` | Gateway + Redis 令牌桶 | Sentinel | 云厂商（APISIX/SLB/ALB） |
|------|---------------------|------------------------|----------|--------------------------|
| 上手成本 | 极低（几行配置） | 低（starter 自带） | 中（需理解资源模型 + Dashboard） | 低（付费托管） |
| 动态规则 | ❌ 改配置需 reload | ❌ 改配置需重启 | ✅ Dashboard 实时下发 | ✅ 控制台实时 |
| 可视化监控 | ❌ | ❌ | ✅ Dashboard 自带 | ✅ 平台自带 |
| 接口级/参数级限流 | ❌ 只能按 location | ❌ 只能按路由 | ✅ 热点参数/关联/链路 | ✅ |
| 熔断降级 | ❌ | ❌ | ✅ 一体化 | ✅ |
| 集群流控 | ❌ | ✅（Redis 天然集群） | ✅（需 Token Server） | ✅ |
| 规则持久化 | 配置文件 | 配置文件 | Nacos 数据源 | 平台托管 |
| 额外依赖 | 无 | Redis | Dashboard + Nacos | 无（云内） |
| 适合规模 | 全部 | 中小 | 中大 | 大 |

### 1.2 按公司规模的推荐分层

**中小公司（日 PV < 百万，服务 < 10 个）**
- 粗粒度限流（防 CC、保护服务整体）：**Nginx `limit_req`**，一行配置搞定，运维都会，没必要上 Java 组件。
- 如果已经用了 Spring Cloud Gateway：网关自带的 **`RequestRateLimiter` + Redis 令牌桶** 就够，配置简单、集群一致。
- 接口级精细限流：业务里用 `@SentinelResource`，**单机内存模式即可**（不接 Nacos，重启丢规则也没关系，Dashboard 上重新推 30 秒）。

**中型公司（服务 10~50 个，有限流/熔断诉求）**
- Nginx 粗粒度 + Sentinel 细粒度，**Sentinel 单机内存或 Nacos 持久化**看运维能力。

**中大公司（服务 50+，多团队、多租户）**
- 全套 Sentinel：Dashboard + Nacos 持久化 + 集群流控 + 熔断降级，值得投入。

### 1.3 什么时候用 Sentinel 才划算

Sentinel 真正的价值**不是限流**（限流谁都做得差不多），而是这三样：

1. **熔断降级**——下游服务挂了，快速失败防止雪崩
2. **热点参数限流**——按 `userId` / `productId` 维度限流，Nginx 和网关做不到
3. **集群流控**——多机统一配额，而非每机各限各的

如果业务只需要"接口 QPS 别打爆"这一个需求，**Nginx + 网关 `RequestRateLimiter` 就够了**，没必要上 Sentinel 全家桶。
本项目接入 Sentinel 全家桶是出于**学习目的**，踩完这些坑就真懂 Spring Cloud Alibaba 了。

> **一句话结论**：粗粒度用 Nginx/网关，需要细粒度限流 + 熔断降级时才上 Sentinel。

---

## 二、本项目限流架构

### 2.1 二级限流设计

项目采用**网关层 + 服务层**的二级限流，两层职责分离、独立生效：

```
                         客户端请求 /order/getTestOrder
                                    │
                                    ▼
                  ┌─────────────────────────────────────┐
                  │        Gateway :10000                │
                  │  ┌───────────────────────────────┐  │
 网关层（路由级）  │  │ SentinelGatewayFilter         │  │  资源 service-order
 QPS 100         │  │ 规则: gateway-sentinel-        │  │  整条 /order/** 路由共享 100 QPS
                  │  │        gw-flow.json            │  │  超限返回 {"code":429,...}
                  │  └───────────────┬───────────────┘  │
                  └──────────────────┼──────────────────┘
                                     │ 通过
                                     ▼
                  ┌─────────────────────────────────────┐
 服务层（接口级）  │     service-order :8000              │  资源 getTestOrder
 QPS 20          │  ┌───────────────────────────────┐  │  只针对 /getTestOrder 这一个接口
                  │  │ @SentinelResource             │  │  规则: service-order-sentinel-flow.json
                  │  │ "getTestOrder"                │  │  超限走 blockHandler 返回 {"code":429,...}
                  │  └───────────────────────────────┘  │
                  └─────────────────────────────────────┘
```

**为什么分两层？**

| 层级 | 作用 | 不配的后果 |
|------|------|-----------|
| 网关层（路由级） | 保护整个服务，防整体被打爆 | 下游服务直接扛全量流量 |
| 服务层（接口级） | 保护单个热点接口，精确控流 | 接口被刷爆时网关层阈值已经太高，拦不住 |

两层**叠加生效**，同一个请求 `/order/getTestOrder` 会先过网关 100，再过服务 20，最终受限于更严格的 20。

> 即使有人绕过网关直连 `service-order:8000`，服务层的 `@SentinelResource` 仍然生效，这是二级架构的最大价值。

### 2.2 关键类与配置对照

| 角色 | 实现 | 位置 |
|------|------|------|
| 网关限流过滤器 | `SentinelGatewayFilter`（@Bean） | `gateway/.../config/GatewayConfiguration.java` |
| 网关规则数据源 | `NacosDataSource` 手动注册 | `gateway/.../config/GatewayConfiguration.java#initNacosDataSource` |
| 网关限流响应 | `GatewayCallbackManager.setBlockHandler` | `gateway/.../config/GatewayConfiguration.java#initBlockHandler` |
| 网关规则文件 | `gateway-sentinel-gw-flow.json` | Nacos `SENTINEL_GROUP` |
| 服务接口限流 | `@SentinelResource("getTestOrder")` | `service-order/.../controller/OrderController.java` |
| 服务限流兜底 | `getTestOrderBlockHandler` 返回 `R{code:429}` | 同上 |
| 服务规则文件 | `service-order-sentinel-flow.json` | Nacos `SENTINEL_GROUP` |
| Sentinel 连接 | `spring.cloud.sentinel.*` | `gateway/application.yml`、`service-order/application-dev.yml` |
| Sentinel Dashboard | Docker 容器 | `bladex/sentinel-dashboard:1.8.8` |

---

## 三、Sentinel 限流原理

### 3.1 Sentinel 的"资源"模型

Sentinel 按**资源**管理流量，不是按单个 URL：

```
你以为的监控（APM 风格）：
  GET /product/getProductList  200  120ms
  GET /product/getProductList  200  98ms

Sentinel 实际看到的（资源聚合）：
  资源 service-product:  通过 15 QPS, 平均 150ms, 拒绝 2
  资源 service-order:    通过 5 QPS, 平均 800ms, 拒绝 0
```

本项目中有两类资源：
- **网关资源**：一条路由 = 一个资源，资源名 = 路由 id（`service-product` / `service-order`）
- **服务资源**：`@SentinelResource` 注解的 value 值（如 `getTestOrder`）

### 3.2 两种规则类型的区别

| 规则类型 | `rule-type` 值 | 配置菜单 | 用在哪 | 规则类 |
|---------|---------------|---------|--------|--------|
| **网关流控规则** | `gw-flow` | Dashboard「网关流控规则」 | 网关层（路由/API 分组） | `GatewayFlowRule` |
| **普通流控规则** | `flow` | Dashboard「流控规则」 | 业务服务（`@SentinelResource`） | `FlowRule` |

> 网关层只能用 `gw-flow`，业务服务只能用 `flow`，两者规则类不同，**数据源和 Nacos 文件必须分开**。

### 3.3 限流判定流程

```
请求进入 Gateway / 业务服务
  │
  ▼
Sentinel 拦截器识别资源（路由 id / @SentinelResource 名）
  │
  ├─ 查找规则：该资源挂了哪些流控规则？
  │     （规则来自 Nacos 动态下发，存在客户端内存）
  │
  ├─ 统计判定：滑动窗口统计当前 QPS，是否超过阈值？
  │
  ├─ 未超限 ─► 放行
  │
  └─ 超限 ─► 抛出 BlockException
              │
              ├─ 网关层：SentinelGatewayBlockExceptionHandler → GatewayCallbackManager
              └─ 服务层：@SentinelResource 的 blockHandler 方法
              │
              ▼
        返回 {"code":429,"message":"请求过于频繁，请稍后再试"}
```

### 3.4 Sentinel 不是 APM

| 需求 | 该用什么 |
|------|---------|
| 每个请求的 URL、状态码、耗时 | Gateway AccessLog / 项目里的 LoggingFilter |
| 接口级 QPS/RT 聚合监控 | Sentinel（本项目） |
| 分布式链路追踪 | SkyWalking / Zipkin / OpenTelemetry |
| 请求日志搜索 | ELK |

Sentinel 的职责是**流量治理**（限流、熔断、统计聚合），不是请求明细记录。

---

## 四、配置详解

### 4.1 网关层规则：`gateway-sentinel-gw-flow.json`

存放在 Nacos `SENTINEL_GROUP` 组，namespace 与服务发现相同。

```json
[
  {
    "resource": "service-order",
    "resourceMode": 0,
    "grade": 1,
    "count": 100,
    "intervalSec": 1,
    "controlBehavior": 0,
    "burst": 0
  },
  {
    "resource": "service-product",
    "resourceMode": 0,
    "grade": 1,
    "count": 10,
    "intervalSec": 1,
    "controlBehavior": 0,
    "burst": 0
  }
]
```

**字段含义（网关流控规则 `GatewayFlowRule`）：**

| 字段 | 含义 | 本项目取值 |
|------|------|-----------|
| `resource` | 资源名 = 路由 id | `service-order` / `service-product` |
| `resourceMode` | 0=路由 id，1=API 分组名 | `0` |
| `grade` | **1=QPS 限流，0=并发线程数限流** | `1` |
| `count` | 阈值（intervalSec 内允许的请求数） | `100` / `10` |
| `intervalSec` | 统计窗口秒数 | `1`（即每秒 QPS） |
| `controlBehavior` | 0=直接拒绝，1=warmUp，2=匀速排队 | `0` |
| `burst` | 突发允许的额外请求数 | `0` |

> ⚠️ `GatewayFlowRule` **只有上面这 7 个字段**（外加 `paramItem`、`maxQueueingTimeoutMs`），不支持 `comment` 等额外字段，加了会导致 JSON 解析失败（见[第五章坑 3](#3-json-解析失败gatewayflowrule-不认-comment-字段)）。

### 4.2 服务层规则：`service-order-sentinel-flow.json`

同样存放在 Nacos `SENTINEL_GROUP`：

```json
[
  {
    "resource": "getTestOrder",
    "limitApp": "default",
    "grade": 1,
    "count": 20,
    "strategy": 0,
    "controlBehavior": 0,
    "clusterMode": false
  }
]
```

**字段含义（普通流控规则 `FlowRule`）：**

| 字段 | 含义 | 取值 |
|------|------|------|
| `resource` | 资源名 = `@SentinelResource` 的 value | `getTestOrder` |
| `limitApp` | 针对的调用方，`default` 表示所有 | `default` |
| `grade` | 1=QPS，0=并发线程数 | `1` |
| `count` | 单机阈值 | `20` |
| `strategy` | 0=直接，1=关联，2=链路 | `0` |
| `controlBehavior` | 0=直接拒绝，1=warmUp，2=匀速排队 | `0` |
| `clusterMode` | 是否集群流控 | `false` |

### 4.3 业务服务侧配置（`service-order`）

**① 依赖**：`services/pom.xml` 已统一引入，子模块无需重复声明：
```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
</dependency>
<dependency>
    <groupId>com.alibaba.csp</groupId>
    <artifactId>sentinel-datasource-nacos</artifactId>
    <version>1.8.8</version>
</dependency>
```

**② 配置**（`service-order/application-dev.yml`）：
```yaml
spring:
  cloud:
    sentinel:
      transport:
        dashboard: 127.0.0.1:8858
        port: 8721                  # 客户端通信端口
      eager: true
      datasource:
        flow:
          nacos:
            server-addr: 127.0.0.1:8848
            username: nacos
            password: nacos
            namespace: 4c46f065-676d-4ae9-b3ca-8cab6aafb11b
            group-id: SENTINEL_GROUP
            data-id: service-order-sentinel-flow.json
            data-type: json
            rule-type: flow         # flow 类型 SCA 自带 converter，可直接用 yml 数据源
```

> 业务服务用 `rule-type: flow`，SCA 内置了 JSON converter，yml 数据源方式可用。
> 网关用 `rule-type: gw-flow` 则不行，见下一节。

**③ 代码**（`OrderController.java`）：
```java
@GetMapping("/getTestOrder")
@SentinelResource(value = "getTestOrder", blockHandler = "getTestOrderBlockHandler")
public R getTestOrder() {
    return R.ok("success", orderService.list());
}

// 被限流时调用，返回 code=429 的 R，与正常 code=200 明显区分
public R getTestOrderBlockHandler(BlockException ex) {
    log.warn("getTestOrder 被限流: {}", ex.getClass().getSimpleName());
    return R.error(429, "请求过于频繁，请稍后再试");
}
```

### 4.4 网关侧配置（`gateway`）

网关侧**不能**用 `application.yml` 的 `spring.cloud.sentinel.datasource` 方式接 gw-flow 规则（SCA 对 gw-flow 没有内置 converter，会报错）。改由 `GatewayConfiguration` **手动注册 NacosDataSource**，连接信息复用 `spring.cloud.nacos.*`。

**① `application.yml`** 只保留 Sentinel 连接配置，不配 datasource：
```yaml
spring:
  cloud:
    nacos:
      server-addr: 127.0.0.1:8848
      username: nacos
      password: nacos
      discovery:
        namespace: 4c46f065-676d-4ae9-b3ca-8cab6aafb11b
    sentinel:
      transport:
        dashboard: 127.0.0.1:8858
        port: 8719
      eager: true
      filter:
        enabled: false   # 关闭默认拦截器，避免和 gateway 适配器重复
```

**② `GatewayConfiguration.java`** 手动注册数据源 + 过滤器 + 限流响应：
```java
@PostConstruct
public void initNacosDataSource() {
    Properties props = new Properties();
    props.setProperty("serverAddr", nacosServerAddr);     // 复用 spring.cloud.nacos.server-addr
    // ... username / password / namespace

    ReadableDataSource<String, Set<GatewayFlowRule>> ds = new NacosDataSource<>(
            props, "SENTINEL_GROUP", "gateway-sentinel-gw-flow.json",
            source -> {
                // 容错：忽略 GatewayFlowRule 不认识的额外字段（如 comment）
                ObjectMapper mapper = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                return mapper.readValue(source, new TypeReference<Set<GatewayFlowRule>>() {});
            });
    GatewayRuleManager.register2Property(ds.getProperty());
}
```

完整代码见 `gateway/.../config/GatewayConfiguration.java`。

### 4.5 Sentinel Dashboard 验证

菜单：**流控规则** / **网关流控规则**，可看到从 Nacos 加载的规则。

**懒加载特性**：应用启动后不会立刻出现在 Dashboard，必须**先发一次请求**触发资源初始化。配置里已加 `spring.cloud.sentinel.eager=true`，但保险起见启动后仍建议先发一个请求。

---

## 五、踩坑记录（重点）

以下是本项目接入 Sentinel 过程中实际踩过的坑，每个都标注「现象/根因/解法」，避免重复踩。

### 目录

- [坑 1：SCA 不再传递 Gateway 适配器，Dashboard 监控空白](#1-sca-不再传递-gateway-适配器dashboard-监控空白)
- [坑 2：SCA 对 gw-flow 无内置 converter，yml 数据源报错](#2-sca-对-gw-flow-无内置-converyml-数据源报错)
- [坑 3：JSON 解析失败，GatewayFlowRule 不认 comment 字段](#3-json-解析失败gatewayflowrule-不认-comment-字段)
- [坑 4：@Value 占位符引用已删除的 yml 配置，启动失败](#4-value-占位符引用已删除的-yml-配置启动失败)
- [坑 5：blockHandler 返回空列表，限流是否生效看不出来](#5-blockhandler-返回空列表限流是否生效看不出来)

---

#### 1. SCA 不再传递 Gateway 适配器，Dashboard 监控空白

**现象**：只引入 `spring-cloud-starter-alibaba-sentinel`，Dashboard 左侧能出现 gateway 应用，但**实时监控永远是空白**。

**根因**：SCA 2023.x 不再传递 `sentinel-spring-cloud-gateway-adapter`，请求经过网关时没有被识别成 Sentinel 资源。很多教程仍按旧版本讲，照抄会踩坑。

**解法**：
```xml
<!-- gateway/pom.xml 额外引入适配器 -->
<dependency>
    <groupId>com.alibaba.csp</groupId>
    <artifactId>sentinel-spring-cloud-gateway-adapter</artifactId>
    <version>1.8.8</version>
</dependency>
```
```java
// GatewayConfiguration.java 手动注册 SentinelGatewayFilter（SCA 2023.x 不会自动装配）
@Bean
@Order(-1000)
public SentinelGatewayFilter sentinelGatewayFilter() {
    return new SentinelGatewayFilter();
}
```

---

#### 2. SCA 对 gw-flow 无内置 converter，yml 数据源报错

**现象**：`application.yml` 配置 `spring.cloud.sentinel.datasource.gw-flow` 后，启动报：
```
[Sentinel Starter] DataSource gw-flow build error:
No bean named 'sentinel-json-gw-flow-converter'
```

**根因**：SCA 的自动装配只为 `flow` / `degrade` / `param-flow` / `system` / `authority` 这几种 rule-type 提供了 JSON converter，**`gw-flow` 没有内置 converter**，yml 数据源方式直接报错。

**解法**：网关层**不要用 yml datasource**，改由 `GatewayConfiguration` 手动 `new NacosDataSource` 并 `GatewayRuleManager.register2Property()` 注册，绕开 SCA 自动装配。业务服务的 `flow` 规则不受影响，仍可用 yml 数据源。

> 关键区分：`flow` 类型 yml 可用，`gw-flow` 类型 yml 不可用。

---

#### 3. JSON 解析失败，GatewayFlowRule 不认 comment 字段

**现象**：网关启动时控制台输出，且**整个规则集解析失败返回空集合，网关一条规则都没加载**：
```
[Sentinel] 解析 Nacos 网关流控规则失败: Unrecognized field "comment"
(class GatewayFlowRule), not marked as ignorable (9 known properties: ...)
```

**根因**：`GatewayFlowRule` 只有 9 个字段（`burst`/`resourceMode`/`maxQueueingTimeoutMs`/`grade`/`intervalSec`/`resource`/`count`/`paramItem`/`controlBehavior`）。Nacos 规则 JSON 里带了 `comment` 字段（人为加的注释），Jackson 默认遇到未知字段就抛异常，**导致整个规则集解析失败**。

**解法**（双重保险）：
1. Nacos 规则 JSON 里**去掉所有额外字段**，只保留 `GatewayFlowRule` 认识的 9 个
2. `GatewayConfiguration` 的 ObjectMapper 配置容错，防止以后 Dashboard 下发带额外字段时再次失败：
```java
ObjectMapper mapper = new ObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
```

> 这是「整个规则集静默失效」的典型，排查时一定要看启动日志里有没有这行错误。

---

#### 4. @Value 占位符引用已删除的 yml 配置，启动失败

**现象**：删除 `application.yml` 里的 `spring.cloud.sentinel.datasource.gw-flow.*` 配置块后，启动报：
```
Could not resolve placeholder 'spring.cloud.sentinel.datasource.gw-flow.nacos.server-addr'
```

**根因**：`GatewayConfiguration` 里有 6 个 `@Value` 字段引用了 `spring.cloud.sentinel.datasource.gw-flow.nacos.*` 占位符。删了 yml 但忘了同步清理 Java 代码，占位符解析失败导致 Bean 创建失败、应用启动失败。

**解法**：把数据源的连接信息来源**从 `spring.cloud.sentinel.datasource.*` 改为复用 `spring.cloud.nacos.*`**（服务发现用的那套，本来就在 yml 里）：
```java
@Value("${spring.cloud.nacos.server-addr}")
private String nacosServerAddr;
@Value("${spring.cloud.nacos.username}")
private String nacosUsername;
@Value("${spring.cloud.nacos.password}")
private String nacosPassword;
@Value("${spring.cloud.nacos.discovery.namespace}")
private String nacosNamespace;
private static final String NACOS_GROUP_ID = "SENTINEL_GROUP";
private static final String NACOS_DATA_ID = "gateway-sentinel-gw-flow.json";
```
好处：Nacos 连接信息只维护一份，group-id/data-id 这两个稳定标识直接硬编码。

> 教训：删除 yml 配置时，全局搜索对应的 `@Value("${...}")` 引用，避免遗留占位符。

---

#### 5. blockHandler 返回空列表，限流是否生效看不出来

**现象**：压测 `/getTestOrder`，QPS 能达到 100，**误以为 QPS=20 的限流没生效**。

**根因**：blockHandler 返回 `Collections.emptyList()` 且 HTTP 200，与"数据库查出来就是空"长得一模一样：
```java
// 错误写法：被限流返回 []，HTTP 200，和正常空结果无法区分
public List<OrderEntity> getTestOrderBlockHandler(BlockException ex) {
    return Collections.emptyList();
}
```
压测工具（ab/wrk/jmeter）把限流兜底当成成功请求统计，肉眼调试也分不清，导致**限流其实生效了却误判没生效**。

**验证方法**：查 `~/logs/csp/sentinel-block.log`，能看到大量 `getTestOrder,FlowException` 被拒记录，证明限流一直在工作。

**解法**：blockHandler 改用统一返回对象 `R`，被限流时 `code=429`，与正常 `code=200` 明显区分：
```java
public R getTestOrderBlockHandler(BlockException ex) {
    return R.error(429, "请求过于频繁，请稍后再试");
}
```

> 这是「观测方法误导判断」的典型。判断限流是否生效，**别只看 HTTP 状态码**，看 Sentinel 的拒绝日志或 Dashboard 的拒绝 QPS 曲线。

---

## 六、生产/商用实践

### 6.1 规则持久化：何时该接 Nacos

| 场景 | 建议 |
|------|------|
| 学习/ demo | 单机内存，重启丢规则无所谓 |
| 小规模生产（< 3 实例，规则少变） | 单机内存 + Dashboard 推送，重启后手动推一次 |
| 中大规模生产（多实例、规则频繁调整） | **必须接 Nacos 持久化**，否则重启规则全丢 |

> 本项目为演示完整商用方案，直接接入了 Nacos 持久化。实际小项目可先用单机内存模式。

### 6.2 典型规则设计

| 场景 | 规则设计 |
|------|---------|
| 防恶意刷接口 | 按 IP 限流（`paramItem`），QPS 上限设正常用户达不到的值 |
| 保护核心接口 | 秒杀类接口单独限流，阈值设系统能承受的 QPS |
| 突发流量削峰 | `controlBehavior=1`（warmUp 冷启动），避免瞬时流量打垮系统 |
| 排队平滑 | `controlBehavior=2`（匀速排队），请求匀速通过 |
| 多租户隔离 | 按调用方（`limitApp`）分别设阈值，VIP 客户配额更高 |

### 6.3 观测限流的正确方法

**不要只看 HTTP 状态码**——blockHandler 可能返回 200（本项目就是返回 `R{code:429}` 但 HTTP 200）。三个可靠途径：

| 途径 | 位置 | 看什么 |
|------|------|--------|
| **Sentinel 日志** | `~/logs/csp/sentinel-block.log` | 每秒记录被拒次数、资源名、异常类型 |
| **Sentinel Dashboard** | http://127.0.0.1:8858 | 实时监控曲线：通过 QPS vs 拒绝 QPS |
| **响应体** | 业务自定义 | 本项目看 `code` 字段：200=真通过，429=被限流 |

### 6.4 国内常见组合方案

**中大型项目典型组合**：Nginx/网关粗粒度限流（防 CC）+ Sentinel 业务细粒度限流（按接口/用户）+ Sentinel 熔断降级（防雪崩）。

```
Nginx limit_req（IP 维度，防 CC）
    └─ Gateway Sentinel（路由维度，保护服务整体）
        └─ 业务服务 @SentinelResource（接口维度，保护热点接口）
            └─ Feign 调用 Sentinel 熔断（防下游雪崩）
```

---

## 七、环境与启动

### 7.1 依赖版本

| 组件 | 版本 |
|------|------|
| Spring Cloud | 2023.0.3 |
| Spring Cloud Alibaba | 2023.0.3.2 |
| Sentinel（客户端） | 1.8.8（由 SCA 传递） |
| Sentinel Dashboard | 1.8.8（Docker: `bladex/sentinel-dashboard:1.8.8`） |
| Nacos | 2.4.x |

### 7.2 启动 Sentinel Dashboard

```bash
docker run -d --name sentinel-dashboard -p 8858:8858 \
  bladex/sentinel-dashboard:1.8.8
```

访问 http://localhost:8858，账号密码 `sentinel` / `sentinel`。

### 7.3 Nacos 规则配置初始化

首次部署需在 Nacos 控制台（namespace 与服务发现相同）的 `SENTINEL_GROUP` 组下创建两个配置：

| dataId | 内容 | 作用 |
|--------|------|------|
| `gateway-sentinel-gw-flow.json` | 见 [4.1](#41-网关层规则gateway-sentinel-gw-flowjson) | 网关层路由级限流 |
| `service-order-sentinel-flow.json` | 见 [4.2](#42-服务层规则service-order-sentinel-flowjson) | 服务层接口级限流 |

### 7.4 完整启动顺序

1. 启动 Nacos（8848）
2. 启动 Sentinel Dashboard（8858）
3. 在 Nacos 创建上述两个规则配置（`SENTINEL_GROUP`）
4. 启动 service-order（8000）、service-product（9000）—— 注册到 Nacos
5. 启动 gateway（10000）
6. 发一次请求触发 Sentinel 资源初始化：
   ```bash
   curl http://localhost:10000/order/getTestOrder
   ```
7. 打开 Dashboard，左侧出现 `gateway` / `service-order` 应用，可看到加载的规则和实时监控

### 7.5 端口占用说明

Sentinel 客户端默认通信端口 8719，被占用会自动 +1（8720、8721...）。
本项目已显式指定：gateway=8719，service-product=8719/8720，service-order=8721。
**不影响 Dashboard 连接**——客户端启动时会把自己的端口上报给 Dashboard。

### 7.6 验证限流是否生效

```bash
# 压测 getTestOrder（应受服务层 QPS=20 限制，超限返回 code=429）
for i in {1..50}; do
  curl -s "http://localhost:10000/order/getTestOrder" | python3 -c "import sys,json;print(json.load(sys.stdin)['code'])"
done | sort | uniq -c
# 预期：多数为 200，少量为 429

# 查 Sentinel 拒绝日志
tail -f ~/logs/csp/sentinel-block.log
```
