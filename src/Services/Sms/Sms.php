<?php
declare(strict_types=1);

namespace CloudHub\Services\Sms;

/**
 * The gateway .env asks for, built once per request.
 *
 * A driver that is named but cannot work -- missing credentials, the
 * development outbox in production, a webhook over plain HTTP to the internet,
 * a gateway app's format without its key -- is logged once and treated as no
 * gateway at all. Two-step verification
 * then cannot be turned on, and accounts that already have it can only finish
 * signing in with a recovery code. Sign-in never falls back to the password
 * alone.
 */
final class Sms
{
    public const DRIVERS = ['twilio', 'webhook', 'log'];

    /** The configured sender, or null when there is none that can be used. */
    public static function fromConfig(array $config, string $projectRoot, ?HttpTransport $http = null): ?SmsSender
    {
        $driver = (string)($config['sms_driver'] ?? '');
        if ($driver === '' || $driver === 'none') return null;
        $problem = self::problem($config);
        if ($problem !== null) {
            static $logged = false;
            if (!$logged) { error_log('[sms] '.$problem); $logged = true; }
            return null;
        }

        $http ??= new NativeHttpTransport();
        $timeout = max(2, min(30, (int)($config['sms_timeout_seconds'] ?? 10)));
        return match ($driver) {
            'twilio' => new TwilioSms((string)$config['twilio_account_sid'], (string)$config['twilio_auth_token'],
                (string)($config['sms_from'] ?? ''), (string)($config['twilio_messaging_service_sid'] ?? ''), $http, $timeout),
            'webhook' => new WebhookSms((string)$config['sms_webhook_url'], (string)($config['sms_webhook_token'] ?? ''),
                (string)($config['sms_from'] ?? ''), $http, $timeout, self::webhookFormat($config)),
            'log' => new LogSms(rtrim($projectRoot, '/').'/logs/sms-outbox.log'),
        };
    }

    /**
     * Why the configured driver cannot be used, or null when it can (or when
     * none is configured). Names settings, never their values.
     */
    public static function problem(array $config): ?string
    {
        $driver = (string)($config['sms_driver'] ?? '');
        if ($driver === '' || $driver === 'none') return null;
        if (!in_array($driver, self::DRIVERS, true)) {
            return 'SMS_DRIVER must be one of '.implode(', ', self::DRIVERS).'; text messages are off';
        }
        if ($driver === 'log' && ($config['app_env'] ?? 'production') !== 'development') {
            return 'SMS_DRIVER=log writes codes to a file and only runs with APP_ENV=development; text messages are off';
        }
        if ($driver === 'twilio') {
            if (!preg_match('/^AC[0-9a-f]{32}$/i', (string)($config['twilio_account_sid'] ?? ''))) {
                return 'TWILIO_ACCOUNT_SID is missing or malformed; text messages are off';
            }
            if ((string)($config['twilio_auth_token'] ?? '') === '') return 'TWILIO_AUTH_TOKEN is missing; text messages are off';
            if ((string)($config['sms_from'] ?? '') === '' && (string)($config['twilio_messaging_service_sid'] ?? '') === '') {
                return 'Twilio needs SMS_FROM or TWILIO_MESSAGING_SERVICE_SID; text messages are off';
            }
        }
        if ($driver === 'webhook') {
            $url = (string)($config['sms_webhook_url'] ?? '');
            $parts = filter_var($url, FILTER_VALIDATE_URL) !== false ? parse_url($url) : false;
            $scheme = strtolower((string)($parts['scheme'] ?? ''));
            if (!is_array($parts) || !in_array($scheme, ['https', 'http'], true) || empty($parts['host'])) {
                return 'SMS_WEBHOOK_URL must be an http(s) URL; text messages are off';
            }
            if ($scheme === 'http' && !self::isLocalHost((string)$parts['host'])) {
                return 'SMS_WEBHOOK_URL may use plain http:// only for a gateway on this machine, the local network or your Tailscale network; text messages are off';
            }
            $format = self::webhookFormat($config);
            $token = (string)($config['sms_webhook_token'] ?? '');
            if (!in_array($format, WebhookSms::FORMATS, true)) {
                return 'SMS_WEBHOOK_FORMAT must be one of '.implode(', ', WebhookSms::FORMATS).'; text messages are off';
            }
            if ($format === 'traccar' && $token === '') {
                return 'SMS_WEBHOOK_FORMAT=traccar needs the API key the app shows, in SMS_WEBHOOK_TOKEN; text messages are off';
            }
            if ($format === 'smsgate' && !preg_match('/^[^:]+:.+$/', $token)) {
                return 'SMS_WEBHOOK_FORMAT=smsgate needs the username:password the app shows, in SMS_WEBHOOK_TOKEN; text messages are off';
            }
        }
        return null;
    }

    /** The webhook's request format; unset means CloudHub's own. */
    private static function webhookFormat(array $config): string
    {
        $format = strtolower(trim((string)($config['sms_webhook_format'] ?? '')));
        return $format === '' ? 'cloudhub' : $format;
    }

    /**
     * Names that, by standard, never resolve on the public internet: loopback
     * (RFC 6761), mDNS (RFC 6762), home networks (RFC 8375), ICANN's
     * private-use TLD, and Tailscale's MagicDNS names.
     */
    private const LOCAL_NAME_SUFFIXES = ['localhost', 'local', 'home.arpa', 'internal', 'ts.net'];

    /**
     * Loopback, a private address or a private overlay network, where a code
     * crossing the network in clear stays on hardware the operator controls --
     * an SMS gateway app on the phone CloudHub runs on, one beside it on the
     * LAN, or one on the same Tailscale network.
     *
     * Tailscale numbers devices from 100.64.0.0/10 (RFC 6598 shared address
     * space) and names them under ts.net, and carries their traffic over
     * WireGuard, encrypted end to end. Neither was counted as local, so a
     * gateway phone given its Tailscale address -- the stable "fixed address"
     * the README asks for, and the obvious one for a server reached over
     * Tailscale -- was refused, texts silently went off, and every account with
     * two-step verification could sign in only with a recovery code. The same
     * range is also used for carrier-grade NAT inside an ISP; a gateway is only
     * ever there because the operator typed that address into .env.
     */
    public static function isLocalHost(string $host): bool
    {
        $host = rtrim(strtolower(trim($host, '[]')), '.');
        if ($host === 'localhost') return true;
        foreach (self::LOCAL_NAME_SUFFIXES as $suffix) {
            if (str_ends_with($host, '.'.$suffix)) return true;
        }
        if (filter_var($host, FILTER_VALIDATE_IP) === false) return false;
        if (str_starts_with($host, '127.') || $host === '::1') return true;
        if (filter_var($host, FILTER_VALIDATE_IP, FILTER_FLAG_IPV4) !== false) {
            // 100.64.0.0/10: first octet 100, top two bits of the second 01.
            $octets = array_map('intval', explode('.', $host));
            if ($octets[0] === 100 && ($octets[1] & 0xC0) === 0x40) return true;
        }
        return filter_var($host, FILTER_VALIDATE_IP, FILTER_FLAG_NO_PRIV_RANGE) === false;
    }
}
