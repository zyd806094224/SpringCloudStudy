package com.example.serviceproduct.controller;


import com.example.model.product.Product;
import com.example.serviceproduct.service.ProductService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductController {

    @Autowired
    private ProductService productService;
    @GetMapping("/product/{id}")
    @SneakyThrows
    public Product getProduct(@PathVariable("id") Long id,
                              HttpServletRequest request) {
        String header = request.getHeader("X-Token");
        System.out.println("Hello, XToken: " + header);
        Product product = productService.getProductById(id);

        /*
         * 模拟慢调用
         */
        // TimeUnit.SECONDS.sleep(2);

        /*
         * 模拟异常
         */
        // int i = 1 / 0;

        return product;
    }
}
