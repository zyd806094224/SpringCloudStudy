package com.example.serviceproduct.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.serviceproduct.dao.DeadLetterMessageEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DeadLetterMessageMapper extends BaseMapper<DeadLetterMessageEntity> {

}
