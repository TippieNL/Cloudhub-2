<?php
declare(strict_types=1);

/**
 * The API, exercised over real HTTP against a real database.
 *
 *   php tests/http/run.php
 *
 * Starts its own server on a free port, signs in, and makes requests. Needs a
 * migrated database and an admin account -- see .github/workflows/ci.yml, which
 * is the canonical setup and runs this on every push.
 *
 * Credentials come from the environment so CI and a laptop can differ:
 *   CLOUDHUB_TEST_USER (admin), CLOUDHUB_TEST_PASS (smoke-test-pass-123)
 */
require __DIR__.'/Client.php';

use CloudHub\Tests\Http\Client;

$root = dirname(__DIR__, 2);
$user = getenv('CLOUDHUB_TEST_USER') ?: 'admin';
$pass = getenv('CLOUDHUB_TEST_PASS') ?: 'smoke-test-pass-123';

/* ---- a server of our own -------------------------------------------------- */

$port = 8000 + random_int(600, 900);
$log = sys_get_temp_dir().'/cloudhub-http-test-'.$port.'.log';
$server = proc_open(
    sprintf('exec %s -S 127.0.0.1:%d -t %s %s',
        escapeshellarg(PHP_BINARY), $port, escapeshellarg($root.'/public'), escapeshellarg($root.'/router.php')),
    [1 => ['file', $log, 'w'], 2 => ['file', $log, 'a']],
    $pipes,
);
if (!is_resource($server)) { fwrite(STDERR, "Could not start the test server\n"); exit(1); }

// Always take the server down, including on a fatal error partway through.
register_shutdown_function(function () use ($server) {
    if (is_resource($server)) { proc_terminate($server); proc_close($server); }
});

$base = "http://127.0.0.1:$port";
$up = false;
for ($i = 0; $i < 50; $i++) {
    $probe = @file_get_contents($base.'/?route='.rawurlencode('/api/auth/status'));
    if ($probe !== false) { $up = true; break; }
    usleep(100_000);
}
if (!$up) {
    fwrite(STDERR, "The test server never answered. Log:\n".@file_get_contents($log)."\n");
    exit(1);
}

/* ---- the harness ---------------------------------------------------------- */

$passed = 0;
$failures = [];
$currentCase = '';

function check(string $what, bool $ok, string $detail = ''): void
{
    global $passed, $failures, $currentCase;
    if ($ok) { $passed++; return; }
    $failures[] = $currentCase.': '.$what.($detail !== '' ? ' -- '.$detail : '');
}

function scenario(string $name, callable $body): void
{
    global $currentCase, $failures;
    $currentCase = $name;
    try {
        $body();
    } catch (Throwable $e) {
        $failures[] = $name.': threw '.get_class($e).' -- '.$e->getMessage();
    }
    echo '  '.$name.PHP_EOL;
}

$client = new Client($base);
$scratch = '/_httptest_'.bin2hex(random_bytes(4));

echo "CloudHub API over HTTP on port $port".PHP_EOL;

/* ---- sign in -------------------------------------------------------------- */

scenario('an anonymous request is refused', function () use ($base) {
    $anon = new Client($base);
    $r = $anon->get('/api/files/list', ['path' => '/']);
    check('listing without a session is 401', $r->status === 401, $r->describe());
    check('the refusal names itself', $r->errorCode() === 'UNAUTHORIZED', (string)$r->errorCode());
});

scenario('a wrong password is refused', function () use ($base, $user) {
    $bad = new Client($base);
    $r = $bad->signIn($user, 'definitely-not-the-password');
    check('sign in fails', !$r->ok() || ($r->json['success'] ?? false) === false, $r->describe());
});

scenario('signing in', function () use ($client, $user, $pass) {
    $r = $client->signIn($user, $pass);
    check('sign in succeeds', $r->ok() && ($r->json['success'] ?? false) === true, $r->describe());
    check('a CSRF token comes back', $client->csrfToken() !== '');

    $status = $client->get('/api/auth/status');
    check('the session is live', ($status->json['authenticated'] ?? false) === true, $status->describe());
});

scenario('a write without the CSRF token is refused', function () use ($client, $scratch) {
    // The guard is invisible until someone "simplifies" the middleware.
    $r = $client->postWithoutCsrf('/api/files/mkdir', ['path' => $scratch.'-csrf']);
    check('the write is rejected', !$r->ok(), $r->describe());
    check('and says why', in_array($r->status, [403, 419], true), (string)$r->status);
});

/* ---- files ---------------------------------------------------------------- */

