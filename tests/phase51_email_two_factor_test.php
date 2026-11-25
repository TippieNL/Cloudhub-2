<?php
declare(strict_types=1);

/**
 * Two-step verification codes by email.
 *
 *  - Addresses: what is accepted, how they are stored and how they are shown.
 *  - Configuration: MAIL_* settings that cannot work are refused, as SMS_*
 *    ones are, and plain SMTP only to a local, LAN or Tailscale server.
 *  - The SMTP client against tests/http/smtp_server.php: plain with AUTH,
 *    STARTTLS and TLS-from-the-start with a certificate made here, and every
 *    way it must refuse -- an untrusted certificate, the wrong host name,
 *    STARTTLS not offered, a refused address, a server that is down.
 *  - TwoFactor on SQLite with an email sender standing in: turning it on by
 *    email, signing in with an emailed code, moving between phone and email,
 *    and the way back in for an account whose phone can no longer be texted.
 *
 * Over HTTP, against a real database and mail server stand-in, the same flows
 * are covered by tests/http/two_factor_run.php.
 */
spl_autoload_register(static function (string $class): void {
    if (!str_starts_with($class, 'CloudHub\\')) return;
    $file = dirname(__DIR__).'/src/'.str_replace('\\', '/', substr($class, 9)).'.php';
    if (is_file($file)) require $file;
});

use CloudHub\Repositories\TwoFactorRepository;
use CloudHub\Services\Auth;
use CloudHub\Services\EmailAddress;
use CloudHub\Services\LoginRateLimiter;
use CloudHub\Services\Mail\LogMail;
use CloudHub\Services\Mail\Mail;
use CloudHub\Services\Mail\MailException;
use CloudHub\Services\Mail\MailSender;
use CloudHub\Services\Mail\SmtpMail;
use CloudHub\Services\Sms\SmsException;
use CloudHub\Services\Sms\SmsSender;
use CloudHub\Services\TwoFactor;
use CloudHub\Services\TwoFactorError;

$root = dirname(__DIR__);
$scratch = sys_get_temp_dir().'/cloudhub-p51-'.bin2hex(random_bytes(5));
mkdir($scratch.'/sessions', 0775, true);
mkdir($scratch.'/smtp', 0775, true);
$errorLog = $scratch.'/error.log';
ini_set('error_log', $errorLog);
ini_set('session.save_path', $scratch.'/sessions');
ini_set('session.use_cookies', '0');
ini_set('session.use_only_cookies', '0');
ini_set('session.use_strict_mode', '0');
ini_set('session.cache_limiter', '');
session_start();

$checks = [];
$servers = [];

register_shutdown_function(static function () use (&$servers, $scratch): void {
    foreach ($servers as $server) { if (is_resource($server)) { proc_terminate($server); proc_close($server); } }
    $rm = static function (string $p) use (&$rm): void {
        if (is_dir($p)) { foreach (array_diff(scandir($p) ?: [], ['.', '..']) as $n) $rm($p.'/'.$n); @rmdir($p); } else @unlink($p);
    };
    $rm($scratch);
});

function refusal(callable $fn): ?string
{
    try { $fn(); return null; }
    catch (TwoFactorError $e) { return $e->errorCode; }
    catch (MailException $e) { return 'MAIL:'.$e->kind; }
    catch (RuntimeException $e) { return 'HTTP'.$e->getCode(); }
}

final class FakeMail implements MailSender
{
    /** @var list<array{to:string,subject:string,body:string}> */
    public array $sent = [];
    public ?MailException $fail = null;
    public function send(string $to, string $subject, string $body): string
    {
        if ($this->fail !== null) throw $this->fail;
        $this->sent[] = ['to' => $to, 'subject' => $subject, 'body' => $body];
        return 'fake-'.count($this->sent);
    }
    public function name(): string { return 'fake-mail'; }
    public function lastCode(): string
    {
        $last = end($this->sent);
        return $last !== false && preg_match('/^(\d{6}) /', $last['subject'], $m) ? $m[1] : '';
    }
}

final class FakeSms implements SmsSender
{
    public array $sent = [];
    public function send(string $to, string $message): string { $this->sent[] = ['to' => $to, 'message' => $message]; return 'sms-'.count($this->sent); }
    public function name(): string { return 'fake-sms'; }
    public function lastCode(): string
    {
        $last = end($this->sent);
        return $last !== false && preg_match('/^(\d{6}) /', $last['message'], $m) ? $m[1] : '';
    }
}

