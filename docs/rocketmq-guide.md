# RocketMQ 使用与商用方案

> 本文档沉淀本项目 RocketMQ 的使用方式，以及生产/商用环境下的最佳实践。
> 代码示例均可在 `services/service-product`、`services/service-order` 中找到对应实现。

---

## 一、本项目中的使用方式

### 1.1 整体链路

项目通过 RocketMQ 实现「订单服务 → 商品服务」的分布式事务最终一致性。

```
service-order                              service-product
┌────────────────────┐    transfer_topic    ┌──────────────────────────┐
│ createOrderV3()    │ ───────────────────► │ TransferMessageListener  │
│ sendMessageIn...   │  事务消息(半消息)     │   └─ 扣减库存(Redis幂等) │
│   ├ 半消息         │                       │                          │
│   ├ 本地事务       │ ◄─ 事务回查 ────────  │                          │
│   └ COMMIT/ROLLBACK│                       │                          │
└────────────────────┘                       └──────────────────────────┘
       │ 失败重试耗尽                                          │
       ▼                                                       ▼
 %DLQ%transfer_consumer_group ──► TransferDlqListener(落库+告警)
                                       │
                                       ▼ status=0
                               DeadLetterRetryTask(定时重试)
```

### 1.2 三个演进版本

项目里订单服务有三个版本，体现了 MQ 接入的演进：

| 版本 | 接口 | 方案 | 说明 |
|------|------|------|------|
| V1 | `GET /create` | 同步调用 | Feign 直连商品服务，无 MQ |
| V2 | `GET /createV2` | 本地消息表 | 本地消息表 + 定时任务补偿发送 |
| V3 | `GET /createV3` | RocketMQ 事务消息 | 半消息 + 本地事务 + 事务回查 |

### 1.3 关键类对照

| 角色 | 类 | 所在服务 |
|------|----|----|
| 事务消息发送 | `OrderServiceImpl.createOrderV3()` | service-order |
| 本地事务 + 回查 | `TransferTransactionListener` | service-order |
| 普通消费（扣库存） | `TransferMessageListener` | service-product |
| 扣库存 + 幂等核心 | `TransferConsumeService` | service-product |
| 死信监听（落库+告警） | `TransferDlqListener` | service-product |
| 死信定时重试 | `DeadLetterRetryTask` | service-product |
| 钉钉告警 | `AlarmService` / `DingTalkAlarmServiceImpl` | service-product |

---

## 二、事务消息机制（半消息）

这是 V3 的核心，也是商用最常用的可靠消息方案。

### 2.1 半消息工作流程

```
Producer                      Broker                      Consumer
   │                            │                            │
   │ 1. 发送半消息               │                            │
   │ ─────────────────────────► │ (半消息对消费者不可见)       │
   │                            │                            │
   │ 2. 执行本地事务             │                            │
   │ ────────────────────────────┼────────────────────────── ►│
   │                            │                            │
   │ 3. COMMIT / ROLLBACK       │                            │
   │ ─────────────────────────► │                            │
   │                            │ 4. COMMIT → 投递给消费者    │
   │                            │    ROLLBACK → 删除半消息    │
   │                            │ ─────────────────────────► │
   │                            │                            │
   │              5. 超时未收到确认，主动回查                  │
   │ ◄───────────────────────── │                            │
   │ 6. 根据本地事务状态返回     │                            │
   │ ─────────────────────────► │                            │
```

### 2.2 代码实现

**发送方**（`OrderServiceImpl.createOrderV3()`）：
```java
// 发送事务消息（核心：半消息在此产生）
rocketMQTemplate.sendMessageInTransaction("transfer_topic", message, null);
```

**本地事务监听器**（`TransferTransactionListener`）：
```java
@RocketMQTransactionListener
public class TransferTransactionListener implements RocketMQLocalTransactionListener {

    // 1. 半消息发送成功后执行本地事务
    @Override
    @Transactional
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        try {
            // 创建订单 + 写本地消息表（同一事务，原子性）
            ...
            return RocketMQLocalTransactionState.COMMIT;   // 成功 → 投递消息
        } catch (Exception e) {
            return RocketMQLocalTransactionState.UNKNOWN;  // 异常 → 触发回查
        }
    }

    // 2. 事务回查：broker 超时未收到确认时调用
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        // 查本地消息表判断事务到底成功没有
        LocalMessage localMessage = localMessageMapper.selectById(txId);
        if (localMessage.getStatus() == 1) {
            return RocketMQLocalTransactionState.COMMIT;   // 事务成功 → 投递
        } else {
            return RocketMQLocalTransactionState.UNKNOWN;  // 仍不确定 → 继续回查
        }
    }
}
```

