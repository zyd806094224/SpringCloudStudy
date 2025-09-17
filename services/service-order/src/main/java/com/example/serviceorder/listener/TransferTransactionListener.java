package com.example.serviceorder.listener;

import com.alibaba.fastjson.JSONObject;
import com.example.serviceorder.dao.LocalMessage;
import com.example.serviceorder.dao.OrderEntity;
import com.example.serviceorder.mapper.LocalMessageMapper;
import com.example.serviceorder.service.OrderService;
import com.example.serviceorder.utils.OrderUtils;
import lombok.extern.log4j.Log4j2;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Log4j2
@Component
@RocketMQTransactionListener
public class TransferTransactionListener implements RocketMQLocalTransactionListener {

    @Autowired
    private LocalMessageMapper localMessageMapper;  // 本地消息表服务

    @Autowired
    private OrderService orderService;

    // 1. 执行本地事务（半消息发送成功后触发）
    @Override
    @Transactional  // 本地事务注解，确保操作的原子性
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        try {
            // 解析消息内容（获取转账信息）
            String messageString = new String((byte[]) msg.getPayload());
            JSONObject jsonObject = JSONObject.parseObject(messageString);
            Long productId = jsonObject.getLong("productId");
            String txId = jsonObject.getString("txId");

            Long userId = 101L;

            // （这两步在同一个@Transactional中，要么都成功，要么都失败）
            OrderEntity orderEntity = OrderUtils.generateOrderEntity(productId,"苏泊尔不粘锅套装",userId);
            //创建订单
            boolean saveSuccess = orderService.save(orderEntity);

            if (saveSuccess) {
                // 本地事务成功：记录消息到本地消息表（状态为“待确认”）
                LocalMessage localMessage = new LocalMessage();
                localMessage.setTxId(txId);
                localMessage.setMessage(messageString);
                localMessage.setStatus(0); // 待发送 （后续会被标记为已发送）
                localMessage.setCreateTime(new Date());
                localMessage.setUpdateTime(new Date());
                localMessageMapper.insert(localMessage);
                int i = 1/0;

                log.info("rocketMQ将半消息标记为“可投递”");
                // 返回COMMIT：通知RocketMQ将半消息标记为“可投递”
                return RocketMQLocalTransactionState.COMMIT;
            } else {
                // 本地事务失败：返回ROLLBACK，RocketMQ会删除半消息
                return RocketMQLocalTransactionState.ROLLBACK;
            }
        } catch (Exception e) {
            // 异常情况：返回UNKNOWN，RocketMQ会触发回查
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    // 2. 本地事务回查（当executeLocalTransaction返回UNKNOWN时触发）
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        // 解析消息中的事务ID
        String messageString = new String((byte[]) msg.getPayload());
        JSONObject jsonObject = JSONObject.parseObject(messageString);
        Long productId = jsonObject.getLong("productId");
        String txId = jsonObject.getString("txId");

        // 检查本地消息表中该事务的状态
        LocalMessage localMessage = localMessageMapper.selectById(txId);
        localMessageMapper.updateStatus(txId,1); //更新事务消息状态为已发送
        int status = localMessage.getStatus();
        log.info("本地事务回查，事务ID：" + txId + "，状态：" + status);
        if (status == 0) {
            // 本地消息未确认：可能是本地事务未完成，返回UNKNOWN继续回查
            return RocketMQLocalTransactionState.UNKNOWN;
        } else if (status == 1) {
            // 本地消息已确认：说明本地事务成功，返回COMMIT
            return RocketMQLocalTransactionState.COMMIT;
        } else {
            // 本地消息标记为失败：返回ROLLBACK
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }
}

