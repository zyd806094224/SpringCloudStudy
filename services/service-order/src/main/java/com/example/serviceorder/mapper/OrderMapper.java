package com.example.serviceorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.serviceorder.dao.OrderEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderMapper extends BaseMapper<OrderEntity> {

}
