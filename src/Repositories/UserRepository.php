<?php
declare(strict_types=1);

namespace CloudHub\Repositories;

use CloudHub\Services\Authorization;
use CloudHub\Services\Auth;
use PDO;
use RuntimeException;

/**
 * Account storage for the admin user-management screen.
 *
 * Password hashes never leave this class: every read method selects an explicit
 * column list rather than `SELECT *`, so a hash cannot reach an API response by
 * accident.
 *
 * Unlike ServerRepository this takes its PDO rather than opening a second
 * connection, so it shares the one the request already has.
 */
final class UserRepository
{
    /** Columns that are safe to hand back to a client. */
    private const PUBLIC_COLUMNS = 'id, username, role, is_active, created_at, last_login_at';

    public function __construct(private readonly PDO $db) {}

    /** @return list<array> */
    public function all(): array
    {
        $rows = $this->db->query('SELECT '.self::PUBLIC_COLUMNS.' FROM users ORDER BY username')->fetchAll(PDO::FETCH_ASSOC);
        return array_map([$this, 'decode'], $rows ?: []);
    }

    public function get(int $id): ?array
    {
        $stmt = $this->db->prepare('SELECT '.self::PUBLIC_COLUMNS.' FROM users WHERE id = ?');
        $stmt->execute([$id]);
        $row = $stmt->fetch(PDO::FETCH_ASSOC);
        return $row ? $this->decode($row) : null;
    }

    public function findByUsername(string $username): ?array
    {
        $stmt = $this->db->prepare('SELECT '.self::PUBLIC_COLUMNS.' FROM users WHERE username = ?');
        $stmt->execute([$username]);
        $row = $stmt->fetch(PDO::FETCH_ASSOC);
        return $row ? $this->decode($row) : null;
    }

    /**
     * Live account state, used by the throttled session revalidation.
     *
     * Returns null when the account has been deleted.
     */
    public function status(int $id): ?array
    {
        $stmt = $this->db->prepare('SELECT role, is_active FROM users WHERE id = ?');
        $stmt->execute([$id]);
        $row = $stmt->fetch(PDO::FETCH_ASSOC);
        if (!$row) return null;
        return ['role' => self::normaliseRole((string)$row['role']), 'isActive' => (bool)$row['is_active']];
    }

    public function create(string $username, string $password, string $role): array
    {
        if ($this->findByUsername($username) !== null) {
            throw new RuntimeException('That username is already taken', 409);
        }
        $stmt = $this->db->prepare('INSERT INTO users (username, password_hash, is_active, role) VALUES (?, ?, 1, ?)');
        $stmt->execute([$username, Auth::hashPassword($password), self::normaliseRole($role)]);

        $created = $this->findByUsername($username);
        if ($created === null) throw new RuntimeException('Unable to create the account', 500);
        return $created;
    }

    /**
     * Apply role / active / password changes. Only supplied keys are touched.
     *
     * A new password also revokes the account's app passwords, in the same
     * transaction: changing it is what someone does when they think the
     * account is compromised, and a WebDAV client holding an app password
     * would otherwise keep its access through that.
     */
    public function update(int $id, array $changes): array
    {
        $sets = [];
        $values = [];

        if (array_key_exists('role', $changes)) {
            $sets[] = 'role = ?';
            $values[] = self::normaliseRole((string)$changes['role']);
        }
        if (array_key_exists('isActive', $changes)) {
            $sets[] = 'is_active = ?';
            $values[] = $changes['isActive'] ? 1 : 0;
        }
        if (array_key_exists('password', $changes)) {
            $sets[] = 'password_hash = ?';
            $values[] = Auth::hashPassword((string)$changes['password']);
        }

        if ($sets) {
            $values[] = $id;
            $this->db->beginTransaction();
            try {
                $stmt = $this->db->prepare('UPDATE users SET '.implode(', ', $sets).' WHERE id = ?');
                $stmt->execute($values);
                if (array_key_exists('password', $changes)) $this->revokeAppPasswords($id);
                $this->db->commit();
            } catch (\Throwable $e) {
                $this->db->rollBack();
                throw $e;
            }
        }

        $updated = $this->get($id);
        if ($updated === null) throw new RuntimeException('Account not found', 404);
        return $updated;
    }

    /** Delete an account and the app passwords that sign it in. */
    public function delete(int $id): void
    {
        $this->db->beginTransaction();
        try {
            $stmt = $this->db->prepare('DELETE FROM users WHERE id = ?');
            $stmt->execute([$id]);
            if (!$stmt->rowCount()) throw new RuntimeException('Account not found', 404);
            $this->revokeAppPasswords($id);
            $this->db->commit();
        } catch (\Throwable $e) {
            $this->db->rollBack();
            throw $e;
        }
    }

    /**
     * Revoke every app password of an account.
     *
     * An installation that has not run the migration has no app_passwords
     * table and so none to revoke; anything else that goes wrong fails the
     * change it belongs to, rather than leaving a client signed in.
     */
    private function revokeAppPasswords(int $id): void
    {
        try {
            $this->db->prepare('DELETE FROM app_passwords WHERE user_id = ?')->execute([$id]);
        } catch (\PDOException $e) {
            if ((string)$e->getCode() !== '42S02') throw $e;
        }
    }

    /**
     * How many enabled administrators exist, optionally ignoring one account.
     *
     * Used to refuse the change that would leave nobody able to administer the
     * installation. Pass the account being disabled, demoted or deleted.
     */
    public function activeAdminCount(?int $excludingId = null): int
    {
        $sql = "SELECT COUNT(*) FROM users WHERE role = 'admin' AND is_active = 1";
        $values = [];
        if ($excludingId !== null) {
            $sql .= ' AND id <> ?';
            $values[] = $excludingId;
        }
        $stmt = $this->db->prepare($sql);
        $stmt->execute($values);
        return (int)$stmt->fetchColumn();
    }

    /** Confirm a password against the stored hash, for self-service changes. */
    public function verifyPassword(int $id, string $password): bool
    {
        $stmt = $this->db->prepare('SELECT password_hash FROM users WHERE id = ?');
        $stmt->execute([$id]);
        $hash = $stmt->fetchColumn();
        return is_string($hash) && password_verify($password, $hash);
    }

    public static function normaliseRole(string $role): string
    {
        return in_array($role, [Authorization::VIEWER, Authorization::EDITOR, Authorization::ADMIN], true)
            ? $role
            : Authorization::VIEWER;
    }

    private function decode(array $row): array
    {
        return [
            'id' => (int)$row['id'],
            'username' => (string)$row['username'],
            'role' => self::normaliseRole((string)$row['role']),
            'isActive' => (bool)$row['is_active'],
            'createdAt' => $row['created_at'] ? gmdate('c', strtotime((string)$row['created_at'])) : null,
            'lastLoginAt' => $row['last_login_at'] ? gmdate('c', strtotime((string)$row['last_login_at'])) : null,
        ];
    }
}
