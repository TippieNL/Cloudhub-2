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
