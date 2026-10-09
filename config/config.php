<?php
$root = (string)env('ROOT_DIR', 'storage/files');

// Compatibility repair: Phase 5/6 shipped ROOT_DIR=storage in .env.example,
// while the application schema and previous builds use storage/files.
// If that legacy value is present and storage/files exists, use the actual
// file store rather than exposing the storage infrastructure directory.
$normalisedRoot = trim(str_replace('\\','/', $root), '/');
if ($normalisedRoot === 'storage' && is_dir(dirname(__DIR__) . '/storage/files')) {
    $root = 'storage/files';
}

if (!str_starts_with($root, '/') && !preg_match('/^[A-Za-z]:[\\\\\/]/', $root)) $root = dirname(__DIR__) . '/' . $root;
$root = rtrim(str_replace('\\','/', $root), '/');
if (!is_dir($root)) mkdir($root, 0775, true);
return [
 'app_env'=>(string)env('APP_ENV','production'), 'app_url'=>(string)env('APP_URL',''), 'root_dir'=>$root,
 'read_only'=>env_bool('READ_ONLY'), 'allow_delete'=>env_bool('ALLOW_DELETE',true), 'allow_overwrite'=>env_bool('ALLOW_OVERWRITE',true),
 'trash_enabled'=>env_bool('TRASH_ENABLED',true), 'trash_retention_days'=>(int)env('TRASH_RETENTION_DAYS',30),
 // Overwriting a file keeps the previous contents. They are real bytes on real
 // disk, so they are capped both ways and counted against storage and quota.
 'versions_enabled'=>env_bool('VERSIONS_ENABLED',true),
 'version_retention_days'=>(int)env('VERSION_RETENTION_DAYS',30),
 'max_versions_per_file'=>(int)env('MAX_VERSIONS_PER_FILE',10),
 'storage_limit_gb'=>(float)env('STORAGE_LIMIT_GB',0), 'user_quota_gb'=>(float)env('USER_QUOTA_GB',0),
 'usage_cache_seconds'=>(int)env('USAGE_CACHE_SECONDS',300),
 'https_enabled'=>env_bool('HTTPS_ENABLED'), 'require_https'=>env_bool('REQUIRE_HTTPS',false),
 'trust_proxy'=>env_bool('TRUST_PROXY',false), 'hsts_enabled'=>env_bool('HSTS_ENABLED',false), 'hsts_max_age'=>(int)env('HSTS_MAX_AGE',31536000),
 'session_idle_seconds'=>(int)env('SESSION_IDLE_SECONDS',3600), 'session_absolute_seconds'=>(int)env('SESSION_ABSOLUTE_SECONDS',43200),
 'session_samesite'=>(string)env('SESSION_SAMESITE','Lax'), 'session_rotate_seconds'=>(int)env('SESSION_ROTATE_SECONDS',900),
 'login_rate_window_seconds'=>(int)env('LOGIN_RATE_WINDOW_SECONDS',900), 'login_rate_user_attempts'=>(int)env('LOGIN_RATE_USER_ATTEMPTS',5),
 'login_rate_ip_attempts'=>(int)env('LOGIN_RATE_IP_ATTEMPTS',20), 'login_rate_retention_seconds'=>(int)env('LOGIN_RATE_RETENTION_SECONDS',86400),
 'rate_limit_secret'=>(string)env('RATE_LIMIT_SECRET',''), 'security_event_retention_days'=>(int)env('SECURITY_EVENT_RETENTION_DAYS',90), 'share_expiry_hours'=>(int)env('SHARE_EXPIRY_HOURS',0),
 'max_upload_mb'=>(int)env('MAX_UPLOAD_MB',2048), 'max_upload_files'=>(int)env('MAX_UPLOAD_FILES',20),
 'upload_chunk_mb'=>(int)env('UPLOAD_CHUNK_MB',8), 'upload_retry_count'=>(int)env('UPLOAD_RETRY_COUNT',3),
 'upload_abandon_hours'=>(int)env('UPLOAD_ABANDON_HOURS',24), 'upload_conflict'=>(string)env('UPLOAD_CONFLICT','rename'),
 'upload_staging_dir'=>(string)env('UPLOAD_STAGING_DIR',''),
 // SMS two-step verification; see .env.example and SECURITY.md. With no
 // SMS_DRIVER nobody can turn it on, and nothing about signing in changes.
 'sms_driver'=>strtolower(trim((string)env('SMS_DRIVER',''))),
 'sms_from'=>trim((string)env('SMS_FROM','')),
 'sms_app_name'=>trim((string)env('SMS_APP_NAME','CloudHub')),
 'sms_timeout_seconds'=>(int)env('SMS_TIMEOUT_SECONDS',10),
 'twilio_account_sid'=>trim((string)env('TWILIO_ACCOUNT_SID','')),
 'twilio_auth_token'=>trim((string)env('TWILIO_AUTH_TOKEN','')),
 'twilio_messaging_service_sid'=>trim((string)env('TWILIO_MESSAGING_SERVICE_SID','')),
 'sms_webhook_url'=>trim((string)env('SMS_WEBHOOK_URL','')),
 'sms_webhook_token'=>trim((string)env('SMS_WEBHOOK_TOKEN','')),
 'sms_webhook_format'=>strtolower(trim((string)env('SMS_WEBHOOK_FORMAT','cloudhub'))),
 'two_factor_secret'=>(string)env('TWO_FACTOR_SECRET',''),
 'two_factor_code_ttl_seconds'=>(int)env('TWO_FACTOR_CODE_TTL_SECONDS',300),
 'two_factor_max_attempts'=>(int)env('TWO_FACTOR_MAX_ATTEMPTS',5),
 'two_factor_resend_seconds'=>(int)env('TWO_FACTOR_RESEND_SECONDS',60),
 'two_factor_sms_per_hour'=>(int)env('TWO_FACTOR_SMS_PER_HOUR',5),
 'two_factor_sms_ip_per_hour'=>(int)env('TWO_FACTOR_SMS_IP_PER_HOUR',20),
 'two_factor_failures_per_hour'=>(int)env('TWO_FACTOR_FAILURES_PER_HOUR',10),
 'two_factor_ip_failures_per_hour'=>(int)env('TWO_FACTOR_IP_FAILURES_PER_HOUR',30),
];
