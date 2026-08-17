# Gateway 使用与商用方案

> 本文档沉淀本项目 Spring Cloud Gateway 的完整实践：网关的作用与定位、集成方式、
> 路由/过滤器原理、本项目配置逐项解读、实际商用场景、优劣势分析、踩坑记录与生产注意事项。
> 所有代码示例均可在 `gateway` 模块中找到对应实现；网关层限流（Sentinel）在
> [Sentinel 网关限流使用与商用方案](Sentinel网关限流使用与商用方案.md) 中单独展开，本文不重复。

---

## 一、Gateway 是什么，本项目里它干什么

### 1.1 一句话定位

Spring Cloud Gateway 是 Spring Cloud 官方的 **API 网关**：建立在 Spring WebFlux + Project Reactor
之上，用异步非阻塞的方式把"外部请求"路由到"内部微服务"，并在路由过程中统一执行横切逻辑
（鉴权、限流、日志、跨域、灰度等）。

**网关解决的核心问题是"统一入口"**：没有网关时，前端要记住每个服务的地址和端口、每个服务
各自处理跨域和鉴权、实例扩缩容后调用方全要改；有了网关，这些事收敛到一处，写一次全服务生效。

### 1.2 本项目中网关承担的职责

```
                    客户端（浏览器 / 其他系统）
                              │
                              ▼
                ┌───────────────────────────┐
                │      Gateway :10000       │  ← 整个集群对外的唯一入口
                │                           │
                │  ① 路由转发（核心）        │  /order/**   → lb://service-order
                │     Path 匹配 + StripPrefix│  /product/** → lb://service-product
                │                           │
                │  ② 负载均衡               │  lb:// → Nacos 实例列表 → 轮询选一个
                │     RoundRobin（可切换）   │
                │                           │
                │  ③ 统一跨域               │  globalcors + DedupeResponseHeader
                │     下游服务无需再配       │
                │                           │
                │  ④ 统一日志               │  LoggingFilter（GlobalFilter）
                │     记录每个请求的耗时/状态│
                │                           │
                │  ⑤ 统一限流入口           │  Sentinel 路由级 QPS 限制
                │     超限返回 429 JSON     │  （详见 Sentinel 文档）
                │                           │
                │  ⑥ 扩展点：统一鉴权/灰度   │  写一个 GlobalFilter 即可全服务生效
                └──────────┬───────┬────────┘
                           │       │
                ┌──────────▼─┐ ┌───▼──────────────┐
                │ service-order│ │ service-product  │
                │   :8000      │ │   :9000          │
                └──────────────┘ └──────────────────┘
                        （都注册在 Nacos 同一 namespace）
```

| 职责 | 实现位置 | 说明 |
|------|---------|------|
| 路由转发 | `gateway/src/main/resources/application.yml` | 两条路由：order / product |
| 负载均衡 | `config/LoadBalancerConfig.java` | 轮询策略，可切换随机 |
| 统一日志 | `filter/LoggingFilter.java` | GlobalFilter 示例，记录请求耗时 |
| 跨域 | `application.yml` 的 `globalcors` | 下游服务不用再各配一份 |
| 限流 | `config/GatewayConfiguration.java` | Sentinel 网关层路由级限流 |

### 1.3 网关在整体架构里的位置（与注册中心的关系）

网关本身也是一个 **Nacos 客户端**：启动时把自己注册进去（可选），更重要的是作为消费者
**订阅** `service-order` / `service-product` 的实例列表。实例上下线时 Nacos 推送变更，
网关自动把流量切到新实例——这就是 `lb://service-xxx` 能"永远转发生存实例"的原因。

```
Nacos :8848  ──推送实例变更──▶  Gateway（持有一份服务名 → [实例1, 实例2...] 的列表）
     ▲                              │
     │ 注册/心跳                     │ 按路由匹配 + 负载均衡选实例转发
     │                              ▼
service-order:8000 / 8001 ...   service-product:9000 ...
```

> 注意一个容易忽略的点：网关和业务服务必须配在 **同一个 namespace**（本项目是
> `4c46f065-...`），否则网关拿到的实例列表为空，请求直接 503。这是真实踩过的坑，见第五章。

---

## 二、集成方式（本项目怎么接的）

### 2.1 依赖引入（gateway/pom.xml）

