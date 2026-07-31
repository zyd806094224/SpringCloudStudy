package com.example.serviceproduct.listener;

import com.example.serviceproduct.service.ConsumeResult;
import com.example.serviceproduct.service.TransferConsumeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@RocketMQMessageListener(
        topic = "transfer_topic",
        consumerGroup = "transfer_consumer_group",
        maxReconsumeTimes = 3 // 失败重试3次后进入死信队列（默认16次，耗时太长）
)
public class TransferMessageListener implements RocketMQListener<String> {

    @Autowired
    private TransferConsumeService transferConsumeService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onMessage(String message) {
        String txId = null;
        try {
            // 解析消息
            JsonNode jsonNode = objectMapper.readTree(message);
            txId = jsonNode.get("txId").asText();
            Long productId = jsonNode.get("productId").asLong();

            // 委托给 Service 执行扣减库存 + 幂等控制
            ConsumeResult result = transferConsumeService.deductStock(txId, productId);

            switch (result) {
                case SUCCESS:
                case ALREADY_PROCESSED:
                    // 成功或已处理过，正常结束（ACK）
                    return;
                case BUSINESS_FAILED:
                    // 业务失败（库存不足等），抛异常触发 MQ 重试，多次失败后进死信
                    log.info("处理消息失败，将由RocketMQ自动重试: " + message);
                    throw new RuntimeException("处理消息失败");
                default:
                    return;
            }
        } catch (Exception e) {
            log.info("处理消息异常: " + e.getMessage());
            // 抛出异常，让RocketMQ进行重试
            throw new RuntimeException("处理消息异常", e);
        }
    }
}