scenario('making a folder', function () use ($client, $scratch) {
    $r = $client->post('/api/files/mkdir', ['path' => $scratch]);
    check('mkdir succeeds', $r->ok(), $r->describe());

    $list = $client->get('/api/files/list', ['path' => '/']);
    $names = array_column($list->json ?? [], 'name');
    check('the folder is listed', in_array(ltrim($scratch, '/'), $names, true), implode(',', array_slice($names, 0, 8)));
});

scenario('the bookkeeping folders stay hidden', function () use ($client) {
    // .trash, .thumbnails and .versions are the app's own; a listing that shows
    // them invites someone to delete their own history.
    $list = $client->get('/api/files/list', ['path' => '/']);
    $names = array_column($list->json ?? [], 'name');
    foreach (['.trash', '.thumbnails', '.versions'] as $hidden) {
        check("$hidden is not listed", !in_array($hidden, $names, true));
    }
});

$uploaded = $scratch.'/notes.txt';
$firstBody = "version one\n".str_repeat('a', 400);

scenario('a chunked upload arrives whole', function () use ($client, $scratch, $uploaded, $firstBody) {
    $init = $client->post('/api/uploads/init', [
        'targetPath' => $scratch, 'name' => 'notes.txt',
        'size' => strlen($firstBody), 'uploadId' => 'httptest'.bin2hex(random_bytes(8)),
        'conflict' => 'overwrite',
    ]);
    check('init succeeds', $init->ok(), $init->describe());
    $id = $init->json['id'] ?? '';
    check('an upload id comes back', $id !== '');
    if ($id === '') return;

    // Two chunks, so the offset bookkeeping is exercised rather than assumed.
    $half = (int)floor(strlen($firstBody) / 2);
    $a = $client->putChunk($id, 0, substr($firstBody, 0, $half));
    check('the first chunk is accepted', $a->ok(), $a->describe());
    check('the server reports what it holds', ($a->json['received'] ?? -1) === $half, json_encode($a->json));

    $b = $client->putChunk($id, $half, substr($firstBody, $half));
    check('the second chunk is accepted', $b->ok(), $b->describe());

    $done = $client->post('/api/uploads/complete', ['id' => $id]);
    check('the upload completes', $done->ok(), $done->describe());

    $back = $client->get('/api/files/download', ['path' => $uploaded]);
    check('the bytes come back unchanged', $back->body === $firstBody,
        strlen($back->body).' bytes of '.strlen($firstBody));
});

scenario('the media route serves byte ranges', function () use ($client, $scratch) {
    /*
     * Range serving is what video seeking rides on, so it is worth a real
     * partial fetch rather than an assumption. It needs an actual media file:
     * the stream route refuses anything that is not audio or video, which is
     * itself the behaviour checked here.
     *
     * A minimal WAV, synthesised rather than committed as a fixture: 44 bytes
     * of header and a second of silence.
     */
    $samples = 8000;
    $pcm = str_repeat("\x00\x00", $samples);
    $wav = 'RIFF'.pack('V', 36 + strlen($pcm)).'WAVEfmt '.pack('VvvVVvv', 16, 1, 1, 8000, 16000, 2, 16)
        .'data'.pack('V', strlen($pcm)).$pcm;

    $init = $client->post('/api/uploads/init', [
        'targetPath' => $scratch, 'name' => 'tone.wav',
        'size' => strlen($wav), 'uploadId' => 'wav'.bin2hex(random_bytes(8)),
        'conflict' => 'overwrite',
    ]);
    $id = $init->json['id'] ?? '';
    if ($id === '') { check('the media file uploads', false, $init->describe()); return; }
    $client->putChunk($id, 0, $wav);
    $client->post('/api/uploads/complete', ['id' => $id]);
    $media = $scratch.'/tone.wav';

    $whole = $client->get('/api/files/stream', ['path' => $media]);
    check('a whole stream is 200', $whole->status === 200, $whole->describe());
    check('ranges are advertised', $whole->header('Accept-Ranges') === 'bytes',
        (string)$whole->header('Accept-Ranges'));

    $part = $client->getRange('/api/files/stream', ['path' => $media], 10, 29);
    check('a range is 206', $part->status === 206, $part->describe());
    check('and is exactly the bytes asked for', $part->body === substr($wav, 10, 20),
        strlen($part->body).' bytes');
    check('with the range named back',
        str_starts_with((string)$part->header('Content-Range'), 'bytes 10-29/'),
        (string)$part->header('Content-Range'));
});

scenario('a document is refused by the media route', function () use ($client, $uploaded) {
    // The guard that stops the streaming path being a general file reader.
    $r = $client->get('/api/files/stream', ['path' => $uploaded]);
    check('a text file cannot be streamed as media', $r->status === 415, $r->describe());
});