```xml
<!-- 核心：基于 WebFlux + Netty 的异步非阻塞网关。
     ⚠️ 千万不要引入 spring-boot-starter-web，否则启动直接报错（原因见第五章坑 1）-->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-gateway</artifactId>
</dependency>

<!-- 服务发现：从 Nacos 拉取 service-order / service-product 的实例列表 -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>

<!-- 负载均衡器：lb://service-xxx 这种写法依赖它做实例选择 -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
```

三个依赖的分工：**starter-gateway 负责"转发"，nacos-discovery 负责"知道转给谁"（拿实例列表），
loadbalancer 负责"转给哪一个"（从多个实例里选一个）**。三者缺一，`lb://` 路由都无法工作。

Sentinel 相关依赖（限流用，与本文主线无关，详见 Sentinel 文档）：
`spring-cloud-starter-alibaba-sentinel` + 手动引入的
`sentinel-spring-cloud-gateway-adapter:1.8.8` + `sentinel-datasource-nacos:1.8.8`。

### 2.2 路由配置（核心，application.yml）

一条路由 = **id（标识） + uri（转发到哪） + predicates（哪些请求匹配） + filters（转发前后怎么加工）**：

```yaml
spring:
  cloud:
    nacos:
      server-addr: 127.0.0.1:8848
      discovery:
        namespace: 4c46f065-676d-4ae9-b3ca-8cab6aafb11b   # 必须与业务服务同 namespace
    gateway:
      routes:
        - id: service-order                 # 路由标识，唯一即可（习惯上写目标服务名）
          uri: lb://service-order           # lb:// = 走负载均衡，后面是 Nacos 里的服务名
          predicates:                        # 断言：满足条件才走这条路由
            - Path=/order/**                 # 路径以 /order/ 开头的请求
          filters:
            - StripPrefix=1                  # 转发前去掉第 1 层前缀：/order/create → /create

        - id: service-product
          uri: lb://service-product
          predicates:
            - Path=/product/**
          filters:
            - StripPrefix=1                  # /product/getProductList → /getProductList
```

一次实际转发：

```
客户端请求   GET gateway:10000/order/getOrderList
                     │
                     │ ① Path 断言匹配 /order/** → 命中路由 service-order
                     │ ② StripPrefix=1 → 路径变为 /getOrderList
                     │ ③ lb://service-order → 从 Nacos 实例列表轮询选一个
                     ▼
下游收到     GET service-order:8000/getOrderList   ← 注意前缀 /order 已被剥掉
```

**为什么需要 StripPrefix？** 网关用第一段路径区分"转给哪个服务"，但下游 Controller 上写的
是 `@GetMapping("/getOrderList")` 而不是 `@GetMapping("/order/getOrderList")`——`/order`
这层前缀是网关的路由约定，不该泄漏给下游。这是一个"路径约定"问题，团队内统一即可；
如果下游接口本身就带 `/order` 前缀，那就不要配 StripPrefix。

### 2.3 统一跨域配置

```yaml
gateway:
  default-filters:
    # 去重响应头：网关配了 CORS、下游如果也配过，会出现两个 Access-Control-Allow-Origin，
    # 浏览器直接报错。RETAIN_UNIQUE 表示保留唯一值。
    - DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials RETAIN_UNIQUE
  globalcors:
    cors-configurations:
      '[/**]':
        allowedOriginPatterns: "*"     # 生产应收窄为具体域名；配了 allowCredentials 就不能用 "*"
        allowedMethods: "*"
        allowedHeaders: "*"
        allowCredentials: true
        maxAge: 3600                   # 预检请求(OPTIONS)结果缓存 1 小时，减少一次往返
```

**跨域只在网关配，下游不要再配**——这是网关"统一治理"最典型的收益：配置一份，全服务生效。
`DedupeResponseHeader` 是防御性配置，防止某个老服务残留 CORS 配置导致响应头重复。

### 2.4 负载均衡策略（LoadBalancerConfig.java）

Spring Cloud LoadBalancer 4.x **不再支持** 在 yml 里写策略类名（Ribbon 时代的
`NFLoadBalancerRuleClassName` 已废弃），改为通过 `@LoadBalancerClients` 指定配置类覆盖默认策略：

```java
@Configuration
@LoadBalancerClients(defaultConfiguration = LoadBalancerConfig.RoundRobinConfig.class)
public class LoadBalancerConfig {

    /** 轮询策略（默认，当前启用）：请求依次分给实例1、实例2、实例3... 循环 */
    public static class RoundRobinConfig {
        @Bean
        ReactorLoadBalancer<ServiceInstance> reactorServiceInstanceLoadBalancer(
                Environment environment, LoadBalancerClientFactory factory) {
            String serviceId = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
            return new RoundRobinLoadBalancer(
                    factory.getLazyProvider(serviceId, ServiceInstanceListSupplier.class), serviceId);
        }
    }
    // RandomConfig（随机策略）已注释，切换方法见类内注释
}
```

