-- App 版本管理表 (兼容 OceanBase)
CREATE TABLE IF NOT EXISTS `app_version` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `platform` varchar(20) NOT NULL COMMENT '平台类型：android/ios/web',
  `latest_version` varchar(50) NOT NULL COMMENT '最新版本号',
  `version_code` int(11) NOT NULL COMMENT '版本编号',
  `force_update` tinyint(1) DEFAULT 1 COMMENT '是否强制更新：0-否，1-是',
  `download_url` varchar(500) NOT NULL COMMENT '下载页面地址',
  `release_notes` text COMMENT '更新说明',
  `min_version` varchar(50) DEFAULT NULL COMMENT '最低兼容版本',
  `published_at` datetime DEFAULT NULL COMMENT '发布时间',
  `is_deleted` int(11) DEFAULT 0 COMMENT '逻辑删除',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='App版本管理表';

-- 初始化数据
INSERT INTO `app_version` (`platform`, `latest_version`, `version_code`, `force_update`, `download_url`, `release_notes`, `min_version`, `published_at`) VALUES
('android', '1.0.1', 2, 1, 'https://daren-admin.sjms.online/zqsjAgents/ewm/download.html', '修复若干问题，提升稳定性', '1.0.0', NOW()),
('ios', '1.0.0', 1, 0, 'https://apps.apple.com/xxx', '初始版本', '1.0.0', NOW()),
('web', '1.0.0', 1, 0, 'https://daren-admin.sjms.online/zqsjAgents', '初始版本', '1.0.0', NOW());