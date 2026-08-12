# RocketMQ 环境接入与商用部署

> 本文讲「RocketMQ 服务端怎么搭、应用怎么连、商用环境怎么部署」，偏 **环境与基础设施** 层。
> 应用层的幂等 / 重试 / 死信 / 事务消息等业务实践，见 [RocketMQ 使用与商用方案](RocketMQ使用与商用方案.md)。

---

## 一、核心概念：两层配置，各管各的

很多人会混淆「配 mq 地址」和「把 mq 跑起来」是两件事，先理清：

| | 基础设施层（起服务端） | 应用层（连服务端） |
|---|---|---|
| **管什么** | 把 RocketMQ 进程跑起来 | 让 Spring Boot 应用连上它 |
| **本项目载体** | `~/dockerfiles/rocketmq/`（docker compose） | 各服务的 `application.yml` |
| **类比** | 安装并启动 MySQL 服务端 | 应用配 `spring.datasource.url` 连 MySQL |

```
~/dockerfiles/rocketmq/              ← 起 mq 服务端（基础设施，多项目共享）
├── docker-compose.yml
└── broker.conf
        ↓ 启动后监听 宿主机 9876 / 10911 端口
        ↓
service-*/application.yml            ← 应用连上去（应用层，随项目走）
        rocketmq.name-server: 127.0.0.1:9876
```

> 两层缺一不可：yaml 配得再对，没有服务端进程监听 9876，连接直接被拒。

---

## 二、本地接入方式

### 2.1 应用侧配置（已就绪，无需改动）

**service-order**（生产者 + 事务消息发送方）`application.yml`：
```yaml
rocketmq:
  name-server: 127.0.0.1:9876
  producer:
    group: order-producer-group
```

**service-product**（消费者）`application.yml`：
```yaml
rocketmq:
  name-server: 127.0.0.1:9876
```

> 服务跑在 IDE（宿主机）里，所以 `name-server` 用 `127.0.0.1`——因为 RocketMQ 的 9876 端口已映射到宿主机。

### 2.2 Topic / Group 一览

| Topic | 生产者 | 消费者组 | 用途 |
|-------|--------|---------|------|
| `order-topic` | OrderController `/test-mq` | `product-consumer-group` | 基础联调测试 |
| `transfer_topic` | OrderServiceImpl.createOrderV3（事务消息） | `transfer_consumer_group` | V3 扣库存主链路 |
| `%DLQ%transfer_consumer_group` | 系统（重试耗尽自动投递） | `transfer_dlq_consumer_group` | 死信兜底 |

### 2.3 RocketMQ 服务端启动

服务端用 docker compose 起在项目外（多项目共享），位置 `~/dockerfiles/rocketmq/`：

```bash
cd ~/dockerfiles/rocketmq
docker compose up -d        # 启动
docker compose ps           # 查看状态
docker compose down         # 停止（保留消息数据）
docker compose down -v      # 停止并清空 broker 数据
```

启动后也可直接在 **Docker Desktop** 图形界面里对 `rocketmq` 这个 compose 项目做启停。

**三个容器职责**：

| 容器 | 作用 | 是否必需 |
|------|------|---------|
| `rmqnamesrv` | NameServer，服务发现，broker/客户端靠它找彼此 | ✅ 必须 |
| `rmqbroker` | Broker，消息存储转发的核心 | ✅ 必须 |
| `rocketmq-dashboard` | Web 管理台，看 topic/消息/消费进度/死信 | ❌ 可选（强烈建议留） |

**端口表**：

| 服务 | 宿主机端口 | 容器内端口 | 用途 |
|------|-----------|-----------|------|
| NameServer | 9876 | 9876 | 客户端连接、服务发现 |
| Broker | 10911 | 10911 | 收发消息（starter 走此端口） |
| Broker | 10909 / 10912 | 同左 | VIP channel / HA |
| Dashboard | 8082 | 8082 | Web 控制台 http://localhost:8082（⚠️ 端口不可改：前端 JS 硬编码 localhost:8082） |

> broker proxy 的 8080/8081 **不映射到宿主机**：本项目 starter 走 remoting 直连 10911，用不到 proxy 端口；且 8080 要让给 nacos 控制台。proxy 进程仍在容器内运行，不影响收发消息。

### 2.4 完整启动顺序

跑通一条 MQ 链路，依赖启动顺序如下：

```
1. 中间件：MySQL、Redis、Nacos、RocketMQ      ← ~/dockerfiles/* 或 docker desktop 启动
2. 初始化：执行 sql/ 下建表脚本
3. 应用：  service-order、service-product      ← IDE 启动
4. 验证：  调 POST /test-mq，打开 dashboard 看 order-topic
```

### 2.5 验证

