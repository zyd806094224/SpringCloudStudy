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

 Date: 26/08/2025 17:19:17
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for product
-- ----------------------------
DROP TABLE IF EXISTS `product`;
CREATE TABLE `product` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '商品ID',
  `product_name` varchar(255) NOT NULL COMMENT '商品名称',
  `category_id` bigint DEFAULT NULL COMMENT '商品分类ID',
  `price` decimal(10,2) NOT NULL COMMENT '商品单价',
  `stock` int NOT NULL DEFAULT '0' COMMENT '库存数量',
  `description` text COMMENT '商品描述',
  `image_url` varchar(512) DEFAULT NULL COMMENT '商品图片URL',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0-下架，1-上架',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_category` (`category_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品表';

-- ----------------------------
-- Records of product
-- ----------------------------
BEGIN;
INSERT INTO `product` (`id`, `product_name`, `category_id`, `price`, `stock`, `description`, `image_url`, `status`, `create_time`, `update_time`) VALUES (1, 'Apple iPhone 15 Pro', 1, 8999.00, 94, '6.1英寸超视网膜XDR显示屏，A17 Pro芯片', 'https://example.com/iphone15pro.jpg', 1, '2025-08-25 14:51:00', '2025-08-25 14:51:00');
INSERT INTO `product` (`id`, `product_name`, `category_id`, `price`, `stock`, `description`, `image_url`, `status`, `create_time`, `update_time`) VALUES (2, 'Samsung Galaxy S24 Ultra', 1, 7999.00, 120, '6.8英寸动态AMOLED 2X，骁龙8 Gen 3处理器', 'https://example.com/s24ultra.jpg', 1, '2025-08-25 14:51:00', '2025-08-25 14:51:00');
INSERT INTO `product` (`id`, `product_name`, `category_id`, `price`, `stock`, `description`, `image_url`, `status`, `create_time`, `update_time`) VALUES (3, 'Sony WH-1000XM5 耳机', 2, 2499.00, 80, '行业领先的降噪效果，30小时续航', 'https://example.com/sonywh1000xm5.jpg', 1, '2025-08-25 14:51:00', '2025-08-25 14:51:00');
INSERT INTO `product` (`id`, `product_name`, `category_id`, `price`, `stock`, `description`, `image_url`, `status`, `create_time`, `update_time`) VALUES (4, '小米空气净化器4 Pro', 3, 1299.00, 200, '适用面积40-60㎡，HEPA高效过滤', 'https://example.com/mi-airpurifier.jpg', 1, '2025-08-25 14:51:00', '2025-08-25 14:51:00');
INSERT INTO `product` (`id`, `product_name`, `category_id`, `price`, `stock`, `description`, `image_url`, `status`, `create_time`, `update_time`) VALUES (5, '苏泊尔不粘锅套装', 4, 399.00, 300, '3件套含煎锅、炒锅、汤锅，无油烟设计', 'https://example.com/supor-pans.jpg', 1, '2025-08-25 14:51:00', '2025-08-25 14:51:00');
INSERT INTO `product` (`id`, `product_name`, `category_id`, `price`, `stock`, `description`, `image_url`, `status`, `create_time`, `update_time`) VALUES (6, '全棉四件套床上用品', 5, 599.00, 180, '100%新疆长绒棉，柔软亲肤', 'https://example.com/cotton-bedding.jpg', 1, '2025-08-25 14:51:00', '2025-08-25 14:51:00');
INSERT INTO `product` (`id`, `product_name`, `category_id`, `price`, `stock`, `description`, `image_url`, `status`, `create_time`, `update_time`) VALUES (7, 'Levi\'s 501原色牛仔裤', 6, 899.00, 95, '经典直筒版型，耐穿百搭', 'https://example.com/levis501.jpg', 1, '2025-08-25 14:51:00', '2025-08-25 14:51:00');
INSERT INTO `product` (`id`, `product_name`, `category_id`, `price`, `stock`, `description`, `image_url`, `status`, `create_time`, `update_time`) VALUES (8, 'Nike Air Max 270 运动鞋', 7, 1099.00, 110, '舒适缓震，时尚外观', 'https://example.com/nikeairmax.jpg', 1, '2025-08-25 14:51:00', '2025-08-25 14:51:00');
INSERT INTO `product` (`id`, `product_name`, `category_id`, `price`, `stock`, `description`, `image_url`, `status`, `create_time`, `update_time`) VALUES (9, '茅台飞天53度白酒', 8, 1499.00, 30, '500ml，酱香型白酒', 'https://example.com/maotai.jpg', 1, '2025-08-25 14:51:00', '2025-08-25 14:51:00');
INSERT INTO `product` (`id`, `product_name`, `category_id`, `price`, `stock`, `description`, `image_url`, `status`, `create_time`, `update_time`) VALUES (10, '星巴克美式咖啡豆', 9, 89.00, 250, '中度烘焙，1kg装', 'https://example.com/starbucks-beans.jpg', 1, '2025-08-25 14:51:00', '2025-08-25 14:51:00');
COMMIT;

SET FOREIGN_KEY_CHECKS = 1;
