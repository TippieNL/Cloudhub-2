<?php
declare(strict_types=1);

/**
 * Storage server credentials are sealed at rest.
 *
 * A server's config carries the passwords and keys of another system, and it
 * was stored as plain JSON, so every database dump and backup carried them in
 * the clear. Secrets seals the credential values under APP_KEY, which lives in
 * .env rather than in the database.
 *
 * Exercised against the real class; the route, the repository and the
 * migration are exercised over HTTP, against MySQL, by tests/http/run.php.
 */
require dirname(__DIR__).'/src/Services/Secrets.php';

use CloudHub\Services\Secrets;

$root = dirname(__DIR__);
$checks = [];
$key = bin2hex(random_bytes(32));
$box = new Secrets($key);

// --- sealing ---------------------------------------------------------------
$sealed = $box->seal('hunter2');
$checks['a sealed value does not contain the secret'] = !str_contains($sealed, 'hunter2')
    && !str_contains(base64_decode(substr($sealed, 7)) ?: '', 'hunter2');
$checks['a sealed value says what it is'] = Secrets::isSealed($sealed) && !Secrets::isSealed('hunter2');
$checks['it opens with the same key'] = $box->open($sealed) === 'hunter2';
$checks['the same secret seals differently every time'] = $box->seal('hunter2') !== $sealed;
$checks['another key cannot open it'] = (new Secrets(bin2hex(random_bytes(32))))->open($sealed) === null;
$tampered = substr($sealed, 0, -2).(substr($sealed, -2) === 'AA' ? 'BB' : 'AA');
$checks['an altered value does not open'] = $box->open($tampered) === null;
$checks['nor does one that is not base64'] = $box->open('enc:v1:%%%') === null;

// --- without a usable key ----------------------------------------------------
$refused = static function (Secrets $s): string {
    try { $s->seal('x'); return ''; } catch (RuntimeException $e) { return $e->getCode().' '.$e->getMessage(); }
};
$none = new Secrets('');
$checks['no key: nothing can be sealed, and the refusal says why'] = !$none->available()
    && str_starts_with($refused($none), '503 ') && str_contains($refused($none), 'APP_KEY');
$checks['no key: nothing sealed is opened'] = $none->open($sealed) === null;
$short = new Secrets('abc123');
$checks['a malformed key is refused, not used'] = !$short->available()
    && str_contains($refused($short), '64 hexadecimal');

// --- which values are credentials ----------------------------------------------
$config = [
    'host' => 'nas.local', 'port' => 22, 'username' => 'backup', 'path' => '/srv',
    'password' => 'pw-1', 'passphrase' => 'pp-2', 'privateKey' => "-----BEGIN KEY-----\nabc", 'apiKey' => 'ak-3',
    'token' => 'tk-4', 'secretKey' => 'sk-5', 'useTls' => true, 'emptyPassword' => '',
    'headers' => ['Accept' => 'application/json', 'Authorization' => 'Bearer tk-6'],
    'credentials' => ['user' => 'u-7', 'value' => 'v-8'],
];
$stored = $box->sealAll($config);
$json = (string)json_encode($stored);
$checks['every credential is sealed, however it is named or nested'] =
    !preg_match('/pw-1|pp-2|BEGIN KEY|ak-3|tk-4|sk-5|tk-6|u-7|v-8/', $json);
$checks['everything else is stored as written'] = $stored['host'] === 'nas.local' && $stored['port'] === 22
    && $stored['username'] === 'backup' && $stored['path'] === '/srv' && $stored['useTls'] === true
    && $stored['headers']['Accept'] === 'application/json' && $stored['emptyPassword'] === '';
$checks['a stored config is still JSON'] = is_array(json_decode($json, true));
$checks['opening restores the config exactly'] = $box->openAll($stored) === $config;
$checks['a sealed value is never sealed twice'] = $box->sealAll($stored) === $stored;
$checks['plain text is found, sealed text is not'] = Secrets::hasPlaintext($config)
    && !Secrets::hasPlaintext($stored) && !Secrets::hasPlaintext(['host' => 'x', 'password' => '']);

// A key that cannot open a value leaves it sealed, so a toggle or an edit made
// after APP_KEY changed keeps the credential rather than destroying it.
$other = new Secrets(bin2hex(random_bytes(32)));
$kept = $other->openAll($stored);
$checks['a value another key sealed stays sealed'] = $kept['password'] === $stored['password']
    && $other->sealAll($kept)['password'] === $stored['password'];
$checks['a config with no credentials needs no key'] = $none->sealAll(['host' => 'x', 'path' => '/y']) === ['host' => 'x', 'path' => '/y'];

// --- what clients see, and what they send back ---------------------------------
$masked = Secrets::mask($config);
$checks['clients see no credential'] = !preg_match('/pw-1|pp-2|BEGIN KEY|ak-3|tk-4|sk-5|tk-6|u-7|v-8/', (string)json_encode($masked))
    && $masked['passphrase'] === Secrets::MASK && $masked['headers']['Authorization'] === Secrets::MASK;
$checks['but do see the rest'] = $masked['host'] === 'nas.local' && $masked['useTls'] === true
    && $masked['headers']['Accept'] === 'application/json' && $masked['emptyPassword'] === '';
$edited = $masked;
$edited['host'] = 'nas2.local';
$edited['passphrase'] = 'new-pp';
$back = Secrets::unmask($edited, $config);
$checks['an edit keeps the credentials it did not change'] = $back['password'] === 'pw-1'
    && $back['headers']['Authorization'] === 'Bearer tk-6' && $back['credentials']['value'] === 'v-8';
$checks['and takes the ones it did'] = $back['passphrase'] === 'new-pp' && $back['host'] === 'nas2.local';

// --- wiring -----------------------------------------------------------------
$index = (string)file_get_contents($root.'/public/index.php');
$repo = (string)file_get_contents($root.'/src/Repositories/ServerRepository.php');
$migrate = (string)file_get_contents($root.'/database/migrate.php');
$checks['the repository seals on the way in and opens on the way out'] =
    str_contains($repo, '$this->secrets->sealAll($config)') && str_contains($repo, '$this->secrets->openAll($c)');
$checks['every server answer is masked the same way'] = str_contains($index, "\$s['config'] = Secrets::mask(\$s['config']);")
    && !str_contains($index, "['password', 'privateKey', 'apiKey']");
$checks['the migration seals what was stored before'] = str_contains($migrate, '$secrets->sealAll($serverConfig)');
$checks['a missing key is explained, not hidden as an internal error'] = str_contains($index, "503 => 'SERVICE_UNAVAILABLE',");

$bad = false;
foreach ($checks as $name => $ok) { echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL; $bad = $bad || !$ok; }
exit($bad ? 1 : 0);