两点注意：

1. **defaultConfiguration 指向哪个内部类，就全局用哪个策略**，两个策略类不能同时启用
   （@Bean 方法同名会冲突）。如果只想对某个服务用不同策略，用
   `@LoadBalancerClients({@LoadBalancerClient(name = "service-order", configuration = XxxConfig.class), ...})`。
2. yml 里 `loadbalancer.cache.enabled: false` 是**调试配置**（关掉实例列表缓存，实例上下线
   立即可见），**生产必须删掉这行**——缓存默认 35s 过期，关掉意味着每次请求都去查服务列表，
   给 Nacos 白白加压。

### 2.5 全局过滤器（LoggingFilter.java）

```java
@Slf4j
@Component
public class LoggingFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        long start = System.currentTimeMillis();
        log.info("[Gateway] 收到请求 -> {} {} from {}", request.getMethod().name(),
                request.getURI().getPath(), request.getRemoteAddress().getHostString());

        // chain.filter(exchange)：把请求传给过滤器链的下一环
        // .then(...)：响应返回时的回调，可以拿到最终状态码、计算耗时
        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            long cost = System.currentTimeMillis() - start;
            log.info("[Gateway] 请求完成 <- {} status={} cost={}ms",
                    request.getURI().getPath(),
                    exchange.getResponse().getStatusCode().value(), cost);
        }));
    }

    @Override
    public int getOrder() { return -1; }   // 数字越小越先执行
}
```

`GlobalFilter` 对**每一条路由**都生效，这是网关"统一治理"能力的落点。真实项目里在这里做
统一鉴权（校验 JWT）、黑名单拦截、埋点注入 traceId 等——**写一次，所有经过网关的服务都生效**，
不用每个服务重复实现。注意写法必须是响应式的（返回 `Mono`/`Mono.defer`），不能在里面写阻塞代码。

### 2.6 关键文件清单

| 文件 | 作用 |
|------|------|
| `gateway/pom.xml` | 依赖引入（含"不能引 starter-web"的注释警示） |
| `gateway/src/main/resources/application.yml` | 路由、跨域、Nacos、负载均衡、Sentinel 全部配置 |
| `.../gateway/GatewayApplication.java` | 启动类（WebFlux 应用，不能加 @EnableFeignClients 等 Servlet 组件） |
| `.../gateway/config/LoadBalancerConfig.java` | 负载均衡策略（轮询/随机切换） |
| `.../gateway/config/GatewayConfiguration.java` | Sentinel 网关限流（过滤器注册、Nacos 规则源、429 响应） |
| `.../gateway/filter/LoggingFilter.java` | 全局日志过滤器（统一鉴权的模板位置） |

---

## 三、工作原理：一次请求经过网关的完整链路

理解这条链路，是排查"为什么 404 / 为什么 503 / 为什么过滤器没执行"的基础：

```
客户端请求
   │
   ▼
① HandlerMapping：用所有路由的 Predicate 逐条匹配，选出第一条命中的 Route
   │   （Path=/order/** 匹配 /order/getOrderList → 路由 service-order）
   ▼
② 组装过滤器链：GlobalFilter（全局） + 该路由的 GatewayFilter（配置里的 filters）
   │   按 getOrder() 从小到大排序，依次执行：
   │
   │   [-1000] SentinelGatewayFilter   ← 网关层限流，超限直接返回 429，不再往下走
   │   [   -1] LoggingFilter           ← 记录请求日志
   │   [    0] StripPrefix filter      ← 剥掉 /order 前缀（路由 filters 配置而来）
   │   [  N] NettyRoutingFilter        ← 真正发起 HTTP 转发的内置过滤器
   ▼
③ LoadBalancer：uri 是 lb:// 时，NettyRoutingFilter 之前会先把服务名解析成具体实例
   │   lb://service-order → Nacos 实例列表 [192.168.1.5:8000, 192.168.1.6:8000]
   │   → RoundRobinLoadBalancer 选出 192.168.1.5:8000
   ▼
④ Netty 转发：GET http://192.168.1.5:8000/getOrderList
   │
   ▼
⑤ 响应原路返回：每个过滤器的 .then() 回调按相反顺序执行（记日志、加工响应头）
   │
   ▼
客户端拿到响应
```

