package com.example.serviceproduct.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.serviceproduct.dao.ProductEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductMapper extends BaseMapper<ProductEntity> {

}
