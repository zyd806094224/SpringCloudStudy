package com.example.serviceorder.controller;

import com.example.model.order.Order;
import com.example.serviceorder.dao.OrderEntity;
import com.example.serviceorder.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
public class OrderController {

    @Value("${url_address.testUrl}")
    private String testUrl;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private OrderService orderService;
    @GetMapping("/create")
    public Order createOrder(@RequestParam("userId") Long userId,
                             @RequestParam("productId") Long productId) {
        log.info("创建订单" + testUrl);
        return orderService.createOrder(productId, userId);
    }

    @GetMapping("/createV2")
    public Order createOrderV2() {
        return orderService.createOrderV2();
    }

    @GetMapping("/createV3")
    public boolean createOrderV3() {
        return orderService.createOrderV3();
    }

    @GetMapping("/getOrderList")
    public List<OrderEntity> getOrderList() {
        return orderService.list();
    }

    @GetMapping("/test-mq")
    public String testMq() {
        rocketMQTemplate.convertAndSend("order-topic", "Hello, RocketMQ!");
        return "Message sent!";
    }
}
