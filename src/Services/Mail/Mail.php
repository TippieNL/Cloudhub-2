<?php
declare(strict_types=1);

namespace CloudHub\Services\Mail;

use CloudHub\Services\EmailAddress;
use CloudHub\Services\Sms\Sms;

/**
 * The mail server .env asks for, built once per request -- the email
 * counterpart of Sms::fromConfig().
 *
 * A driver that is named but cannot work -- no host, a sender that is not an
 * address, plain SMTP to the internet, the development outbox in production --
 * is logged once and treated as no mail server at all. Accounts that chose
 * email can then only finish signing in with a recovery code, and nobody can
 * turn email codes on. Sign-in never falls back to the password alone.
 */
final class Mail
{
    public const DRIVERS = ['smtp', 'log'];

    /** The configured sender, or null when there is none that can be used. */
    public static function fromConfig(array $config, string $projectRoot): ?MailSender
    {
        $driver = (string)($config['mail_driver'] ?? '');
        if ($driver === '' || $driver === 'none') return null;
        $problem = self::problem($config);
        if ($problem !== null) {
            static $logged = false;
            if (!$logged) { error_log('[mail] '.$problem); $logged = true; }
            return null;
        }
        if ($driver === 'log') return new LogMail(rtrim($projectRoot, '/').'/logs/mail-outbox.log');
        $encryption = (string)self::encryption($config);
        return new SmtpMail(
            strtolower(trim((string)$config['mail_host'])),
            self::port($config, $encryption),
            $encryption,
            (string)($config['mail_username'] ?? ''),
            (string)($config['mail_password'] ?? ''),
            (string)self::from($config),
            self::fromName($config),
            max(2, min(30, (int)($config['mail_timeout_seconds'] ?? 10) ?: 10)),
            self::heloName($config),
            // Added to the verified defaults: a bundle to check the server against
            // where PHP's OpenSSL has none of its own.
            self::caFile($config) !== '' ? ['cafile' => self::caFile($config)] : [],
        );
    }

    /**
     * Why the configured driver cannot be used, or null when it can (or when
     * none is configured). Names settings, never their values.
     */
    public static function problem(array $config): ?string
    {
        $driver = (string)($config['mail_driver'] ?? '');
        if ($driver === '' || $driver === 'none') return null;
        if (!in_array($driver, self::DRIVERS, true)) {
            return 'MAIL_DRIVER must be one of '.implode(', ', self::DRIVERS).'; emails are off';
        }
        if ($driver === 'log') {
            return ($config['app_env'] ?? 'production') === 'development' ? null
                : 'MAIL_DRIVER=log writes codes to a file and only runs with APP_ENV=development; emails are off';
        }
        $host = strtolower(trim((string)($config['mail_host'] ?? '')));
        if ($host === '') return 'MAIL_HOST is missing; emails are off';
        if (!self::validHost($host)) return 'MAIL_HOST must be a host name or an IP address; emails are off';
        $encryption = self::encryption($config);
        if ($encryption === null) return 'MAIL_ENCRYPTION must be starttls, ssl or none; emails are off';
        $port = self::port($config, $encryption);
        if ($port < 1 || $port > 65535) return 'MAIL_PORT must be a port number; emails are off';
        if ($encryption === 'none' && !Sms::isLocalHost($host)) {
            return 'MAIL_ENCRYPTION=none is allowed only for a mail server on this machine, the local network or your Tailscale network; emails are off';
        }
        $username = (string)($config['mail_username'] ?? '');
        $password = (string)($config['mail_password'] ?? '');
        if (($username === '') !== ($password === '')) return 'MAIL_USERNAME and MAIL_PASSWORD go together; emails are off';
        if (self::from($config) === null) {
            return 'MAIL_FROM must be an email address (or MAIL_USERNAME one, to send as); emails are off';
        }
        $caFile = self::caFile($config);
        if ($caFile !== '' && (!is_file($caFile) || !is_readable($caFile))) {
            return 'MAIL_CA_FILE (or SMTP_CA_FILE) names a file that cannot be read; emails are off';
        }
        return null;
    }

    private static function caFile(array $config): string
    {
        return trim((string)($config['mail_ca_file'] ?? ''));
    }

    /**
     * starttls, ssl or none; null when the setting is none of them. "tls" is
     * read as STARTTLS, as most mail settings pages and frameworks spell it,
     * and an unset value follows the port: 465 is TLS from the start.
     */
    private static function encryption(array $config): ?string
    {
        $value = strtolower(trim((string)($config['mail_encryption'] ?? '')));
        if ($value === '') return (int)($config['mail_port'] ?? 0) === 465 ? 'ssl' : 'starttls';
        return match ($value) {
            'starttls', 'tls' => 'starttls',
            'ssl', 'smtps' => 'ssl',
            'none', 'plain' => 'none',
            default => null,
        };
    }

    private static function port(array $config, string $encryption): int
    {
        $port = (int)($config['mail_port'] ?? 0);
        if ($port !== 0) return $port;
        return match ($encryption) { 'ssl' => 465, 'none' => 25, default => 587 };
    }

    /** MAIL_FROM, or MAIL_USERNAME when that is an address -- as it is for Gmail and most providers. */
    private static function from(array $config): ?string
    {
        $from = trim((string)($config['mail_from'] ?? ''));
        if ($from !== '') return EmailAddress::normalize($from);
        return EmailAddress::normalize((string)($config['mail_username'] ?? ''));
    }

    private static function fromName(array $config): string
    {
        foreach ([$config['mail_from_name'] ?? '', $config['sms_app_name'] ?? ''] as $name) {
            $name = trim(mb_substr((string)preg_replace('/\p{C}+/u', '', (string)$name), 0, 60));
            if ($name !== '') return $name;
        }
        return 'CloudHub';
    }

    /** What CloudHub calls itself in EHLO: APP_URL's host when it is a name, else "localhost". */
    private static function heloName(array $config): string
    {
        $host = strtolower((string)(parse_url((string)($config['app_url'] ?? ''), PHP_URL_HOST) ?? ''));
        return preg_match('/^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/', $host) === 1 ? $host : 'localhost';
    }

    private static function validHost(string $host): bool
    {
        if (filter_var(trim($host, '[]'), FILTER_VALIDATE_IP) !== false) return true;
        return preg_match('/^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$/', $host) === 1;
    }
}
