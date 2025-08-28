package com.example.serviceorder.utils;

import com.example.serviceorder.dao.OrderEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public class OrderUtils {

    /**
     * 生成订单
     * @param productId
     * @param productName
     * @param userId
     * @return
     */
    public static OrderEntity generateOrderEntity(Long productId, String productName, Long userId) {
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setUserId(userId);
        orderEntity.setProductId(productId);
        orderEntity.setOrderNo(UUID.randomUUID().toString());
        orderEntity.setProductName(productName);
        orderEntity.setProductPrice(new BigDecimal("8999.00"));
        orderEntity.setOrderAmount(new BigDecimal("8999.00"));
        orderEntity.setPayAmount(new BigDecimal("8999.00"));
        orderEntity.setPayType(1);
        orderEntity.setOrderStatus(1);
        orderEntity.setCreateTime(LocalDateTime.now());
        orderEntity.setPayTime(LocalDateTime.now());
        orderEntity.setUpdateTime(LocalDateTime.now());
        return orderEntity;
    }

}
