<?php
declare(strict_types=1);

namespace CloudHub\Services\Mail;

/**
 * An email the server did not accept, and why, in the terms the caller acts
 * on -- the same three kinds a text message can fail with.
 *
 * The message is for the server log. It names the stage of the conversation
 * and the server's reply code, never the recipient, the text or the code in
 * it: SMTP servers routinely quote the address in their error text, so that
 * text is not kept.
 */
final class MailException extends \RuntimeException
{
    /** The server refused this address; sending again will not help. */
    public const REJECTED = 'rejected';

    /**
     * The server was unreachable, refused CloudHub's credentials or sender, or
     * answered with something that cannot be read. It may work later.
     */
    public const UNAVAILABLE = 'unavailable';

    /** No mail server is configured, or the one named cannot be used here. */
    public const NOT_CONFIGURED = 'not_configured';

    public function __construct(public readonly string $kind, string $message, ?\Throwable $previous = null)
    {
        parent::__construct($message, 0, $previous);
    }
}
