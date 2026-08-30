<?php
declare(strict_types=1);

/**
 * Versions on overwrite, share-link lifetimes, and CI.
 *
 * These are the usual regression pins: they assert the source still reads a
 * certain way, which is what stops a later edit quietly undoing a decision.
 * What they cannot do is assert the application *works* -- that is
 * tests/http/run.php, which drives the real API over real HTTP against a real
 * database, and which this round exists partly to introduce.
 */
$root = dirname(__DIR__);
$read = fn(string $path): string => (string)@file_get_contents($root.'/'.$path);

$index = $read('public/index.php');
$files = $read('src/Services/FileService.php');
$uploads = $read('src/Services/UploadService.php');
$config = $read('config/config.php');
$migrate = $read('database/migrate.php');
$app = $read('public/assets/js/app.js');
$workflow = $read('.github/workflows/ci.yml');
$httpRunner = $read('tests/http/run.php');
$httpClient = $read('tests/http/Client.php');
$router = $read('router.php');

$checks = [];

/* --- share links -------------------------------------------------------------
 *
 * The bug: /api/shares/create returned any live link for the file and only
 * read expiresInHours when creating a new one, so asking for an hour on a file
 * already shared for a month handed back the month-long token. The comment
 * above it claimed the opposite.
 */
$checks['a share reuses only a link with the lifetime asked for'] =
    str_contains($index, 'WHERE file_path=? AND expires_hours=? AND (expires_at IS NULL OR expires_at>UTC_TIMESTAMP())');
// Matching on remaining time cannot work: a 24-hour link made three minutes
// ago has 23h57m left. The requested lifetime has to be stored.
$checks['the requested lifetime is stored, not just the expiry'] =
    str_contains($migrate, "addColumn(\$pdo, 'share_links', 'expires_hours'")
    && str_contains($index, 'INSERT INTO share_links(token,file_path,expires_at,expires_hours,created_by)');
$checks['a share link records who made it'] =
    str_contains($migrate, "addColumn(\$pdo, 'share_links', 'created_by'")
    && str_contains($index, "'createdBy' => \$x['username'] ?? 'unknown'");
// A link whose creator was deleted must still appear, or an admin loses sight
// of something that is still public.
$checks['a link outlives the account that made it'] =
    str_contains($index, 'LEFT JOIN users u ON u.id = s.created_by');
// The client used to revoke the old token before asking for a new lifetime,
// which was a workaround for the server bug above.
$checks['the client no longer works around the server'] =
    !str_contains($app, "the server reuses any live link for\n * a file, so the old token has to go");

/* --- versions ------------------------------------------------------------------ */

// The line that used to destroy data outright.
$checks['an overwrite no longer unlinks the previous file'] =
    !str_contains($uploads, "\$policy === 'overwrite' && is_file(\$dest) && !unlink(\$dest)")
    && str_contains($uploads, '$this->files->keepVersion(');
$checks['the version store mirrors the trash'] =
    str_contains($files, 'public function keepVersion(')
    && str_contains($files, 'public function versionRestore(')
    && str_contains($files, "\$entry.'/payload'");
// Without this the history folder is listed, and browsable, and deletable
// through the ordinary file routes.
$checks['the version store is hidden from every file route'] =
    str_contains($files, "public const RESERVED_ROOT_NAMES=['.trash','.thumbnails','.versions'];");
// Restoring is itself a change; recovering the wrong version must not destroy
// the right one.
$checks['restoring keeps what it replaces'] =
    str_contains($files, 'if(is_file($target))$this->keepVersion($target,$actor,$maxVersions);');
$checks['a file rewritten nightly does not grow without bound'] =
    str_contains($files, 'public function trimVersions(')
    && str_contains($config, "'max_versions_per_file'=>(int)env('MAX_VERSIONS_PER_FILE',10)");
$checks['old versions are swept like old trash'] =
    str_contains($files, 'public function versionsPurgeExpired(')
    && str_contains($index, "\$fs->versionsPurgeExpired((int)\$config['version_retention_days']);");
