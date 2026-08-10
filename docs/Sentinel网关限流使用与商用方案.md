# Sentinel 网关限流使用与商用方案

> 本文档沉淀本项目 Spring Cloud Gateway 接入 Sentinel 实现限流的方式，以及生产/商用环境下的最佳实践。
> 代码示例均可在 `gateway` 模块中找到对应实现。

---

## 一、本项目中的使用方式

### 1.1 整体链路

项目在网关层接入 Sentinel，对所有经过 gateway 的请求做统一限流。

```
客户端
  │
  ▼
┌──────────────────────────────────────┐
│  Gateway :10000                       │
│  ┌──────────────────────────────┐    │
│  │ SentinelGatewayFilter (全局) │ ◄── 拦截每个请求，按路由做流控判定
│  └──────────────┬───────────────┘    │
│                 │ 通过                  │
│                 ▼                      │
│  路由: /product/** ─► service-product │
│  路由: /order/**   ─► service-order   │
└──────────────────────────────────────┘
        │ 限流命中
        ▼
┌──────────────────────────────────────┐
│  GatewayCallbackManager.setBlockHandler │
│  返回 {"code":429,"msg":"请求过于频繁"} │
└──────────────────────────────────────┘

规则来源：Sentinel Dashboard (127.0.0.1:8858) 动态下发
```

### 1.2 关键类对照

| 角色 | 类 / 配置 | 位置 |
|------|----------|------|
| 核心限流过滤器 | `SentinelGatewayFilter` (声明为 @Bean) | `gateway/.../config/GatewayConfiguration.java` |
| 限流异常处理 | `SentinelGatewayBlockExceptionHandler` | `gateway/.../config/GatewayConfiguration.java` |
| 自定义限流响应 | `GatewayCallbackManager.setBlockHandler` | `gateway/.../config/GatewayConfiguration.java` |
| Sentinel 连接配置 | `spring.cloud.sentinel.*` | `gateway/src/main/resources/application.yml` |
| Sentinel Dashboard | Docker 容器 `sentinel-dashboard` | `bladex/sentinel-dashboard:1.8.8` |

### 1.3 一个踩过的大坑（重点）

**SCA 2023.x 的 `spring-cloud-starter-alibaba-sentinel` 不再传递 Gateway 适配器。**

只引入 starter 会发现：Dashboard 左侧能出现 gateway 应用，但**实时监控永远是空白**，因为请求经过网关时没有被识别成 Sentinel 资源。

必须手动做两件事：

1. **额外引入适配器依赖**：
```xml
<dependency>
    <groupId>com.alibaba.csp</groupId>
    <artifactId>sentinel-spring-cloud-gateway-adapter</artifactId>
    <version>1.8.8</version>
</dependency>
```

2. **手动注册 SentinelGatewayFilter 为 Bean**（SCA 2023.x 不会自动装配它）：
```java
@Bean
@Order(-1000)
public SentinelGatewayFilter sentinelGatewayFilter() {
    return new SentinelGatewayFilter();
}
```

> 这是 2023.x 相对老版本最大的变化，很多教程仍按旧版本讲，照抄会踩坑。

---

## 二、限流原理

### 2.1 Sentinel 的"资源"模型

Sentinel 把流量按**资源**管理，不是按单个 URL：

```
你以为的监控（APM 风格）：
  GET /product/getProductList  200  120ms
  GET /product/getProductList  200  98ms

Sentinel 实际看到的（资源聚合）：
  资源 service-product:  通过 15 QPS, 平均 150ms, 拒绝 2
  资源 service-order:    通过 5 QPS, 平均 800ms, 拒绝 0
```

在网关模式下，**一条路由 = 一个资源**，资源名 = 路由 id（`service-product` / `service-order`）。
`/product/getProductList` 和 `/product/{id}` 归到同一个资源 `service-product` 下统计。

### 2.2 限流判定流程