### 2.3 三种事务状态

| 状态 | 含义 | Broker 动作 |
|------|------|-------------|
| `COMMIT` | 本地事务成功 | 投递消息给消费者 |
| `ROLLBACK` | 本地事务失败 | 删除半消息，消息丢失 |
| `UNKNOWN` | 不确定（异常/超时） | 触发事务回查（最多 15 次） |

> **⚠️ 关键点**：本地事务监听器里的业务操作（建订单+写消息表）必须和返回 `COMMIT/UNKNOWN` 保证一致性。
> 本项目用 `@Transactional` + 本地消息表实现：事务提交成功才说明订单创建成功。

---

## 三、消费幂等性

### 3.1 为什么需要幂等

RocketMQ 提供 **at-least-once**（至少投递一次）语义，意味着同一条消息**可能被消费多次**：
- 消费者处理成功但 ACK 丢失 → broker 重投
- 消费者抛异常 → 触发重试
- broker 主从切换、Rebalance → 可能重复投递

对于「扣库存」这种非幂等操作，重复消费会导致**库存被多次扣减**，必须做幂等控制。

### 3.2 本项目的 Redis 幂等方案

```java
// TransferConsumeServiceImpl.deductStock()
String idempotentKey = "consume:transfer:" + txId;

// SETNX 抢锁：成功说明首次处理，失败说明已处理过
Boolean firstTime = stringRedisTemplate.opsForValue()
        .setIfAbsent(idempotentKey, "PROCESSING", 24, TimeUnit.HOURS);

if (firstTime == null || !firstTime) {
    return ConsumeResult.ALREADY_PROCESSED;  // 已处理过，跳过
}

try {
    // ... 扣减库存 ...
    if (success) {
        // 成功：标记为 SUCCESS
        redis.opsForValue().set(idempotentKey, "SUCCESS", 24, TimeUnit.HOURS);
        return ConsumeResult.SUCCESS;
    } else {
        // 失败：删除 key，让 MQ 重试时能重新处理
        redis.delete(idempotentKey);
        return ConsumeResult.BUSINESS_FAILED;
    }
} catch (Exception e) {
    redis.delete(idempotentKey);  // 异常：释放锁，让重试可重新处理
    throw e;
}
```

**幂等 key 设计**：用业务唯一标识 `txId`（事务消息生成时产生的 UUID）。

**三种状态流转**：
- `PROCESSING`：处理中（SETNX 成功后）
- `SUCCESS`：处理成功（扣减成功后更新）
- key 不存在：未处理 或 处理失败后释放

### 3.3 常见幂等方案对比

| 方案 | 实现难度 | 性能 | 可靠性 | 适用场景 |
|------|---------|------|--------|---------|
| **Redis SETNX**（本项目） | 低 | 高 | 中（Redis 宕机可能丢） | 大多数互联网场景 |
| **数据库唯一索引** | 中 | 中 | 高 | 对可靠性要求高、并发不极高 |
| **Redis + DB 双保险** | 高 | 高 | 极高 | 金融、资金类场景 |
| **状态机校验** | 中 | 高 | 高 | 有明确状态流转的业务 |

---

## 四、消费失败重试机制

### 4.1 重试配置

```java
@RocketMQMessageListener(
    topic = "transfer_topic",
    consumerGroup = "transfer_consumer_group",
    maxReconsumeTimes = 3  // 失败重试 3 次后进入死信队列
)
```

### 4.2 触发重试的条件

**核心：消费方抛异常 → MQ 判定消费失败 → 自动重试。**

```java
@Override
public void onMessage(String message) {
    try {
        ConsumeResult result = transferConsumeService.deductStock(txId, productId);
        if (result == ConsumeResult.BUSINESS_FAILED) {
            throw new RuntimeException("处理消息失败");  // 抛异常触发重试
        }
    } catch (Exception e) {
        throw new RuntimeException("处理消息异常", e);  // 抛异常触发重试
    }
}
```

### 4.3 默认重试时间间隔（共 16 次默认）

```
10s → 30s → 1m → 2m → 3m → 4m → 5m → 6m → 7m → 8m → 9m → 10m → 20m → 30m → 1h → 2h
```