三个关键机制：

1. **Predicate 决定"谁处理"**：除 `Path` 外还有 `Method`、`Header`、`Query`、`Weight`（灰度）、
   `After/Before/Between`（时间窗）等十几种，可组合使用。多条路由按**声明顺序**匹配，
   第一条命中即生效——顺序很重要，宽泛的路由要放后面。
2. **Filter 决定"怎么处理"**：分两类——`GatewayFilter` 只对所属路由生效（如 StripPrefix），
   `GlobalFilter` 全局生效（如 LoggingFilter、SentinelGatewayFilter）。执行前逻辑按 order
   升序，响应回程逻辑按相反顺序。
3. **lb:// 的解析发生在转发前一刻**：网关持有的实例列表来自 Nacos 订阅推送 + LoadBalancer
   本地缓存（默认 35s），所以实例下线后最坏会有"缓存期内打到已死实例"的窗口，
   依赖 Netty 层的重试或 Ribbon 式的快速失败来兜底（商用注意第 6.3 节）。

---

## 四、实际商用场景

### 4.1 场景一：统一 API 入口 + 动态扩缩容（最基础也最普遍）

中小公司最先用网关的理由：**前端只记一个域名**，后端有多少服务、扩到多少实例都无感。

```
app.example.com/api/order/**   → service-order（今天 2 个实例，大促扩到 8 个）
app.example.com/api/product/** → service-product
```

实例扩缩容时 Nacos 推送变更，网关自动感知，**调用方零改动**。这就是"上微服务必上网关"的原因：
没有网关，每次扩缩容、迁移机房、换端口都是一次前端发版。

### 4.2 场景二：统一鉴权（商用必做）

在网关写一个 `GlobalFilter`，校验请求头里的 Token，失败直接返回 401，**不透传给下游**：

```java
@Component
public class AuthFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String token = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (!isValid(token)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] bytes = "{\"code\":401,\"msg\":\"未登录或登录已过期\"}".getBytes(StandardCharsets.UTF_8);
            return exchange.getResponse()
                    .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
        }
        // 校验通过后，把解析出的用户信息塞进请求头带给下游（下游不再重复解析）
        ServerHttpRequest newRequest = exchange.getRequest().mutate()
                .header("X-User-Id", parseUserId(token)).build();
        return chain.filter(exchange.mutate().request(newRequest).build());
    }

    @Override
    public int getOrder() { return -100; }   // 要在业务过滤器之前执行
}
```

商用要点：
- **白名单机制**：登录、健康检查等接口要能配置放行（从 Nacos 配置中心读，免重启）。
- **下游要防绕过**：网关鉴权后，下游服务必须只监听内网（安全组/网络策略），否则直连就绕过了鉴权。
- JWT 无状态校验适合放网关（本地验签，无远程调用）；如果是需要查 Redis 的会话校验，
  用响应式的 `ReactiveStringRedisTemplate`，**不要用阻塞的 Jedis/Redisson 同步 API**。

### 4.3 场景三：灰度发布 / 金丝雀发布

利用 Nacos 实例元数据（metadata）+ 自定义负载均衡规则，按版本分流：

```
service-order 注册实例时带上 metadata: version=v2
                │
   网关侧读取请求头 X-Gray: true（或按 userId 取模、按地域）
                │
   自定义 Filter/LoadBalancer：带灰度标的请求只从 version=v2 的实例里选
                │
   ┌────────────┴─────────────┐
   ▼                          ▼
老实例 v1（95% 流量）      新实例 v2（5% 流量，内部员工/白名单先体验）
```

更简单的版本切流用网关自带的 **Weight 断言**（按权重路由，适合 A/B 测试）：

```yaml
routes:
  - id: order-v1
    uri: lb://service-order
    predicates:
      - Path=/order/**
      - Weight=order-group, 95          # 95% 流量走这条
  - id: order-v2
    uri: lb://service-order-v2          # 同一服务的另一组部署（不同 metadata/服务名）
    predicates:
      - Path=/order/**
      - Weight=order-group, 5           # 5% 流量走这条
```

商用要点：灰度规则要**动态生效**（配合 Nacos 配置中心推送），否则每次调权重都要重启网关，
灰度的意义就没了；灰度期间两版接口契约必须兼容，否则前端要按版本适配。

### 4.4 场景四：统一限流熔断入口

网关是做"粗粒度"限流的最佳位置——流量还没进业务服务就拦住了。本项目采用的正是
**网关层（路由级）+ 服务层（接口级）二级限流**：

