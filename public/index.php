<?php
declare(strict_types = 1);
require dirname(__DIR__).'/config/bootstrap.php';
use CloudHub\Helpers\Http;
use CloudHub\Services\FileService;
use CloudHub\Repositories\ServerRepository;
use CloudHub\Services\Auth;
use CloudHub\Services\UploadService;
use CloudHub\Services\Security;
use CloudHub\Services\LoginRateLimiter;
use CloudHub\Services\Authorization;
use CloudHub\Services\AuditLog;

$fs = new FileService($config); $uploads = new UploadService($config, $fs); $basePath = Http::basePath(); $assetBase = Http::assetBase(); $path = Http::requestPath($basePath); $method = $_SERVER['REQUEST_METHOD']??'GET';
$frontController = ($basePath === '' ? '/' : $basePath.'/');
Auth::startSession($config);
Security::applyHeaders($config);
Security::assertProductionConfig($config);
header('X-Request-ID: '.Http::requestId());
function mime_type(string $f): string {
    return function_exists('mime_content_type')?(mime_content_type($f)?:'application/octet-stream'):'application/octet-stream';
}

/**
 * Resolve a browser media MIME type reliably on Android/KSWEB.
 *
 * mime_content_type() is not guaranteed to recognise every extension on
 * Android builds, so media streaming must not depend on libmagic alone.
 */
