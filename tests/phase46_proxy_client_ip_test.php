<?php
declare(strict_types=1);

/**
 * The per-IP login throttle counts the address that really connected.
 *
 * Behind a trusted proxy, X-Forwarded-For is a list the client starts and each
 * proxy appends to. Only the last entry was written by the proxy; the first
 * was whatever the client sent, so reading it let one client present a fresh
 * address with every attempt and never meet the per-IP limit.
 *
 * Exercised against the real class: clientIp() reads $_SERVER and nothing
 * else, so no database is involved.
 */
require dirname(__DIR__).'/src/Services/LoginRateLimiter.php';

use CloudHub\Services\LoginRateLimiter;

$checks = [];
$pdo = new PDO('sqlite::memory:');
$ip = function (bool $trustProxy, ?string $forwarded, string $remote = '10.0.0.2') use ($pdo): string {
    $_SERVER['REMOTE_ADDR'] = $remote;
    if ($forwarded === null) unset($_SERVER['HTTP_X_FORWARDED_FOR']);
    else $_SERVER['HTTP_X_FORWARDED_FOR'] = $forwarded;
    return (new LoginRateLimiter($pdo, ['trust_proxy' => $trustProxy]))->clientIp();
};

$checks['without a trusted proxy the header is ignored'] =
    $ip(false, '198.51.100.7') === '10.0.0.2';
$checks['behind a proxy that sets one address, that address counts'] =
    $ip(true, '203.0.113.9') === '203.0.113.9';
$checks['behind a proxy that appends, the appended address counts'] =
    $ip(true, '198.51.100.7, 203.0.113.9') === '203.0.113.9';
$checks['an address the client wrote cannot pick its own bucket'] =
    $ip(true, '198.51.100.7, 198.51.100.8, 203.0.113.9') === '203.0.113.9'
    && $ip(true, '198.51.100.99, 203.0.113.9') === '203.0.113.9';
$checks['IPv6 is read the same way'] =
    $ip(true, '2001:db8::1, 2001:db8::2') === '2001:db8::2';
$checks['an unusable last entry falls back to the proxy, never to an earlier entry'] =
    $ip(true, '198.51.100.7, unknown') === '10.0.0.2'
    && $ip(true, '198.51.100.7,') === '10.0.0.2';
$checks['no header at all falls back to the connection'] =
    $ip(true, null) === '10.0.0.2';

$bad = false;
foreach ($checks as $name => $ok) { echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL; $bad = $bad || !$ok; }
exit($bad ? 1 : 0);
