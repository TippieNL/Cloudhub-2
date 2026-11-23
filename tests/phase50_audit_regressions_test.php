<?php
declare(strict_types=1);

/**
 * Regressions found by the audit, exercised in-process rather than pinned.
 *
 *  - CloudHub's own top-level names (.trash, .versions, .thumbnails) could be
 *    taken by an upload or a move, which broke every later delete or overwrite.
 *  - A name that is not UTF-8 could be created, and then failed every JSON
 *    answer that named it -- the folder's listing among them.
 *  - The upload ledger matched paths under MySQL's case- and accent-insensitive
 *    collation, so "/Photos" and "/photos" shared rows. SQLite's NOCASE
 *    collation stands in for it here.
 *  - The password throttle counted, then recorded, so a parallel burst all got
 *    through; and changing a password left every other session signed in.
 *
 * Over HTTP these are covered by tests/http/run.php, which needs MySQL.
 */
require dirname(__DIR__).'/src/Helpers/Http.php';
require dirname(__DIR__).'/src/Helpers/Db.php';
require dirname(__DIR__).'/src/Services/FileService.php';
require dirname(__DIR__).'/src/Services/Security.php';
require dirname(__DIR__).'/src/Services/Auth.php';
require dirname(__DIR__).'/src/Services/LoginRateLimiter.php';
require dirname(__DIR__).'/src/Services/Authorization.php';
require dirname(__DIR__).'/src/Repositories/StorageLedger.php';
require dirname(__DIR__).'/src/Repositories/TwoFactorRepository.php';
require dirname(__DIR__).'/src/Repositories/UserRepository.php';

use CloudHub\Helpers\Db;
use CloudHub\Repositories\StorageLedger;
use CloudHub\Repositories\UserRepository;
use CloudHub\Services\Auth;
use CloudHub\Services\FileService;
use CloudHub\Services\LoginRateLimiter;

$root = dirname(__DIR__);
$checks = [];
$refused = static function (callable $fn, int $status): bool {
    try { $fn(); return false; } catch (RuntimeException $e) { return $e->getCode() === $status; }
};

/* ---- the storage root's own names --------------------------------------------- */

$base = sys_get_temp_dir().'/cloudhub-p50-'.bin2hex(random_bytes(5));
mkdir($base.'/Holiday', 0775, true);
$fs = new FileService(['root_dir' => $base, 'read_only' => false]);
foreach (['.trash', '.versions', '.thumbnails', '.Trash', '.versions.'] as $name) {
    $checks["$name cannot be a destination at the top"] = $refused(fn() => $fs->assertNotReserved($fs->root().'/'.$name), 403);
}
$fs->assertNotReserved($fs->root().'/Holiday/.trash');
$fs->assertNotReserved($fs->root().'/notes.txt');
$checks['below the top, and any other name, is fine'] = true;
$checks['the upload, multipart, move and copy routes ask'] =
    substr_count((string)file_get_contents($root.'/public/index.php'), '$fs->assertNotReserved(') === 2
    && substr_count((string)file_get_contents($root.'/src/Services/UploadService.php'), 'assertNotReserved(') === 2;

/* ---- names that are not UTF-8 ---------------------------------------------------- */

$checks['an upload name that is not UTF-8 is refused'] = $refused(fn() => $fs->safeName("caf\xE9.txt"), 400);
$checks['so is a new path'] = $refused(fn() => $fs->destination("/Holiday/caf\xE9.txt"), 400);
$checks['while UTF-8 of any script is fine'] = $fs->safeName('café 写真 🎞.txt') === 'café 写真 🎞.txt'
    && str_ends_with($fs->destination('/Holiday/café.txt'), '/Holiday/café.txt');
file_put_contents($base."/Holiday/legacy-caf\xE9.txt", 'legacy');
$listing = $fs->list('/Holiday');
$encoded = json_encode($listing, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE | JSON_THROW_ON_ERROR);
$checks['a name already on the disk still encodes, as U+FFFD'] = str_contains($encoded, "legacy-caf\u{FFFD}.txt");
$http = (string)file_get_contents($root.'/src/Helpers/Http.php');
$checks['and Http::json() and Http::error() substitute rather than fail'] =
    substr_count($http, 'JSON_INVALID_UTF8_SUBSTITUTE') >= 2;

/* ---- the ledger compares paths exactly -------------------------------------------- */

$db = new PDO('sqlite::memory:', null, null, [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC]);
// COLLATE NOCASE: compares the way utf8mb4_unicode_ci does for these names.
$db->exec('CREATE TABLE file_metadata (id INTEGER PRIMARY KEY AUTOINCREMENT, server_id INTEGER NOT NULL,
    file_path TEXT NOT NULL COLLATE NOCASE, original_name TEXT NOT NULL, size INTEGER NOT NULL, mime_type TEXT, uploaded_by INTEGER)');
