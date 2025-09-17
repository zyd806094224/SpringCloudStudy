package com.example.serviceorder.mapper;

import com.example.serviceorder.dao.LocalMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface LocalMessageMapper {
    // 插入消息
    int insert(LocalMessage message);

    // 更新消息状态
    int updateStatus(@Param("txId") String txId, @Param("status") Integer status);

    // 查询待发送的消息
    List<LocalMessage> selectPendingMessages();

    //查找事务消息
    LocalMessage selectById(@Param("txId")String txId);
}
