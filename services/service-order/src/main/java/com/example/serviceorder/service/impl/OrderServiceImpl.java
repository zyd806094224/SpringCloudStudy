package com.example.serviceorder.service.impl;


import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.model.order.Order;
import com.example.model.product.Product;
import com.example.serviceorder.dao.OrderEntity;
import com.example.serviceorder.feign.ProductFeignClient;
import com.example.serviceorder.mapper.OrderMapper;
import com.example.serviceorder.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, OrderEntity> implements OrderService {

    @Autowired
    private ProductFeignClient productFeignClient;

    @Transactional
    @Override
    public Order createOrder(Long productId, Long userId) {
        OrderEntity orderEntity = generateOrderEntity(productId,userId);
        //创建订单
        save(orderEntity);
        Order order = new Order();
        order.setId(orderEntity.getId());
        order.setOrderNo(orderEntity.getOrderNo());
        order.setUserId(userId);
        //远程调用获取商品信息
        Product product = productFeignClient.getProductById(productId);
        if(product != null){
            log.info("剩余商品数量===》" + product.getNum());
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
     * 生成订单
     * @param productId
     * @param userId
     * @return
     */
    private OrderEntity generateOrderEntity(Long productId, Long userId) {
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setUserId(userId);
        orderEntity.setProductId(productId);
        orderEntity.setOrderNo(UUID.randomUUID().toString());
        orderEntity.setProductName("Apple iPhone 15 Pro");
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
