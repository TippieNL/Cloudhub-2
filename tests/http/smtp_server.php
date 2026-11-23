<?php
declare(strict_types=1);

/**
 * A stand-in SMTP server for the tests: the target of MAIL_DRIVER=smtp.
 *
 *   php tests/http/smtp_server.php <port> <dir> [starttls|implicit <cert.pem> <key.pem>]
 *
 * Records every message it accepts in <dir>/received.jsonl -- envelope, the
 * account it was authenticated as, whether the line was encrypted, and the raw
 * message -- which is how the tests read the codes CloudHub emails. <dir>/mode
 * makes it misbehave: ok, refuse (the recipient is refused, 550 5.1.1), down
 * (421 at the greeting), slow (stalls past the client's timeout). With
 * SMTP_TEST_USER and SMTP_TEST_PASS set it insists on AUTH PLAIN or LOGIN and
 * checks them; without encryption it then answers 530, as a real submission
 * server does, until the line is encrypted.
 *
 * One conversation at a time, which is plenty for one message per request.
 */
[$self, $port, $dir] = $argv + [null, null, null];
$tlsMode = $argv[3] ?? '';
$cert = $argv[4] ?? '';
$key = $argv[5] ?? '';
if ($port === null || $dir === null) { fwrite(STDERR, "usage: smtp_server.php <port> <dir> [starttls|implicit cert key]\n"); exit(2); }
$user = (string)getenv('SMTP_TEST_USER');
$pass = (string)getenv('SMTP_TEST_PASS');

$options = $tlsMode !== '' ? ['ssl' => ['local_cert' => $cert, 'local_pk' => $key, 'verify_peer' => false]] : [];
$scheme = $tlsMode === 'implicit' ? 'tls' : 'tcp';
$server = stream_socket_server($scheme.'://127.0.0.1:'.$port, $errno, $error, STREAM_SERVER_BIND | STREAM_SERVER_LISTEN, stream_context_create($options));
if ($server === false) { fwrite(STDERR, "cannot listen: $error\n"); exit(1); }

$say = static function ($conn, string $line): void { @fwrite($conn, $line."\r\n"); };
$read = static function ($conn): ?string {
    $line = fgets($conn, 8192);
    return $line === false ? null : rtrim($line, "\r\n");
};

while (true) {
    $conn = @stream_socket_accept($server, -1);
    if ($conn === false) continue;
    stream_set_timeout($conn, 15);
    $mode = trim((string)@file_get_contents($dir.'/mode')) ?: 'ok';
    if ($mode === 'down') { $say($conn, '421 4.3.2 Service not available'); fclose($conn); continue; }
    if ($mode === 'slow') { sleep(6); fclose($conn); continue; }

    $encrypted = $tlsMode === 'implicit';
    $authed = '';
    $from = '';
    $to = [];
    $say($conn, '220 smtp.test ESMTP stand-in');
    while (($line = $read($conn)) !== null) {
        $verb = strtoupper(strtok($line, ' ') ?: '');
        if ($verb === 'EHLO' || $verb === 'HELO') {
            $offers = ['250-smtp.test greets you'];
            if ($tlsMode === 'starttls' && !$encrypted) $offers[] = '250-STARTTLS';
            // Like a real submission server: no AUTH on an unencrypted line
            // when encryption is on offer.
            if ($user !== '' && ($encrypted || $tlsMode === '')) $offers[] = '250-AUTH PLAIN LOGIN';
            $offers[] = '250 8BITMIME';
            foreach ($offers as $offer) $say($conn, $offer);
        } elseif ($verb === 'STARTTLS' && $tlsMode === 'starttls' && !$encrypted) {
            // 'inject' plays the man in the middle: a line in clear after the
            // go-ahead, in the same packet, hoping to be read after TLS starts.
            $say($conn, $mode === 'inject' ? "220 2.0.0 Ready to start TLS\r\n250 2.0.0 injected in clear" : '220 2.0.0 Ready to start TLS');
            $encrypted = @stream_socket_enable_crypto($conn, true, STREAM_CRYPTO_METHOD_TLS_SERVER) === true;
            if (!$encrypted) break;
        } elseif ($verb === 'AUTH') {
            $parts = explode(' ', $line);
            $mechanism = strtoupper($parts[1] ?? '');
            if ($mechanism === 'PLAIN') {
                $decoded = explode("\0", (string)base64_decode($parts[2] ?? ''));
                $ok = ($decoded[1] ?? null) === $user && ($decoded[2] ?? null) === $pass;
            } elseif ($mechanism === 'LOGIN') {
                $say($conn, '334 VXNlcm5hbWU6');
                $u = base64_decode((string)$read($conn));
                $say($conn, '334 UGFzc3dvcmQ6');
                $p = base64_decode((string)$read($conn));
                $ok = $u === $user && $p === $pass;
            } else {
                $ok = false;
            }
            if ($ok) { $authed = $user; $say($conn, '235 2.7.0 Authentication successful'); }
            else $say($conn, '535 5.7.8 Authentication credentials invalid');
        } elseif ($verb === 'MAIL') {
            if ($user !== '' && $authed === '') { $say($conn, '530 5.7.0 Authentication required'); continue; }
            $from = preg_match('/<([^>]*)>/', $line, $m) ? $m[1] : '';
            $to = [];
            $say($conn, '250 2.1.0 OK');
        } elseif ($verb === 'RCPT') {
            if ($mode === 'refuse') { $say($conn, '550 5.1.1 <'.(preg_match('/<([^>]*)>/', $line, $m) ? $m[1] : '').'> User unknown'); continue; }
            $to[] = preg_match('/<([^>]*)>/', $line, $m) ? $m[1] : '';
            $say($conn, '250 2.1.5 OK');
        } elseif ($verb === 'DATA') {
            if ($from === '' || $to === []) { $say($conn, '503 5.5.1 Bad sequence of commands'); continue; }
            $say($conn, '354 End data with <CR><LF>.<CR><LF>');
            $data = [];
            while (($dataLine = $read($conn)) !== null && $dataLine !== '.') {
                // Undo dot-stuffing, as a real server does.
                $data[] = str_starts_with($dataLine, '..') ? substr($dataLine, 1) : $dataLine;
            }
            file_put_contents($dir.'/received.jsonl', json_encode([
                'from' => $from, 'to' => $to, 'auth' => $authed, 'encrypted' => $encrypted,
                'data' => implode("\r\n", $data),
            ], JSON_UNESCAPED_SLASHES)."\n", FILE_APPEND);
            $say($conn, '250 2.0.0 OK queued as '.bin2hex(random_bytes(4)));
            $from = '';
            $to = [];
        } elseif ($verb === 'RSET' || $verb === 'NOOP') {
            $say($conn, '250 2.0.0 OK');
        } elseif ($verb === 'QUIT') {
            $say($conn, '221 2.0.0 Bye');
            break;
        } else {
            $say($conn, '502 5.5.2 Command not recognized');
        }
    }
    @fclose($conn);
}
