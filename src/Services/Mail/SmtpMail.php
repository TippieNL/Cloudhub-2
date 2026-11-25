<?php
declare(strict_types=1);

namespace CloudHub\Services\Mail;

use CloudHub\Services\EmailAddress;

/**
 * Email over SMTP with nothing beyond PHP's own streams and OpenSSL.
 *
 * A self-hosted CloudHub has no mail server of its own and no Composer, and
 * an Android/KSWEB PHP has no sendmail for mail() to hand a message to -- so
 * this speaks SMTP itself, to the submission server of the mailbox the
 * operator already has: Gmail, Outlook, their provider, a relay on the LAN.
 *
 * Encryption is STARTTLS (normally port 587) or TLS from the first byte
 * (465), at TLS 1.2 or later, and the certificate is always verified against
 * the host name. Plain SMTP is accepted only to a server on this machine or a
 * private network (Mail::problem() decides), and STARTTLS that the server does
 * not offer is a failure, never a quiet fall back to plain text -- the
 * password and the code would travel in clear.
 *
 * Nothing that identifies the recipient or carries the code reaches an
 * exception message: servers quote the address in their errors, so only the
 * stage of the conversation and the reply code are kept.
 */
final class SmtpMail implements MailSender
{
    public const ENCRYPTIONS = ['starttls', 'ssl', 'none'];

    /**
     * @param string $encryption one of ENCRYPTIONS
     * @param array $tls further stream SSL options, such as a CA file; they can
     *                   add to the verified defaults but never turn them off
     */
    public function __construct(
        private readonly string $host,
        private readonly int $port,
        private readonly string $encryption,
        private readonly string $username,
        private readonly string $password,
        private readonly string $from,
        private readonly string $fromName,
        private readonly int $timeout,
        private readonly string $heloName = 'localhost',
        private readonly array $tls = [],
    ) {
        if (!in_array($encryption, self::ENCRYPTIONS, true)) throw new \InvalidArgumentException('Unknown SMTP encryption');
    }

    public function name(): string
    {
        return 'smtp';
    }

    public function send(string $to, string $subject, string $body): string
    {
        $to = EmailAddress::normalize($to) ?? throw new MailException(MailException::REJECTED, 'smtp: not an email address');
        $from = EmailAddress::normalize($this->from)
            ?? throw new MailException(MailException::NOT_CONFIGURED, 'smtp: MAIL_FROM is not an email address');
        $messageId = bin2hex(random_bytes(16)).'@'.substr($from, strrpos($from, '@') + 1);
        $data = $this->compose($to, $from, $subject, $body, $messageId);

        $socket = $this->connect();
        try {
            $this->expect($socket, 'greeting', [220]);
            $features = $this->ehlo($socket);
            if ($this->encryption === 'starttls') {
                if (!array_key_exists('STARTTLS', $features)) {
                    throw new MailException(MailException::UNAVAILABLE, 'smtp: '.$this->host.' does not offer STARTTLS; set MAIL_ENCRYPTION=ssl and port 465 if it uses TLS from the start');
                }
                $this->command($socket, 'STARTTLS', 'starttls', [220]);
                $this->startTls($socket);
                // What was offered in clear does not count once encrypted.
                $features = $this->ehlo($socket);
            }
            if ($this->username !== '') $this->authenticate($socket, $features);
            $this->command($socket, 'MAIL FROM:<'.$from.'>', 'sender', [250]);
            $this->command($socket, 'RCPT TO:<'.$to.'>', 'recipient', [250, 251]);
            $this->command($socket, 'DATA', 'data', [354]);
            $this->write($socket, $data."\r\n.\r\n");
            $this->expect($socket, 'message', [250]);
            // Accepted. A server that drops the line instead of saying goodbye
            // has still taken the message.
            try { $this->command($socket, 'QUIT', 'quit', [221]); } catch (MailException) {}
        } finally {
            @fclose($socket);
        }
        return $messageId;
    }

