CREATE TABLE modl_database_departure (
    id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
    move_token VARCHAR(64) NOT NULL,
    moved_to VARCHAR(512) NOT NULL,
    moved_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
