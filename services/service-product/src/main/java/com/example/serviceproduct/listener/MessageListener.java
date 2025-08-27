package com.example.serviceproduct.listener;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RocketMQMessageListener(topic = "order-topic", consumerGroup = "product-consumer-group")
public class MessageListener implements RocketMQListener<String> {

    @Override
    public void onMessage(String message) {
        log.info("Received message: {}", message);
        // 在这里添加处理消息的业务逻辑，例如更新商品库存
    }
}