/* ---- share links ----------------------------------------------------------- */

scenario('a share link opens without a session', function () use ($client, $base, $uploaded) {
    $made = $client->post('/api/shares/create', ['filePath' => $uploaded, 'expiresInHours' => 24]);
    check('the link is created', $made->ok(), $made->describe());
    $token = $made->json['token'] ?? '';
    check('a token comes back', $token !== '');
    if ($token === '') return;

    $anon = new Client($base);
    $raw = $anon->get('/share/'.$token.'/raw');
    check('an anonymous visitor can fetch it', $raw->ok(), $raw->describe());
});

/*
 * The bug this round fixes.
 *
 * /api/shares/create reuses any live link for the path and only reads
 * expiresInHours when it creates a new one -- so asking for an hour on a file
 * already shared for a month hands back the month-long token.
 */
scenario('the requested lifetime is honoured', function () use ($client, $uploaded) {
    $long = $client->post('/api/shares/create', ['filePath' => $uploaded, 'expiresInHours' => 720]);
    check('a long link is created', $long->ok(), $long->describe());
    $longToken = $long->json['token'] ?? '';

    $short = $client->post('/api/shares/create', ['filePath' => $uploaded, 'expiresInHours' => 1]);
    check('a short link is created', $short->ok(), $short->describe());
    $shortToken = $short->json['token'] ?? '';

    check('asking for an hour does not return the month-long link',
        $shortToken !== '' && $shortToken !== $longToken,
        'both came back as '.substr($longToken, 0, 8).'...');

    $expiry = $short->json['expiresAt'] ?? null;
    check('and its expiry really is about an hour away',
        is_string($expiry) && abs(strtotime($expiry) - (time() + 3600)) < 300,
        (string)$expiry);

    // Asking again for the same lifetime should reuse, not pile up tokens.
    $again = $client->post('/api/shares/create', ['filePath' => $uploaded, 'expiresInHours' => 1]);
    check('asking twice for the same lifetime reuses the link',
        ($again->json['token'] ?? '') === $shortToken, 'got a second token');
});

scenario('a revoked link stops working', function () use ($client, $base, $uploaded) {
    $made = $client->post('/api/shares/create', ['filePath' => $uploaded, 'expiresInHours' => 0]);
    $token = $made->json['token'] ?? '';
    check('the link is created', $token !== '', $made->describe());
    if ($token === '') return;

    $gone = $client->delete('/api/shares/revoke', ['token' => $token]);
    check('revoke succeeds', $gone->ok(), $gone->describe());

    $anon = new Client($base);
    $raw = $anon->get('/share/'.$token.'/raw');
    check('the link is dead', $raw->status === 404, $raw->describe());
});

/* ---- playback ---------------------------------------------------------------
 *
 * What a player does between opening a video and finishing it: read a little,
 * seek, come back tomorrow and check whether the file it cached is still the
 * file on the server.
 */

scenario('a cached video is revalidated rather than fetched again', function () use ($client, $scratch) {
    $bytes = str_repeat("\x00\x11\x22\x33", 4096);
    $init = $client->post('/api/uploads/init', [
        'targetPath' => $scratch, 'name' => 'cached.wav', 'size' => strlen($bytes),
        'uploadId' => 'cache'.bin2hex(random_bytes(6)), 'conflict' => 'overwrite',
    ]);
    $id = $init->json['id'] ?? '';
    if ($id === '') { check('the clip uploads', false, $init->describe()); return; }
    $client->putChunk($id, 0, $bytes);
    $client->post('/api/uploads/complete', ['id' => $id]);
    $media = $scratch.'/cached.wav';

    $first = $client->get('/api/files/stream', ['path' => $media]);
    $etag = (string)$first->header('ETag');
    $modified = (string)$first->header('Last-Modified');
    check('the response carries an ETag', $etag !== '', $first->describe());
    check('and a Last-Modified', $modified !== '');

    // The whole point: a player that already has the file asks whether it is
    // still good, and is told in a few bytes rather than the whole video.
    $again = $client->get('/api/files/stream', ['path' => $media], ['If-None-Match: '.$etag]);
    check('an unchanged file is 304', $again->status === 304, $again->describe());
    check('and sends no body at all', $again->body === '', strlen($again->body).' bytes');

    $byDate = $client->get('/api/files/stream', ['path' => $media], ['If-Modified-Since: '.$modified]);
    check('the date form works too', $byDate->status === 304, $byDate->describe());

    $stale = $client->get('/api/files/stream', ['path' => $media], ['If-None-Match: "0-0"']);
    check('a stale validator still gets the file', $stale->status === 200 && $stale->body === $bytes,
        $stale->describe());
});