- 网关层：整条 `/order/**` 路由共享一个 QPS 上限，保护 service-order 整体不被打爆；
- 服务层：`@SentinelResource` 对单个热点接口精确控流。

细节（规则持久化到 Nacos、429 响应体定制、Dashboard 使用）在
[Sentinel 网关限流使用与商用方案](Sentinel网关限流使用与商用方案.md) 第五章踩坑与第六章商用实践里完整展开。

### 4.5 场景五：统一日志 / 审计 / 链路追踪

本项目的 `LoggingFilter` 是这个场景的最小实现。商用形态：

- 网关生成/透传 **traceId**（响应头返回给前端 + 透传给下游，全链路日志可串起来）；
- 访问日志落到 ELK / Loki，按路由维度统计 QPS、耗时分布、错误率；
- 敏感操作（下单、支付、改配置）的审计日志在网关留一份，含来源 IP、用户 ID、时间戳。

### 4.6 场景六：对外能力开放（API 开放平台）

给第三方合作伙伴暴露 API 时，网关统一做：AppKey/AppSecret 签名校验、按应用方限流配额、
按应用方计费统计、脱敏。这是网关"多租户治理"的典型用法，国内云厂商 API 网关的核心卖点
基本都是这些能力的托管版。

---

## 五、优劣势分析（商用选型必看）

### 5.1 与主流网关对比

| 维度 | Spring Cloud Gateway | Nginx | APISIX / Kong | 云 API 网关（阿里云等） |
|------|---------------------|-------|---------------|------------------------|
| 语言/技术栈 | Java / WebFlux | C / Lua 扩展 | Nginx + Lua (OpenResty) | 托管 |
| 与 Spring Cloud/Nacos 集成 | ★★★ 原生无缝 | 需 upstream 配置/三方模块 | 需 consul/etcd 或 nacos 插件 | 需对接 |
| 动态路由（服务发现） | ★★★ lb:// 天然支持 | 弱（需 Consul 模块/upstream 动态化） | ★★★ | ★★★ |
| 自定义逻辑 | Java 写 Filter，团队最熟 | Lua 或 C 模块，门槛高 | Lua 插件，生态丰富 | 配置受限 |
| 性能 | 好（Netty 异步），但弱于 Nginx | ★★★ 行业标杆 | ★★★ 接近 Nginx | ★★★ |
| 内存开销 | JVM，单实例几百 MB 起 | 极低 | 低 | 无（托管） |
| 协议支持 | HTTP/WebSocket（gRPC 有限） | 全（含 TCP/UDP 四层） | 全 + 插件扩展 | 看 product |
| 生态组件 | Sentinel/Resilience4j/Security 直接可用 | OpenResty 生态 | 插件市场（鉴权/可观测 80+） | 平台能力 |
| 运维成本 | 要懂 JVM + WebFlux | 运维都会 | 要懂 APISIX/K8s | 最低 |
| 适合 | Java 技术栈的微服务集群 | 流量最前置的接入层/静态资源 | 多语言技术栈、插件诉求多 | 不想自己运维网关 |

### 5.2 优势

1. **生态一致性**：和业务服务同栈（Java 17 + Spring Boot 3 + Nacos），治理逻辑用 Java 写，
   团队没有额外语言负担；依赖版本由 spring-cloud-alibaba BOM 统一管理。
2. **服务发现原生集成**：`lb://服务名` 一个写法就拿到动态实例 + 负载均衡，扩缩容零成本。
3. **过滤器模型强大**：GlobalFilter + GatewayFilter + 丰富的内置过滤器（StripPrefix、
   RewritePath、AddRequestHeader、Retry、RequestRateLimiter...），横切逻辑收敛一处。
4. **异步非阻塞**：WebFlux + Netty，少量线程扛高并发转发，纯转发场景单实例万级 QPS 没压力。
5. **与 Sentinel/Resilience4j 等熔断限流组件即插即用**，形成完整的流量治理链路。

### 5.3 劣势（选型前要想清楚）

1. **WebFlux 的心智成本**：过滤器里不能写阻塞代码（JDBC、同步 HTTP、`Thread.sleep` 都不行），
   会直接卡死 event loop 拖垮整个网关；排查响应式问题（Mono 链不执行、栈帧看不懂）比命令式难。
2. **不能引入任何 Servlet 组件**：`spring-boot-starter-web`、基于 Servlet 的 Filter、
   `@EnableFeignClients`（阻塞式 OpenFeign）都会导致启动失败或行为异常。
