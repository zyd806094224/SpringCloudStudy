package com.example.serviceorder.service.impl;


import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.model.order.Order;
import com.example.model.product.Product;
import com.example.serviceorder.dao.OrderEntity;
import com.example.serviceorder.feign.ProductFeignClient;
import com.example.serviceorder.mapper.OrderMapper;
import com.example.serviceorder.service.OrderService;
import com.example.serviceorder.utils.OrderUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, OrderEntity> implements OrderService {

    @Autowired
    private ProductFeignClient productFeignClient;

    @Transactional
    @Override
    public Order createOrder(Long productId, Long userId) {
        OrderEntity orderEntity = OrderUtils.generateOrderEntity(productId,"Apple iPhone 15 Pro",userId);
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
     * rocketmq 创建订单  最终一致性
     * @param productId
     * @param userId
     * @return
     */
    @Override
    public Order createOrderV2(Long productId, Long userId) {

        return null;
    }
}
