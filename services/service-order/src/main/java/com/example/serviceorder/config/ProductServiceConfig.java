package com.example.serviceorder.config;


import feign.Logger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProductServiceConfig {

    @Bean
    public Logger.Level feignlogLevel() {
        // 指定 OpenFeign 发请求时，日志级别为 FULL
        return Logger.Level.FULL;
    }
}