3. **功能边界**：基本只管 HTTP/WebSocket。TCP 四层转发、gRPC 原生代理、静态资源、
   WAF 这类能力不是它的菜——这些场景前面挂 Nginx/APISIX 更合适。
4. **内存与启动开销**：JVM 应用，容器里起步几百 MB；对比 Nginx 几 MB，资源敏感场景吃亏。
5. **动态路由默认要重启**：yml 里写死的路由改配置需重启；要动态得引入
   `actuator` 的 `/actuator/gateway/refresh` 或用 Nacos 配置中心 + 自定义 RouteDefinitionRepository。
6. **性能上限低于 Nginx 系**：纯转发吞吐不如 Nginx/APISIX（差距在数量级上通常可接受，
   但超大规模入口层一般还是 Nginx/SLB 打头）。

### 5.4 国内常见的落地组合

```
外部流量 → CDN → SLB/Nginx（四七层接入、TLS 卸载、静态资源、粗限流）
                        │
                        ▼
              Spring Cloud Gateway 集群（路由、鉴权、灰度、细限流、traceId）
                        │
                        ▼
                 Nacos 注册的微服务集群
```

**Nginx 与 Gateway 不是二选一**：Nginx 管"接得住"，Gateway 管"治理得了"，各取所长。
中小规模（日 PV 百万级以内）也可以省掉 Nginx，SLB 直挂 Gateway。

---

## 六、商用注意事项与最佳实践

### 6.1 安全（最重要）

1. **网关是对外唯一入口，下游服务绝不暴露公网**。用安全组/VPC 把 service-* 的端口全部
   收进内网——网关鉴权做得再好，服务能被直连就是白做。本项目演示环境里 service-order:8000
   直连可达，是为了验证"绕过网关时限流仍生效"（二级限流的价值），生产环境网络必须隔离。
2. **鉴权放在网关做第一道**，下游做第二道（防绕过 + 防内网横向）。敏感服务（支付、账户）
   下游还要再校验一次权限，不信任"来自网关的请求"这个假设。
3. **跨域配置收窄**：`allowedOriginPatterns: "*"` 配合 `allowCredentials: true` 是演示写法，
   生产必须改成明确的域名列表，否则任何网站都能带着用户 Cookie 发跨域请求。
4. 网关前面要有 **防 CC / WAF**（Nginx 层 limit_req、云 WAF），应用层限流是最后一道，
   不是第一道。
5. 网关自身的 **actuator 端口**（`/actuator/gateway/*`）必须鉴权或只对内网开放，
   否则攻击者可以直接刷路由表。

### 6.2 可用性与部署

1. **网关必须集群部署**（至少 2 个实例），前面挂 SLB/Nginx 做流量入口。网关是单点，
   整个系统就是单点。网关本身无状态，横向扩展没有障碍。
2. **优雅停机**：网关滚动发布时，在 service-order 下线前要先从 Nacos 摘流量
   （`spring-cloud-starter-bootstrap` 场景可配 `server.shutdown: graceful`），等 LoadBalancer
   缓存过期（默认 35s）再真正停进程，否则会有几十秒的 503 窗口。
3. **超时全链路对齐**：网关对下游的超时（`spring.cloud.gateway.httpclient.response-timeout`）
   应**略大于**下游业务的 P99 处理时间；下游 DB/RPC 的超时应小于网关超时。否则会出现
   "网关已经超时返回 500，下游还在跑并写库成功"的错位，用户看到失败但订单实际创建成功。
4. **谨慎开重试**：Gateway 的 `Retry` filter 默认只对 GET 生效是有原因的——POST 重试
   可能造成重复下单。本项目接口全是 GET 所以无感，但商用时非幂等接口严禁网关层重试。
5. 路由变更频率低时用 yml + 重启完全可接受；需要频繁改路由再上动态路由方案
   （Nacos + RouteDefinitionRepository），不要一开始就过度设计。

### 6.3 性能与容量