/** A stand-in SMTP server on a free port, waiting until it answers. */
$startSmtp = static function (array $extra = [], array $env = []) use (&$servers, $scratch, $root): int {
    $probe = stream_socket_server('tcp://127.0.0.1:0');
    $port = (int)substr(strrchr((string)stream_socket_get_name($probe, false), ':'), 1);
    fclose($probe);
    $servers[] = proc_open(array_merge([PHP_BINARY, $root.'/tests/http/smtp_server.php', (string)$port, $scratch.'/smtp'], $extra),
        [1 => ['file', $scratch.'/smtp-out.log', 'a'], 2 => ['file', $scratch.'/smtp-out.log', 'a']], $pipes, null, $env + getenv());
    for ($i = 0; $i < 50; $i++) {
        $s = @stream_socket_client('tcp://127.0.0.1:'.$port, $no, $err, 0.2);
        if ($s !== false) { fclose($s); break; }
        usleep(100_000);
    }
    return $port;
};
$lastReceived = static function () use ($scratch): ?array {
    $lines = @file($scratch.'/smtp/received.jsonl', FILE_IGNORE_NEW_LINES) ?: [];
    return $lines ? json_decode((string)end($lines), true) : null;
};
$mode = static function (string $mode) use ($scratch): void { file_put_contents($scratch.'/smtp/mode', $mode); };