```
请求进入 Gateway
  │
  ▼
SentinelGatewayFilter 拦截
  │
  ├─ 1. 识别资源：根据匹配的路由，资源名 = service-product
  │
  ├─ 2. 查找规则：该资源上挂了哪些流控规则？
  │     （规则来自 Dashboard 动态下发，存在客户端内存）
  │
  ├─ 3. 统计判定：滑动窗口统计当前 QPS，是否超过阈值？
  │
  ├─ 未超限 ─► 放行，请求转发到后端服务
  │
  └─ 超限 ─► 抛出 BlockException
              │
              ▼
        SentinelGatewayBlockExceptionHandler 捕获
              │
              ▼
        GatewayCallbackManager.setBlockHandler 生成响应
              │
              ▼
        返回 {"code":429,"msg":"请求过于频繁，请稍后再试"}
```

### 2.3 Sentinel 不是 APM

| 需求 | 该用什么 |
|------|---------|
| 每个请求的 URL、状态码、耗时 | Gateway AccessLog / 项目里的 LoggingFilter |
| 接口级 QPS/RT 聚合监控 | Sentinel（本项目） |
| 分布式链路追踪 | SkyWalking / Zipkin / OpenTelemetry |
| 请求日志搜索 | ELK |

Sentinel 的职责是**流量治理**（限流、熔断、统计聚合），不是请求明细记录。

---

## 三、配置规则

### 3.1 两种菜单的区别

Dashboard 里有两个容易混淆的菜单：

| 菜单 | 适用 | 字段 | 网关能用吗 |
|------|------|------|-----------|
| **流控规则**（通用） | 所有 Sentinel 资源 | 资源名/针对来源/阈值类型/单机阈值/是否集群 | ✅ 能，资源名填路由 id |
| **网关流控规则**（专用） | 专门针对网关 | API名称/针对请求属性/阈值类型/阈值 | ✅ 能，可按 API 分组、请求头限流 |

入门用"流控规则"菜单就够了，**资源名必须严格填路由 id**（如 `service-product`），填错（比如填 `product-api`）规则不会生效。

### 3.2 配置一条限流规则

菜单：**流控规则** → 新增

| 字段 | 填什么 | 说明 |
|------|--------|------|
| 资源名 | `service-product` | 必须是路由 id，可在"簇点链路"菜单核对 |
| 针对来源 | `default` | 对所有调用方生效 |
| 阈值类型 | QPS | 按每秒请求数限 |
| 单机阈值 | `1` | 故意设小，方便测出限流 |
| 是否集群 | 否 | 单机模式 |

### 3.3 验证

```bash
for i in 1 2 3 4 5; do
  printf "第 $i 次: "
  curl -s -o /dev/null -w "状态码 %{http_code}\n" http://localhost:10000/product/getProductList
done
```

预期：第 1 个 `200`，后面 `429`。Dashboard "实时监控"能看到拒绝 QPS 曲线。

### 3.4 懒加载特性

Sentinel 默认**懒加载**：应用启动后不会立刻出现在 Dashboard，必须**先发一次请求**，客户端才会初始化该资源的统计并向 Dashboard 注册。

配置里已加 `spring.cloud.sentinel.eager=true`，但保险起见，启动后仍建议先发一个请求触发资源初始化。

---

## 四、商用方案

### 4.1 当前方案的局限

本项目规则存在 **Dashboard 内存**里，存在两个问题：

1. **重启 Dashboard 后规则丢失**
2. **重启 gateway 后规则也丢失**（客户端拉不到规则）

商用环境必须解决"规则持久化"。

### 4.2 规则持久化：Nacos 数据源（推荐）

让规则存到 Nacos，Dashboard 改规则时推送到 Nacos，gateway 监听变化自动加载：

```
Dashboard ──保存规则──► Nacos ──配置变更推送──► gateway (自动加载)
                                            │
                                            └─ 重启后从 Nacos 拉取，规则不丢
```

配置方式（gateway 加依赖 + 配置）：

