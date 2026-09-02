<?php
declare(strict_types=1);

namespace CloudHub\Services;

/**
 * The same photo, twice.
 *
 * A phone backing up alongside a manual copy, a folder duplicated "just in
 * case", the same holiday imported from two cameras: a file server accumulates
 * exact copies, and nothing in CloudHub could point at them. This finds them.
 *
 * Only byte-for-byte copies count. A photo that was resized or recompressed is
 * a different file with different pixels, and calling it a duplicate would
 * eventually delete somebody's only copy of something. What this reports is
 * certain, so acting on it is safe.
 *
 * Reading every file to compare them would be absurd on a store of any size,
 * so the work is arranged to avoid it:
 *
 *   1. Files of a different size cannot be identical. Group by size; a group
 *      of one is finished with, and on a real store that is nearly all of them.
 *   2. Files that differ near the start or the end are not identical. Hash a
 *      sample from each end -- 64 KB, one read each -- and split the groups
 *      again. A 4 GB video costs 128 KB to rule out.
 *   3. Only what survives both is hashed in full, which is the only way to be
 *      certain and is now a handful of files rather than the whole store.
 *
 * Hashes are cached against the file's size and modification time, so a second
 * scan re-reads nothing that has not changed.
 */
final class DuplicateFinder
{
    /** Bytes read from each end of a file for the cheap comparison. */
    public const SAMPLE_BYTES = 65536;

    /** What a duplicate finder for photos and videos looks at. */
    public const MEDIA = ['image', 'video'];

    /** Everything, for the "include other files" switch. */
    public const EVERYTHING = ['image', 'video', 'audio', 'document', 'archive', 'other'];

    /**
     * How many hashes are kept between scans.
     *
     * Each is a path, a size, a time and two hashes -- a few hundred bytes --
     * so this is a cache of a handful of megabytes for a store of fifty
     * thousand media files, and it stops one growing without limit as folders
     * are renamed and files replaced.
     */
    public const MAX_CACHED_HASHES = 50000;

    /** @var array<string,array{sample?:string,full?:string}> */
    private array $hashes = [];
    private array $seen = [];
    /** Files whose bytes were actually read this scan, as opposed to recalled. */
    private int $reads = 0;

    public function __construct(
        private FileService $fs,
        private string $cachePath,
        /** How long a scan may spend hashing before it reports what it has. */
        private int $budgetSeconds = 20,
    ) {
    }

    /**
     * @param list<string> $categories from FileService::fileCategory()
     * @return array{groups:list<array>,groupCount:int,wastedBytes:int,filesScanned:int,
     *               candidates:int,hashedFiles:int,complete:bool,scannedAt:string}
     */
    public function scan(array $categories = self::MEDIA): array
    {
        $this->loadCache();
        $started = microtime(true);

        /** @var array<int,list<array{path:string,abs:string,bytes:int,modified:int}>> */
        $bySize = [];
        $filesScanned = 0;

        $this->fs->eachFile(function (string $abs, string $rel, int $bytes, int $modified) use (&$bySize, &$filesScanned, $categories): bool {
            if (!in_array(FileService::fileCategory($abs), $categories, true)) return true;
            $filesScanned++;
            /*
             * Empty files are all identical to one another, which is true and
             * useless: a store with forty stray zero-byte files would report
             * one enormous group of things nobody wants deleted.
             */
            if ($bytes <= 0) return true;
            $bySize[$bytes][] = ['path' => $rel, 'abs' => $abs, 'bytes' => $bytes, 'modified' => $modified];
            return true;
        });

        $candidates = 0;
        $sameSize = [];
        foreach ($bySize as $bytes => $files) {
            if (count($files) < 2) continue;
            $candidates += count($files);
            $sameSize[] = $files;
        }
        unset($bySize);

        $complete = true;
        $groups = [];

        foreach ($sameSize as $files) {
            if (microtime(true) - $started > $this->budgetSeconds) { $complete = false; break; }

            // Cheap pass: both ends of the file, which separates same-sized
            // files that merely happen to be the same size.
            $bySample = [];
            foreach ($files as $file) {
                $sample = $this->sampleHash($file);
                if ($sample === null) continue;
                $bySample[$sample][] = $file;
            }

            foreach ($bySample as $sharing) {
                if (count($sharing) < 2) continue;
                if (microtime(true) - $started > $this->budgetSeconds) { $complete = false; break 2; }

                // Certain pass, on what is left.
                $byContent = [];
                foreach ($sharing as $file) {
                    $full = $this->fullHash($file);
                    if ($full === null) continue;
                    $byContent[$full][] = $file;
                }

                foreach ($byContent as $hash => $copies) {
                    if (count($copies) < 2) continue;
                    $groups[] = $this->describe($hash, $copies);
                }
            }
        }

        // Biggest saving first: that is the order anybody works through them in.
        usort($groups, fn(array $a, array $b) => $b['wastedBytes'] <=> $a['wastedBytes']);

        $this->saveCache();

        return [
            'groups' => $groups,
            'groupCount' => count($groups),
            'wastedBytes' => array_sum(array_column($groups, 'wastedBytes')),
            'filesScanned' => $filesScanned,
            'candidates' => $candidates,
            'hashedFiles' => $this->reads,
            'complete' => $complete,
            'scannedAt' => gmdate('c'),
        ];
    }