/*
 * If-Range is the header that stops a resumed download stitching two
 * different videos together -- a file replaced between two range requests
 * cannot be detected by any player, so the server has to answer for it.
 */
scenario('resuming a range only continues the same file', function () use ($client, $scratch) {
    $bytes = str_repeat("\x44\x55", 8192);
    $init = $client->post('/api/uploads/init', [
        'targetPath' => $scratch, 'name' => 'resumed.wav', 'size' => strlen($bytes),
        'uploadId' => 'ifrange'.bin2hex(random_bytes(6)), 'conflict' => 'overwrite',
    ]);
    $id = $init->json['id'] ?? '';
    if ($id === '') { check('the clip uploads', false, $init->describe()); return; }
    $client->putChunk($id, 0, $bytes);
    $client->post('/api/uploads/complete', ['id' => $id]);
    $media = $scratch.'/resumed.wav';

    $etag = (string)$client->get('/api/files/stream', ['path' => $media])->header('ETag');
    if ($etag === '') { check('an ETag is issued', false); return; }

    $ok = $client->get('/api/files/stream', ['path' => $media], ['Range: bytes=100-199', 'If-Range: '.$etag]);
    check('the same file continues as a range', $ok->status === 206 && $ok->body === substr($bytes, 100, 100),
        $ok->describe());

    $changed = $client->get('/api/files/stream', ['path' => $media], ['Range: bytes=100-199', 'If-Range: "0-0"']);
    check('a file that changed is sent whole instead',
        $changed->status === 200 && $changed->body === $bytes, $changed->describe());

    /*
     * A player seeking holds the file *and* wants bytes from the middle of
     * it. Answering "still fresh" to that is true and useless: it asked for
     * bytes, and 304 has none, so playback stops at the seek.
     */
    $seeking = $client->get('/api/files/stream', ['path' => $media],
        ['Range: bytes=100-199', 'If-None-Match: '.$etag]);
    check('a seek by a client that has the file still gets bytes',
        $seeking->status === 206 && $seeking->body === substr($bytes, 100, 100), $seeking->describe());
});

scenario('seeking still works, and says what it sent', function () use ($client, $scratch) {
    // The behaviour the validators must not have broken.
    $media = $scratch.'/resumed.wav';
    $part = $client->getRange('/api/files/stream', ['path' => $media], 10, 29);
    check('a range is still 206', $part->status === 206, $part->describe());
    check('with the range named back',
        str_starts_with((string)$part->header('Content-Range'), 'bytes 10-29/'),
        (string)$part->header('Content-Range'));
    check('and an ETag to resume against', (string)$part->header('ETag') !== '');
});

/* ---- share links that carry the file's name -------------------------------
 *
 * The link handed out ends in the file's own name and returns the file
 * itself, so it works in an <img> tag, in `curl -O` and in the clients that
 * decide what a link is by looking at its extension.
 *
 * These go through Client::fetchUrl(), which fetches the URL exactly as the
 * API handed it out -- a clean path through the web server's own routing,
 * rather than the ?route= form everything else here uses. That difference is
 * the point: a share link is a URL given to someone else's browser.
 */

/** Put a file on the server and hand back its path. */
$put = function (string $name, string $bytes) use ($client, $scratch): string {
    $init = $client->post('/api/uploads/init', [
        'targetPath' => $scratch, 'name' => $name, 'size' => strlen($bytes),
        'uploadId' => 'named'.bin2hex(random_bytes(8)), 'conflict' => 'overwrite',
    ]);
    $id = $init->json['id'] ?? '';
    if ($id === '') return '';
    $client->putChunk($id, 0, $bytes);
    $client->post('/api/uploads/complete', ['id' => $id]);
    return $scratch.'/'.$name;
};

