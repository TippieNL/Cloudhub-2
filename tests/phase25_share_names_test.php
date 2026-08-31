<?php
declare(strict_types=1);

/**
 * Share links that end in the file's name.
 *
 * The behaviour itself is exercised for real in tests/http/run.php, which
 * fetches a named link over HTTP and checks what comes back. These are the
 * pins over the source for the parts that are structural: that one pattern
 * serves both the routing and the no-session decision, and that the three
 * server configurations do not refuse the new URLs.
 */
$root = dirname(__DIR__);
$read = fn(string $path): string => (string)@file_get_contents($root.'/'.$path);

$index = $read('public/index.php');
$view = $read('views/pages/share.php');
$app = $read('public/assets/js/app.js');
$appView = $read('views/pages/app.php');
$htaccess = $read('.htaccess');
$router = $read('router.php');
$nginx = $read('deploy/nginx-security.conf.example');
$http = $read('tests/http/run.php');

$checks = [];

/* --- the URL ------------------------------------------------------------- */

$checks['the file URL ends in the file\'s own name'] =
    str_contains($index, 'function share_file_url(')
    && str_contains($index, "return share_url(\$config, \$basePath, \$token).'/'.rawurlencode(\$name);");
// A name with a space or a bracket must survive the round trip, and a name is
// never allowed to introduce a path segment of its own.
$checks['the name is percent-encoded'] = substr_count($index, 'rawurlencode($name)') >= 3;
$checks['there is a named download URL too'] =
    str_contains($index, 'function share_download_url(')
    && str_contains($index, "'/download/'.rawurlencode(\$name)");

/* --- one pattern, two jobs -----------------------------------------------
 *
 * The route and the "start no session for this visitor" test read the same
 * constant. Widening one alone would hand every anonymous viewer of a named
 * link a cookie and leave a session file per view -- which is exactly what
 * the session-less share routes exist to avoid, and it would not show up as a
 * failure anywhere else.
 */
$checks['routing and the session decision share one pattern'] =
    str_contains($index, 'const SHARE_ROUTE =')
    && substr_count($index, 'preg_match(SHARE_ROUTE') === 2
    && !preg_match("#preg_match\('\#\^/share/#", $index);

/* --- the name may not lie ------------------------------------------------- */

$checks['a name that is not the file\'s redirects to the one that is'] =
    str_contains($index, "if (\$named !== '' && \$named !== \$name) {")
    && str_contains($index, "header('Location: '.\$canonical, true, 302);");
// The variant has to survive the bounce or a download link would come back
// as an inline view.
$checks['the redirect keeps the download variant'] =
    str_contains($index, "\$variant === 'download'")
    && str_contains($index, 'share_download_url($config, $basePath, (string)$share[\'token\'], $name)');
$checks['the token is still the only credential'] =
    substr_count($index, 'share_resolve($fs, $m[1])') === 1;

/* --- what the clients are given ------------------------------------------ */

$checks['the API hands out the named file URL'] =
    str_contains($index, "'url' => share_file_url(\$config, \$basePath, (string)\$r['token'], basename(\$f))")
    && str_contains($index, "'pageUrl' => share_url(\$config, \$basePath, (string)\$r['token'])");
$checks['the admin list carries both URLs as well'] =
    str_contains($index, "'url' => share_file_url(\$config, \$basePath, (string)\$x['token'], basename((string)\$x['file_path']))")
    && str_contains($index, "'pageUrl' => share_url(\$config, \$basePath, (string)\$x['token'])");
$checks['the copied link is the file, the Open link is the page'] =
    str_contains($app, 'shareUI.url.value = d.url;')
    && str_contains($app, 'shareUI.open.href = d.pageUrl || d.url;')
    && str_contains($appView, 'Open preview page');
// Saving the picture out of the viewer page used to suggest a file called
// "raw", because that was the last segment of the URL it came from.
$checks['the viewer page loads the media from its named URL'] =
    str_contains($index, "'rawUrl' => Http::encodePath(\$basePath).'/share/'.\$share['token'].'/'.rawurlencode(\$name)")
    && str_contains($index, "'downloadUrl' => Http::encodePath(\$basePath).'/share/'.\$share['token'].'/download/'.rawurlencode(\$name)");
// An install in a folder called "Cloud File Hub" otherwise hands out links
// with real spaces in them: chat clients cut those short at the space.
$checks['a base path with spaces in it is encoded'] =
    str_contains($index, "return public_origin(\$config).Http::encodePath(\$basePath).'/share/'.\$token;")
    && str_contains((string)@file_get_contents($root.'/src/Helpers/Http.php'), 'public static function encodePath(');
$checks['link previews point at the named file'] =
    str_contains($view, '<meta property="og:image" content="<?= $fileUrl ?>">')
    && str_contains($view, '<meta property="og:video" content="<?= $fileUrl ?>">');

/* --- the servers that would have refused the new URLs ---------------------
 *
 * All three refuse *.log, *.ini, *.sql and friends before the front
 * controller. Those rules protect real files under the project root; a share
 * URL never names one. Without an exemption, sharing notes.log produces a
 * link that 403s -- and on the development server only, which is the worst
 * way for a difference between environments to surface.
 */
$checks['Apache lets share links through before the deny rules'] =
    str_contains($htaccess, 'RewriteRule ^share/[A-Za-z0-9_-]{20,128}(?:/|$) index.php [QSA,L]')
    && strpos($htaccess, '^share/') < strpos($htaccess, 'bak|old|orig');
$checks['the development server does the same'] =
    (bool)preg_match("#if \(preg_match\('\#\^/share/\[A-Za-z0-9_-\]\{20,128\}\(\?:/\|\\\$\)\#', \\\$uri\)\)#", $router)
    && strpos($router, '/share/') < strpos($router, '$denied =');
$checks['the nginx example says so too'] =
    str_contains($nginx, 'location ~ ^/share/[A-Za-z0-9_-]{20,128}(/|$)')
    && strpos($nginx, '^/share/') < strpos($nginx, 'sqlite|bak');

/* --- covered for real ----------------------------------------------------- */

$checks['a named link is actually fetched over HTTP somewhere'] =
    str_contains($http, 'the link ends in the file') && str_contains($http, 'notes.log');

$bad = false;
foreach ($checks as $name => $ok) {
    echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL;
    $bad = $bad || !$ok;
}
exit($bad ? 1 : 0);
