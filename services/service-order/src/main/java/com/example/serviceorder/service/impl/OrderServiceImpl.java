package com.example.serviceorder.service.impl;


import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.example.model.order.Order;
import com.example.model.product.Product;
import com.example.serviceorder.feign.ProductFeignClient;
import com.example.serviceorder.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private ProductFeignClient productFeignClient;

    @Override
    @SentinelResource(value = "createOrder", blockHandler = "createOrderFallback")
    public Order createOrder(Long productId, Long userId) {
        Order order = new Order();
        order.setId(1L);
        //远程调用获取商品信息
        Product product = productFeignClient.getProductById(productId);
        if(product != null){
            BigDecimal totalAmount = product.getPrice().multiply(new BigDecimal(product.getNum()));
            order.setTotalAmount(totalAmount);
            order.setUserId(userId);
            order.setNickname("yiluxiangbei");
            order.setAddress("北京");
            order.setProductList(List.of(product));
        }
        return order;
    }
    /**
     * 指定兜底回调
     */
    public Order createOrderFallback(Long productId, Long userId, BlockException e) {
        Order order = new Order();
        order.setId(0L);
        order.setTotalAmount(new BigDecimal("0"));
        order.setUserId(userId);
        order.setNickname("未知用户");
        order.setAddress("异常信息: " + e.getClass());
        return order;
    }

}
