# SpringCloudStudy

## 项目简介

这是一个基于Spring Cloud的分布式电商系统示例项目，主要演示了在分布式系统中如何处理订单创建和库存扣减的一致性问题。项目通过Spring Cloud微服务架构，结合RocketMQ消息队列实现分布式事务，确保订单服务和商品服务的数据一致性。

## 系统架构

项目采用微服务架构，主要包括以下两个服务：

1. **service-order（订单服务）**
2. **service-product（商品服务）**

### 技术栈

- Spring Boot 3.x
- Spring Cloud
- MyBatis Plus
- RocketMQ（消息队列）
- Redisson（分布式锁）
- Nacos（服务注册与发现）
- OpenFeign（服务间调用）
- MySQL（数据存储）

## 功能模块

### 1. 商品服务（service-product）

- 提供商品信息查询接口
- 实现库存扣减功能
- 使用Redisson实现分布式锁，防止并发场景下库存超卖

### 2. 订单服务（service-order）

- 提供订单创建接口
- 通过OpenFeign远程调用商品服务
- 实现三种不同的分布式事务处理方案：
  1. 基于本地消息表的最终一致性方案
  2. 基于RocketMQ事务消息的最终一致性方案
  3. 基于可靠消息服务的最终一致性方案

## 核心特性

### 分布式事务处理

项目实现了多种分布式事务解决方案：

1. **本地消息表方案**（createOrderV2接口）
   - 在订单数据库中创建本地消息表
   - 订单创建成功后，将消息写入本地消息表
   - 通过定时任务确保消息可靠发送到RocketMQ

2. **RocketMQ事务消息方案**（createOrderV3接口）
   - 利用RocketMQ的事务消息机制
   - 通过本地事务监听器确保订单创建和消息发送的一致性
   - 支持事务状态回查机制

### 并发控制

- 使用Redisson分布式锁防止商品库存超卖
- 在高并发场景下保证数据一致性

### 服务治理

- 基于Nacos实现服务注册与发现
- 使用OpenFeign实现服务间通信
- 提供Feign客户端降级处理

## 数据库设计

### 商品表（product）
- 存储商品信息和库存
- 包含商品名称、价格、库存等字段

### 订单表（order）
- 存储订单信息
- 包含订单编号、用户ID、商品信息、订单状态等字段

### 本地消息表（local_message）
- 存储待发送的消息
- 用于实现基于本地消息表的分布式事务

## 接口说明

### 订单服务接口

1. `GET /create` - 创建订单（同步调用）
2. `GET /createV2` - 创建订单（本地消息表方案）
3. `GET /createV3` - 创建订单（RocketMQ事务消息方案）
4. `GET /getOrderList` - 获取订单列表
5. `GET /test-mq` - 测试RocketMQ消息发送

### 商品服务接口

1. `GET /getProductList` - 获取商品列表
2. `GET /product/{id}` - 获取商品详情并扣减库存

## 部署说明

1. 启动Nacos服务注册中心
2. 启动RocketMQ消息队列
3. 启动Redis服务
4. 创建MySQL数据库并执行sql目录下的脚本
5. 分别启动service-order和service-product服务

## 学习价值

本项目适合学习以下技术点：
- Spring Cloud微服务架构实践
- 分布式事务的多种解决方案
- RocketMQ在分布式系统中的应用
- Redisson分布式锁的使用
- 微服务间通信机制（OpenFeign）
- 服务注册与发现（Nacos）