> **建议**：商用环境通常不会用默认 16 次（耗时 4 个多小时）。本项目设为 3 次，失败后尽快进死信人工介入。

---

## 五、死信队列（DLQ）处理

### 5.1 什么是死信队列

消息重试达到 `maxReconsumeTimes` 仍然失败后，RocketMQ 会将其投递到**死信队列**。

- **死信 topic 命名规则**：`%DLQ%{消费者组}`
  - 本项目：`%DLQ%transfer_consumer_group`
- **特征**：默认沉睡，堆积在那里，**不会有消费者自动消费**，必须主动订阅才能处理。

### 5.2 本项目的死信处理闭环

```
消息重试 3 次失败
      │
      ▼
%DLQ%transfer_consumer_group
      │
      ▼ TransferDlqListener 消费
┌─────────────────────────────────────┐
│ 1. 解析 txId                        │
│ 2. 落库 dead_letter_message 表      │
│ 3. log.error 告警日志               │
│ 4. 钉钉告警推送                     │
└─────────────────────────────────────┘
      │
      ▼ status=0
DeadLetterRetryTask（每 2 分钟扫描）
      │
      ├─ 重试成功 → status=1（销账）
      ├─ 业务失败 → retryCount+1，达 5 次后 status=2（升级人工）
      └─ 异常 → retryCount+1，达 5 次后 status=2（升级人工+告警）
```

### 5.3 死信状态机

| status | 含义 | 说明 |
|--------|------|------|
| `0` | 待处理 | 刚落库，等待定时任务重试 |
| `1` | 自动重试成功 | 已销账 |
| `2` | 升级人工处理 | 自动重试耗尽，需人工介入 |

`retry_count` 字段记录已自动重试次数，上限 `MAX_RETRY=5`。

### 5.4 死信落库表结构

