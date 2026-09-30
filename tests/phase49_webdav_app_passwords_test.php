<?php
declare(strict_types=1);

/**
 * WebDAV clients sign in with HTTP Basic and an app password.
 *
 * WebDAV accepted only the browser's session cookie and CSRF token, which no
 * WebDAV client has, so Finder, Explorer, rclone and davfs2 could not sign in
 * at all. The parts that need no database are exercised here against the real
 * code; signing in, roles, revocation and the rest run over HTTP, against
 * MySQL, in tests/http/run.php.
 */
require dirname(__DIR__).'/src/Helpers/Http.php';
require dirname(__DIR__).'/src/Services/Authorization.php';
require dirname(__DIR__).'/src/Repositories/UserRepository.php';
require dirname(__DIR__).'/src/Repositories/AppPasswordRepository.php';

use CloudHub\Helpers\Http;
use CloudHub\Repositories\AppPasswordRepository;

$root = dirname(__DIR__);
$checks = [];

// --- reading the Authorization header, however the server hands it over -----
$credentials = static function (array $server): ?array {
    foreach (['PHP_AUTH_USER', 'PHP_AUTH_PW', 'HTTP_AUTHORIZATION', 'REDIRECT_HTTP_AUTHORIZATION'] as $k) unset($_SERVER[$k]);
    foreach ($server as $k => $v) $_SERVER[$k] = $v;
    return Http::basicCredentials();
};
$checks['as PHP parsed it (mod_php, the built-in server)'] =
    $credentials(['PHP_AUTH_USER' => 'ann', 'PHP_AUTH_PW' => 'pw']) === ['ann', 'pw'];
$checks['as a FastCGI header'] =
    $credentials(['HTTP_AUTHORIZATION' => 'Basic '.base64_encode('ann:pw')]) === ['ann', 'pw'];
$checks['after an Apache rewrite'] =
    $credentials(['REDIRECT_HTTP_AUTHORIZATION' => 'basic '.base64_encode('ann:pw')]) === ['ann', 'pw'];
$checks['a colon in the password is kept'] =
    $credentials(['HTTP_AUTHORIZATION' => 'Basic '.base64_encode('ann:a:b')]) === ['ann', 'a:b'];
$checks['anything else is not Basic credentials'] =
    $credentials(['HTTP_AUTHORIZATION' => 'Bearer abc']) === null
    && $credentials(['HTTP_AUTHORIZATION' => 'Basic !!!']) === null
    && $credentials(['HTTP_AUTHORIZATION' => 'Basic '.base64_encode('no-colon')]) === null
    && $credentials([]) === null;

// --- what an app password looks like --------------------------------------------
$checks['the issued form is accepted'] = AppPasswordRepository::normalise('abcde-fghjk-mnpqr-stuv2') === 'abcdefghjkmnpqrstuv2';
$checks['spaces and capitals are forgiven'] = AppPasswordRepository::normalise(' ABCDE FGHJK MNPQR STUV2 ') === 'abcdefghjkmnpqrstuv2';
$checks['the wrong length is not one'] = AppPasswordRepository::normalise('abcde-fghjk-mnpqr') === null
    && AppPasswordRepository::normalise('abcde-fghjk-mnpqr-stuv2x') === null;
// An account password is not an app password, so it never reaches the database.
$checks['characters outside the alphabet are not one'] = AppPasswordRepository::normalise('abcde-fghjk-mnpqr-stuv0') === null
    && AppPasswordRepository::normalise('abcde-fghjk-mnpqr-stuv1') === null
    && AppPasswordRepository::normalise('abcde-fghjk-mnpqr-stuvl') === null
    && AppPasswordRepository::normalise('correct horse battery') === null;

// --- wiring -------------------------------------------------------------------
$index = (string)file_get_contents($root.'/public/index.php');
$repo = (string)file_get_contents($root.'/src/Repositories/AppPasswordRepository.php');
$users = (string)file_get_contents($root.'/src/Repositories/UserRepository.php');
$schema = (string)file_get_contents($root.'/database/schema.sql');
$migrate = (string)file_get_contents($root.'/database/migrate.php');
$checks['only /webdav reads Basic credentials'] =
    str_contains($index, "\$davCredentials = str_starts_with(\$path, '/webdav') ? Http::basicCredentials() : null;")
    && substr_count($index, 'Http::basicCredentials()') === 1;
$checks['a request with its own credentials starts no session'] =
    str_contains($index, 'if (!$isPublicShare && $davCredentials === null) Auth::startSession($config);');
$checks['a WebDAV client that has not signed in is challenged'] =
    str_contains($index, "header('WWW-Authenticate: Basic realm=\"CloudHub WebDAV\", charset=\"UTF-8\"');")
    && str_contains($index, "if (str_starts_with(\$path, '/webdav') && Auth::user() === null) webdav_challenge();");
$checks['without the CSRF token, but not across sites'] =
    str_contains($index, "if (\$davCredentials !== null)Security::rejectCrossSite();\n        else Auth::verifyCsrf();");
$checks['and still under the role rules'] = str_contains($index, 'elseif (!in_array($path, $writeExemptPost, true))Authorization::requireWrite();');
$checks['only a hash is stored'] = str_contains($repo, "hash('sha256', \$plain)") && str_contains($schema, 'token_hash CHAR(64) NOT NULL')
    && str_contains($migrate, 'CREATE TABLE IF NOT EXISTS app_passwords');
$checks['issuing one takes the account password'] =
    (bool)preg_match("/app-passwords' && \\\$method === 'POST'.*?verifyPassword\(\\\$id, \\\$current\).*?app_passwords\(\)->create/s", $index);
$checks['a new password or a deleted account revokes them'] = substr_count($users, '$this->revokeAppPasswords($id);') === 2;
$checks['Apache passes the header on to PHP'] =
    str_contains((string)file_get_contents($root.'/.htaccess'), 'RewriteRule .* - [E=HTTP_AUTHORIZATION:%{HTTP:Authorization}]')
    && str_contains((string)file_get_contents($root.'/public/.htaccess'), 'RewriteRule .* - [E=HTTP_AUTHORIZATION:%{HTTP:Authorization}]');

$bad = false;
foreach ($checks as $name => $ok) { echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL; $bad = $bad || !$ok; }
exit($bad ? 1 : 0);