1. `docker compose ps` 三个服务都 `Up`
2. 浏览器开 http://localhost:8082 → Cluster 菜单看到 `broker-a`（⚠️ dashboard 端口不可改，前端硬编码 8082）
3. 启 service-order，调 `POST /test-mq` → dashboard 的 `order-topic` 出现消息，service-product 日志输出消费记录

---

## 三、brokerIP1 与「IP 变了怎么办」

这是本地单机部署最容易踩的坑，单独说明。

### 3.1 原理

客户端连 NameServer 拿到的不是「NameServer 地址」，而是 **Broker 的地址**（来自 broker.conf 的 `brokerIP1`），然后客户端再去连这个地址收发消息。

```
应用 --(1) 连 NameServer 9876--> 拿到 broker 地址 = brokerIP1
应用 --(2) 连 brokerIP1:10911--> 真正收发消息
```

所以 `brokerIP1` 必须是**应用能访问到 Broker 的地址**。本项目应用跑在宿主机，因此 `brokerIP1 = 宿主机局域网 IP`。

### 3.2 IP 变了的现象与处理

换 WiFi / 局域网 IP 变更后，`brokerIP1` 失效，应用报 `connect to <旧IP>:10911 failed`。

处理（一行）：
1. 查当前 IP：`ipconfig getifaddr en0`
2. 改 `~/dockerfiles/rocketmq/broker.conf` 里 `brokerIP1=新IP`
3. Docker Desktop 里重启 `rmqbroker`（或 `docker compose restart rmqbroker`）

> 这是本地单机的固有局限。商用环境用固定内网 VIP / SLB / 域名，不存在此问题。

---

## 四、多项目共享这套 mq

把 compose 放在 `~/dockerfiles/rocketmq`（项目外）的目的：多个本地项目共用一套中间件，省资源、一次启动全局可用。

```
~/dockerfiles/
├── rocketmq/      ← 本项目 + 其他项目共用
├── nacos/
├── redis/
└── mysql/
```

**其他项目接入**：`application.yml` 配同样的 name-server 即可（mq 始终监听宿主机 9876）：
```yaml
rocketmq:
  name-server: 127.0.0.1:9876
```

**⚠️ 必须做 topic / group 命名隔离**，否则多项目消息会串：
- 本项目用 `transfer_topic`、`order-topic`
- 其他项目加前缀，如 `projb-order-topic`

> 商用环境更彻底的隔离是用 RocketMQ 5.x 的 **namespace** 机制，给每个项目/租户分配独立命名空间。

---

## 五、商用部署方式

本地这套是「能跑就行」的单机玩法，**不能直接上生产**。商用部署的核心目标：**高可用、数据不丢、可监控、可扩展**。

### 5.1 本地 vs 商用 对比

| 维度 | 本地（本项目） | 商用生产 |
|------|--------------|---------|
| NameServer | 1 个 | **≥3 节点集群**，跨可用区 |
| Broker | 1 个（单点） | **主从 / DLedger 多副本**，主挂自动切换 |
| Proxy | 直连 broker | 5.x 独立无状态 **Proxy 层**，水平扩展 |
| brokerIP1 | 会变的局域网 IP | **固定内网 VIP / SLB / 域名** |
| 存储 | 容器临时卷 | 高性能 **SSD + 持久卷**，容量规划 |
| Topic 创建 | 自动创建（学习方便） | **关闭自动创建**，运维统一管控 |
| 鉴权 | 无 | **ACL**（accessKey/secretKey） |
| 监控 | dashboard 看 | **Prometheus + Grafana + rocketmq-exporter** + 告警 |
| 部署形态 | 单机 docker-compose | **K8s Operator** 或 **云托管** |

### 5.2 商用部署架构

```
                    ┌─────────────────────────┐
   Producer/Consumer│      Proxy 层(5.x)      │  ← 无状态，水平扩展，统一鉴权/限流
   ────────────────►│  proxy-1  proxy-2  ...  │
                    └────────────┬────────────┘
                                 │
                    ┌────────────▼────────────┐
                    │     Broker 高可用集群     │
                    │  broker-a(M) broker-a(S) │  ← 同一副本组主从，主挂自动切
                    │  broker-b(M) broker-b(S) │  ← 不同副本组分担流量（分片）
                    └────────────┬────────────┘
                                 │ 注册
                    ┌────────────▼────────────┐
                    │   NameServer 集群(≥3)    │  ← 节点间独立、互不同步
                    └─────────────────────────┘
```

**关键组件**：