    /** The whole message, headers and body, dot-stuffed and with CRLF line endings throughout. */
    private function compose(string $to, string $from, string $subject, string $body, string $messageId): string
    {
        $headers = [
            'Date: '.gmdate('D, d M Y H:i:s').' +0000',
            'From: '.self::mailbox($this->fromName, $from),
            'To: <'.$to.'>',
            'Subject: '.self::headerText($subject),
            'Message-ID: <'.$messageId.'>',
            'MIME-Version: 1.0',
            'Content-Type: text/plain; charset=UTF-8',
            'Content-Transfer-Encoding: quoted-printable',
            // RFC 3834: a machine sent this, so no out-of-office replies to it.
            'Auto-Submitted: auto-generated',
        ];
        $text = quoted_printable_encode(str_replace(["\r\n", "\r", "\n"], ["\n", "\n", "\r\n"], $body));
        $message = implode("\r\n", $headers)."\r\n\r\n".$text;
        // A line that is a single "." ends the message: every line that starts
        // with one gets a second (RFC 5321 4.5.2). Quoted-printable leaves them.
        return (string)preg_replace('/^\./m', '..', $message);
    }

    /** A header value on one line: control characters gone, non-ASCII as an RFC 2047 encoded word. */
    private static function headerText(string $value): string
    {
        $value = trim((string)preg_replace('/[\x00-\x1F\x7F]+/', ' ', $value));
        return preg_match('/^[\x20-\x7E]*$/', $value) === 1 ? $value : mb_encode_mimeheader($value, 'UTF-8', 'B', "\r\n ");
    }

    private static function mailbox(string $name, string $address): string
    {
        $name = trim((string)preg_replace('/[\x00-\x1F\x7F"\\\\]+/', ' ', $name));
        if ($name === '') return '<'.$address.'>';
        $shown = preg_match('/^[\x20-\x7E]*$/', $name) === 1 ? '"'.$name.'"' : mb_encode_mimeheader($name, 'UTF-8', 'B', "\r\n ");
        return $shown.' <'.$address.'>';
    }

    /** @return resource */
    private function connect()
    {
        // Defaults first, so the extra options can add (a CA file) but never
        // switch verification off.
        $context = stream_context_create(['ssl' => [
            'verify_peer' => true,
            'verify_peer_name' => true,
            'allow_self_signed' => false,
            'peer_name' => $this->host,
            'SNI_enabled' => true,
            'crypto_method' => self::tlsMethods(),
        ] + $this->tls]);
        $target = filter_var($this->host, FILTER_VALIDATE_IP, FILTER_FLAG_IPV6) !== false ? '['.$this->host.']' : $this->host;
        $scheme = $this->encryption === 'ssl' ? 'ssl' : 'tcp';
        $socket = @stream_socket_client($scheme.'://'.$target.':'.$this->port, $errno, $error,
            $this->timeout, STREAM_CLIENT_CONNECT, $context);
        if ($socket === false) {
            throw new MailException(MailException::UNAVAILABLE, 'smtp: cannot reach '.$this->host.':'.$this->port
                .' ('.($error !== '' ? $error : self::lastError()).')');
        }
        stream_set_timeout($socket, $this->timeout);
        return $socket;
    }

    /** @param resource $socket */
    private function startTls($socket): void
    {
        // Anything already waiting arrived in clear after "220 Ready": a
        // man in the middle's line, which would otherwise be read as the
        // encrypted server's answer (STARTTLS response injection).
        if ((int)(stream_get_meta_data($socket)['unread_bytes'] ?? 0) > 0) {
            throw new MailException(MailException::UNAVAILABLE, 'smtp: '.$this->host.' sent more than it should before TLS began; not continuing');
        }
        if (@stream_socket_enable_crypto($socket, true, self::tlsMethods()) !== true) {
            throw new MailException(MailException::UNAVAILABLE, 'smtp: TLS with '.$this->host.' failed ('.self::lastError().')');
        }
    }

    private static function tlsMethods(): int
    {
        return STREAM_CRYPTO_METHOD_TLSv1_2_CLIENT
            | (defined('STREAM_CRYPTO_METHOD_TLSv1_3_CLIENT') ? STREAM_CRYPTO_METHOD_TLSv1_3_CLIENT : 0);
    }

