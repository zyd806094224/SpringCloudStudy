package com.example.serviceorder.service;


import com.baomidou.mybatisplus.extension.service.IService;
import com.example.model.order.Order;
import com.example.serviceorder.dao.OrderEntity;

public interface OrderService extends IService<OrderEntity> {
    Order createOrder(Long productId, Long userId);

    Order createOrderV2();
}
