package com.example.serviceproduct.listener;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.serviceproduct.dao.ProductEntity;
import com.example.serviceproduct.mapper.ProductMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@RocketMQMessageListener(topic = "transfer_topic", consumerGroup = "transfer_consumer_group")
public class TransferMessageListener implements RocketMQListener<String> {

    @Autowired
    private ProductMapper productMapper;

    private ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onMessage(String message) {
        try {
            // 解析消息
            JsonNode jsonNode = objectMapper.readTree(message);

            String txId = jsonNode.get("txId").asText();
            Long productId = jsonNode.get("productId").asLong();
            // 使用 QueryWrapper 构造查询条件
            QueryWrapper<ProductEntity> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("id", productId); // 使用实体类属性名
            ProductEntity productEntity = productMapper.selectOne(queryWrapper);

            boolean success = false;
            if (productEntity != null && productEntity.getStock() > 0) {
                // 扣减库存
                productEntity.setStock(productEntity.getStock() - 1);

                // 更新数据库
                success = productMapper.updateById(productEntity) > 0;
            }
            if (success) {
                log.info("接收消息成功，已为扣减库存");
            } else {
                // 处理失败，会由RocketMQ自动重试
                log.info("处理消息失败，将由RocketMQ自动重试: " + message);
                throw new RuntimeException("处理消息失败");
            }
        } catch (Exception e) {
            log.info("处理消息异常: " + e.getMessage());
            // 抛出异常，让RocketMQ进行重试
            throw new RuntimeException("处理消息异常", e);
        }
    }
}

