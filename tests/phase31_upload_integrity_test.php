<?php
declare(strict_types=1);

/**
 * Resumable-upload integrity, exercised against a real staging tree.
 *
 * These pin the fixes ported from the web build: a positioned chunk write (so
 * two requests at the same offset cannot double the file), init() refusing
 * another account's session rather than wiping it, cancel() proving ownership,
 * and complete() replacing an existing file without ever destroying it first.
 */
require dirname(__DIR__).'/src/Services/FileService.php';
require dirname(__DIR__).'/src/Services/UploadService.php';

use CloudHub\Services\FileService;
use CloudHub\Services\UploadService;

function rmrf31(string $p): void {
    if (is_link($p) || is_file($p)) { @unlink($p); return; }
    if (is_dir($p)) { foreach (scandir($p) ?: [] as $n) if ($n !== '.' && $n !== '..') rmrf31($p.'/'.$n); @rmdir($p); }
}

$pass = 0; $fail = 0;
function check(string $name, bool $ok, string $detail = ''): void {
    global $pass, $fail; echo ($ok ? '[PASS] ' : '[FAIL] ').$name.($ok ? '' : ($detail !== '' ? ' -- '.$detail : '')).PHP_EOL;
    $ok ? $pass++ : $fail++;
}

$base = sys_get_temp_dir().'/cloudhub-p31-'.bin2hex(random_bytes(5));
mkdir($base.'/files', 0775, true);
$stage = $base.'/staging';
mkdir($stage, 0775, true);

$config = [
    'root_dir' => $base.'/files', 'read_only' => false,
    'upload_abandon_hours' => 24, 'max_upload_mb' => 64,
    'upload_chunk_mb' => 1, 'upload_staging_dir' => $stage,
    'upload_conflict' => 'rename', 'allow_overwrite' => true,
    'versions_enabled' => true, 'max_versions_per_file' => 10,
];
$fs = new FileService($config);
$svc = new UploadService($config, $fs);

/** One chunk through append() from a real file. */
$sendChunk = static function (UploadService $svc, string $id, int $offset, string $bytes) use ($base): array {
    $chunkFile = $base.'/chunk-'.bin2hex(random_bytes(4)).'.bin';
    file_put_contents($chunkFile, $bytes);
    try { return $svc->append($id, $offset, $chunkFile); }
    finally { @unlink($chunkFile); }
};

/* ---- a positioned write survives a duplicate chunk at the same offset ---- */

$_SESSION = ['user_id' => 7, 'username' => 'seven'];
$payload = random_bytes(300000);
$id = 'pos'.bin2hex(random_bytes(6));
$svc->init('/', 'movie.bin', strlen($payload), $id, 'overwrite');

// Three chunks, each at the offset the previous one reached.
$first = $sendChunk($svc, $id, 0, substr($payload, 0, 100000));
check('the first chunk is accepted', ($first['received'] ?? -1) === 100000, json_encode($first));
$second = $sendChunk($svc, $id, 100000, substr($payload, 100000, 100000));
check('the second chunk continues from the confirmed offset', ($second['received'] ?? -1) === 200000, json_encode($second));

// A stale offset -- one the server has already moved past, as a lost-response
// retry produces -- is refused rather than appended over the top.
$stale = null;
try { $sendChunk($svc, $id, 0, 'x'); } catch (RuntimeException $e) { $stale = $e; }
check('a chunk at a stale offset is a 409', $stale !== null && $stale->getCode() === 409,
    $stale ? (string)$stale->getCode() : 'no throw');
check('the stale chunk changed nothing', ($svc->status($id)['received'] ?? -1) === 200000);

// Finish it and prove the bytes are exactly the source.
$sendChunk($svc, $id, 200000, substr($payload, 200000));
$done = $svc->complete($id);
$landed = $base.'/files'.$done['path'];
check('the finished upload is byte-identical to the source',
    is_file($landed) && hash_file('sha256', $landed) === hash('sha256', $payload));

// The correctness of the concurrent case (two requests that both read the same
// offset before either writes) rests on the write being *positioned* rather
// than appended -- the append-at-EOF version doubled the file. That race is
// not deterministic from one process, so the guarantee is pinned at the
// source: append() opens for update and seeks to the checked offset.
$src = (string)file_get_contents(dirname(__DIR__).'/src/Services/UploadService.php');
check('the chunk write is positioned, not appended',
    str_contains($src, "openPartForWriting(\$part)") && str_contains($src, 'fseek($out, $current)')
    && !str_contains($src, "fopen(\$part, 'ab')"));

/* ---- init() refuses another owner rather than wiping the session --------- */

$shared = 'shared'.bin2hex(random_bytes(6));
$_SESSION = ['user_id' => 1, 'username' => 'one'];
$svc->init('/', 'same.bin', 1000, $shared, 'rename');
$sendChunk($svc, $shared, 0, str_repeat('A', 400));

$_SESSION = ['user_id' => 2, 'username' => 'two'];
$intruder = null;
try { $svc->init('/', 'same.bin', 1000, $shared, 'rename'); }
catch (RuntimeException $e) { $intruder = $e; }
check('a second account cannot init someone else\'s upload id', $intruder !== null && $intruder->getCode() === 404,
    $intruder ? (string)$intruder->getCode() : 'no throw');

$_SESSION = ['user_id' => 1, 'username' => 'one'];
$status = $svc->status($shared);
check('the first account\'s staged bytes are intact', ($status['received'] ?? -1) === 400, json_encode($status));

/* ---- cancel() proves ownership ------------------------------------------- */

$_SESSION = ['user_id' => 2, 'username' => 'two'];
$cancelDenied = null;
try { $svc->cancel($shared); } catch (RuntimeException $e) { $cancelDenied = $e; }
check('another account cannot cancel the upload', $cancelDenied !== null && $cancelDenied->getCode() === 404);
$_SESSION = ['user_id' => 1, 'username' => 'one'];
check('the owner can still see it after the refused cancel', ($svc->status($shared)['received'] ?? -1) === 400);
$svc->cancel($shared);

/* ---- overwrite keeps the previous file rather than destroying it --------- */

$_SESSION = ['user_id' => 7, 'username' => 'seven'];
$v1 = 'version one contents';
$i1 = 'over'.bin2hex(random_bytes(6));
$svc->init('/', 'doc.txt', strlen($v1), $i1, 'overwrite');
$sendChunk($svc, $i1, 0, $v1);
$svc->complete($i1);

$v2 = 'version two contents, longer';
$i2 = 'over'.bin2hex(random_bytes(6));
$svc->init('/', 'doc.txt', strlen($v2), $i2, 'overwrite');
$sendChunk($svc, $i2, 0, $v2);
$svc->complete($i2);

check('the current file holds the new bytes', @file_get_contents($base.'/files/doc.txt') === $v2);
$versions = $fs->versionList('/doc.txt');
check('the replaced file is kept as a version', count($versions) === 1, json_encode($versions));
if ($versions) {
    $kept = $fs->versionPayload('/doc.txt', $versions[0]['id']);
    check('and the kept version is the original bytes', @file_get_contents($kept) === $v1);
}

rmrf31($base);
exit($fail ? 1 : 0);