1. **绝不在过滤器里写阻塞调用**（JDBC、同步 HTTP 客户端、`ObjectMapper` 大报文解析）。
   Netty event loop 线程数默认约等于 CPU 核数，一个阻塞调用就能拖垮整个网关的吞吐。
   必须阻塞时用 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` 切走。
2. **生产开启 LoadBalancer 缓存**（删掉本项目调试用的 `cache.enabled: false`），
   并打开健康检查（`loadbalancer.health-check`），让故障实例更快被剔除。
3. 大文件上传/下载会长时间占用网关连接：设置合理的 `maxInMemorySize`（默认 256KB，
   超出会落盘/报错）与上传大小限制，超大文件考虑直传对象存储（网关只发签名）。
4. 容量规划：纯转发场景单实例（4C8G）几千到上万 QPS；开启了鉴权/限流/日志落盘后要压测定，
   按峰值的 1.5~2 倍留余量。

### 6.4 可观测性

1. 访问日志必须包含：traceId、路由 id、目标实例、状态码、耗时。出问题时"请求到了哪个实例"
   是排查的第一线索。
2. 接入 Micrometer + Prometheus：Gateway 自带 `spring.cloud.gateway.requests` 等 metric，
   按路由维度看 QPS/延迟/错误率；再配告警（5xx 比例、P99 延迟、限流拒绝数）。
3. 网关日志和下游日志用同一个 traceId 串联（网关生成 → header 透传 → 下游 MDC 接住），
   否则跨服务排查全靠猜。

### 6.5 配置治理

1. **路由配置进 Git**（yml 随代码发布、可回滚），动态路由规则进 Nacos（可审计、可灰度）。
2. namespace 隔离：开发/测试/生产的网关与业务服务分别使用不同 namespace，
   杜绝"测试网关发现生产服务"的事故。
3. 端口规划文档化。本项目端口：gateway 10000、service-order 8000、service-product 9000、
   Nacos 8848、Sentinel Dashboard 8858。

---

## 七、踩坑记录（重点，均为本项目实际验证或典型高频坑）

### 坑 1：引入 spring-boot-starter-web 导致启动失败

```
Spring MVC found on classpath, which is incompatible with Spring Cloud Gateway.
Please remove spring-boot-starter-web dependency...
```

**原因**：Gateway 基于 WebFlux（Reactor + Netty），`spring-boot-starter-web` 是 Servlet 栈
（Tomcat），两者在类路径上共存时 Spring Boot 自动装配直接报错。
**解决**：网关模块只依赖 `spring-cloud-starter-gateway`，任何 Servlet 组件都不引入；
`GatewayApplication` 上也不要加 `@EnableFeignClients`（阻塞式 OpenFeign 同理）。

### 坑 2：SCA 2023.x 不会自动带上 Sentinel 的 Gateway 适配器

**现象**：接了 `spring-cloud-starter-alibaba-sentinel`，但 Dashboard 上网关路由的实时监控是空的，
路由也没有成为 Sentinel 资源。
**原因**：SCA 2023.x 的 sentinel starter 默认只传递 webflux/webmvc 通用适配器，
Gateway 专用的 `sentinel-spring-cloud-gateway-adapter` 需手动引入。
**解决**：`gateway/pom.xml` 中显式引入 `sentinel-spring-cloud-gateway-adapter:1.8.8`，
并在 `GatewayConfiguration` 中手动注册 `SentinelGatewayFilter`。

### 坑 3：gw-flow 规则用 SCA 自动装配报 No bean named 'sentinel-json-gw-flow-converter'

**原因**：SCA 对 `rule-type=gw-flow`（网关流控规则）没有内置 JSON converter，
`spring.cloud.sentinel.datasource.*` 自动装配直接抛异常。
**解决**：不配 SCA 的 datasource，改为 `GatewayConfiguration#initNacosDataSource` 手动构造
`NacosDataSource` 监听 Nacos 上的 `gateway-sentinel-gw-flow.json`。解析时还要
`FAIL_ON_UNKNOWN_PROPERTIES=false`，因为 Dashboard 下发的规则常带 comment 等额外字段，
不忽略会导致整个规则集解析失败。详见 Sentinel 文档第五章。

### 坑 4：namespace 不一致，网关 503

**现象**：路由配置都对，请求返回 `503 Service Unavailable`，日志里 `Unable to find instance`。
**原因**：网关的 `spring.cloud.nacos.discovery.namespace` 与业务服务不一致，
网关订阅不到任何实例，`lb://service-order` 解析不出地址。
**解决**：两侧 namespace 完全一致（本项目 `4c46f065-...`）。排查 503 的固定三板斧：
① 两边 namespace/group 是否一致；② 服务名拼写是否与 Nacos 控制台一致；
③ 网关是否漏了 `spring-cloud-starter-loadbalancer` 依赖。

### 坑 5：忘配 StripPrefix，下游 404