try {

// --- addresses --------------------------------------------------------------------------

$checks['an address is tidied and kept lower case'] = EmailAddress::normalize('  Kevin.Tipker+Cloud@Example.COM ') === 'kevin.tipker+cloud@example.com';
$checks['anything that could carry a second recipient or header is refused'] =
    EmailAddress::normalize("a@example.com\r\nBcc: b@example.com") === null && EmailAddress::normalize('a@example.com, b@example.com') === null
    && EmailAddress::normalize('<a@example.com>') === null && EmailAddress::normalize('"a b"@example.com') === null
    && EmailAddress::normalize('a@[127.0.0.1]') === null && EmailAddress::normalize('a b@example.com') === null;
$checks['and so is what is not an address'] = EmailAddress::normalize('') === null && EmailAddress::normalize('kevin') === null
    && EmailAddress::normalize('kevin@localhost') === null && EmailAddress::normalize(str_repeat('a', 65).'@example.com') === null
    && EmailAddress::normalize('a@'.str_repeat('b', 250).'.com') === null;
$checks['an address is shown masked, domain whole'] = EmailAddress::mask('kevin@example.com') === 'k•••@example.com'
    && EmailAddress::mask('k@example.com') === 'k•••@example.com';

// --- configuration ----------------------------------------------------------------------

$smtp = static fn(array $more = []): array => $more + ['mail_driver' => 'smtp', 'mail_host' => 'smtp.example.com',
    'mail_username' => 'cloudhub@example.com', 'mail_password' => 'app-password', 'app_env' => 'production'];
$checks['no driver: no mail server, and nothing to complain about'] = Mail::fromConfig([], $scratch) === null && Mail::problem([]) === null;
$checks['Gmail-style settings work: STARTTLS on 587, sending as the account'] = Mail::problem($smtp()) === null
    && Mail::fromConfig($smtp(), $scratch) instanceof SmtpMail;
$checks['"tls" means STARTTLS, "ssl" and port 465 TLS from the start'] = Mail::problem($smtp(['mail_encryption' => 'tls'])) === null
    && Mail::problem($smtp(['mail_encryption' => 'ssl'])) === null && Mail::problem($smtp(['mail_port' => 465])) === null;
$checks['no host, a bad host, a bad encryption or port is refused'] = str_contains((string)Mail::problem($smtp(['mail_host' => ''])), 'MAIL_HOST')
    && str_contains((string)Mail::problem($smtp(['mail_host' => 'ssl://evil'])), 'MAIL_HOST')
    && str_contains((string)Mail::problem($smtp(['mail_encryption' => 'rot13'])), 'MAIL_ENCRYPTION')
    && str_contains((string)Mail::problem($smtp(['mail_port' => 70000])), 'MAIL_PORT');
$checks['a username needs its password, and the sender must be an address'] =
    str_contains((string)Mail::problem($smtp(['mail_password' => ''])), 'MAIL_PASSWORD')
    && str_contains((string)Mail::problem($smtp(['mail_username' => 'kevin', 'mail_from' => ''])), 'MAIL_FROM')
    && Mail::problem($smtp(['mail_username' => 'kevin', 'mail_from' => 'cloud@example.com'])) === null;
$checks['plain SMTP to the internet is refused'] = str_contains((string)Mail::problem($smtp(['mail_encryption' => 'none'])), 'MAIL_ENCRYPTION=none');
$checks['but allowed to this machine, the LAN and Tailscale'] = Mail::problem($smtp(['mail_encryption' => 'none', 'mail_host' => '127.0.0.1'])) === null
    && Mail::problem($smtp(['mail_encryption' => 'none', 'mail_host' => '192.168.1.5'])) === null
    && Mail::problem($smtp(['mail_encryption' => 'none', 'mail_host' => '100.90.78.46'])) === null
    && Mail::problem($smtp(['mail_encryption' => 'none', 'mail_host' => 'relay.tail1234.ts.net'])) === null;
$checks['the development outbox only in development'] = Mail::fromConfig(['mail_driver' => 'log', 'app_env' => 'production'], $scratch) === null
    && Mail::fromConfig(['mail_driver' => 'log', 'app_env' => 'development'], $scratch) instanceof LogMail;
$checks['an unknown driver is no mail server'] = Mail::fromConfig(['mail_driver' => 'pigeon'], $scratch) === null;
$checks['the reasons name settings, never their values'] = !str_contains((string)Mail::problem($smtp(['mail_password' => ''])), 'cloudhub@example.com')
    && !str_contains((string)Mail::problem($smtp(['mail_encryption' => 'none'])), 'smtp.example.com');
$outbox = new LogMail($scratch.'/mail-outbox.log');
$outbox->send('kevin@example.com', '123456 is your CloudHub sign-in code', "123456 is your code.\n\nIt expires.");
$checks['the development outbox keeps the code and masks the address'] = str_contains((string)file_get_contents($scratch.'/mail-outbox.log'), 'to k•••@example.com: 123456 is your CloudHub sign-in code')
    && !str_contains((string)file_get_contents($scratch.'/mail-outbox.log'), 'kevin@');

// --- the SMTP client --------------------------------------------------------------------

$creds = ['SMTP_TEST_USER' => 'cloudhub@example.test', 'SMTP_TEST_PASS' => 'p4ss word'];
$client = static fn(int $port, string $encryption, array $tls = [], string $password = 'p4ss word'): SmtpMail =>
    new SmtpMail('127.0.0.1', $port, $encryption, 'cloudhub@example.test', $password, 'cloudhub@example.test', "Kevin's CloudHub", 3, 'localhost', $tls);

$plain = $startSmtp([], $creds);
$body = "123456 is your code.\n.\nThe line above is a lone dot, and this one has Café • in it.";
$id = $client($plain, 'none')->send('Kevin@Example.test', '123456 is your CloudHub sign-in code', $body);
$got = $lastReceived();
[$head, $raw] = explode("\r\n\r\n", (string)($got['data'] ?? ''), 2) + [1 => ''];
$checks['a message goes through, signed in as the account'] = $got !== null && $got['to'] === ['kevin@example.test'] && $got['auth'] === 'cloudhub@example.test';
$checks['the body arrives exactly, a lone dot included'] = quoted_printable_decode($raw) === str_replace("\n", "\r\n", $body);
$checks['with the headers a mail client expects'] = str_contains($head, "From: \"Kevin's CloudHub\" <cloudhub@example.test>")
    && str_contains($head, 'To: <kevin@example.test>') && str_contains($head, 'Subject: 123456 is your CloudHub sign-in code')
    && str_contains($head, 'Message-ID: <'.$id.'>') && str_contains($head, 'Content-Type: text/plain; charset=UTF-8')
    && str_contains($head, 'Auto-Submitted: auto-generated');
$client($plain, 'none')->send('k@example.test', "Code\r\nBcc: someone@evil.test", 'body');
$checks['a line break in the subject cannot add a header'] = !preg_match('/^Bcc:/mi', (string)$lastReceived()['data']) && $lastReceived()['to'] === ['k@example.test'];
$checks['a recipient that is not an address is never sent to'] = refusal(fn() => $client($plain, 'none')->send("a@example.test>\r\nRCPT TO:<b@example.test", 's', 'b')) === 'MAIL:rejected';
$checks['a wrong password is reported, without it'] = ($e = refusal(fn() => $client($plain, 'none', [], 'wrong')->send('k@example.test', 's', 'b'))) === 'MAIL:unavailable';
$mode('refuse');
$checks['a refused address is "rejected": sending again will not help'] = refusal(fn() => $client($plain, 'none')->send('nobody@example.test', 's', 'b')) === 'MAIL:rejected';
$mode('down');
$checks['a server that will not talk is "unavailable"'] = refusal(fn() => $client($plain, 'none')->send('k@example.test', 's', 'b')) === 'MAIL:unavailable';
$mode('ok');
$checks['so is one that is not there'] = refusal(fn() => $client(1, 'none')->send('k@example.test', 's', 'b')) === 'MAIL:unavailable';
$checks['STARTTLS that is not offered is a failure, never plain text'] = refusal(fn() => $client($plain, 'starttls')->send('k@example.test', 's', 'b')) === 'MAIL:unavailable';

// tools/test-mail.php, the operator's check of the settings: a child process
// reading them from its environment, which .env never overrides.
$testMail = static function (array $env) use ($root, $plain): array {
    $env += ['MAIL_DRIVER' => 'smtp', 'MAIL_HOST' => '127.0.0.1', 'MAIL_PORT' => (string)$plain, 'MAIL_ENCRYPTION' => 'none',
        'MAIL_USERNAME' => 'cloudhub@example.test', 'MAIL_PASSWORD' => 'p4ss word', 'MAIL_FROM' => '', 'MAIL_FROM_NAME' => '',
        'MAIL_TIMEOUT_SECONDS' => '3', 'APP_ENV' => 'production'];
    $proc = proc_open([PHP_BINARY, $root.'/tools/test-mail.php', 'Kevin@Example.test'], [1 => ['pipe', 'w'], 2 => ['pipe', 'w']], $pipes, $root, $env + getenv());
    $out = stream_get_contents($pipes[1]).stream_get_contents($pipes[2]);
    fclose($pipes[1]);
    fclose($pipes[2]);
    return [proc_close($proc), trim((string)$out)];
};
[$status, $said] = $testMail([]);
$checks['tools/test-mail.php sends one test email with the .env settings'] = $status === 0 && str_starts_with($said, 'Sent.')
    && $lastReceived()['to'] === ['kevin@example.test'] && str_contains((string)$lastReceived()['data'], 'Subject: CloudHub test email');
[$status, $said] = $testMail(['MAIL_PASSWORD' => 'n0t-the-app-password']);
$checks['and says the mail server refused the sign-in, without the password or the address'] = $status === 1
    && str_contains($said, 'auth answered 535') && !str_contains($said, 'n0t-the-app-password') && !str_contains(strtolower($said), 'kevin@');
[$status, $said] = $testMail(['MAIL_HOST' => '']);
$checks['or names the setting at fault'] = $status === 1 && str_contains($said, 'MAIL_HOST is missing');
[$status, $said] = $testMail(['MAIL_DRIVER' => '']);
$checks['or that no mail driver is set'] = $status === 1 && str_contains($said, 'MAIL_DRIVER is not set');

if (extension_loaded('openssl')) {
    // A certificate for this run, trusted only through the CA option the client is handed.
    $cnf = $scratch.'/openssl.cnf';
    $makeCert = static function (string $name, string $san) use ($scratch, $cnf): array {
        file_put_contents($cnf, "[req]\ndistinguished_name=dn\n[dn]\n[v3]\nsubjectAltName=$san\nbasicConstraints=CA:TRUE\n");
        $key = openssl_pkey_new(['private_key_bits' => 2048, 'private_key_type' => OPENSSL_KEYTYPE_RSA, 'config' => $cnf]);
        $csr = openssl_csr_new(['commonName' => $name], $key, ['config' => $cnf, 'digest_alg' => 'sha256']);
        $cert = openssl_csr_sign($csr, null, $key, 1, ['config' => $cnf, 'x509_extensions' => 'v3', 'digest_alg' => 'sha256']);
        openssl_x509_export_to_file($cert, $scratch.'/'.$name.'.crt');
        openssl_pkey_export_to_file($key, $scratch.'/'.$name.'.key', null, ['config' => $cnf]);
        return [$scratch.'/'.$name.'.crt', $scratch.'/'.$name.'.key'];
    };
    [$crt, $key] = $makeCert('localhost', 'DNS:localhost,IP:127.0.0.1');
    [$otherCrt, $otherKey] = $makeCert('other.test', 'DNS:other.test');

    $starttls = $startSmtp(['starttls', $crt, $key], $creds);
    $client($starttls, 'starttls', ['cafile' => $crt])->send('k@example.test', 's', 'b');
    $checks['STARTTLS: encrypted before the password is sent'] = ($lastReceived()['encrypted'] ?? false) === true && $lastReceived()['auth'] === 'cloudhub@example.test';
    $checks['a certificate nobody vouches for is refused'] = refusal(fn() => $client($starttls, 'starttls')->send('k@example.test', 's', 'b')) === 'MAIL:unavailable';
    $checks['and verification cannot be switched off from outside'] = refusal(fn() => $client($starttls, 'starttls', ['verify_peer' => false, 'verify_peer_name' => false])->send('k@example.test', 's', 'b')) === 'MAIL:unavailable';
    $mode('inject');
    // Refused by the check made for it, before anything else is sent -- not by
    // the muddle a desynchronised conversation would fall into later.
    try { $client($starttls, 'starttls', ['cafile' => $crt])->send('k@example.test', 's', 'b'); $injected = 'sent'; }
    catch (MailException $e) { $injected = $e->getMessage(); }
    $checks['a line slipped in before TLS starts ends the conversation'] = str_contains($injected, 'before TLS began');
    $mode('ok');
    $wrongName = $startSmtp(['starttls', $otherCrt, $otherKey], $creds);
    $checks['a certificate for another name is refused'] = refusal(fn() => $client($wrongName, 'starttls', ['cafile' => $otherCrt])->send('k@example.test', 's', 'b')) === 'MAIL:unavailable';
    $implicit = $startSmtp(['implicit', $crt, $key], $creds);
    $client($implicit, 'ssl', ['cafile' => $crt])->send('k@example.test', 's', 'b');
    $checks['TLS from the first byte, as on port 465'] = ($lastReceived()['encrypted'] ?? false) === true;
}

// --- two-step verification by email -------------------------------------------------

function sqlite_table(string $schema, string $table): string
{
    if (!preg_match('/CREATE TABLE IF NOT EXISTS '.$table.' \((.*?)\) ENGINE=[^;]*;/s', $schema, $m)) return '';
    $body = preg_replace([
        '/\b(?:BIG)?INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY/', '/ENUM\([^)]*\)/', '/\bUNSIGNED\b/',
        '/UNIQUE KEY \w+\s*\(/', '/ON UPDATE CURRENT_TIMESTAMP/',
    ], ['INTEGER PRIMARY KEY AUTOINCREMENT', 'TEXT', '', 'UNIQUE(', ''], $m[1]) ?? '';
    $lines = array_values(array_filter(array_map('rtrim', preg_split('/\R/', $body) ?: []),
        static fn(string $l): bool => trim($l) !== '' && !str_starts_with(trim($l), '--') && !str_starts_with(trim($l), 'INDEX ')));
    if ($lines) $lines[count($lines) - 1] = rtrim($lines[count($lines) - 1], ',');
    return 'CREATE TABLE '.$table.' ('.implode("\n", $lines).')';
}
$schema = (string)file_get_contents($root.'/database/schema.sql');
$db = new PDO('sqlite::memory:', null, null, [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC]);
$db->sqliteCreateFunction('UTC_TIMESTAMP', static fn(): string => gmdate('Y-m-d H:i:s'), 0);
foreach (['users', 'login_attempts', 'security_events', 'two_factor_challenges', 'two_factor_recovery_codes'] as $table) $db->exec(sqlite_table($schema, $table));
$checks['schema.sql gives a fresh install the address column'] = str_contains(sqlite_table($schema, 'users'), 'two_factor_email VARCHAR(254) NULL');
$addUser = $db->prepare('INSERT INTO users (id, username, password_hash, is_active, role) VALUES (?, ?, ?, 1, ?)');
foreach ([[1, 'kevin', 'kevin-pass-1234', 'admin'], [2, 'anna', 'anna-pass-12345', 'editor'], [3, 'piet', 'piet-pass-12345', 'viewer']] as [$uid, $name, $pw, $role]) {
    $addUser->execute([$uid, $name, password_hash($pw, PASSWORD_BCRYPT, ['cost' => 4]), $role]);
}
$now = time();
$clock = static function () use (&$now): int { return $now; };
$config = ['app_url' => 'https://cloud.example.com', 'app_env' => 'production', 'sms_app_name' => 'CloudHub',
    'two_factor_secret' => str_repeat('k', 64), 'rate_limit_secret' => 'r',
    // Texts and emails share one hourly limit per account; these flows send more
    // than the default five, and the limit itself is checked below with two.
    'two_factor_sms_per_hour' => 50, 'two_factor_sms_ip_per_hour' => 200,
    'login_rate_window_seconds' => 900, 'login_rate_user_attempts' => 5, 'login_rate_ip_attempts' => 20];
$_SERVER['REMOTE_ADDR'] = '203.0.113.7';
$repo = new TwoFactorRepository($db, $clock);
$limiter = new LoginRateLimiter($db, $config, $clock);
$mail = new FakeMail();
$sms = new FakeSms();
$tf = new TwoFactor($db, $repo, $limiter, $sms, $config, $clock, $mail);
$mailOnly = new TwoFactor($db, $repo, $limiter, null, $config, $clock, $mail);
$signIn = static function (string $user, string $password) use ($db): void {
    $_SESSION = [];
    Auth::login($db, $user, $password);
};

$overview = $tf->overview(1);
$checks['the server offers both, and the account has neither yet'] = $overview['available'] && $overview['emailAvailable'] && $overview['smsAvailable']
    && !$overview['enabled'] && $overview['channel'] === null;

// Turning it on by email: the address is proven with a code sent to it.
$checks['an address that is not one is refused'] = refusal(fn() => $tf->startAction(1, 'kevin', 'email', 'kevin-pass-1234', 'not-an-address')) === 'VALIDATION_FAILED';
$start = $tf->startAction(1, 'kevin', 'email', 'kevin-pass-1234', 'Kevin@Example.com');
$checks['turning it on by email sends a code to that address'] = $start['stage'] === 'new' && $start['sent'] === true
    && $start['channel'] === 'email' && $start['destination'] === 'k•••@example.com' && $start['phoneEnding'] === null
    && $mail->sent[0]['to'] === 'kevin@example.com' && $sms->sent === [];
$checks['the email says what the code is for, and for how long'] = str_contains($mail->sent[0]['subject'], 'is your CloudHub code to confirm this email address')
    && str_contains($mail->sent[0]['body'], 'It expires in 5 minutes') && preg_match('/^\d{6} /', $mail->sent[0]['subject']) === 1;
$checks['a recovery code cannot stand in for a new address'] = refusal(fn() => $tf->confirmAction(1, null, 'abcd-efgh-jkmn-pqrs')) === 'TWO_FACTOR_RECOVERY_NOT_ALLOWED';
$on = $tf->confirmAction(1, $mail->lastCode(), null);
$checks['the right code turns it on, with recovery codes'] = $on['done'] && $on['enabled'] && $on['channel'] === 'email' && count($on['recoveryCodes']) === 10
    && $repo->state(1)['email'] === 'kevin@example.com' && $repo->state(1)['phone'] === null && $repo->state(1)['enabled'];
$recoveryCodes = $on['recoveryCodes'];
$overview = $tf->overview(1);
$checks['the overview says email, and shows the address masked'] = $overview['enabled'] && $overview['channel'] === 'email'
    && $overview['destination'] === 'k•••@example.com' && $overview['phoneEnding'] === null && $overview['codeAvailable'];
$checks['the same address again is refused'] = refusal(fn() => $tf->startAction(1, 'kevin', 'email', 'kevin-pass-1234', 'kevin@example.com')) === 'VALIDATION_FAILED';
$tf->cancelAction(1);

// Signing in with the emailed code.
$signIn('kevin', 'kevin-pass-1234');
$checks['the password alone is no longer enough'] = Auth::user() === null && Auth::pendingSecondFactor() !== null;
$mail->sent = [];
$info = $tf->beginLogin();
$checks['the code step says email, and stays readable by the installed app'] = $info['channel'] === 'email' && $info['method'] === 'email'
    && $info['destination'] === 'k•••@example.com' && $info['phoneEnding'] === null && $info['smsAvailable'] === true
    && $info['codeSent'] === false && $mail->sent === [];
$now += 61;
$sent = $tf->sendLoginCode();
$checks['the sign-in code is emailed'] = $sent['sent'] && $sent['channel'] === 'email' && count($mail->sent) === 1
    && $mail->sent[0]['to'] === 'kevin@example.com' && str_contains($mail->sent[0]['subject'], 'is your CloudHub sign-in code')
    && str_contains($mail->sent[0]['body'], 'someone has your password');
$checks['a wrong code is refused'] = refusal(fn() => $tf->verifyLogin('000000' === $mail->lastCode() ? '111111' : '000000', null)) === 'TWO_FACTOR_CODE_INVALID';
$done = $tf->verifyLogin($mail->lastCode(), null);
$checks['the emailed code signs in'] = $done['user']['username'] === 'kevin' && $done['method'] === 'email' && Auth::user() !== null;

// A mail server that refuses or is down never lets anyone in.
$signIn('kevin', 'kevin-pass-1234');
$tf->beginLogin();
$now += 61;
$mail->fail = new MailException(MailException::UNAVAILABLE, 'smtp: greeting answered 421');
$checks['a mail server that is down: EMAIL_UNAVAILABLE, nobody signed in'] = refusal(fn() => $tf->sendLoginCode()) === 'EMAIL_UNAVAILABLE' && Auth::user() === null;
$mail->fail = new MailException(MailException::REJECTED, 'smtp: recipient answered 550');
$now += 61;
$checks['an address the server refuses: EMAIL_REJECTED'] = refusal(fn() => $tf->sendLoginCode()) === 'EMAIL_REJECTED' && Auth::user() === null;
$mail->fail = null;
$noMail = new TwoFactor($db, $repo, $limiter, $sms, $config, $clock, null);
$offInfo = $noMail->beginLogin();
$checks['with no mail server, the code step offers a recovery code only'] = $offInfo['smsAvailable'] === false && $offInfo['channel'] === 'email';
$checks['and sending says so'] = refusal(fn() => $noMail->sendLoginCode()) === 'EMAIL_NOT_CONFIGURED';
$tf->cancelLogin();

// Moving codes from email to a phone, and back: the old place is told.
$signIn('kevin', 'kevin-pass-1234');
$tf->beginLogin();
$now += 61;
$tf->sendLoginCode();
$tf->verifyLogin($mail->lastCode(), null);
$mail->sent = []; $sms->sent = [];
$move = $tf->startAction(1, 'kevin', 'phone', 'kevin-pass-1234', '+31612345678');
$checks['just signed in: moving to a phone needs only the phone\'s code'] = $move['stage'] === 'new' && $move['channel'] === 'sms' && count($sms->sent) === 1;
$moved = $tf->confirmAction(1, $sms->lastCode(), null);
$checks['codes now go to the phone, and the address is forgotten'] = $moved['done'] && $moved['channel'] === 'sms' && $moved['phoneEnding'] === '78'
    && $repo->state(1)['phone'] === '+31612345678' && $repo->state(1)['email'] === null && $moved['recoveryCodes'] === null;
$checks['and the old address is emailed that they moved'] = count($mail->sent) === 1 && $mail->sent[0]['to'] === 'kevin@example.com'
    && str_contains($mail->sent[0]['body'], 'now go to a different phone number or email address');

// The way back in, as for an account whose phone can no longer be texted: a
// server with email only, a recovery code to sign in, then the new address.
$signIn('kevin', 'kevin-pass-1234');
$stuck = $mailOnly->beginLogin();
$checks['a phone account on an email-only server is offered a recovery code'] = $stuck['smsAvailable'] === false && $stuck['channel'] === 'sms';
$mailOnly->verifyLogin(null, $recoveryCodes[0]);
$checks['which signs in'] = Auth::user() !== null;
$mail->sent = []; $sms->sent = [];
$back = $mailOnly->startAction(1, 'kevin', 'email', 'kevin-pass-1234', 'kevin@example.com');
$checks['having just used a recovery code, the new address is all that is asked'] = $back['stage'] === 'new' && $back['channel'] === 'email' && count($mail->sent) === 1;
$mailOnly->confirmAction(1, $mail->lastCode(), null);
$checks['and codes go to email again'] = $repo->state(1)['email'] === 'kevin@example.com' && $repo->state(1)['phone'] === null;

// Later, without a fresh proof, a change needs the current address first -- or a recovery code.
$now += TwoFactor::RECENT_SECONDS + 61;
$mail->sent = [];
$later = $tf->startAction(1, 'kevin', 'phone', 'kevin-pass-1234', '+31687654321');
$checks['without a recent proof, the current address is asked for first'] = $later['stage'] === 'current' && $later['channel'] === 'email'
    && $later['recoveryAllowed'] === true && count($mail->sent) === 1 && str_contains($mail->sent[0]['subject'], 'code to change where your two-step verification codes go');
$tf->cancelAction(1);

// Turning it off needs the current address, and tells it.
$mail->sent = [];
$tf->startAction(1, 'kevin', 'disable', 'kevin-pass-1234', null);
$off = $tf->confirmAction(1, $mail->lastCode(), null);
$checks['turning it off takes the emailed code, and tells the address'] = $off['done'] && $off['enabled'] === false
    && $repo->state(1)['email'] === null && !$repo->state(1)['enabled']
    && str_contains(end($mail->sent)['body'], 'Two-step verification was turned off');

// An administrator's reset tells the owner by email.
$_SESSION = ['user_id' => 2, 'username' => 'anna', 'role' => 'editor'];
$mail->sent = [];
$tf->startAction(2, 'anna', 'email', 'anna-pass-12345', 'anna@example.org');
$tf->confirmAction(2, $mail->lastCode(), null);
$mail->sent = [];
$reset = $tf->adminReset(1, 'kevin', 'kevin-pass-1234', 2);
$checks['an administrator\'s reset emails the owner, and reports only the mask'] = $reset['channel'] === 'email' && $reset['destination'] === 'a•••@example.org'
    && $reset['phoneEnding'] === null && count($mail->sent) === 1 && $mail->sent[0]['to'] === 'anna@example.org'
    && str_contains($mail->sent[0]['body'], 'An administrator turned off two-step verification');

// Limits count emails as they count texts.
$_SESSION = ['user_id' => 3, 'username' => 'piet', 'role' => 'viewer'];
$db->exec('DELETE FROM login_attempts');
$limited = new TwoFactor($db, $repo, $limiter, $sms, ['two_factor_sms_per_hour' => 2] + $config, $clock, $mail);
$limited->startAction(3, 'piet', 'email', 'piet-pass-12345', 'piet@example.net');
$now += 61;
$limited->resendAction(3);
$now += 61;
$capped = refusal(fn() => $limited->resendAction(3));
$checks['emails to one address are capped by the hourly limit'] = $capped === 'TWO_FACTOR_SMS_LIMIT';
$limited->cancelAction(3);

// A database migrate.php has not given the column: email cannot be chosen,
// and an account that has two-step verification on still has it on.
$old = new PDO('sqlite::memory:', null, null, [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC]);
$old->exec(str_replace(",\n two_factor_email VARCHAR(254) NULL", '', sqlite_table($schema, 'users')));
$old->exec("INSERT INTO users (id, username, password_hash, is_active, role, two_factor_phone, two_factor_enabled_at) VALUES (1, 'x', '', 1, 'viewer', '+31611111111', '2026-01-01 00:00:00')");
foreach (['two_factor_challenges', 'two_factor_recovery_codes'] as $table) $old->exec(sqlite_table($schema, $table));
$oldRepo = new TwoFactorRepository($old);
$checks['without the column, an account that has it on still has it on'] = $oldRepo->state(1)['enabled'] === true
    && $oldRepo->state(1)['email'] === null && $oldRepo->requiredFor(1) && !$oldRepo->emailReady();
$checks['and nobody is offered email'] = (new TwoFactor($old, $oldRepo, $limiter, $sms, $config, $clock, $mail))->overview(1)['emailAvailable'] === false;

// What is kept out of the logs and the audit trail.
$trail = json_encode($db->query('SELECT event_type, context_json FROM security_events')->fetchAll());
$checks['the audit trail records email sends, without the address or a code'] = str_contains((string)$trail, 'two_factor.email')
    && !str_contains((string)$trail, '@example.') && !preg_match('/\b\d{6}\b/', (string)preg_replace('/"\d{4}-\d{2}-\d{2}[^"]*"/', '', (string)$trail));
$checks['and so is the error log'] = !str_contains((string)@file_get_contents($errorLog), '@example.');

// The hand-run SQL file, for hosts without a PHP command line, adds the column
// exactly as migrate.php does, only when missing, in syntax both databases take.
$migrate = (string)file_get_contents($root.'/database/migrate.php');
$sql = (string)file_get_contents($root.'/database/migrations/20261123_two_factor_email.sql');
$defined = preg_match("/addColumn\\(\\\$pdo, 'users', 'two_factor_email', '([^']+)'\\)/", $migrate, $m) ? $m[1] : null;
$statements = (string)preg_replace('/^--.*$/m', '', $sql);
$checks['the SQL migration adds the address column as migrate.php does, only when missing'] = $defined === 'VARCHAR(254) NULL'
    && str_contains($sql, "COLUMN_NAME='two_factor_email'") && str_contains($sql, "'ALTER TABLE users ADD COLUMN two_factor_email $defined'")
    && !preg_match('/ADD COLUMN IF NOT EXISTS|DELIMITER|CREATE PROCEDURE|\b(DROP|DELETE|TRUNCATE)\b/i', $statements)
    && !str_contains($statements, '"');

$env = (string)file_get_contents($root.'/.env.example');
$checks['.env.example documents the mail settings, with no secret in it'] = (bool)preg_match('/^MAIL_DRIVER=$/m', $env)
    && (bool)preg_match('/^MAIL_HOST=$/m', $env) && (bool)preg_match('/^MAIL_USERNAME=$/m', $env)
    && (bool)preg_match('/^MAIL_PASSWORD=$/m', $env) && (bool)preg_match('/^MAIL_ENCRYPTION=starttls$/m', $env);
$config = (string)file_get_contents($root.'/config/config.php');
preg_match_all("/env\\('(MAIL_[A-Z_]+)'/", $config, $keys);
$checks['and every MAIL_ setting the server reads is in it'] = count($keys[1]) >= 9
    && array_filter($keys[1], static fn(string $k): bool => !preg_match('/^'.$k.'=/m', $env)) === [];
// .env has no comments after a value: config/bootstrap.php keeps the comment
// as part of it, so a copied example would carry it into a password.
preg_match_all('/^[A-Z][A-Z0-9_]*=.*$/m', $env."\n".(string)file_get_contents($root.'/README.md'), $settings);
$checks['no example setting has a comment after its value, which .env would read as part of it'] = count($settings[0]) > 20
    && array_filter($settings[0], static fn(string $line): bool => (bool)preg_match('/\s#/', $line)) === [];

} catch (Throwable $e) {
    $checks['the scenarios ran to the end ('.get_class($e).': '.$e->getMessage().' at line '.$e->getLine().')'] = false;
}

$bad = false;
foreach ($checks as $name => $ok) { echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL; $bad = $bad || !$ok; }
exit($bad ? 1 : 0);
