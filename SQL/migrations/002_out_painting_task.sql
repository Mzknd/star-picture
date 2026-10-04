-- Apply after 001 on existing databases; fresh create_table.sql already includes this table.
-- No credential or signed source URL is stored. Failed submissions consume the daily attempt budget.
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