**现象**：网关日志显示转发成功，但下游返回 404。
**原因**：`/order/getOrderList` 原样转发，而下游 Controller 是 `@GetMapping("/getOrderList")`，
`/order` 前缀对不上。
**解决**：加 `StripPrefix=1` 剥掉网关路由前缀；或反向统一——下游接口就带前缀、网关不剥。
团队内**约定一种**，混用最坑。

### 坑 6：CORS 配置重复导致浏览器报错

**现象**：`The 'Access-Control-Allow-Origin' header contains multiple values '..., ...'`。
**原因**：网关配了 CORS，下游某个服务历史上也配过，响应头出现两个 Allow-Origin。
**解决**：CORS 只在网关配，下游全部删掉；再加 `default-filters` 的
`DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials RETAIN_UNIQUE`
兜底去重。

### 坑 7：LoadBalancer 缓存导致的"实例已下线还在被打"

**现象**：服务下线后几十秒内仍有请求打到已死实例。
**原因**：LoadBalancer 默认缓存实例列表 35s（`spring.cloud.loadbalancer.cache.ttl`），
   本项目为了调试方便把 `cache.enabled` 设成了 false 才"立即感知"。
**解决**：生产保持缓存开启 + 打开健康检查；接受短暂窗口并配 Netty 侧重试（仅幂等请求），
   或缩短 ttl 换取 Nacos 压力上升（权衡问题）。

### 坑 8：过滤器的 order 顺序踩错

**现象**：自定义鉴权过滤器里打印的日志比 Sentinel 限流拒绝的响应还晚，或压根没执行到。
**原因**：过滤器按 `getOrder()` 升序执行，数字大的先"返回"。鉴权（-100）必须在业务
   处理（0）之前；想抢在所有过滤器前面就用极大负数（本项目 Sentinel 过滤器是 -1000）。
**解决**：团队内规划好 order 区间并注释在代码里，例如：鉴权 -100、日志 -1、业务加工 10+。

---

## 八、环境与启动验证

### 8.1 版本信息

| 组件 | 版本 |
|------|------|
| JDK | 17 |
| Spring Boot | 3.3.4 |
| Spring Cloud | 2023.0.3 |
| Spring Cloud Alibaba | 2023.0.3.2 |
| Sentinel adapter/datasource-nacos | 1.8.8 |
| Nacos | 127.0.0.1:8848（namespace: `4c46f065-...`） |

### 8.2 启动顺序

```bash
# 1. Nacos（网关与业务服务的注册中心，必须最先起）
# 2. 网关
cd gateway && mvn spring-boot:run          # 端口 10000
# 3. 业务服务（顺序无所谓，网关动态感知实例上下线）
cd services/service-order && mvn spring-boot:run    # 8000
cd services/service-product && mvn spring-boot:run  # 9000
```

（如需 Sentinel 网关限流，还需启动 Dashboard:8858 并在 Nacos 建好
`SENTINEL_GROUP/gateway-sentinel-gw-flow.json`，见 Sentinel 文档第七章。）

### 8.3 验证网关转发

```bash
# 走网关访问订单服务（网关剥掉 /order 前缀，转发到 service-order:8000/getOrderList）
curl http://localhost:10000/order/getOrderList

# 走网关访问商品服务
curl http://localhost:10000/product/getProductList

# 对比：绕过网关直连（演示环境可达；生产环境网络隔离后应不可达）
curl http://localhost:8000/getOrderList
curl http://localhost:9000/getProductList
```

网关日志中应能看到 `LoggingFilter` 打印的请求/完成两行日志（含耗时与状态码），
这就是 `gateway` 统一入口 + 路由 + 负载均衡 + 全局过滤器整条链路工作的直接证据。

---

## 九、总结

- **作用**：统一入口、路由转发、负载均衡、统一跨域/日志/鉴权/限流——把所有"每个服务
  都要做一遍"的横切事务收敛到一处。
- **集成方式**：三个依赖（gateway + nacos-discovery + loadbalancer）+ 一份 yml 路由配置，
  复杂逻辑用 GlobalFilter 写 Java 代码。
- **商用定位**：Java 技术栈微服务集群的"治理层网关"；入口层（TLS、防 CC、静态资源）
  交给 Nginx/SLB，两层配合。
- **最大的坑**：WebFlux 不能混入 Servlet 组件/阻塞代码；namespace 与服务名对不上会 503；
  路由前缀约定（StripPrefix）要团队统一。
- **必做的商用动作**：集群部署 + 前置 LB、网关鉴权 + 下游内网隔离、生产开 LoadBalancer
  缓存与健康检查、traceId 全链路透传、超时全链路对齐、非幂等接口禁开重试。