/*
 * The id carries whole seconds, so several rewrites inside one second differ
 * only by random bytes -- sorting on the id alone makes "newest first" a coin
 * toss, and decides which version the cap discards. Found by the HTTP suite.
 */
$checks['version order survives several rewrites in one second'] =
    str_contains($files, "'keptAtMs'=>(int)round(microtime(true)*1000)")
    && str_contains($files, "usort(\$out,fn(\$a,\$b)=>((int)(\$b['keptAtMs']??0)<=>(int)(\$a['keptAtMs']??0))");
// Unlike the trash, versions are ordinary bytes on the disk and count.
$checks['the history counts toward storage'] =
    str_contains($files, 'public function versionsUsage(')
    && str_contains($files, "'versions'=>\$versions,");
$checks['versions can be turned off'] =
    str_contains($config, "'versions_enabled'=>env_bool('VERSIONS_ENABLED',true)")
    && str_contains($uploads, "(\$this->config['versions_enabled'] ?? true)");
$checks['reading a version needs only read, restoring needs write'] =
    str_contains($index, "if (\$path === '/api/files/versions' && \$method === 'GET')")
    && str_contains($index, "if (\$path === '/api/files/versions/restore' && \$method === 'POST')")
    && str_contains($index, "if (\$path === '/api/files/versions' && \$method === 'DELETE')");
$checks['the web client can reach the history'] =
    str_contains($app, 'async function showVersions(path)')
    && str_contains($app, "data-cmd=\"versions\">Previous versions");

/* --- the tests that assert behaviour, and run themselves ------------------------ */

$checks['there is an HTTP suite, and it starts its own server'] =
    str_contains($httpRunner, 'proc_open')
    && str_contains($httpRunner, 'a chunked upload arrives whole')
    && str_contains($httpClient, "\$this->put('/api/uploads/chunk'");
// The bug that broke sign-in on a subdirectory install was a redirect turning
// a PUT into a GET; a client that follows redirects cannot see one.
$checks['the HTTP client does not follow redirects'] =
    str_contains($httpClient, 'CURLOPT_FOLLOWLOCATION => false');
$checks['the suite proves the guards, not just the happy path'] =
    str_contains($httpRunner, 'a write without the CSRF token is refused')
    && str_contains($httpRunner, 'a viewer is refused a write')
    && str_contains($httpRunner, 'an anonymous request is refused');
$checks['the suite covers this round'] =
    str_contains($httpRunner, 'the requested lifetime is honoured')
    && str_contains($httpRunner, 'overwriting keeps the previous file')
    && str_contains($httpRunner, 'a version can be restored');

/*
 * The development server was handing out the whole application unstyled:
 * mime_content_type() sniffs bytes, a stylesheet is bytes of text, so it
 * answered "text/plain" -- and a browser refuses a stylesheet served as
 * text/plain in standards mode. Found while screenshotting the versions panel.
 */
$checks['the dev server types assets by extension, not by sniffing'] =
    str_contains($router, 'function dev_content_type(string $file): string')
    && str_contains($router, "'css' => 'text/css'")
    && !str_contains($router, "mime_content_type(\$aliased)");

$checks['everything runs on push'] =
    str_contains($workflow, 'on:') && str_contains($workflow, 'php tests/run.php')
    && str_contains($workflow, 'php tests/http/run.php');
// The schema is MySQL-specific throughout, so CI brings a real one rather than
// testing a dialect nobody deploys.
$checks['CI uses the database the app actually runs on'] =
    str_contains($workflow, 'mariadb:10.11') && str_contains($workflow, 'php database/migrate.php');
// Without CLOUDHUB_TEST_URL the 13 live Android tests skip, which would defeat
// the point of running them.
$checks['the live Android tests do not skip in CI'] =
    str_contains($workflow, 'CLOUDHUB_TEST_URL: http://127.0.0.1:8900')
    && str_contains($workflow, 'testReleaseUnitTest');

$bad = false;
foreach ($checks as $name => $ok) {
    echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL;
    $bad = $bad || !$ok;
}
exit($bad ? 1 : 0);
