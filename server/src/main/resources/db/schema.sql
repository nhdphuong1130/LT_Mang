CREATE TABLE IF NOT EXISTS admin_users (
    username TEXT PRIMARY KEY,
    password_hash TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    platform TEXT NOT NULL,
    token_hash TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'OFFLINE' CHECK (status IN ('ONLINE', 'OFFLINE')),
    last_seen TEXT
);
CREATE TABLE IF NOT EXISTS latest_metrics (
    device_id TEXT PRIMARY KEY REFERENCES devices(device_id),
    collected_at TEXT NOT NULL,
    cpu_percent REAL NOT NULL,
    ram_percent REAL NOT NULL,
    disk_percent REAL NOT NULL,
    temperature_celsius REAL,
    battery_percent REAL,
    charging INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS incidents (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL REFERENCES devices(device_id),
    risk_type TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('OPEN', 'RESOLVED')),
    opened_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    resolved_at TEXT,
    message TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS one_open_incident ON incidents(device_id, risk_type) WHERE status = 'OPEN';
CREATE TABLE IF NOT EXISTS backup_files (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL REFERENCES devices(device_id),
    relative_path TEXT NOT NULL,
    UNIQUE (device_id, relative_path)
);
CREATE TABLE IF NOT EXISTS backup_versions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_id INTEGER NOT NULL REFERENCES backup_files(id),
    size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0),
    sha256 TEXT NOT NULL,
    storage_path TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS restore_jobs (
    id TEXT PRIMARY KEY,
    backup_version_id INTEGER NOT NULL,
    device_id TEXT NOT NULL REFERENCES devices(device_id),
    admin_username TEXT NOT NULL REFERENCES admin_users(username),
    relative_path TEXT NOT NULL,
    size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0),
    sha256 TEXT NOT NULL,
    storage_path TEXT NOT NULL,
    token_hash TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'AVAILABLE', 'IN_PROGRESS', 'COMPLETED', 'FAILED')),
    requested_at TEXT NOT NULL,
    completed_at TEXT,
    error TEXT,
    lease_owner TEXT
);
CREATE TABLE IF NOT EXISTS audit_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    occurred_at TEXT NOT NULL,
    actor TEXT NOT NULL,
    action TEXT NOT NULL,
    device_id TEXT REFERENCES devices(device_id),
    details TEXT NOT NULL
);
