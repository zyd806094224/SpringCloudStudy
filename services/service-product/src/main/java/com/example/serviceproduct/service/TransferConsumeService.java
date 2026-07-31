package com.example.serviceproduct.service;

/**
 * transfer_topic 消费处理：扣减库存 + 幂等控制。
 * 被 TransferMessageListener 和死信定时重试任务共同复用。
 */
public interface TransferConsumeService {

    /**
     * 处理一条扣减库存消息（幂等）。
     *
     * @param txId      事务ID，用于幂等去重
     * @param productId 商品ID
     * @return 处理结果
     */
    ConsumeResult deductStock(String txId, Long productId);
}
