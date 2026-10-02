<?php
declare(strict_types=1);

namespace CloudHub\Repositories;

use PDO;
use RuntimeException;

/**
 * App passwords: what a WebDAV client signs in with.
 *
 * WebDAV clients -- Finder, Windows Explorer, rclone, davfs2, phone apps --
 * authenticate with HTTP Basic on every request and keep the password they
 * were given. An account password is the wrong thing to hand them: it would be
 * stored in every client, checked with a deliberately slow hash on every
 * request, and could only be withdrawn by changing it everywhere. An app
 * password is issued per client, can be revoked on its own, and is worthless
 * for signing in to the web app.
 *
 * Each is 20 characters drawn from 31 that cannot be mistaken for one another
 * (about 99 bits), so guessing one is not a thing a rate limit needs to stop,
 * and it is stored as a SHA-256 hash: fast to check on every request, and of
 * no use to someone who reads the table. The plaintext is shown once, when it
 * is created.
 *
 * Changing an account's password, or deleting the account, revokes them all
 * (UserRepository), so the usual response to a suspected compromise cuts off
 * every client too.
 */
final class AppPasswordRepository
{
    private const ALPHABET = 'abcdefghjkmnpqrstuvwxyz23456789';
    private const LENGTH = 20;
    /** How stale last_used_at may get before a sign-in refreshes it: a hint, not a log. */
    private const TOUCH_SECONDS = 300;
    /** More than anyone has clients, and a bound on what one account can add. */
    public const MAX_PER_ACCOUNT = 50;

    public function __construct(private readonly PDO $db) {}

    /**
     * The canonical form of a typed app password, or null when it cannot be one.
     *
     * It is displayed in groups of five and typed on phones, so dashes, spaces
     * and capitals are forgiven.
     */
    public static function normalise(string $password): ?string
    {
        $plain = strtolower((string)preg_replace('/[\s-]+/', '', $password));
        if (strlen($plain) !== self::LENGTH || strspn($plain, self::ALPHABET) !== self::LENGTH) return null;
        return $plain;
    }

    /** @return list<array{id:int,name:string,createdAt:string,lastUsedAt:?string}> */
    public function list(int $userId): array
    {
        $stmt = $this->db->prepare('SELECT id, name, created_at, last_used_at FROM app_passwords WHERE user_id = ? ORDER BY created_at DESC, id DESC');
        $stmt->execute([$userId]);
        return array_map(static fn(array $r): array => [
            'id' => (int)$r['id'],
            'name' => (string)$r['name'],
            'createdAt' => gmdate('c', (int)strtotime((string)$r['created_at'].' UTC')),
            'lastUsedAt' => $r['last_used_at'] === null ? null : gmdate('c', (int)strtotime((string)$r['last_used_at'].' UTC')),
        ], $stmt->fetchAll(PDO::FETCH_ASSOC) ?: []);
    }

    /** Issue one. The only time the password itself is ever returned. */
    public function create(int $userId, string $name): array
    {
        $count = $this->db->prepare('SELECT COUNT(*) FROM app_passwords WHERE user_id = ?');
        $count->execute([$userId]);
        if ((int)$count->fetchColumn() >= self::MAX_PER_ACCOUNT) {
            throw new RuntimeException('You have '.self::MAX_PER_ACCOUNT.' app passwords already; revoke one first', 409);
        }

        $plain = '';
        for ($i = 0; $i < self::LENGTH; $i++) $plain .= self::ALPHABET[random_int(0, strlen(self::ALPHABET) - 1)];
        $stmt = $this->db->prepare('INSERT INTO app_passwords (user_id, name, token_hash, created_at) VALUES (?, ?, ?, UTC_TIMESTAMP())');
        $stmt->execute([$userId, $name, hash('sha256', $plain)]);
        $id = (int)$this->db->lastInsertId();

        foreach ($this->list($userId) as $row) {
            if ($row['id'] === $id) return $row + ['password' => implode('-', str_split($plain, 5))];
        }
        throw new RuntimeException('Unable to create the app password', 500);
    }

    public function revoke(int $userId, int $id): bool
    {
        $stmt = $this->db->prepare('DELETE FROM app_passwords WHERE id = ? AND user_id = ?');
        $stmt->execute([$id, $userId]);
        return $stmt->rowCount() > 0;
    }

    /**
     * The account an app password signs in, or null.
     *
     * The username has to match as well, compared by the database the way the
     * login form's is, and the account has to be enabled. Role and state are
     * read here on every request, so a demotion or a disabled account takes
     * effect at once -- there is no session to go stale.
     *
     * @return array{id:int,username:string,role:string}|null
     */
    public function authenticate(string $username, string $password): ?array
    {
        $plain = self::normalise($password);
        if ($plain === null || $username === '') return null;

        $stmt = $this->db->prepare('SELECT a.id, a.last_used_at, u.id AS user_id, u.username, u.role, u.is_active
            FROM app_passwords a JOIN users u ON u.id = a.user_id
            WHERE a.token_hash = ? AND u.username = ? LIMIT 1');
        $stmt->execute([hash('sha256', $plain), $username]);
        $row = $stmt->fetch(PDO::FETCH_ASSOC);
        if (!$row || !(bool)$row['is_active']) return null;

        $last = $row['last_used_at'] === null ? 0 : (int)strtotime((string)$row['last_used_at'].' UTC');
        if (time() - $last > self::TOUCH_SECONDS) {
            try {
                $this->db->prepare('UPDATE app_passwords SET last_used_at = UTC_TIMESTAMP() WHERE id = ?')->execute([(int)$row['id']]);
            } catch (\Throwable $e) {
                error_log('[app-passwords] last-used update failed: '.$e->getMessage());
            }
        }
        return ['id' => (int)$row['user_id'], 'username' => (string)$row['username'],
            'role' => UserRepository::normaliseRole((string)$row['role'])];
    }
}
