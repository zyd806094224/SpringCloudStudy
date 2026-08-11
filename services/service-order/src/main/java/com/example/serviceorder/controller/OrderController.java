package com.example.serviceorder.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.example.model.common.R;
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

    /**
     * 接口级限流示例。
     *
     * @SentinelResource 标记此方法为 Sentinel 资源，资源名 getTestOrder。
     * 阈值规则存在 Nacos（service-order-sentinel-flow.json），动态下发。
     * blockHandler 指定被限流时调用的兜底方法（必须和原方法在同一个类，参数列表末尾加 BlockException）。
     *
     * 返回统一用 R 包装：正常 code=200，被限流 code=429，方便压测工具和前端区分。
     */
    @GetMapping("/getTestOrder")
    @SentinelResource(value = "getTestOrder", blockHandler = "getTestOrderBlockHandler")
    public R getTestOrder() {
        return R.ok("success", orderService.list());
    }

    /**
     * getTestOrder 被限流时的兜底方法。
     * 返回 code=429 的 R，与正常响应（code=200）明显区分，压测/调试一眼能认出是被限流了。
     */
    public R getTestOrderBlockHandler(BlockException ex) {
        log.warn("getTestOrder 被限流: {}", ex.getClass().getSimpleName());
        return R.error(429, "请求过于频繁，请稍后再试");
    }

    @GetMapping("/test-mq")
    public String testMq() {
        rocketMQTemplate.convertAndSend("order-topic", "Hello, RocketMQ!");
        return "Message sent!";
    }
}