scenario('the link ends in the file\'s name and returns the file', function () use ($client, $base, $put) {
    // Not a real photograph, but named like one: what a browser is told a
    // shared file is comes from its extension, not from sniffing its bytes.
    $jpeg = "\xFF\xD8\xFF\xE0".str_repeat("\x42", 2048)."\xFF\xD9";
    $path = $put('holiday.jpg', $jpeg);
    check('the photo uploads', $path !== '');
    if ($path === '') return;

    $made = $client->post('/api/shares/create', ['filePath' => $path, 'expiresInHours' => 24]);
    $url = $made->json['url'] ?? '';
    check('a link comes back', $url !== '', $made->describe());
    check('and it ends in the file name', str_ends_with($url, '/holiday.jpg'), $url);
    check('the viewer page is offered separately',
        ($made->json['pageUrl'] ?? '') !== '' && !str_ends_with((string)($made->json['pageUrl'] ?? ''), '.jpg'),
        (string)($made->json['pageUrl'] ?? ''));
    if ($url === '') return;

    $anon = new Client($base);
    $got = $anon->fetchUrl($url);
    check('an anonymous visitor gets the file', $got->status === 200, $got->describe());
    check('as an image', $got->header('Content-Type') === 'image/jpeg', (string)$got->header('Content-Type'));
    check('shown rather than downloaded',
        str_starts_with((string)$got->header('Content-Disposition'), 'inline'),
        (string)$got->header('Content-Disposition'));
    check('and the bytes are the file', $got->body === $jpeg, strlen($got->body).' bytes');

    /*
     * Link previews and video players fetch these, and a share link is public:
     * a session started here would put a cookie on every anonymous viewer and
     * leave a session file per view. The route pattern doing double duty is
     * what prevents it, and only a real fetch can tell.
     */
    check('and no session was started for them', $got->header('Set-Cookie') === null,
        (string)$got->header('Set-Cookie'));

    // The viewer page is the other half: it loads the picture from the named
    // URL, which is what makes "Save image as" offer holiday.jpg rather than
    // the "raw" it used to.
    $page = $anon->fetchUrl((string)($made->json['pageUrl'] ?? ''));
    check('the viewer page still renders', $page->status === 200
        && str_starts_with((string)$page->header('Content-Type'), 'text/html'), $page->describe());
    check('and loads the picture from its named URL',
        str_contains($page->body, 'src="'.parse_url($url, PHP_URL_PATH).'"'),
        substr($page->body, 0, 0).parse_url($url, PHP_URL_PATH));
    check('the download button carries the name too',
        str_contains($page->body, '/download/holiday.jpg"'));
    check('a link preview is pointed at the file itself',
        str_contains($page->body, '<meta property="og:image" content="'.$url.'">'), $url);
    // A page that renders with a PHP notice in it is a page nobody looked at.
    check('and nothing leaked into the page',
        !str_contains($page->body, 'Warning:') && !str_contains($page->body, 'Notice:'));
});

scenario('a shared video can still be seeked through the pretty URL', function () use ($client, $base, $put) {
    $samples = 4000;
    $pcm = str_repeat("\x00\x00", $samples);
    $wav = 'RIFF'.pack('V', 36 + strlen($pcm)).'WAVEfmt '.pack('VvvVVvv', 16, 1, 1, 8000, 16000, 2, 16)
        .'data'.pack('V', strlen($pcm)).$pcm;
    $path = $put('clip.wav', $wav);
    if ($path === '') { check('the clip uploads', false); return; }

    $url = $client->post('/api/shares/create', ['filePath' => $path, 'expiresInHours' => 24])->json['url'] ?? '';
    check('the link ends in .wav', str_ends_with((string)$url, '/clip.wav'), (string)$url);
    if ($url === '') return;

    $anon = new Client($base);
    $part = $anon->fetchUrl($url, ['Range: bytes=10-29']);
    check('a range is 206', $part->status === 206, $part->describe());
    check('and is exactly the bytes asked for', $part->body === substr($wav, 10, 20),
        strlen($part->body).' bytes');
});

scenario('a name that is not the file\'s bounces to the one that is', function () use ($client, $base, $put) {
    // The token is the credential; the name is decoration -- but decoration is
    // not allowed to claim the file is a PDF when it is a JPEG.
    $path = $put('receipt.jpg', str_repeat("\x11", 64));
    if ($path === '') { check('the file uploads', false); return; }

    $made = $client->post('/api/shares/create', ['filePath' => $path, 'expiresInHours' => 24]);
    $token = $made->json['token'] ?? '';
    if ($token === '') { check('a link comes back', false, $made->describe()); return; }

    $anon = new Client($base);
    $lie = $anon->fetchUrl($base.'/share/'.$token.'/invoice.pdf');
    check('the made-up name redirects', $lie->status === 302, $lie->describe());
    check('to the name the file really has',
        str_ends_with((string)$lie->header('Location'), '/receipt.jpg'), (string)$lie->header('Location'));

    $asked = $anon->fetchUrl($base.'/share/'.$token.'/download/invoice.pdf');
    check('and a download keeps being a download',
        $asked->status === 302 && str_contains((string)$asked->header('Location'), '/download/receipt.jpg'),
        $asked->status.' '.(string)$asked->header('Location'));
});

