/*
 Navicat Premium Dump SQL

 Source Server         : localhost
 Source Server Type    : MySQL
 Source Server Version : 80031 (8.0.31)
 Source Host           : localhost:3306
 Source Schema         : spring_cloud

 Target Server Type    : MySQL
 Target Server Version : 80031 (8.0.31)
 File Encoding         : 65001

 Date: 26/08/2025 17:19:27
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for order
-- ----------------------------
DROP TABLE IF EXISTS `order`;
CREATE TABLE `order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '订单ID（主键，自增）',
  `order_no` varchar(64) NOT NULL COMMENT '订单编号（唯一，如：20250826123456789）',
  `user_id` bigint NOT NULL COMMENT '用户ID（关联用户表 user.id）',
  `product_id` bigint NOT NULL COMMENT '商品ID（关联商品表 product.id）',
  `product_name` varchar(100) DEFAULT NULL COMMENT '商品名称（冗余存储，避免关联查询）',
  `product_price` decimal(10,2) NOT NULL COMMENT '商品单价（下单时的价格，单位：元）',
  `order_amount` decimal(10,2) NOT NULL COMMENT '订单总金额（=商品单价×购买数量，单位：元）',
  `pay_amount` decimal(10,2) DEFAULT NULL COMMENT '实际支付金额（可能包含折扣，单位：元）',
  `pay_type` tinyint DEFAULT NULL COMMENT '支付方式（1=支付宝，2=微信，3=银行卡，0=未支付）',
  `order_status` tinyint NOT NULL COMMENT '订单状态（0=待支付，1=已支付，2=已发货，3=已完成，4=已取消，5=退款中，6=已退款）',
  `pay_time` datetime DEFAULT NULL COMMENT '支付时间（支付成功时更新）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '订单创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '订单更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除（0=未删除，1=已删除）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`) COMMENT '订单编号唯一索引，防止重复下单',
  KEY `idx_user_id` (`user_id`) COMMENT '用户ID索引，便于查询用户的所有订单',
  KEY `idx_product_id` (`product_id`) COMMENT '商品ID索引，便于统计商品的订单量',
  KEY `idx_order_status` (`order_status`) COMMENT '订单状态索引，便于筛选不同状态的订单'
) ENGINE=InnoDB AUTO_INCREMENT=98 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单表';

-- ----------------------------
-- Records of order
-- ----------------------------
BEGIN;
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (1, 'ORD20250826001', 101, 1, 'Apple iPhone 15 Pro', 8999.00, 8999.00, 8999.00, 1, 1, '2025-08-26 10:00:00', '2025-08-26 10:00:00', '2025-08-26 10:00:00', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (2, 'ORD20250826002', 102, 2, 'Samsung Galaxy S24 Ultra', 7999.00, 7999.00, 7999.00, 2, 1, '2025-08-26 10:10:00', '2025-08-26 10:10:00', '2025-08-26 10:10:00', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (3, 'ORD20250826003', 103, 3, 'Sony WH-1000XM5 耳', 2499.00, 2499.00, 2499.00, 1, 2, '2025-08-26 10:20:00', '2025-08-26 10:20:00', '2025-08-26 10:20:00', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (4, 'ORD20250826004', 104, 4, '小米空气净化器4 Pro', 1299.00, 1299.00, 1299.00, 3, 1, '2025-08-26 10:30:00', '2025-08-26 10:30:00', '2025-08-26 10:30:00', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (5, 'ORD20250826005', 105, 5, '苏泊尔不粘炒锅', 399.00, 399.00, 399.00, 2, 3, '2025-08-26 10:40:00', '2025-08-26 10:40:00', '2025-08-26 10:40:00', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (6, 'ORD20250826006', 106, 6, '全棉四件套床上用品', 599.00, 599.00, 599.00, 1, 1, '2025-08-26 10:50:00', '2025-08-26 10:50:00', '2025-08-26 10:50:00', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (7, 'ORD20250826007', 107, 7, 'Levi\'s 501原色牛仔裤', 899.00, 899.00, 899.00, 3, 2, '2025-08-26 11:00:00', '2025-08-26 11:00:00', '2025-08-26 11:00:00', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (8, 'ORD20250826008', 108, 8, 'Nike Air Max 270运动鞋', 1099.00, 1099.00, 1099.00, 2, 1, '2025-08-26 11:10:00', '2025-08-26 11:10:00', '2025-08-26 11:10:00', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (9, 'ORD20250826009', 109, 9, '茅台飞天53度白酒', 1499.00, 1499.00, 1499.00, 1, 3, '2025-08-26 11:20:00', '2025-08-26 11:20:00', '2025-08-26 11:20:00', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (10, 'ORD20250826010', 110, 10, '星巴克美式咖啡豆', 89.00, 89.00, 89.00, 3, 1, '2025-08-26 11:30:00', '2025-08-26 11:30:00', '2025-08-26 11:30:00', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (21, '20250826100001', 1001, 1, 'Apple iPhone 15 Pro', 8999.00, 8999.00, 8999.00, 1, 1, '2025-08-26 09:15:30', '2025-08-26 09:10:20', '2025-08-26 09:15:30', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (22, '20250826100002', 1001, 3, 'Sony WH-1000XM5 耳机', 2499.00, 2499.00, 2499.00, 2, 1, '2025-08-26 10:20:15', '2025-08-26 10:18:40', '2025-08-26 10:20:15', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (23, '20250826100003', 1001, 10, '星巴克美式咖啡豆', 89.00, 178.00, 178.00, 3, 2, '2025-08-26 14:30:00', '2025-08-26 14:25:10', '2025-08-26 15:40:20', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (24, '20250826100004', 1002, 2, 'Samsung Galaxy S24 Ultra', 7999.00, 7999.00, 7999.00, 1, 3, '2025-08-25 11:05:20', '2025-08-25 10:50:30', '2025-08-26 08:10:15', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (25, '20250826100005', 1002, 5, '苏泊尔不粘锅套装', 399.00, 399.00, NULL, 0, 0, NULL, '2025-08-26 16:40:25', '2025-08-26 16:40:25', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (26, '20250826100006', 1002, 7, 'Levi\'s 501原色牛仔裤', 899.00, 899.00, 899.00, 2, 4, '2025-08-26 08:30:10', '2025-08-26 08:20:50', '2025-08-26 09:15:30', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (27, '20250826100007', 1003, 4, '小米空气净化器4 Pro', 1299.00, 1299.00, 1299.00, 1, 2, '2025-08-26 11:45:30', '2025-08-26 11:30:20', '2025-08-26 13:20:10', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (28, '20250826100008', 1003, 6, '全棉四件套床上用品', 599.00, 599.00, 599.00, 3, 5, '2025-08-26 10:15:40', '2025-08-26 09:50:10', '2025-08-26 14:30:20', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (29, '20250826100009', 1003, 9, '茅台飞天53度白酒', 1499.00, 2998.00, 2998.00, 1, 6, '2025-08-26 07:20:30', '2025-08-26 07:10:50', '2025-08-26 11:45:30', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (30, '20250826100010', 1004, 8, 'Nike Air Max 270 运动鞋', 1099.00, 1099.00, 1099.00, 2, 1, '2025-08-26 15:20:10', '2025-08-26 15:10:30', '2025-08-26 15:20:10', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (31, '20250826100011', 1004, 1, 'Apple iPhone 15 Pro', 8999.00, 8999.00, NULL, 0, 0, NULL, '2025-08-26 16:30:40', '2025-08-26 16:30:40', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (32, '20250826100012', 1004, 3, 'Sony WH-1000XM5 耳机', 2499.00, 2499.00, 2499.00, 1, 2, '2025-08-26 13:15:20', '2025-08-26 13:05:10', '2025-08-26 14:50:30', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (33, '20250826100013', 1005, 5, '苏泊尔不粘锅套装', 399.00, 798.00, 798.00, 3, 3, '2025-08-26 09:40:10', '2025-08-26 09:25:30', '2025-08-26 11:30:20', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (34, '20250826100014', 1005, 7, 'Levi\'s 501原色牛仔裤', 899.00, 899.00, 899.00, 2, 4, '2025-08-26 12:10:50', '2025-08-26 12:05:20', '2025-08-26 12:30:40', 0);
INSERT INTO `order` (`id`, `order_no`, `user_id`, `product_id`, `product_name`, `product_price`, `order_amount`, `pay_amount`, `pay_type`, `order_status`, `pay_time`, `create_time`, `update_time`, `is_deleted`) VALUES (35, '20250826100015', 1005, 10, '星巴克美式咖啡豆', 89.00, 445.00, 445.00, 1, 1, '2025-08-26 14:50:30', '2025-08-26 14:40:10', '2025-08-26 14:50:30', 0);
COMMIT;

SET FOREIGN_KEY_CHECKS = 1;
