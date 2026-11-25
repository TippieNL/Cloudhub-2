<?php
declare(strict_types=1);

namespace CloudHub\Services\Mail;

use CloudHub\Services\EmailAddress;

/**
 * Development only: "send" by appending to logs/mail-outbox.log.
 *
 * The email counterpart of LogSms, refused by Mail::fromConfig() unless
 * APP_ENV=development for the same reason: the file holds working codes. The
 * address is masked; a developer needs the code, not the mailbox.
 */
final class LogMail implements MailSender
{
    public function __construct(private readonly string $file) {}

    public function name(): string
    {
        return 'log';
    }

    public function send(string $to, string $subject, string $body): string
    {
        $dir = dirname($this->file);
        if (!is_dir($dir) && !@mkdir($dir, 0775, true) && !is_dir($dir)) {
            throw new MailException(MailException::UNAVAILABLE, 'log: cannot create '.$dir);
        }
        $flat = static fn(string $s): string => trim((string)preg_replace('/\s+/', ' ', $s));
        $line = gmdate('c').' to '.EmailAddress::mask($to).': '.$flat($subject).' | '.$flat($body).PHP_EOL;
        if (@file_put_contents($this->file, $line, FILE_APPEND) === false) {
            throw new MailException(MailException::UNAVAILABLE, 'log: cannot write '.$this->file);
        }
        return 'log-'.bin2hex(random_bytes(6));
    }
}