scenario('links made before the name was added still work', function () use ($client, $base, $put) {
    $path = $put('legacy.jpg', str_repeat("\x22", 32));
    if ($path === '') { check('the file uploads', false); return; }
    $token = $client->post('/api/shares/create', ['filePath' => $path, 'expiresInHours' => 24])->json['token'] ?? '';
    if ($token === '') { check('a link comes back', false); return; }

    $anon = new Client($base);
    foreach (['raw' => 'inline', 'download' => 'attachment'] as $variant => $disposition) {
        $r = $anon->fetchUrl($base.'/share/'.$token.'/'.$variant);
        check("/$variant still answers", $r->status === 200, $r->describe());
        check("/$variant is still $disposition",
            str_starts_with((string)$r->header('Content-Disposition'), $disposition),
            (string)$r->header('Content-Disposition'));
    }
    $named = $anon->fetchUrl($base.'/share/'.$token.'/download/legacy.jpg');
    check('and the named download is an attachment',
        $named->status === 200 && str_contains((string)$named->header('Content-Disposition'), 'legacy.jpg'),
        $named->status.' '.(string)$named->header('Content-Disposition'));
});

/*
 * The trap this round could easily have shipped.
 *
 * The root .htaccess, router.php and the nginx example all refuse anything
 * ending .log, .ini, .sql and friends before the front controller sees it --
 * rules that protect real files under the project root. Put the shared file's
 * name in the path and sharing notes.log becomes a 403, on some deployments
 * and not others. Nothing but a real fetch through the web server finds this.
 */
scenario('sharing a file whose extension the server usually refuses', function () use ($client, $base, $put) {
    $path = $put('notes.log', "started\nfinished\n");
    if ($path === '') { check('the log uploads', false); return; }

    $url = $client->post('/api/shares/create', ['filePath' => $path, 'expiresInHours' => 24])->json['url'] ?? '';
    check('the link ends in .log', str_ends_with((string)$url, '/notes.log'), (string)$url);
    if ($url === '') return;

    $anon = new Client($base);
    $got = $anon->fetchUrl((string)$url);
    check('and it is not refused by a deny rule', $got->status === 200, $got->describe());
    check('a log file is downloaded, never shown',
        str_starts_with((string)$got->header('Content-Disposition'), 'attachment'),
        (string)$got->header('Content-Disposition'));
});

scenario('a name with spaces and brackets survives the URL', function () use ($client, $base, $put) {
    $bytes = str_repeat("\x33", 128);
    $path = $put('holiday photo (1).jpg', $bytes);
    if ($path === '') { check('the file uploads', false); return; }

    $url = $client->post('/api/shares/create', ['filePath' => $path, 'expiresInHours' => 24])->json['url'] ?? '';
    check('the name is percent-encoded in the link',
        str_ends_with((string)$url, '/holiday%20photo%20%281%29.jpg'), (string)$url);
    if ($url === '') return;

    $got = (new Client($base))->fetchUrl((string)$url);
    check('and it comes back whole', $got->status === 200 && $got->body === $bytes, $got->describe());
});

scenario('a revoked link is dead under its pretty name too', function () use ($client, $base, $put) {
    $path = $put('secret.jpg', str_repeat("\x44", 16));
    if ($path === '') { check('the file uploads', false); return; }
    $made = $client->post('/api/shares/create', ['filePath' => $path, 'expiresInHours' => 0]);
    $token = $made->json['token'] ?? '';
    $url = $made->json['url'] ?? '';
    if ($token === '') { check('a link comes back', false, $made->describe()); return; }

    $client->delete('/api/shares/revoke', ['token' => $token]);
    $got = (new Client($base))->fetchUrl((string)$url);
    check('the named link is gone', $got->status === 404, $got->describe());
});

scenario('the share list says who made each link', function () use ($client, $uploaded) {
    $client->post('/api/shares/create', ['filePath' => $uploaded, 'expiresInHours' => 24]);
    $list = $client->get('/api/shares/list');
    check('the list is readable by an admin', $list->ok(), $list->describe());
    $mine = null;
    foreach ($list->json ?? [] as $row) if (($row['filePath'] ?? '') === $uploaded) { $mine = $row; break; }
    check('the link is in the list', $mine !== null);
    if ($mine === null) return;
    check('with a creator', ($mine['createdBy'] ?? '') !== '', json_encode($mine));
});

/* ---- versions -------------------------------------------------------------- */

$secondBody = "version two\n".str_repeat('b', 900);

