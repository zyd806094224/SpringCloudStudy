package com.example.model.product;


import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class Product {
    private Long id;
    private BigDecimal price;
    private String productName;
    private int num;
}
