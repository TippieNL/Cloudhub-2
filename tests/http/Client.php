<?php
declare(strict_types=1);

namespace CloudHub\Tests\Http;

/**
 * A CloudHub client for the tests, over real HTTP.
 *
 * The checks in tests/*_test.php assert that the source *reads* a certain way.
 * They have caught real reverts, but nothing in the repository asserted that
 * the application *works* -- every round of that was a php -S server and a pile
 * of curl invocations, written by hand and thrown away afterwards. This is that
 * work, kept.
 *
 * It carries the session cookie and the CSRF token the way a browser does,
 * because the interesting failures live exactly there: a rotated session, a
 * missing token, a redirect that drops a request body.
 */
final class Client
{
    private array $cookies = [];
    private string $csrf = '';

    public function __construct(private string $base) {}

    public function csrfToken(): string { return $this->csrf; }

    /** Forget the session, as closing the browser would. */
    public function reset(): void { $this->cookies = []; $this->csrf = ''; }

    public function get(string $route, array $query = []): Response
    {
        return $this->send('GET', $route, $query, null);
    }

    public function post(string $route, array $body, array $query = []): Response
    {
        return $this->send('POST', $route, $query, $body);
    }

    public function put(string $route, string $raw, array $query = [], array $headers = []): Response
    {
        return $this->send('PUT', $route, $query, $raw, $headers);
    }

    /**
     * One chunk of a resumable upload.
     *
     * The offset travels in X-Upload-Offset rather than the query string, which
     * is what UploadService::append reads.
     */
    public function putChunk(string $id, int $offset, string $bytes): Response
    {
        return $this->put('/api/uploads/chunk', $bytes, ['id' => $id], ['X-Upload-Offset: '.$offset]);
    }

    /**
     * A plain GET of a URL exactly as it was handed out.
     *
     * Everything else here goes through the portable ?route= form, which never
     * touches the web server's own path handling -- so it cannot see a share
     * link refused by a deny rule, or a redirect on a name that does not match
     * the file. Public share URLs are clean paths given to other people, and
     * this fetches them the way those people's browsers will.
     */
    public function fetchUrl(string $url, array $headers = []): Response
    {
        return $this->perform($url, 'GET', array_merge(['Accept: */*'], $headers), null);
    }

    /** A partial fetch, for the streaming route video seeking relies on. */
    public function getRange(string $route, array $query, int $from, int $to): Response
    {
        return $this->send('GET', $route, $query, null, ["Range: bytes=$from-$to"]);
    }

    public function delete(string $route, array $body = []): Response
    {
        return $this->send('DELETE', $route, [], $body);
    }

    /** Sign in and keep the session for everything that follows. */
    public function signIn(string $username, string $password): Response
    {
        // The token is issued by /api/auth/status, which answers without a
        // session -- the same order the real clients use.
        $this->get('/api/auth/status');
        return $this->post('/api/auth/login', ['username' => $username, 'password' => $password]);
    }

    /**
     * A request with no CSRF token, to prove the guard is real.
     *
     * Tempting to skip: it is the one test that fails loudly if someone
     * "simplifies" the middleware.
     */
    public function postWithoutCsrf(string $route, array $body): Response
    {
        return $this->send('POST', $route, [], $body, [], false);
    }

    private function send(
        string $method,
        string $route,
        array $query,
        array|string|null $body,
        array $extraHeaders = [],
        bool $withCsrf = true,
    ): Response {
        // The portable form the front controller understands, and the one the
        // Android client uses: ?route=%2Fapi%2F...
        $url = rtrim($this->base, '/').'/?route='.rawurlencode($route);
        foreach ($query as $key => $value) {
            $url .= '&'.rawurlencode((string)$key).'='.rawurlencode((string)$value);
        }

        $headers = ['Accept: application/json'];
        if ($this->cookies) {
            $pairs = [];
            foreach ($this->cookies as $name => $value) $pairs[] = $name.'='.$value;
            $headers[] = 'Cookie: '.implode('; ', $pairs);
        }
        if ($withCsrf && $this->csrf !== '' && $method !== 'GET') {
            $headers[] = 'X-CSRF-Token: '.$this->csrf;
        }

        $payload = null;
        if (is_array($body)) {
            $payload = json_encode($body, JSON_UNESCAPED_SLASHES);
            $headers[] = 'Content-Type: application/json';
        } elseif (is_string($body)) {
            $payload = $body;
            $headers[] = 'Content-Type: application/octet-stream';
        }
        foreach ($extraHeaders as $header) $headers[] = $header;

        return $this->perform($url, $method, $headers, $payload);
    }

    private function perform(string $url, string $method, array $headers, ?string $payload): Response
    {
        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_HEADER => true,
            CURLOPT_CUSTOMREQUEST => $method,
            CURLOPT_HTTPHEADER => $headers,
            // Never follow: a redirect that silently turns a PUT into a GET is
            // the bug that broke sign-in on a subdirectory install, and these
            // tests must be able to see one.
            CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_TIMEOUT => 30,
        ]);
        if ($payload !== null) curl_setopt($ch, CURLOPT_POSTFIELDS, $payload);

        $raw = curl_exec($ch);
        if ($raw === false) {
            $error = curl_error($ch);
            curl_close($ch);
            throw new \RuntimeException("$method $route failed: $error");
        }
        $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        $headerSize = (int)curl_getinfo($ch, CURLINFO_HEADER_SIZE);
        curl_close($ch);

        $rawHeaders = substr((string)$raw, 0, $headerSize);
        $responseBody = substr((string)$raw, $headerSize);

        $this->takeCookies($rawHeaders);
        $decoded = json_decode($responseBody, true);
        // The server rotates the session periodically and reissues the token
        // with it; storing whatever arrives keeps the client in step.
        if (is_array($decoded) && isset($decoded['csrfToken']) && is_string($decoded['csrfToken'])) {
            $this->csrf = $decoded['csrfToken'];
        }

        return new Response($status, $rawHeaders, $responseBody, is_array($decoded) ? $decoded : null);
    }

    private function takeCookies(string $rawHeaders): void
    {
        foreach (explode("\r\n", $rawHeaders) as $line) {
            if (stripos($line, 'Set-Cookie:') !== 0) continue;
            $pair = trim(explode(';', substr($line, 11))[0]);
            if (!str_contains($pair, '=')) continue;
            [$name, $value] = explode('=', $pair, 2);
            if ($value === '' || $value === 'deleted') unset($this->cookies[trim($name)]);
            else $this->cookies[trim($name)] = $value;
        }
    }
}

final class Response
{
    public function __construct(
        public readonly int $status,
        public readonly string $rawHeaders,
        public readonly string $body,
        public readonly ?array $json,
    ) {}

    public function ok(): bool { return $this->status >= 200 && $this->status < 300; }

    /** The API's error envelope: {"error":{"code":"...","message":"..."}}. */
    public function errorCode(): ?string { return $this->json['error']['code'] ?? null; }

    public function header(string $name): ?string
    {
        foreach (explode("\r\n", $this->rawHeaders) as $line) {
            if (stripos($line, $name.':') === 0) return trim(substr($line, strlen($name) + 1));
        }
        return null;
    }

    public function describe(): string
    {
        $note = $this->json['error']['message'] ?? substr($this->body, 0, 160);
        return $this->status.' '.$note;
    }
}
