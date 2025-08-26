package com.example.serviceproduct.controller;

import com.example.model.product.Product;
import com.example.serviceproduct.dao.ProductEntity;
import com.example.serviceproduct.service.ProductService;
import com.example.serviceproduct.utils.RedissonLockUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
public class ProductController {


    @Autowired
    private ProductService productService;

    @Autowired
    private RedissonLockUtil redissonLockUtil;

    @GetMapping("/getProductList")
    public List<ProductEntity> getProductList() {
        return productService.list();
    }

    @GetMapping("/product/{id}")
    @SneakyThrows
    public Product getProduct(@PathVariable("id") Long id,
                              HttpServletRequest request) {
        String lockKey = "product:" + id;
        //分布式锁 防止分布式场景下库存删减不一致问题
        RLock lock = redissonLockUtil.lock(lockKey);
        try {
            // 业务逻辑（查询库存、减库存等）
            String header = request.getHeader("X-Token");
            log.info("XToken: " + header);
            ProductEntity productEntity = productService.getById(id);
            if (productEntity != null) {
                int stock = productEntity.getStock();
                if (stock > 0) {
                    //模拟慢调用2秒
                    TimeUnit.SECONDS.sleep(2);
                    //扣减库存
                    productEntity.setStock(stock - 1);
                    productService.updateById(productEntity);
                    Product product = new Product();
                    product.setId(productEntity.getId());
                    product.setProductName(productEntity.getProductName());
                    product.setNum(productEntity.getStock());
                    product.setPrice(productEntity.getPrice());
                    return product;
                }
            }
        } finally {
            redissonLockUtil.unlock(lock);
        }
        return null;
    }
}
