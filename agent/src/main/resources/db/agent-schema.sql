CREATE TABLE IF NOT EXISTS uploaded_files (
    relative_path TEXT PRIMARY KEY,
    sha256 TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS backup_queue (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    relative_path TEXT NOT NULL UNIQUE,
    size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0 AND size_bytes <= 104857600),
    modified_at TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'UPLOADING', 'RETRY')),
    uploading_sha256 TEXT,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt INTEGER NOT NULL DEFAULT 0,
    last_error TEXT
);

CREATE INDEX IF NOT EXISTS backup_queue_ready ON backup_queue(status, next_attempt, id);
