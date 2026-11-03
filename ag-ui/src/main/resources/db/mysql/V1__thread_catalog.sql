CREATE TABLE IF NOT EXISTS thread_catalog (
    id VARCHAR(128) NOT NULL PRIMARY KEY,
    title VARCHAR(512) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    seq BIGINT NOT NULL,
    named BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX thread_catalog_recent (updated_at DESC, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