    /**
     * Which copy to keep, when something has to be chosen for you.
     *
     * The oldest, because it is the original: the copies are what came later.
     * Ties go to the shallowest path and then to alphabetical order, so the
     * answer is the same every time the same folder is scanned -- a suggestion
     * that moved around between scans would be impossible to trust.
     */
    public static function suggestedKeeper(array $copies): string
    {
        $best = null;
        foreach ($copies as $copy) {
            if ($best === null) { $best = $copy; continue; }
            $modified = (int)($copy['modified'] ?? 0) <=> (int)($best['modified'] ?? 0);
            if ($modified < 0) { $best = $copy; continue; }
            if ($modified > 0) continue;

            $depth = substr_count((string)$copy['path'], '/') <=> substr_count((string)$best['path'], '/');
            if ($depth < 0) { $best = $copy; continue; }
            if ($depth > 0) continue;

            if (strcmp((string)$copy['path'], (string)$best['path']) < 0) $best = $copy;
        }
        return (string)($best['path'] ?? '');
    }

    private function describe(string $hash, array $copies): array
    {
        usort($copies, fn(array $a, array $b) => [$a['modified'], $a['path']] <=> [$b['modified'], $b['path']]);
        $bytes = (int)$copies[0]['bytes'];

        return [
            'hash' => substr($hash, 0, 16),
            'bytes' => $bytes,
            // What deleting all but one would give back.
            'wastedBytes' => $bytes * (count($copies) - 1),
            'copies' => count($copies),
            'keep' => self::suggestedKeeper($copies),
            'files' => array_map(fn(array $c) => [
                'path' => $c['path'],
                'name' => basename((string)$c['path']),
                'folder' => dirname((string)$c['path']) === '\\' ? '/' : dirname((string)$c['path']),
                'bytes' => (int)$c['bytes'],
                'modified' => gmdate('c', (int)$c['modified']),
            ], $copies),
        ];
    }

    /* ---- hashing, and not re-doing it ------------------------------------ */

    private function key(array $file): string
    {
        return $file['path'].'|'.$file['bytes'].'|'.$file['modified'];
    }

    private function sampleHash(array $file): ?string
    {
        $key = $this->key($file);
        $this->seen[$key] = true;
        if (isset($this->hashes[$key]['sample'])) return $this->hashes[$key]['sample'];

        $handle = @fopen($file['abs'], 'rb');
        if ($handle === false) return null;
        $head = (string)fread($handle, self::SAMPLE_BYTES);
        $tail = '';
        if ($file['bytes'] > self::SAMPLE_BYTES) {
            fseek($handle, -min(self::SAMPLE_BYTES, $file['bytes']), SEEK_END);
            $tail = (string)fread($handle, self::SAMPLE_BYTES);
        }
        fclose($handle);

        $hash = hash('sha256', $file['bytes'].'|'.$head.'|'.$tail);
        $this->hashes[$key]['sample'] = $hash;
        $this->reads++;
        return $hash;
    }

    private function fullHash(array $file): ?string
    {
        $key = $this->key($file);
        $this->seen[$key] = true;
        if (isset($this->hashes[$key]['full'])) return $this->hashes[$key]['full'];

        $hash = @hash_file('sha256', $file['abs']);
        if ($hash === false) return null;
        $this->hashes[$key]['full'] = $hash;
        $this->reads++;
        return $hash;
    }

    private function loadCache(): void
    {
        if (!is_file($this->cachePath)) return;
        $stored = json_decode((string)@file_get_contents($this->cachePath), true);
        if (is_array($stored)) $this->hashes = $stored;
    }

    /**
     * Kept: what this scan used, and then as much of the rest as fits.
     *
     * Not "only what this scan saw", which was the first attempt and was
     * wrong: a scan of photos and videos would throw away the hashes an
     * all-files scan had just paid for, so alternating the two re-read the
     * store every time. Entries are dropped only when there are too many, and
     * the ones this scan touched are the last to go.
     */
    private function saveCache(): void
    {
        $used = array_intersect_key($this->hashes, $this->seen);
        $rest = array_diff_key($this->hashes, $this->seen);
        $room = max(0, self::MAX_CACHED_HASHES - count($used));
        $keep = $used + array_slice($rest, 0, $room, true);

        $dir = dirname($this->cachePath);
        if (!is_dir($dir)) @mkdir($dir, 0775, true);
        @file_put_contents($this->cachePath, json_encode($keep, JSON_UNESCAPED_SLASHES));
    }
}
