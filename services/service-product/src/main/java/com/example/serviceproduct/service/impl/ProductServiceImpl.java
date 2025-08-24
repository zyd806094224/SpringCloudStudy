package com.example.serviceproduct.service.impl;


import com.example.model.product.Product;
import com.example.serviceproduct.service.ProductService;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class ProductServiceImpl implements ProductService {
    @Override
    @SneakyThrows
    public Product getProductById(Long id) {
        System.out.println("调用了商品服务getProductById...");
        Product product = new Product();
        product.setId(id);
        product.setPrice(new BigDecimal("99"));
        product.setProductName("apple-" + id);
        product.setNum(2);

        return product;
    }
}
