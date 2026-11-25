<?php
declare(strict_types=1);

/**
 * Send one test email with the MAIL_* settings in .env.
 *
 *   php tools/test-mail.php <address>
 *
 * For checking those settings before an account's two-step codes move to
 * email, and for finding out why codes do not arrive. It names the setting
 * that is wrong, or the step at which the mail server said no and its reply
 * code -- 535 at "auth", for one, is a wrong username or app password. Like
 * the server's own log, it never prints the password or the address.
 */
if (PHP_SAPI !== 'cli') { http_response_code(404); exit; }

require dirname(__DIR__).'/config/bootstrap.php';

use CloudHub\Services\Mail\Mail;
use CloudHub\Services\Mail\MailException;

$to = trim((string)($argv[1] ?? ''));
if ($to === '') {
    fwrite(STDERR, "Usage: php tools/test-mail.php <address>\n");
    exit(1);
}
if ($config['mail_driver'] === '' || $config['mail_driver'] === 'none') {
    fwrite(STDERR, "MAIL_DRIVER is not set in .env, so no account can choose email codes.\n");
    exit(1);
}
$problem = Mail::problem($config);
$mail = $problem === null ? Mail::fromConfig($config, dirname(__DIR__)) : null;
if ($mail === null) {
    fwrite(STDERR, 'Not sent: '.($problem ?? 'the mail settings cannot be used').PHP_EOL);
    exit(1);
}

try {
    $mail->send($to, 'CloudHub test email', "This is a test from CloudHub.\n\nIt reached you, so two-step verification codes can go by email.");
} catch (MailException $e) {
    fwrite(STDERR, 'Not sent: '.$e->getMessage().PHP_EOL);
    exit(1);
}
echo $mail->name() === 'log'
    ? "Written to logs/mail-outbox.log: MAIL_DRIVER=log sends nothing.\n"
    : "Sent. If it is not in the inbox, look in the spam folder.\n";