scenario('overwriting keeps the previous file', function () use ($client, $scratch, $uploaded, $firstBody, $secondBody) {
    $init = $client->post('/api/uploads/init', [
        'targetPath' => $scratch, 'name' => 'notes.txt',
        'size' => strlen($secondBody), 'uploadId' => 'httptest'.bin2hex(random_bytes(8)),
        'conflict' => 'overwrite',
    ]);
    check('init succeeds', $init->ok(), $init->describe());
    $id = $init->json['id'] ?? '';
    if ($id === '') return;

    $client->putChunk($id, 0, $secondBody);
    $done = $client->post('/api/uploads/complete', ['id' => $id]);
    check('the overwrite completes', $done->ok(), $done->describe());

    $now = $client->get('/api/files/download', ['path' => $uploaded]);
    check('the new bytes are in place', $now->body === $secondBody, strlen($now->body).' bytes');

    $versions = $client->get('/api/files/versions', ['path' => $uploaded]);
    check('a version was kept', $versions->ok() && count($versions->json['versions'] ?? []) >= 1, $versions->describe());
    $id = $versions->json['versions'][0]['id'] ?? '';
    check('the version has an id', $id !== '');
    if ($id === '') return;

    // The point of the whole feature: the old bytes are still readable.
    $old = $client->get('/api/files/versions/download', ['path' => $uploaded, 'id' => $id]);
    check('the previous contents are intact', $old->body === $firstBody,
        strlen($old->body).' bytes of '.strlen($firstBody));
});

scenario('a version can be restored', function () use ($client, $uploaded, $firstBody, $secondBody) {
    $versions = $client->get('/api/files/versions', ['path' => $uploaded]);
    $id = $versions->json['versions'][0]['id'] ?? '';
    check('there is a version to restore', $id !== '', $versions->describe());
    if ($id === '') return;

    $r = $client->post('/api/files/versions/restore', ['path' => $uploaded, 'id' => $id]);
    check('restore succeeds', $r->ok(), $r->describe());

    $now = $client->get('/api/files/download', ['path' => $uploaded]);
    check('the old contents are back', $now->body === $firstBody, strlen($now->body).' bytes');

    // Restoring is itself a change, so what it replaced must be recoverable.
    $after = $client->get('/api/files/versions', ['path' => $uploaded]);
    $bodies = [];
    foreach ($after->json['versions'] ?? [] as $v) {
        $bodies[] = $client->get('/api/files/versions/download', ['path' => $uploaded, 'id' => $v['id']])->body;
    }
    check('the replaced version was kept too', in_array($secondBody, $bodies, true),
        count($bodies).' versions held');
});

scenario('only so many versions are kept', function () use ($client, $scratch) {
    $path = $scratch.'/churn.txt';
    for ($i = 0; $i <= 12; $i++) {
        $body = "revision $i\n";
        $init = $client->post('/api/uploads/init', [
            'targetPath' => $scratch, 'name' => 'churn.txt',
            'size' => strlen($body), 'uploadId' => 'churn'.$i.bin2hex(random_bytes(6)),
            'conflict' => 'overwrite',
        ]);
        $id = $init->json['id'] ?? '';
        if ($id === '') { check("upload $i starts", false, $init->describe()); return; }
        $client->putChunk($id, 0, $body);
        $client->post('/api/uploads/complete', ['id' => $id]);
    }
    $versions = $client->get('/api/files/versions', ['path' => $path]);
    $count = count($versions->json['versions'] ?? []);
    // A file rewritten nightly must not grow history without bound.
    check('the history is capped at ten', $count === 10, $count.' versions kept');
    if ($count === 0) return;
    $newest = $client->get('/api/files/versions/download',
        ['path' => $path, 'id' => $versions->json['versions'][0]['id']])->body;
    check('and the newest is first', str_contains($newest, 'revision 11'), trim($newest));
});

/* ---- storage ------------------------------------------------------------------- */

scenario('an ordinary account can see its own storage', function () use ($base) {
    /*
     * /api/storage/usage is admin-only, so before this endpoint a user with a
     * quota had no way to see how much of it they had used -- they found out
     * when an upload came back 507.
     */
    $viewer = new Client($base);
    $in = $viewer->signIn('viewer', getenv('CLOUDHUB_VIEWER_PASS') ?: 'viewer-test-pass-123');
    if (!$in->ok()) { check('the viewer account signs in', false, $in->describe()); return; }

    $admin = $viewer->get('/api/storage/usage');
    check('the whole-server view stays admin-only', $admin->status === 403, $admin->describe());

    $mine = $viewer->get('/api/storage/me');
    check('but a viewer can read their own', $mine->ok(), $mine->describe());
    foreach (['usedBytes', 'quotaBytes', 'storageLimitBytes', 'diskFreeBytes', 'diskTotalBytes'] as $key) {
        check("it reports $key", array_key_exists($key, $mine->json ?? []), json_encode($mine->json));
    }
    check('the disk figures are real', (int)($mine->json['diskTotalBytes'] ?? 0) > 0,
        (string)($mine->json['diskTotalBytes'] ?? 'missing'));
});