    /**
     * @param resource $socket
     * @return array<string, list<string>> the extensions the server offers, by keyword
     */
    private function ehlo($socket): array
    {
        [, $lines] = $this->command($socket, 'EHLO '.$this->heloName, 'ehlo', [250]);
        $features = [];
        foreach (array_slice($lines, 1) as $line) {
            $words = preg_split('/[\s=]+/', strtoupper(trim($line))) ?: [];
            $keyword = (string)array_shift($words);
            if ($keyword !== '') $features[$keyword] = $words;
        }
        return $features;
    }

    /**
     * PLAIN, or LOGIN where that is all there is. The credentials are in the
     * commands, so a refusal reports the reply code only.
     *
     * @param resource $socket
     */
    private function authenticate($socket, array $features): void
    {
        $mechanisms = $features['AUTH'] ?? [];
        if (in_array('PLAIN', $mechanisms, true)) {
            $this->command($socket, 'AUTH PLAIN '.base64_encode("\0".$this->username."\0".$this->password), 'auth', [235]);
            return;
        }
        if (in_array('LOGIN', $mechanisms, true)) {
            $this->command($socket, 'AUTH LOGIN', 'auth', [334]);
            $this->command($socket, base64_encode($this->username), 'auth', [334]);
            $this->command($socket, base64_encode($this->password), 'auth', [235]);
            return;
        }
        throw new MailException(MailException::UNAVAILABLE, 'smtp: '.$this->host.' offers no AUTH PLAIN or LOGIN'
            .($mechanisms === [] ? ', or no sign-in at all on this connection' : ''));
    }

    /**
     * @param resource $socket
     * @param list<int> $accept
     * @return array{0: int, 1: list<string>}
     */
    private function command($socket, string $line, string $stage, array $accept): array
    {
        $this->write($socket, $line."\r\n");
        return $this->expect($socket, $stage, $accept);
    }

    /**
     * @param resource $socket
     * @param list<int> $accept
     * @return array{0: int, 1: list<string>}
     */
    private function expect($socket, string $stage, array $accept): array
    {
        [$code, $lines] = $this->reply($socket, $stage);
        if (in_array($code, $accept, true)) return [$code, $lines];
        // RFC 3463's enhanced code says what happened without the server's
        // prose, which may quote the address.
        $detail = preg_match('/^([245]\.\d{1,3}\.\d{1,3})\b/', $lines[0] ?? '', $m) === 1 ? ' '.$m[1] : '';
        $kind = $stage === 'recipient' && $code >= 500 ? MailException::REJECTED : MailException::UNAVAILABLE;
        throw new MailException($kind, 'smtp: '.$stage.' answered '.$code.$detail);
    }

    /**
     * One reply, which may span lines ("250-..." then "250 ...").
     *
     * @param resource $socket
     * @return array{0: int, 1: list<string>}
     */
    private function reply($socket, string $stage): array
    {
        $lines = [];
        while (count($lines) < 100) {
            $line = fgets($socket, 4096);
            if ($line === false) {
                $timedOut = (bool)(stream_get_meta_data($socket)['timed_out'] ?? false);
                throw new MailException(MailException::UNAVAILABLE, 'smtp: '.($timedOut ? 'timed out' : 'connection closed').' waiting for the '.$stage.' reply');
            }
            if (preg_match('/^(\d{3})(?:([ -])(.*))?$/s', rtrim($line, "\r\n"), $m) !== 1) {
                throw new MailException(MailException::UNAVAILABLE, 'smtp: unreadable '.$stage.' reply');
            }
            $lines[] = (string)($m[3] ?? '');
            if (($m[2] ?? ' ') !== '-') return [(int)$m[1], $lines];
        }
        throw new MailException(MailException::UNAVAILABLE, 'smtp: endless '.$stage.' reply');
    }

    /** @param resource $socket */
    private function write($socket, string $data): void
    {
        $total = strlen($data);
        for ($done = 0; $done < $total; $done += $written) {
            $written = @fwrite($socket, substr($data, $done));
            if ($written === false || $written === 0) throw new MailException(MailException::UNAVAILABLE, 'smtp: connection lost while sending');
        }
    }

    private static function lastError(): string
    {
        $message = (string)(error_get_last()['message'] ?? 'no detail');
        // One line: OpenSSL's own messages arrive broken over several.
        return trim((string)preg_replace(['/^stream_socket_\w+\(\): /', '/\s+/'], ['', ' '], $message));
    }
}
