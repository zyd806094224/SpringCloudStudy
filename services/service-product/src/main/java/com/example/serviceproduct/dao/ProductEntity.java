package com.example.serviceproduct.dao;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@TableName("product")
@Data
public class ProductEntity {

    private Long id;
    private String productName;
    private Long categoryId;
    private BigDecimal price;
    private int stock;
    private String description;
    private String imageUrl;
    private int status;
    private String createTime;
    private String updateTime;
}
