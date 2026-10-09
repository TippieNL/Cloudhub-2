CREATE DATABASE IF NOT EXISTS cloud_file_hub CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE cloud_file_hub;

CREATE TABLE IF NOT EXISTS users (
 id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(100) NOT NULL UNIQUE,
 password_hash VARCHAR(255) NOT NULL,
 is_active TINYINT(1) NOT NULL DEFAULT 1,
 role ENUM('viewer','editor','admin') NOT NULL DEFAULT 'viewer',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 last_login_at TIMESTAMP NULL DEFAULT NULL,
 -- SMS two-step verification. NULL two_factor_enabled_at means off, which
 -- every account is until its owner turns it on. The number is E.164.
 two_factor_phone VARCHAR(20) NULL,
 two_factor_enabled_at DATETIME NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- Throttle events: password failures ('user', 'ip'), and for two-step
-- verification the codes checked ('verify_*') and text messages sent ('sms_*').
-- attempt_key is an HMAC, never a raw username, address or phone number.
CREATE TABLE IF NOT EXISTS login_attempts (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 scope ENUM('user','ip','verify_user','verify_ip','sms_user','sms_ip','sms_phone') NOT NULL,
 attempt_key CHAR(64) NOT NULL,
 attempted_at DATETIME NOT NULL,
 INDEX idx_login_attempt_lookup(scope, attempt_key, attempted_at),
 INDEX idx_login_attempt_cleanup(attempted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


CREATE TABLE IF NOT EXISTS security_events (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id INT UNSIGNED NULL,
 username VARCHAR(100) NULL,
 event_type VARCHAR(80) NOT NULL,
 outcome VARCHAR(20) NOT NULL DEFAULT 'success',
 ip_address VARCHAR(45) NOT NULL DEFAULT '',
 user_agent VARCHAR(255) NOT NULL DEFAULT '',
 request_id VARCHAR(32) NOT NULL DEFAULT '',
 context_json JSON NULL,
 created_at DATETIME NOT NULL,
 INDEX idx_security_events_created(created_at),
 INDEX idx_security_events_user(user_id,created_at),
 INDEX idx_security_events_type(event_type,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- An SMS code waiting to be entered. One row per account and purpose
-- ('login', 'confirm' for proving the current phone before a change, 'phone'
-- for proving a new number): asking again replaces it, which is what makes an
-- older code stop working, and using it deletes it. code_hash is an HMAC of the
-- code under a server secret, never the code itself. id is random and is what
-- the session holds. Times are UTC, written by PHP.
CREATE TABLE IF NOT EXISTS two_factor_challenges (
 id CHAR(32) NOT NULL PRIMARY KEY,
 user_id INT UNSIGNED NOT NULL,
 purpose VARCHAR(16) NOT NULL,
 code_hash CHAR(64) NULL,
 attempts INT UNSIGNED NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL,
 sent_at DATETIME NULL,
 expires_at DATETIME NULL,
 UNIQUE KEY uq_two_factor_challenge(user_id, purpose),
 INDEX idx_two_factor_challenge_created(created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- One-time recovery codes for an account whose phone is lost. Only a SHA-256
-- of each is kept; used_at marks the one that has been spent.
CREATE TABLE IF NOT EXISTS two_factor_recovery_codes (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id INT UNSIGNED NOT NULL,
 code_hash CHAR(64) NOT NULL,
 created_at DATETIME NOT NULL,
 used_at DATETIME NULL,
 UNIQUE KEY uq_two_factor_recovery(user_id, code_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS storage_servers (
 id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(190) NOT NULL,
 type ENUM('local','ftp','sftp','smb','http_api') NOT NULL DEFAULT 'local',
 is_active TINYINT(1) NOT NULL DEFAULT 1,
 is_default TINYINT(1) NOT NULL DEFAULT 0,
 config JSON NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 INDEX idx_storage_active(is_active), INDEX idx_storage_default(is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS file_metadata (
 id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 server_id INT UNSIGNED NOT NULL,
 file_path TEXT NOT NULL,
 original_name VARCHAR(255) NOT NULL,
 size BIGINT UNSIGNED NOT NULL DEFAULT 0,
 mime_type VARCHAR(190) NULL,
 uploaded_by INT UNSIGNED NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 -- No ON DELETE CASCADE to storage_servers: this table is the upload ledger,
 -- server_id is a fiction (files always live on the local filesystem), and a
 -- cascade meant deleting a server row silently wiped every account's usage
 -- history, which sweep() never rebuilds. migrate.php drops the old constraint.
 INDEX idx_file_server(server_id), INDEX idx_file_server_path(server_id, file_path(190)), INDEX idx_file_uploader(uploaded_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS share_links (
 token CHAR(43) NOT NULL PRIMARY KEY,
 file_path TEXT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 expires_at TIMESTAMP NULL DEFAULT NULL,
 -- The lifetime asked for, and who asked. /api/shares/create inserts both and
 -- /api/shares/list reads them; a fresh install from this file used to 500 on
 -- the first share because the INSERT named columns only migrate.php added.
 expires_hours INT NULL DEFAULT NULL,
 created_by INT UNSIGNED NULL DEFAULT NULL,
 INDEX idx_share_expires(expires_at),
 INDEX idx_share_creator(created_by),
 -- Share creation looks up a live link by path before issuing a token.
 INDEX idx_share_path(file_path(190))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Files each account has starred. path_hash is SHA-256 of file_path: TEXT
-- cannot carry a whole-value unique key, and a prefix one would treat two long
-- paths sharing their first 190 characters as the same file.
CREATE TABLE IF NOT EXISTS favorites (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id INT UNSIGNED NOT NULL,
 file_path TEXT NOT NULL,
 path_hash CHAR(64) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uq_favorite_user_path(user_id, path_hash),
 INDEX idx_favorite_user(user_id, created_at),
 -- Renames, moves and deletes find the favorites under a path by prefix.
 INDEX idx_favorite_path(file_path(190))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO storage_servers (name,type,is_active,is_default,config)
SELECT 'Local Storage','local',1,1,JSON_OBJECT('path','storage/files')
WHERE NOT EXISTS (SELECT 1 FROM storage_servers);