```sql
CREATE TABLE `dead_letter_message` (
  `id`              bigint        NOT NULL AUTO_INCREMENT,
  `tx_id`           varchar(64)   DEFAULT NULL       COMMENT '事务ID',
  `topic`           varchar(128)  DEFAULT NULL       COMMENT '原始topic',
  `consumer_group`  varchar(128)  DEFAULT NULL       COMMENT '消费者组',
  `message_content` text                              COMMENT '原始消息内容',
  `error_msg`       varchar(1024) DEFAULT NULL       COMMENT '错误信息',
  `status`          tinyint       NOT NULL DEFAULT 0 COMMENT '0-待处理,1-自动成功,2-人工处理',
  `retry_count`     int           NOT NULL DEFAULT 0 COMMENT '已自动重试次数',
  `create_time`     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_tx_id` (`tx_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utfmb4 COMMENT='死信消息表';
```

---

## 六、告警机制

### 6.1 告警触发场景

| 场景 | 触发点 | 级别 |
|------|--------|------|
| 死信消息落库 | `TransferDlqListener` | ERROR |
| 死信自动重试耗尽 | `DeadLetterRetryTask.markAsManual()` | ERROR |

### 6.2 钉钉告警配置

```yaml
alarm:
  ding-talk:
    enabled: false                                                         # 开关
    webhook: https://oapi.dingtalk.com/robot/send?access_token=YOUR_TOKEN  # 机器人地址
    secret: YOUR_SECRET                                                     # 加签密钥
    keyword: "[库存服务]"                                                    # 关键词（需与机器人安全设置一致）
```

### 6.3 设计要点

- **`enabled=false` 默认关闭**：避免开发/测试环境误告警
- **旁路逻辑不抛异常**：告警失败只记日志，绝不影响主业务流程
- **加签安全验证**：防止 webhook 被盗用伪造告警

---

## 七、商用最佳实践

### 7.1 生产环境清单

| 维度 | 要求 | 本项目实现 |
|------|------|-----------|
| **消息可靠投递** | 事务消息/本地消息表 | ✅ V3 事务消息 |
| **消费幂等** | 防重复处理 | ✅ Redis SETNX |
| **失败重试** | 有限次重试 | ✅ maxReconsumeTimes=3 |
| **死信兜底** | 落库 + 告警 | ✅ DLQ 监听器 |
| **自动恢复** | 定时任务重试 | ✅ DeadLetterRetryTask |
| **实时告警** | 异常主动通知 | ✅ 钉钉告警 |
| **最终对账** | 批量校验数据一致性 | ⚠️ 未实现（见 7.4） |

### 7.2 高可用部署建议

**Broker 端**：
- 采用 Dledger 集群（至少 3 节点），自动主从切换
- 生产环境建议 `NameServer` 至少 2 节点
- 开启消息轨迹（messageTrace），便于问题定位

**应用端**：
- ConsumerGroup 名字规范：`{业务域}_{用途}_group`（如 `product_transfer_group`）
- 同一 ConsumerGroup 内消费者应处理相同业务，避免消费逻辑混乱
- 生产环境 `pullBatchSize` 控制单次拉取量，防止 OOM

### 7.3 监控指标（必须接入）

| 指标 | 含义 | 告警阈值 |
|------|------|---------|
| 消费 TPS | 消费吞吐 | 突降 50% |
| 消费延迟 | 消息积压量 | > 10000 条 |
| 重试队列积压 | `%RETRY%{group}` 消息数 | > 1000 条 |
| 死信队列积压 | `%DLQ%{group}` 消息数 | > 0 即告警 |
| 消费失败率 | 失败/总消费 | > 1% |

### 7.4 对账机制（终极兜底）

死信处理得再好也可能有漏网之鱼，生产环境必须有**对账**作为最终保障：

- **定时全量对账**：每天凌晨比对业务数据一致性
  - 本项目：`SELECT COUNT(*) FROM order WHERE create_time = 昨天` vs `库存扣减记录总数`
- **对账结果**：不一致 → 生成差异清单 → 人工或自动补偿
- 这是「信不信任 MQ」的最后一道防线，金融级系统必须做

### 7.5 常见坑与规避

| 坑 | 现象 | 规避 |
|----|------|------|
| **广播模式无死信** | BROADCASTING 模式失败不重试不进死信 | 关键业务用 CLUSTERING 模式 |
| **消费阻塞** | 某条消息处理慢导致后续消息堆积 | 消费逻辑异步化 / 设置合理超时 |
| **DLQ 无限重试** | DLQ 监听器抛异常导致死循环 | DLQ 内部 try-catch 不抛异常（本项目已做） |
| **幂等 key 选错** | 用时间戳/随机数做 key | 用业务唯一 ID（本项目用 txId） |
| **Redis 幂等失效** | Redis 宕机或 key 提前过期 | 关键场景用 Redis+DB 双保险 |
| **事务回查遗漏** | `checkLocalTransaction` 返回 UNKNOWN 导致一直回查 | 回查逻辑要明确，查不到按 ROLLBACK 处理 |

---

## 八、验证与测试

### 8.1 本地环境依赖

1. **MySQL**：执行 `sql/product.sql`、`sql/order.sql`、`sql/dead_letter_message.sql`
2. **Redis**：默认 `127.0.0.1:6379`
3. **RocketMQ**：默认 `127.0.0.1:9876`（NameServer）
4. **Nacos**：默认 `127.0.0.1:8848`

### 8.2 全链路验证步骤

```bash
# 1. 正常流程：调 createV3，观察库存扣减 + Redis 幂等标记
curl http://localhost:9001/createV3
# 期望：product 表 stock-1，Redis 出现 consume:transfer:{txId}=SUCCESS

# 2. 幂等验证：重复投递同一条消息，库存不应再扣
# 期望：日志打印「消息已处理过，幂等跳过」

# 3. 死信验证：把商品库存改成 0，再调 createV3
UPDATE product SET stock = 0 WHERE id = 5;
curl http://localhost:9001/createV3
# 期望：
#   - TransferMessageListener 重试 3 次失败
#   - TransferDlqListener 打印「收到死信消息并已落库」
#   - dead_letter_message 表出现 status=0 记录
#   - DeadLetterRetryTask 每 2 分钟扫描重试

# 4. 恢复验证：把库存补回去
UPDATE product SET stock = 10 WHERE id = id=5;
# 期望：下次重试任务执行时，死信重试成功，status 变为 1
```

### 8.3 钉钉告警验证

将 `application.yml` 中 `alarm.ding-talk.enabled` 改为 `true` 并填入真实 webhook/secret，触发死信后观察钉钉群是否收到告警。

---

## 九、参考文档

- [RocketMQ 官方文档](https://rocketmq.apache.org/zh/docs/)
- [rocketmq-spring-boot-starter](https://github.com/apache/rocketmq-spring)
- [RocketMQ 事务消息最佳实践](https://rocketmq.apache.org/zh/docs/bestPractice/13BESTPRACTICES)