scenario('an anonymous visitor cannot read storage', function () use ($base) {
    $anon = new Client($base);
    check('own storage needs a session', $anon->get('/api/storage/me')->status === 401);
});

scenario('the per-account view cannot force a tree walk', function () use ($client) {
    // Measuring walks the whole store. A route every account can call must not
    // be a way to make the server do that on demand.
    $first = $client->get('/api/storage/me');
    check('it answers', $first->ok(), $first->describe());
    check('and reports the cached measurement', ($first->json['cached'] ?? null) !== null, json_encode($first->json));

    $forced = $client->get('/api/storage/me', ['refresh' => '1']);
    check('refresh is ignored rather than honoured',
        ($forced->json['cached'] ?? false) === ($first->json['cached'] ?? false)
        || ($forced->json['cached'] ?? false) === true,
        'cached went from '.json_encode($first->json['cached'] ?? null).' to '.json_encode($forced->json['cached'] ?? null));
});

scenario('an admin still sees the whole server', function () use ($client) {
    $r = $client->get('/api/storage/usage');
    check('the admin report is readable', $r->ok(), $r->describe());
    check('with a per-account breakdown', is_array($r->json['byUser'] ?? null), json_encode(array_keys($r->json ?? [])));
    check('and what the version history costs', isset($r->json['versions']['bytes']),
        json_encode($r->json['versions'] ?? null));
});

/* ---- trash ------------------------------------------------------------------ */

scenario('deleting goes to the trash and comes back', function () use ($client, $uploaded) {
    $del = $client->delete('/api/files/delete', ['path' => $uploaded]);
    check('delete succeeds', $del->ok(), $del->describe());

    $trash = $client->get('/api/trash');
    $entry = null;
    foreach ($trash->json['entries'] ?? [] as $row) if (($row['originalPath'] ?? '') === $uploaded) { $entry = $row; break; }
    check('the file is in the trash', $entry !== null, $trash->describe());
    if ($entry === null) return;

    $back = $client->post('/api/trash/restore', ['id' => $entry['id']]);
    check('restore succeeds', $back->ok(), $back->describe());

    $now = $client->get('/api/files/download', ['path' => $uploaded]);
    check('the file is readable again', $now->ok(), $now->describe());
});

/* ---- a viewer cannot write ---------------------------------------------------- */

scenario('a viewer is refused a write', function () use ($base, $scratch) {
    $viewer = new Client($base);
    $in = $viewer->signIn('viewer', getenv('CLOUDHUB_VIEWER_PASS') ?: 'viewer-test-pass-123');
    if (!$in->ok()) { check('the viewer account signs in', false, $in->describe()); return; }
    check('the viewer signs in', true);

    $r = $viewer->post('/api/files/mkdir', ['path' => $scratch.'/nope']);
    check('mkdir is refused', $r->status === 403, $r->describe());

    $list = $viewer->get('/api/files/list', ['path' => '/']);
    check('but reading is allowed', $list->ok(), $list->describe());
});

/* ---- clean up ------------------------------------------------------------------ */

scenario('tidying the scratch folder', function () use ($client, $scratch) {
    $client->delete('/api/files/delete', ['path' => $scratch]);
    $trash = $client->get('/api/trash');
    foreach ($trash->json['entries'] ?? [] as $row) {
        if (str_starts_with((string)($row['originalPath'] ?? ''), $scratch)) {
            $client->post('/api/trash/purge', ['id' => $row['id']]);
        }
    }
    check('the scratch folder is gone',
        !in_array(ltrim($scratch, '/'), array_column($client->get('/api/files/list', ['path' => '/'])->json ?? [], 'name'), true));
});

/* ---- the verdict ------------------------------------------------------------------ */

echo PHP_EOL;
if ($failures) {
    foreach ($failures as $failure) echo '  FAIL  '.$failure.PHP_EOL;
    echo PHP_EOL.count($failures).' checks failed, '.$passed.' passed.'.PHP_EOL;
    echo 'Server log: '.$log.PHP_EOL;
    exit(1);
}
echo 'All '.$passed.' HTTP checks passed.'.PHP_EOL;
exit(0);
