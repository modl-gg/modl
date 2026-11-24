CREATE TABLE modl_database_departure (
    id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
    move_token VARCHAR(64) COLLATE "C" NOT NULL,
    moved_to VARCHAR(512) COLLATE "C" NOT NULL,
    moved_at BIGINT NOT NULL
);
