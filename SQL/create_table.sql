-- 新数据库初始化脚本，可重复执行；旧数据库只执行 SQL/migrations 中未应用的迁移。
CREATE DATABASE IF NOT EXISTS star_pic CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE star_pic;
CREATE TABLE IF NOT EXISTS user (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 userAccount VARCHAR(256) NOT NULL,
 userPassword VARCHAR(512) NOT NULL,
 userName VARCHAR(256), userAvatar VARCHAR(1024), userProfile VARCHAR(512),
 userRole VARCHAR(256) NOT NULL DEFAULT 'user',
 vipExpireTime DATETIME, vipCode VARCHAR(128), vipNumber BIGINT,
 shareCode VARCHAR(20), inviteUser BIGINT,
 editTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 createTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updateTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 isDelete TINYINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_userAccount (userAccount), INDEX idx_userName (userName)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS picture (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 url VARCHAR(512) NOT NULL, thumbnailUrl VARCHAR(512), originalKey VARCHAR(512),
 name VARCHAR(128) NOT NULL, introduction VARCHAR(512), category VARCHAR(64), tags VARCHAR(512),
 picSize BIGINT, picWidth INT, picHeight INT, picScale DOUBLE, picFormat VARCHAR(32), picColor VARCHAR(16),
 userId BIGINT NOT NULL, spaceId BIGINT,
 reviewStatus INT NOT NULL DEFAULT 0, reviewMessage VARCHAR(512), reviewerId BIGINT, reviewTime DATETIME,
 createTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 editTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updateTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 isDelete TINYINT NOT NULL DEFAULT 0,
 INDEX idx_space_review_time (spaceId, isDelete, reviewStatus, createTime),
 INDEX idx_userId (userId), INDEX idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS space (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, spaceName VARCHAR(128), spaceLevel INT NOT NULL DEFAULT 0,
 maxSize BIGINT NOT NULL DEFAULT 0, maxCount BIGINT NOT NULL DEFAULT 0,
 totalSize BIGINT NOT NULL DEFAULT 0, totalCount BIGINT NOT NULL DEFAULT 0,
 userId BIGINT NOT NULL,
 createTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 editTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updateTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 isDelete TINYINT NOT NULL DEFAULT 0,
 INDEX idx_userId (userId), INDEX idx_spaceName (spaceName)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- AI outpainting tasks; migration 002 is included for fresh installations.
CREATE TABLE IF NOT EXISTS out_painting_task (
    id BIGINT NOT NULL PRIMARY KEY,
    userId BIGINT NOT NULL,
    pictureId BIGINT NOT NULL,
    spaceId BIGINT NULL,
    providerTaskId VARCHAR(128) NULL,
    status VARCHAR(16) NOT NULL,
    parametersJson TEXT NOT NULL,
    outputImageUrl VARCHAR(4096) NULL,
    errorCode VARCHAR(128) NULL,
    errorMessage VARCHAR(256) NULL,
    savedPictureId BIGINT NULL,
    createTime DATETIME(3) NOT NULL,
    updateTime DATETIME(3) NOT NULL,
    expiresAt DATETIME(3) NOT NULL,
    lastPollTime DATETIME(3) NULL,
    pollToken VARCHAR(64) NULL,
    UNIQUE KEY uk_out_painting_provider_task (providerTaskId),
    UNIQUE KEY uk_out_painting_saved_picture (savedPictureId),
    INDEX idx_out_painting_daily (userId, createTime),
    INDEX idx_out_painting_active (userId, status, expiresAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
