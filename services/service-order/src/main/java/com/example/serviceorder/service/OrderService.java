package com.example.serviceorder.service;


import com.example.model.order.Order;

public interface OrderService {
    Order createOrder(Long productId, Long userId);
}
