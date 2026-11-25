<?php
declare(strict_types=1);

namespace CloudHub\Helpers;

/**
 * An ENUM column's values, for migrations that may only add to them.
 *
 * Redefining an ENUM keeps each row's value by its text, but a value the new
 * definition leaves out breaks every row that holds it: an error in strict
 * mode, an empty string otherwise. So a migration redefines the column with
 * every value it already has, in their order, and the missing ones after them,
 * whatever another build of CloudHub added -- the email-only build of two-step
 * verification, for one, swapped the text-message scopes for its own.
 */
final class SchemaEnum
{
    /** @return list<string> the values of an information_schema COLUMN_TYPE such as "enum('a','b')"; none for another type */
    public static function values(string $columnType): array
    {
        if (preg_match('/^enum\((.*)\)$/is', trim($columnType), $m) !== 1) return [];
        preg_match_all("/'((?:[^']|'')*)'/", $m[1], $all);
        return array_map(static fn(string $v): string => str_replace("''", "'", $v), $all[1]);
    }

    /**
     * The values the column has with the needed ones added after them; null
     * when none is missing, or when the column is not an ENUM to add to.
     *
     * @param list<string> $needed
     * @return list<string>|null
     */
    public static function withAdded(string $columnType, array $needed): ?array
    {
        $have = self::values($columnType);
        if ($have === []) return null;
        $missing = array_values(array_diff($needed, $have));
        return $missing === [] ? null : [...$have, ...$missing];
    }
}