$db->exec('CREATE TABLE storage_servers (id INTEGER PRIMARY KEY AUTOINCREMENT, is_default INTEGER)');
$db->exec('INSERT INTO storage_servers (is_default) VALUES (1)');
$ledger = new StorageLedger($db);
$ledger->record('/Photos/a.jpg', 'a.jpg', 100, null, 1);
$ledger->record('/photos/a.jpg', 'a.jpg', 30, null, 2);
$ledger->record('/A.txt', 'A.txt', 5, null, 1);
$ledger->record('/a.txt', 'a.txt', 7, null, 2);
$checks['uploading /a.txt does not replace the row of /A.txt'] = $ledger->usage(1) === 105 && $ledger->usage(2) === 37;
$checks['rowsUnder(/photos) is that folder only'] = array_column($ledger->rowsUnder('/photos'), 'path') === ['/photos/a.jpg'];
$ledger->relocate('/photos', '/old/photos');
$paths = array_column($db->query('SELECT file_path FROM file_metadata ORDER BY id')->fetchAll(), 'file_path');
$checks['moving /photos leaves /Photos where it is'] = $paths === ['/Photos/a.jpg', '/old/photos/a.jpg', '/A.txt', '/a.txt'];
$ledger->forget('/old/photos');
$ledger->forget('/a.txt');
$checks['and forgetting one never takes the other'] = $ledger->usage(1) === 105 && $ledger->usage(2) === 0;

/* ---- the password throttle --------------------------------------------------------- */

$db->exec('CREATE TABLE login_attempts (id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, attempt_key TEXT NOT NULL, attempted_at TEXT NOT NULL)');
$_SERVER['REMOTE_ADDR'] = '203.0.113.9';
$limiterConfig = ['login_rate_window_seconds' => 900, 'login_rate_user_attempts' => 5, 'login_rate_ip_attempts' => 20, 'rate_limit_secret' => 's'];
$limiter = new LoginRateLimiter($db, $limiterConfig);
// Five attempts all begun before any finished: each is counted as it begins.
$held = [];
for ($i = 0; $i < 5; $i++) $held[] = $limiter->beginAttempt('alice');
$checks['a sixth attempt in flight is refused before its password is checked'] = $refused(fn() => $limiter->beginAttempt('Alice '), 429);
$limiter->succeeded($held[0]);
$checks['a right password gives its slot back'] = count($limiter->beginAttempt('alice')) === 2;
$checks['and a refusal never kept one'] = (int)$db->query("SELECT COUNT(*) FROM login_attempts WHERE scope='user'")->fetchColumn() === 5;

/* ---- a password change ends the other sessions ------------------------------------ */

$db->exec('CREATE TABLE users (id INTEGER PRIMARY KEY, username TEXT, password_hash TEXT, is_active INTEGER, role TEXT,
    two_factor_enabled_at TEXT, created_at TEXT, last_login_at TEXT)');
$db->prepare('INSERT INTO users (id, username, password_hash, is_active, role) VALUES (1, ?, ?, 1, ?)')->execute(['alice', password_hash('old-password-1', PASSWORD_BCRYPT, ['cost' => 4]), 'editor']);
(new ReflectionProperty(Db::class, 'pdo'))->setValue(null, $db);
$scratch = $base.'/.sessions';
mkdir($scratch);
ini_set('session.save_path', $scratch);
ini_set('session.use_cookies', '0');
ini_set('session.use_only_cookies', '0');
ini_set('session.use_strict_mode', '0');
ini_set('session.cache_limiter', '');
session_start();
$revalidate = new ReflectionMethod(Auth::class, 'revalidateAccount');
$checks['signing in stamps the session'] = Auth::login($db, 'alice', 'old-password-1') && is_string($_SESSION['credential'] ?? null);
$_SESSION['account_checked_at'] = 0;
$revalidate->invoke(null, time());
$checks['an unchanged password keeps it signed in'] = Auth::user() !== null;

(new UserRepository($db))->update(1, ['password' => 'new-password-12']);
$_SESSION['account_checked_at'] = 0;
$revalidate->invoke(null, time());
$checks['a changed password signs it out at the next re-check'] = Auth::user() === null;

Auth::login($db, 'alice', 'new-password-12');
(new UserRepository($db))->update(1, ['password' => 'newer-password-1']);
Auth::refreshCredential($db);
$_SESSION['account_checked_at'] = 0;
$revalidate->invoke(null, time());
$checks['the session that made the change carries on'] = Auth::user() !== null;

unset($_SESSION['credential']);
$_SESSION['account_checked_at'] = 0;
$revalidate->invoke(null, time());
$checks['a session from before stamps adopts one rather than ending'] = Auth::user() !== null && isset($_SESSION['credential']);

// A login that upgrades the stored hash must stamp the session with the new
// hash, or its own next re-check would read the upgrade as a change.
$db->prepare('UPDATE users SET password_hash = ? WHERE id = 1')->execute([password_hash('newer-password-1', PASSWORD_BCRYPT, ['cost' => 4])]);
Auth::login($db, 'alice', 'newer-password-1');
$_SESSION['account_checked_at'] = 0;
$revalidate->invoke(null, time());
$checks['a rehash at sign-in does not sign the session out'] = Auth::user() !== null;
$checks['the stamp is a digest, never the hash itself'] =
    !str_contains((string)json_encode($_SESSION), '$2y$') && !str_contains((string)json_encode($_SESSION), '$argon2');
session_write_close();

/* ---- tidy ---------------------------------------------------------------------------- */

$rm = static function (string $p) use (&$rm): void {
    if (is_dir($p) && !is_link($p)) { foreach (array_diff(scandir($p) ?: [], ['.', '..']) as $n) $rm($p.'/'.$n); @rmdir($p); }
    else @unlink($p);
};
$rm($base);

$bad = false;
foreach ($checks as $name => $ok) {
    echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL;
    $bad = $bad || !$ok;
}
exit($bad ? 1 : 0);