function media_mime_type(string $f): string {
    $extensionMime = match (strtolower((string)pathinfo($f, PATHINFO_EXTENSION))) {
        'mp4', 'm4v' => 'video/mp4',
        'webm' => 'video/webm',
        'ogv', 'ogg' => 'video/ogg',
        'mov' => 'video/quicktime',
        'avi' => 'video/x-msvideo',
        'mkv' => 'video/x-matroska',
        'mpeg', 'mpg' => 'video/mpeg',
        '3gp' => 'video/3gpp',
        '3g2' => 'video/3gpp2',
        'ts', 'm2ts', 'mts' => 'video/mp2t',
        'mp3' => 'audio/mpeg',
        'wav' => 'audio/wav',
        'm4a' => 'audio/mp4',
        'aac' => 'audio/aac',
        'flac' => 'audio/flac',
        default => '',
    };

    if ($extensionMime !== '') return $extensionMime;

    $mime = mime_type($f);
    return $mime !== '' ? $mime : 'application/octet-stream';
}
function api_try(callable $fn): never {
    try {
        $r = $fn(); if ($r !== null)Http::json($r);
    }catch(RuntimeException $e) {
        $status = (int)$e->getCode(); if ($status < 400 || $status > 599)$status = 500; $codes = [400 => 'BAD_REQUEST',
            401 => 'UNAUTHORIZED',
            403 => 'FORBIDDEN',
            404 => 'NOT_FOUND',
            409 => 'CONFLICT',
            410 => 'GONE',
            413 => 'REQUEST_TOO_LARGE',
            415 => 'UNSUPPORTED_MEDIA_TYPE',
            419 => 'CSRF_FAILED',
            422 => 'VALIDATION_FAILED',
            429 => 'RATE_LIMITED']; $msg = $status >= 500?'An internal server error occurred':$e->getMessage(); if ($status >= 500)error_log('['.Http::requestId().'] '.$e->getMessage()); Http::error($status, $codes[$status]??'INTERNAL_ERROR', $msg);
    }catch(Throwable $e) {
        error_log('['.Http::requestId().'] '.get_class($e).': '.$e->getMessage()); Http::error(500, 'INTERNAL_ERROR', 'An internal server error occurred');
    }exit;
}
function db(): PDO {
    static $pdo; if (!$pdo) {
        $c = require dirname(__DIR__).'/config/database.php'; $pdo = new PDO($c['dsn'], $c['user'], $c['pass'], [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
    }return $pdo;
}
function mask_server(array $s): array {
    foreach (['password', 'privateKey', 'apiKey'] as $k)if (!empty($s['config'][$k]))$s['config'][$k] = '••••••••'; return $s;
}

if ($path === '/api/auth/login' && $method === 'POST') api_try(function()use($config) {
    $b = Http::body(16384); $u = Http::string($b, 'username', 1, 100); $p = Http::string($b, 'password', 1, 4096);
    $limiter = new LoginRateLimiter(db(), $config); $limiter->assertAllowed($u);
    if (!Auth::login(db(), $u, $p)) {
        $limiter->recordFailure($u); AuditLog::write(db(), 'auth.login', 'failure', ['username' => $u]); throw new RuntimeException('Invalid username or password', 401);
    }
    $limiter->clearUserFailures($u);
    AuditLog::write(db(), 'auth.login', 'success');
    return ['success' => true,
        'user' => Auth::user(),
        'csrfToken' => $_SESSION['csrf']];
});
if ($path === '/api/auth/status' && $method === 'GET') Http::json(['authenticated' => Auth::user() !== null, 'user' => Auth::user(), 'csrfToken' => $_SESSION['csrf']]);
if ($path === '/api/auth/logout' && $method === 'POST') {
    Authorization::requireRead(); Auth::verifyCsrf(); AuditLog::write(db(), 'auth.logout'); Auth::logout(); Http::json(['success' => true]);
}
$isAuthEndpoint = str_starts_with($path, '/api/auth/');
$isProtectedApi = (str_starts_with($path, '/api/')&&!$isAuthEndpoint) || str_starts_with($path, '/webdav');
if ($isProtectedApi && $method !== 'OPTIONS') {
    Authorization::requireRead();
    if (in_array($method, ['POST', 'PUT', 'PATCH', 'DELETE'], true)) {
        Auth::verifyCsrf();
        if (str_starts_with($path, '/api/servers'))Authorization::requireAdmin();
        else Authorization::requireWrite();
    }
}
if ($path === '/api/files/config') Http::json([
    'readOnly' => $config['read_only'], 'allowDelete' => $config['allow_delete'], 'allowOverwrite' => $config['allow_overwrite'],
    'maxUploadMb' => $config['max_upload_mb'], 'maxUploadFiles' => $config['max_upload_files'],
    'chunkMb' => $config['upload_chunk_mb'], 'retryCount' => $config['upload_retry_count'],
    'conflict' => $config['upload_conflict']
]);
if ($path === '/api/files/list' && $method === 'GET') api_try(fn() => $fs->list((string)($_GET['path']??'/')));
if ($path === '/api/files/download' && $method === 'GET') api_try(function()use($fs) {
    $f = $fs->existing((string)($_GET['path']??'')); if (!is_file($f))throw new RuntimeException('File not found', 404); header('Content-Type: '.mime_type($f)); header('Content-Disposition: attachment; filename="'.str_replace(['"', "\r", "\n"], '_', basename($f)).'"'); header('Content-Length: '.filesize($f)); readfile($f); exit;
});
/**
* Streams a file for the authenticated preview dialog.
*
* The browser receives an inline disposition for previewable media. The route
* still uses FileService::sanitize() and the normal authenticated API guard, so
* arbitrary filesystem paths cannot be requested by the client.
*/

/**
* Streams authenticated media for the dedicated cfh-player route.
*
* This keeps video playback on the custom player while allowing the rest of
* the UI to use the generic preview endpoint for non-video assets.
*/
if (($path === '/api/files/stream') && ($method === 'GET' || $method === 'HEAD')) api_try(function()use($fs, $method) {
    $f = $fs->existing((string)($_GET['path']??''));
    if (!is_file($f))throw new RuntimeException('File not found', 404);

    $mime = media_mime_type($f);
    if (!str_starts_with($mime, 'video/') && !str_starts_with($mime, 'audio/')) {
        throw new RuntimeException('This file type does not support media streaming', 415);
    }

    $size = filesize($f);
    if ($size === false)throw new RuntimeException('Unable to determine file size', 500);

    $start = 0;
    $end = max(0, $size-1);
    $status = 200;

    $range = trim((string)($_SERVER['HTTP_RANGE']??''));
    if ($range !== '') {
        if (!preg_match('/^bytes=(\d*)-(\d*)$/', $range, $m)) {
            http_response_code(416);
            header('Content-Range: bytes */'.$size);
            header('Accept-Ranges: bytes');
            exit;
        }

        $first = $m[1];
        $last = $m[2];

        if ($first === '' && $last === '') {
            http_response_code(416);
            header('Content-Range: bytes */'.$size);
            header('Accept-Ranges: bytes');
            exit;
        }

        if ($first === '') {
            $suffix = (int)$last;
            if ($suffix <= 0) {
                http_response_code(416);
                header('Content-Range: bytes */'.$size);
                header('Accept-Ranges: bytes');
                exit;
            }
            $suffix = min($suffix, $size);
            $start = $size-$suffix;
            $end = $size-1;
        } else {
            $start = (int)$first;
            $end = $last === ''?$size-1:min((int)$last, $size-1);
            if ($start >= $size || $start > $end) {
                http_response_code(416);
                header('Content-Range: bytes */'.$size);
                header('Accept-Ranges: bytes');
                exit;
            }
        }
        $status = 206;
    }

    $length = $size === 0?0:($end-$start+1);
    http_response_code($status);
    header('Content-Type: '.$mime);
    header('Content-Disposition: inline; filename="'.str_replace(['"', "\r", "\n"], '_', basename($f)).'"');
    header('Accept-Ranges: bytes');
    header('Content-Length: '.$length);
    header('Cache-Control: private,max-age=300');
    header('X-Content-Type-Options: nosniff');
    if ($status === 206)header('Content-Range: bytes '.$start.'-'.$end.'/'.$size);

    if ($method === 'HEAD' || $length === 0)exit;

    @set_time_limit(0);
    while (ob_get_level() > 0)@ob_end_clean();

    $handle = @fopen($f, 'rb');
    if ($handle === false)throw new RuntimeException('Unable to open media file', 500);
    if ($start > 0 && fseek($handle, $start) !== 0) {
        fclose($handle);
        throw new RuntimeException('Unable to seek media file', 500);
    }

    $remaining = $length;
    $bufferSize = 1024*1024;
    while ($remaining > 0&&!feof($handle)) {
        if (connection_aborted())break;
        $read = min($bufferSize, $remaining);
        $data = fread($handle, $read);
        if ($data === false || $data === '')break;
        echo $data;
        $remaining -= strlen($data);
        flush();
    }
    fclose($handle);
    exit;
});
if (($path === '/api/files/preview') && ($method === 'GET' || $method === 'HEAD')) api_try(function()use($fs, $method) {
    $f = $fs->existing((string)($_GET['path']??''));
    if (!is_file($f))throw new RuntimeException('File not found', 404);

    $mime = mime_type($f);
    $inline = str_starts_with($mime, 'image/') || str_starts_with($mime, 'audio/') || $mime === 'application/pdf' || $mime === 'text/plain';
    if (!$inline)throw new RuntimeException('This file type does not support inline preview', 415);

    $size = filesize($f);
    if ($size === false)throw new RuntimeException('Unable to determine file size', 500);

    $start = 0;
    $end = max(0, $size-1);
    $status = 200;

    /*
  * Large media players use HTTP byte ranges to read metadata, start playback
  * quickly and seek without downloading the entire file. Only a single range
  * is served per request; malformed/multiple ranges receive HTTP 416.
  */
    $range = trim((string)($_SERVER['HTTP_RANGE']??''));
    if ($range !== '') {
        if (!preg_match('/^bytes=(\d*)-(\d*)$/', $range, $m)) {
            http_response_code(416);
            header('Content-Range: bytes */'.$size);
            header('Accept-Ranges: bytes');
            exit;
        }

        $first = $m[1];
        $last = $m[2];

        if ($first === '' && $last === '') {
            http_response_code(416);
            header('Content-Range: bytes */'.$size);
            header('Accept-Ranges: bytes');
            exit;
        }

        if ($first === '') {
            // Suffix range: bytes=-500 requests the final 500 bytes.
            $suffix = (int)$last;
            if ($suffix <= 0) {
                http_response_code(416);
                header('Content-Range: bytes */'.$size);
                header('Accept-Ranges: bytes');
                exit;
            }
            $suffix = min($suffix, $size);
            $start = $size-$suffix;
            $end = $size-1;
        } else {
            $start = (int)$first;
            $end = $last === ''?$size-1:min((int)$last, $size-1);
            if ($start >= $size || $start > $end) {
                http_response_code(416);
                header('Content-Range: bytes */'.$size);
                header('Accept-Ranges: bytes');
                exit;
            }
        }
        $status = 206;
    }

    $length = $size === 0?0:($end-$start+1);
    http_response_code($status);
    header('Content-Type: '.$mime);
    header('Content-Disposition: inline; filename="'.str_replace(['"', "\r", "\n"], '_', basename($f)).'"');
    header('Accept-Ranges: bytes');
    header('Content-Length: '.$length);
    header('Cache-Control: private,max-age=300');
    header('X-Content-Type-Options: nosniff');
    if ($status === 206)header('Content-Range: bytes '.$start.'-'.$end.'/'.$size);

    if ($method === 'HEAD' || $length === 0)exit;

    // Avoid readfile() for large media. Stream only the requested byte interval
    // in bounded buffers so PHP memory usage stays essentially constant.
    @set_time_limit(0);
    while (ob_get_level() > 0)@ob_end_clean();

    $handle = @fopen($f, 'rb');
    if ($handle === false)throw new RuntimeException('Unable to open preview file', 500);
    if ($start > 0 && fseek($handle, $start) !== 0) {
        fclose($handle);
        throw new RuntimeException('Unable to seek preview file', 500);
    }

    $remaining = $length;
    $bufferSize = 1024*1024; // 1 MiB server-side streaming buffer.
    while ($remaining > 0&&!feof($handle)) {
        if (connection_aborted())break;
        $read = min($bufferSize, $remaining);
        $data = fread($handle, $read);
        if ($data === false || $data === '')break;
        echo $data;
        $remaining -= strlen($data);
        flush();
    }
    fclose($handle);
    exit;
});
if ($path === '/api/files/mkdir' && $method === 'POST') api_try(function()use($fs) {
    global $config; $fs->writable(); $b = Http::body(); $p = $fs->destination((string)($b['path']??'')); if (file_exists($p))throw new RuntimeException('Directory already exists', 409); if (!mkdir($p, 0775, true)&&!is_dir($p))throw new RuntimeException('Unable to create directory', 500); return ['success' => true,
        'message' => 'Directory created'];
});
if ($path === '/api/files/delete' && $method === 'DELETE') api_try(function()use($fs, $config) {
    $fs->writable(); if (!$config['allow_delete'])throw new RuntimeException('Delete is not allowed', 403); $b = Http::body(); $p = $fs->existing((string)($b['path']??'')); if (!file_exists($p))throw new RuntimeException('File or directory not found', 404); $fs->deleteTree($p); return ['success' => true,
        'message' => 'Deleted successfully'];
});
if ($path === '/api/files/rename' && $method === 'POST') api_try(function()use($fs, $config) {
    $fs->writable(); $b = Http::body(); $a = $fs->existing((string)($b['oldPath']??'')); $z = $fs->destination((string)($b['newPath']??'')); if (!file_exists($a))throw new RuntimeException('Source not found', 404); if (file_exists($z)&&!$config['allow_overwrite'])throw new RuntimeException('Destination already exists', 409); if (!rename($a, $z))throw new RuntimeException('Rename failed', 500); return ['success' => true,
        'message' => 'Renamed successfully'];
});
/**
* Resumable upload protocol.
*
* init -> repeated raw chunk PUTs -> complete. The browser can query status at
* any point and continue from the server-confirmed byte offset. This avoids
* PHP's normal multipart/post_max_size limit because each request contains only
* one small chunk.
*/
if ($path === '/api/uploads/init' && $method === 'POST') api_try(function()use($uploads, $config) {
    $b = Http::body();
    return $uploads->init(
        (string)($b['targetPath']??'/'), (string)($b['name']??''), (int)($b['size']??-1),
        (string)($b['uploadId']??''), (string)($b['conflict']??$config['upload_conflict'])
    );
});
if ($path === '/api/uploads/status' && $method === 'GET') api_try(fn() => $uploads->status((string)($_GET['id']??'')));
if ($path === '/api/uploads/chunk' && $method === 'PUT') api_try(function()use($uploads) {
    $id = (string)($_GET['id']??''); $offset = (int)($_SERVER['HTTP_X_UPLOAD_OFFSET']??-1);
    return $uploads->append($id, $offset, 'php://input');
});
if ($path === '/api/uploads/complete' && $method === 'POST') api_try(function()use($uploads) {
    $b = Http::body(); return $uploads->complete((string)($b['id']??''));
});
if ($path === '/api/uploads/cancel' && $method === 'DELETE') api_try(function()use($uploads) {
    $b = Http::body(); return $uploads->cancel((string)($b['id']??''));
});
if ($path === '/api/uploads/cleanup' && $method === 'POST') api_try(fn() => ['success' => true, 'removed' => $uploads->cleanupAbandoned()]);

/**
* Upload one or more files into the requested storage directory.
*
* Browser-side validation improves feedback, but all limits are repeated here
* because request data is untrusted. PHP upload error codes are translated into
* useful API messages so the upload dialog can explain failures to the user.
*/
if ($path === '/api/files/upload' && $method === 'POST') api_try(function()use($fs, $config) {
    $fs->writable();
    $target = $fs->existing((string)($_POST['targetPath']??'/'));
    if (!is_dir($target)) throw new RuntimeException('Unable to create the upload directory', 500);

    $names = $_FILES['files']['name']??[];
    $tmp = $_FILES['files']['tmp_name']??[];
    $errs = $_FILES['files']['error']??[];
    $sizes = $_FILES['files']['size']??[];
    if (!is_array($names)) {
        $names = [$names]; $tmp = [$tmp]; $errs = [$errs]; $sizes = [$sizes];
    }
    if (!$names) throw new RuntimeException('No files were selected', 400);
    if (count($names) > $config['max_upload_files']) throw new RuntimeException('Maximum '.$config['max_upload_files'].' files can be uploaded at once', 400);

    $maxBytes = max(1, (int)$config['max_upload_mb'])*1024*1024;
    $saved = [];
    $uploadError = function(int $code, string $name): string {
        return match($code) {
            UPLOAD_ERR_INI_SIZE => 'The server rejected '.$name.' because it exceeds upload_max_filesize.',
            UPLOAD_ERR_FORM_SIZE => 'The file '.$name.' exceeds the form upload limit.',
            UPLOAD_ERR_PARTIAL => 'The upload of '.$name.' was interrupted before it completed.',
            UPLOAD_ERR_NO_FILE => 'No file data was received for '.$name.'.',
            UPLOAD_ERR_NO_TMP_DIR => 'The server upload temporary directory is missing.',
            UPLOAD_ERR_CANT_WRITE => 'The server could not write '.$name.' to temporary storage.',
            UPLOAD_ERR_EXTENSION => 'A PHP extension stopped the upload of '.$name.'.',
            default => 'Upload failed for '.$name.'.'
            };
        };

        foreach ($names as $i => $name) {
            $safe = $fs->safeName((string)$name);
            $error = (int)($errs[$i]??UPLOAD_ERR_NO_FILE);
            if ($error !== UPLOAD_ERR_OK) throw new RuntimeException($uploadError($error, $safe), 400);
            if ((int)($sizes[$i]??0) > $maxBytes) throw new RuntimeException($safe.' exceeds the '.$config['max_upload_mb'].' MB per-file limit', 413);
            if (!is_uploaded_file((string)($tmp[$i]??''))) throw new RuntimeException('Invalid upload data received for '.$safe, 400);
            $dest = $target.'/'.$safe;
            if (file_exists($dest)&&!$config['allow_overwrite']) throw new RuntimeException('File already exists: '.$safe, 409);
            if (!move_uploaded_file((string)$tmp[$i], $dest)) throw new RuntimeException('Unable to save '.$safe, 500);
            $saved[] = $safe;
        }
        return ['success' => true,
            'message' => count($saved).' file(s) uploaded successfully',
            'files' => $saved];
    });
    if ($path === '/api/files/download-zip' && $method === 'POST') api_try(function()use($fs) {
        if (!class_exists('ZipArchive'))throw new RuntimeException('PHP zip extension is required', 500); $b = Http::body(262144); $files = $b['files']??[]; if (!is_array($files)||!array_is_list($files)||!$files)throw new RuntimeException('No files specified', 400); if (count($files) > 500)Http::error(422, 'VALIDATION_FAILED', 'A maximum of 500 files can be downloaded at once'); foreach ($files as $item)if (!is_string($item) || strlen($item) > 4096)Http::error(422, 'VALIDATION_FAILED', 'Invalid file path in selection'); $tmp = tempnam(sys_get_temp_dir(), 'cfhzip'); $zip = new ZipArchive(); $zip->open($tmp, ZipArchive::OVERWRITE); $add = function($full, $prefix)use(&$add, $zip, $fs) {
            if (is_dir($full)) {
                foreach (scandir($full)?:[] as $n)if ($n !== '.' && $n !== '..'&&!$fs->escapingSymlink($full.'/'.$n))$add($full.'/'.$n, $prefix.'/'.$n);
            } elseif (is_file($full))$zip->addFile($full, ltrim($prefix, '/'));
        }; foreach ($files as $p) {
            $f = $fs->existing((string)$p); if (file_exists($f))$add($f, basename($f));
        }$zip->close(); header('Content-Type: application/zip'); header('Content-Disposition: attachment; filename="download.zip"'); header('Content-Length: '.filesize($tmp)); readfile($tmp); unlink($tmp); exit;
    });

    if ($path === '/api/shares/create' && $method === 'POST') api_try(function()use($fs, $config, $frontController) {
        $b = Http::body(16384); $rel = Http::string($b, 'filePath', 1, 4096); $f = $fs->existing($rel); if (!is_file($f))throw new RuntimeException('File not found', 404); $pdo = db(); $s = $pdo->prepare('SELECT token,expires_at FROM share_links WHERE file_path=? AND (expires_at IS NULL OR expires_at>NOW()) LIMIT 1'); $s->execute([$rel]); $r = $s->fetch(PDO::FETCH_ASSOC); if (!$r) {
            $token = rtrim(strtr(base64_encode(random_bytes(32)), '+/', '-_'), '='); $hours = Http::optionalInt($b, 'expiresInHours', 0, 8760, (int)$config['share_expiry_hours']); $exp = $hours > 0?gmdate('Y-m-d H:i:s', time()+$hours*3600):null; $s = $pdo->prepare('INSERT INTO share_links(token,file_path,expires_at) VALUES(?,?,?)'); $s->execute([$token, $rel, $exp]); $r = ['token' => $token,
                'expires_at' => $exp];
        }$scheme = ($_SERVER['HTTP_X_FORWARDED_PROTO']??($config['https_enabled']?'https':'http')); $host = $_SERVER['HTTP_HOST']??'localhost'; return ['token' => $r['token'],
            'url' => $scheme.'://'.$host.$frontController.'?route='.rawurlencode('/share/'.$r['token']),
            'expiresAt' => $r['expires_at']?gmdate('c', strtotime($r['expires_at'])):null];
    });
    if ($path === '/api/shares/list' && $method === 'GET')Authorization::requireAdmin();
    if ($path === '/api/shares/list' && $method === 'GET') api_try(function() {
        $pdo = db(); $pdo->exec('DELETE FROM share_links WHERE expires_at IS NOT NULL AND expires_at<NOW()'); $r = $pdo->query('SELECT token,file_path,created_at,expires_at FROM share_links ORDER BY created_at DESC')->fetchAll(PDO::FETCH_ASSOC); return array_map(fn($x) => ['token' => $x['token'], 'filePath' => $x['file_path'], 'createdAt' => gmdate('c', strtotime($x['created_at'])), 'expiresAt' => $x['expires_at']?gmdate('c', strtotime($x['expires_at'])):null], $r);
    });
    if ($path === '/api/shares/revoke' && $method === 'DELETE') api_try(function() {
        $b = Http::body(8192); $token = Http::string($b, 'token', 20, 128); if (!preg_match('/^[A-Za-z0-9_-]+$/', $token))Http::error(422, 'VALIDATION_FAILED', 'token has an invalid format'); $s = db()->prepare('DELETE FROM share_links WHERE token=?'); $s->execute([$token]); if (!$s->rowCount())throw new RuntimeException('Token not found', 404); return ['success' => true,
            'message' => 'Share link revoked'];
    });
    if (preg_match('#^/share/([A-Za-z0-9_-]{20,})$#', $path, $m)) api_try(function()use($m, $fs) {
        $s = db()->prepare('SELECT * FROM share_links WHERE token=?'); $s->execute([$m[1]]); $r = $s->fetch(PDO::FETCH_ASSOC); if (!$r)throw new RuntimeException('Share link not found or expired', 404); if ($r['expires_at'] && strtotime($r['expires_at']) < time())throw new RuntimeException('Share link has expired', 410); $f = $fs->sanitize($r['file_path']); if (!is_file($f))throw new RuntimeException('File no longer exists', 404); $mime = mime_type($f); $inline = str_starts_with($mime, 'image/') || str_starts_with($mime, 'video/') || str_starts_with($mime, 'audio/') || in_array($mime, ['application/pdf', 'text/plain'], true); header('Content-Type: '.$mime); header('Content-Disposition: '.($inline?'inline':'attachment').'; filename="'.str_replace(['"', "\r", "\n"], '_', basename($f)).'"'); header('Cache-Control: no-store'); header('X-Content-Type-Options: nosniff'); header("Content-Security-Policy: default-src 'none'; sandbox; img-src 'self' data:; media-src 'self'; style-src 'none'; script-src 'none'"); readfile($f); exit;
    });

    if ($path === '/api/thumbnail' && $method === 'GET') api_try(function()use($fs, $config) {
        $f = $fs->existing((string)($_GET['path'] ?? ''));
        if (!is_file($f))throw new RuntimeException('File not found', 404);

        $ext = strtolower(pathinfo($f, PATHINFO_EXTENSION));
        $videoExtensions = ['mp4', 'webm', 'ogv', 'ogg', 'mov', 'm4v', 'avi', 'mkv', 'mpeg', 'mpg', '3gp', '3g2', 'ts', 'm2ts', 'mts'];

        if (in_array($ext, $videoExtensions, true)) {
            throw new RuntimeException(
                'Video thumbnails are generated by the browser; use the media stream endpoint',
                415
            );
        }

        if (!in_array($ext, ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp'], true)) {
            throw new RuntimeException('Not a supported thumbnail type', 400);
        }

        if (!extension_loaded('gd')) {
            throw new RuntimeException('GD extension is required for image thumbnails', 503);
        }

        $mtime = @filemtime($f);
        if ($mtime === false)throw new RuntimeException('Unable to determine file modification time', 500);

        $cacheDir = dirname(__DIR__) . '/storage/.thumbnails/images';
        if (!is_dir($cacheDir) && !@mkdir($cacheDir, 0775, true) && !is_dir($cacheDir)) {
            throw new RuntimeException('Unable to create the image thumbnail cache', 500);
        }

        $cache = $cacheDir . '/' . md5($f . ':' . $mtime) . '.webp';

        if (!is_file($cache)) {
            [$w, $h] = getimagesize($f) ?: [0, 0];
            $create = match($ext) {
                'jpg', 'jpeg' => @imagecreatefromjpeg($f),
                'png' => @imagecreatefrompng($f),
                'gif' => @imagecreatefromgif($f),
                'webp' => function_exists('imagecreatefromwebp') ? @imagecreatefromwebp($f) : false,
                'bmp' => function_exists('imagecreatefrombmp') ? @imagecreatefrombmp($f) : false,
                default => false
            };

            if (!$create || !$w || !$h)throw new RuntimeException('Failed to generate thumbnail', 500);

            $scale = min(300 / $w, 300 / $h, 1);
            $nw = max(1, (int)round($w * $scale));
            $nh = max(1, (int)round($h * $scale));
            $im = imagecreatetruecolor($nw, $nh);
            imagecopyresampled($im, $create, 0, 0, 0, 0, $nw, $nh, $w, $h);

            if (!@imagewebp($im, $cache, 75)) {
                imagedestroy($im);
                imagedestroy($create);
                throw new RuntimeException('Failed to store image thumbnail', 500);
            }

            imagedestroy($im);
            imagedestroy($create);
        }

        header('Content-Type: image/webp');
        header('Cache-Control: private,max-age=86400,immutable');
        header('X-Content-Type-Options: nosniff');
        header('Content-Length: ' . filesize($cache));
        readfile($cache);
        exit;
    });

    if ($path === '/api/security/events' && $method === 'GET') api_try(function() {
        Authorization::requireAdmin();
        $limit = max(1, min(200, (int)($_GET['limit']??100)));
        $stmt = db()->prepare('SELECT id,user_id,username,event_type,outcome,ip_address,user_agent,request_id,context_json,created_at FROM security_events ORDER BY id DESC LIMIT '.$limit);
        $stmt->execute(); $rows = $stmt->fetchAll(PDO::FETCH_ASSOC);
        return array_map(function($r) {
            $r['context'] = $r['context_json']?json_decode($r['context_json'], true):null; unset($r['context_json']); return $r;
        }, $rows);
    });

    if (str_starts_with($path, '/api/servers')) api_try(function()use($path, $method) {
        $repo = new ServerRepository();
        if ($path === '/api/servers/active' && $method === 'GET')return array_map('mask_server', $repo->all(true));
        Authorization::requireAdmin();
        if ($path === '/api/servers' && $method === 'GET')return array_map('mask_server', $repo->all()); if ($path === '/api/servers' && $method === 'POST') {
            $b = Http::body(); if (empty($b['name']) || empty($b['type'])||!isset($b['config']))throw new RuntimeException('name, type, and config are required', 400); return mask_server($repo->create(['name' => $b['name'], 'type' => $b['type'], 'config' => $b['config'], 'isActive' => $b['isActive']??true, 'isDefault' => $b['isDefault']??false]));
        }if (preg_match('#^/api/servers/(\d+)(?:/(toggle|set-default))?$#', $path, $m)) {
            $id = (int)$m[1]; $s = $repo->get($id); if (!$s)throw new RuntimeException('Server not found', 404); if ($method === 'GET')return mask_server($s); if ($method === 'DELETE') {
                $repo->delete($id); return ['success' => true,
                    'message' => 'Server deleted'];
            }if ($method === 'PUT') {
                $b = Http::body(); if (isset($b['config']))foreach ($b['config'] as $k => $v)if ($v === '••••••••')$b['config'][$k] = $s['config'][$k]??$v; return mask_server($repo->update($id, $b));
            }if (($m[2]??'') === 'toggle' && $method === 'POST')return mask_server($repo->update($id, ['isActive'=>!$s['isActive']])); if (($m[2]??'') === 'set-default' && $method === 'POST') {
                $repo->setDefault($id); return ['success' => true,
                    'message' => $s['name'].' set as default'];
            }}throw new RuntimeException('Endpoint not implemented', 404);
    });

    if (str_starts_with($path, '/webdav')) {
        require dirname(__DIR__).'/src/Services/WebDav.php'; \CloudHub\Services\handle_webdav($fs, $config, $path, $method); exit;
    }
    if (str_starts_with($path, '/api/') && $method === 'OPTIONS') {
        http_response_code(204); header('Allow: GET, POST, PUT, PATCH, DELETE, OPTIONS'); exit;
    }
    if (str_starts_with($path, '/api/'))Http::error(404, 'NOT_FOUND', 'API endpoint not found');
    if ($path === '/' || $path === '/servers' || $path === '/browse') {
        require dirname(__DIR__).'/views/pages/app.php'; exit;
    }
/**
* Handles media playback requests by validating the file,
* populating the $mediaFile payload, and rendering the app view.
*/
if ($path === '/play' && $method === 'GET') {
    Authorization::requireRead();

    $relPath = (string)($_GET['path'] ?? '');
    $mediaFile = null;

    if ($relPath !== '') {
        try {
            $f = $fs->existing($relPath);
            if (is_file($f)) {
                $mime = mime_type($f);
                if (str_starts_with($mime, 'video/') || str_starts_with($mime, 'audio/')) {
                    $size = filesize($f);
                    $mediaFile = [
                        'name' => basename($f),
                        'path' => $relPath,
                        'formatted_size' => method_exists($fs, 'formatSize') ? $fs->formatSize($size) : round($size / 1024 / 1024, 2) . ' MB',
                        'stream_url' => $frontController . '?route=%2Fapi%2Ffiles%2Fstream&path=' . urlencode($relPath),
                        'sprite_url' => ''
                    ];
                }
            }
        } catch (Throwable $e) {
            error_log('['.Http::requestId().'] Playback error: '.$e->getMessage());
        }
    }

    require dirname(__DIR__) . '/views/pages/play.php';
    exit;
}
    http_response_code(404); echo 'Not found';

