<?php
declare(strict_types=1);

namespace CloudHub\Services;

/**
 * Email addresses for two-step verification.
 *
 * Deliberately narrower than RFC 5321 allows: an address is written into SMTP
 * commands and a message header, so quoted local parts, address literals,
 * spaces, brackets, commas and control characters are refused outright rather
 * than escaped -- nobody's real mailbox needs them, and every one is a way to
 * smuggle a second recipient or header into the conversation. Stored lower
 * case: providers treat the local part case-insensitively, and "the same
 * address" has to mean the same thing everywhere it is compared.
 */
final class EmailAddress
{
    /** The address, tidied, or null when it cannot be one. */
    public static function normalize(string $input): ?string
    {
        $address = strtolower(trim($input));
        if ($address === '' || strlen($address) > 254) return null;
        if (preg_match('/[\x00-\x20\x7F<>()\[\],;:"\'\\\\]/', $address) === 1) return null;
        if (filter_var($address, FILTER_VALIDATE_EMAIL) === false) return null;
        [$local, $domain] = explode('@', $address, 2);
        if (strlen($local) > 64 || !str_contains($domain, '.')) return null;
        return $address;
    }

    /**
     * What an answer, a screen or the outbox shows: the first character of the
     * mailbox and the whole domain, "k•••@example.com". Enough for the owner to
     * know which of their addresses it is; not the address.
     */
    public static function mask(string $address): string
    {
        $at = strrpos($address, '@');
        if ($at === false || $at === 0) return '•••';
        return mb_substr(substr($address, 0, $at), 0, 1).'•••'.substr($address, $at);
    }
}