```xml
<!-- gateway/pom.xml -->
<dependency>
    <groupId>com.alibaba.csp</groupId>
    <artifactId>sentinel-datasource-nacos</artifactId>
    <version>1.8.8</version>
</dependency>
```

```yaml
# application.yml
spring:
  cloud:
    sentinel:
      datasource:
        # 数据源名，随意起
        ds1:
          nacos:
            server-addr: 127.0.0.1:8848
            data-id: ${spring.application.name}-sentinel-rules.json
            group-id: SENTINEL_GROUP
            data-type: json
            rule-type: gw-flow          # 网关流控规则类型
```

然后在 Nacos 创建配置 `gateway-sentinel-rules.json`（`SENTINEL_GROUP` 组），内容是规则 JSON 数组。Dashboard 也要改成 Nacos 数据源模式（需要重新打包 Dashboard，配置 `nacos` 作为 rule repository）。

> 这是商用标准做法，但有一定配置成本，本项目作为学习用途暂未接入。

### 4.3 网关限流的典型规则设计

| 场景 | 规则设计 |
|------|---------|
| 防恶意刷接口 | 按 IP 限流，QPS 上限设正常用户达不到的值 |
| 保护核心接口 | 秒杀类接口单独限流，阈值设系统能承受的 QPS |
| 突发流量削峰 | 令牌桶（warmUp 冷启动），避免瞬时流量打垮系统 |
| 多租户隔离 | 按调用方（limitApp）分别设阈值，VIP 客户配额更高 |

### 4.4 Sentinel vs 其他限流方案对比

| 维度 | Gateway+Redis 令牌桶 | Sentinel | Nginx+Lua | 云厂商(SLB/APISIX) |
|------|---------------------|----------|-----------|------------------|
| 上手成本 | 极低 | 中 | 高 | 低（付费） |
| 动态规则 | ❌ 改配置要重启 | ✅ Dashboard 实时下发 | ✅ | ✅ |
| 可视化监控 | ❌ | ✅ Dashboard | 需自建 | ✅ |
| 复杂限流策略 | ❌ | ✅ 热点参数/关联/链路 | ✅ | ✅ |
| 熔断降级 | ❌ | ✅ 一体化 | 需另配 | ✅ |
| 规则持久化 | 配置文件 | Nacos 数据源 | etcd/consul | 平台托管 |
| 适合规模 | 中小 | 中大 | 大 | 大 |

**国内中大型项目常见组合**：Nginx/网关粗粒度限流（防 CC）+ Sentinel 业务细粒度限流（按接口/用户）。

---

## 五、环境与启动

### 5.1 依赖版本

| 组件 | 版本 |
|------|------|
| Spring Cloud | 2023.0.3 |
| Spring Cloud Alibaba | 2023.0.3.2 |
| Sentinel（客户端） | 1.8.8（由 SCA 传递） |
| Sentinel Dashboard | 1.8.8（Docker: `bladex/sentinel-dashboard:1.8.8`） |

### 5.2 启动 Sentinel Dashboard

```bash
docker run -d --name sentinel-dashboard -p 8858:8858 \
  bladex/sentinel-dashboard:1.8.8
```

访问 http://localhost:8858，账号密码 `sentinel` / `sentinel`。

### 5.3 完整启动顺序

1. 启动 Nacos（8848）
2. 启动 service-order（8000）、service-product（9000）—— 注册到 Nacos
3. 启动 Sentinel Dashboard（8858）
4. 启动 gateway（10000）
5. 发一次请求触发 Sentinel 资源初始化：
   ```bash
   curl http://localhost:10000/product/getProductList
   ```
6. 打开 Dashboard，左侧出现 `gateway` 应用，配置流控规则

### 5.4 端口占用说明

Sentinel 客户端默认通信端口 8719，被占用会自动 +1（8720、8721...）。
本项目 service-product 先启动会占用 8719，gateway 会自动用 8720。
**这不影响 Dashboard 连接**——客户端启动时会把自己的端口上报给 Dashboard。
