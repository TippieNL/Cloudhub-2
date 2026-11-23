<?php
declare(strict_types=1);

namespace CloudHub\Services\Mail;

/**
 * Something that can deliver an email: the email counterpart of SmsSender.
 *
 * Two-step verification hands a code to one of these when an account has
 * chosen email over text messages.
 */
interface MailSender
{
    /**
     * Hand one plain-text message to the mail server.
     *
     * Returning means the server accepted it for delivery. Whether it then
     * lands in the inbox -- or in spam -- is not confirmed here.
     *
     * Implementations must never log the recipient or the message: the message
     * carries a verification code.
     *
     * @return string the Message-ID the message was sent with
     * @throws MailException when the server did not accept it
     */
    public function send(string $to, string $subject, string $body): string;

    /** A short, stable name for logs and diagnostics: "smtp", "log". */
    public function name(): string;
}