- **NameServer 集群**：节点间独立、不同步，客户端连任一节点即可拿到全量路由。建议 ≥3 节点跨可用区部署。
- **Broker 高可用**：
  - RocketMQ 4.x：主从复制 + **DLedger**（基于 Raft 的自动选主）
  - RocketMQ 5.x：**Controller 模式**，支持副本组内自动主从切换
  - 多副本组分片（broker-a / broker-b …）横向扩容、分担流量
- **Proxy 层（5.x）**：无状态，客户端只连 Proxy，不再直连 broker；天然支持统一鉴权、限流、流量隔离。
- **存储**：commitlog / consumequeue 分离，高性能 SSD，按消息量做容量规划。

### 5.3 部署形态选型

| 形态 | 适用 | 优点 | 缺点 |
|------|------|------|------|
| **物理机 / 虚机裸部署** | 大厂、有专职运维 | 性能极致、可控性强 | 运维成本高、扩容慢 |
| **K8s + rocketmq-operator** | 已有 K8s 基础设施 | 声明式部署、StatefulSet + PV、易扩缩容 | 有一定 K8s 门槛 |
| **云托管服务**（推荐中小公司） | 中小团队、无专职 MQ 运维 | 高可用+监控+告警全包、按量付费、免运维 | 厂商锁定、长期成本需评估 |

> 中小公司首选**云托管**（阿里云 RocketMQ / 腾讯云 TDMQ / AWS），把高可用和运维交给云厂商，团队聚焦业务。极少有人在生产用单机 docker-compose。

### 5.4 应用接入在商用环境的变化

```yaml
rocketmq:
  # 1. name-server 配多个地址（集群），用 Nacos 配置中心统一管理
  name-server: ${ROCKETMQ_NAMESERVER:namesrv-1:9876;namesrv-2:9876;namesrv-3:9876}
  producer:
    group: order-producer-group
    # 2. 开启 ACL 鉴权
    access-key: ${ROCKETMQ_AK}
    secret-key: ${ROCKETMQ_SK}
  # 3. 5.x 接 Proxy：用 proxy 地址替代直连 broker
```

变化点：
1. **name-server 多地址**：分号分隔，任一可用即可，容灾
2. **配置中心化**：地址/密钥放 Nacos 或环境变量，不硬编码
3. **ACL 鉴权**：accessKey/secretKey，防止误连/越权
4. **namespace 隔离**：按项目/环境（dev/test/prod）隔离路由
5. **关闭自动建 topic**：topic 由运维统一创建，应用只能用已分配的

### 5.5 监控（必须接入）

| 指标 | 告警阈值 |
|------|---------|
| 消费 TPS | 突降 50% |
| 消费延迟（积压量） | > 10000 条 |
| 重试队列 `%RETRY%{group}` | > 1000 条 |
| 死信队列 `%DLQ%{group}` | > 0 即告警 |
| 消费失败率 | > 1% |

技术栈：`rocketmq-exporter` 采集 → Prometheus 存储 → Grafana 展示 → AlertManager 告警。

> 应用层还需配套：消费幂等、失败重试、死信兜底、定时对账——这些本项目已实现，详见 [RocketMQ 使用与商用方案](RocketMQ使用与商用方案.md) 第七节。

---

## 六、踩坑记录（本地环境修复实录）

本次搭建过程踩到的问题，作为排查参考：

| 现象 | 根因 | 规避 |
|------|------|------|
| broker 启动后客户端报 `connect to x.x.x.x:10911 failed` | `brokerIP1` 是失效的旧局域网 IP，客户端拿到后连不上 | brokerIP1 必须是应用侧可达地址；IP 变了及时改 |
| dashboard 打不开 / 看不到集群 | dashboard 与 broker 跨 docker 网络，且用宿主机 IP 连 NameServer | dashboard 与 broker/namesrv 放同一网络，用服务名通信 |
| broker 加载不到自定义配置 | `-c` 指向的配置路径在镜像里不存在（旧版 `rocketmq-latest` 软链接新版已移除） | 固定镜像版本，挂载路径用镜像内真实路径 `rocketmq-5.3.3/conf/` |
| 重启 Docker 后中间件没自动起 | 容器重启策略为 `no` | compose 里设 `restart: unless-stopped` |
| 单文件挂载改了不生效 | 编辑器保存是「替换文件」，会破坏 docker 单文件 bind mount | 挂载目录或改完后重启容器；本项目 broker.conf 不频繁改，单文件挂载够用 |

---

## 七、参考文档

- [RocketMQ 官方文档](https://rocketmq.apache.org/zh/docs/)
- [rocketmq-spring-boot-starter](https://github.com/apache/rocketmq-spring)
- [RocketMQ 架构设计](https://rocketmq.apache.org/zh/docs/introduction/02architecture)
- 本项目应用层实践：[RocketMQ 使用与商用方案](RocketMQ使用与商用方案.md)
