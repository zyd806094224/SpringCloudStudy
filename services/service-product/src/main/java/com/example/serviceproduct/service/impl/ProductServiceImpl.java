package com.example.serviceproduct.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.serviceproduct.dao.ProductEntity;
import com.example.serviceproduct.mapper.ProductMapper;
import com.example.serviceproduct.service.ProductService;
import org.springframework.stereotype.Service;

@Service
public class ProductServiceImpl extends ServiceImpl<ProductMapper, ProductEntity> implements ProductService {

}
