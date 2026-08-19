<?php
declare(strict_types=1);
namespace CloudHub\Services;
use PDO;
use CloudHub\Helpers\Http;

final class Auth {
    /**
     * How long a rotated-away session ID keeps working.
     *
     * Long enough for requests already in flight when the rotation happened,
     * short enough that a stolen predecessor ID is worth little.
     */
    private const ROTATION_GRACE_SECONDS = 60;

    public static function startSession(array $config): void {
        if(session_status()===PHP_SESSION_ACTIVE)return;
        $secure=Security::isHttps($config);
        ini_set('session.use_only_cookies','1');
        ini_set('session.use_strict_mode','1');
        ini_set('session.use_trans_sid','0');

        // Do not force session.save_path into Android shared storage.
        // PHP's files session handler performs UID ownership checks there and
        // can reject its own session files ("not created by your uid").
        // Use PHP's configured session handler/path instead.
        session_name('cloudhub_session');
        session_set_cookie_params(['lifetime'=>0,'path'=>'/','secure'=>$secure,'httponly'=>true,'samesite'=>(string)($config['session_samesite']??'Lax')]);
        session_start();

        $now=time();$idle=max(300,(int)($config['session_idle_seconds']??3600));$absolute=max($idle,(int)($config['session_absolute_seconds']??43200));
        if((isset($_SESSION['created_at'])&&$now-(int)$_SESSION['created_at']>$absolute)||(isset($_SESSION['last_seen_at'])&&$now-(int)$_SESSION['last_seen_at']>$idle)){
            self::destroySession();session_start();
        }
        $_SESSION['created_at']??=$now;$_SESSION['last_seen_at']=$now;$_SESSION['csrf']??=bin2hex(random_bytes(32));
        // A session carried forward from a rotation is only valid for the short
        // grace window below; once it lapses the successor ID is the only one
        // accepted. Requests still holding the old ID are shown the door here
        // rather than being silently handed an empty session.
        if(isset($_SESSION['obsolete_after'])&&$now>(int)$_SESSION['obsolete_after']){
            self::destroySession();session_start();
            $_SESSION['created_at']=$now;$_SESSION['last_seen_at']=$now;$_SESSION['csrf']=bin2hex(random_bytes(32));
        }

        $rotate=max(300,(int)($config['session_rotate_seconds']??900));
        $_SESSION['rotated_at']??=$now;
        if(isset($_SESSION['user_id'])&&!isset($_SESSION['obsolete_after'])&&$now-(int)$_SESSION['rotated_at']>=$rotate){
            // Do not delete the old session file: the file list fires many
            // parallel requests (one per image thumbnail, plus media streams),
            // and any sibling already queued on the previous ID would find
            // nothing, start an empty session under use_strict_mode, and get a
            // surprise 401. Stamp the predecessor with an expiry, keep it
            // readable for that grace period so in-flight requests still
            // authenticate, and let the check above retire it afterwards.
            $_SESSION['obsolete_after']=$now+self::ROTATION_GRACE_SECONDS;
            session_regenerate_id(false);
            unset($_SESSION['obsolete_after']);
            $_SESSION['rotated_at']=$now;
        }
    }
    public static function user(): ?array {return isset($_SESSION['user_id'])?['id'=>(int)$_SESSION['user_id'],'username'=>(string)($_SESSION['username']??''),'role'=>(string)($_SESSION['role']??'viewer')]:null;}
    public static function requireUser(): void {if(!self::user())Http::error(401,'UNAUTHORIZED','Authentication required');}
    public static function verifyCsrf(): void {Security::verifyCsrfRequest();}
    public static function login(PDO $pdo,string $username,string $password): bool {
        try {
            $stmt=$pdo->prepare('SELECT id, username, password_hash, is_active, role FROM users WHERE username = ? LIMIT 1');
            $stmt->execute([$username]);
        } catch (\PDOException $e) {
            // Compatibility with pre-Phase-6 databases. The migration should
            // still be applied; this fallback prevents login from hard-failing.
            if((string)$e->getCode()!=='42S22'&&!str_contains($e->getMessage(),"Unknown column 'role'"))throw $e;
            $stmt=$pdo->prepare("SELECT id, username, password_hash, is_active, 'viewer' AS role FROM users WHERE username = ? LIMIT 1");
            $stmt->execute([$username]);
        }
        $user=$stmt->fetch(PDO::FETCH_ASSOC);
        if(!$user||!(bool)$user['is_active']||!password_verify($password,(string)$user['password_hash']))return false;
        session_regenerate_id(true);$now=time();$_SESSION['user_id']=(int)$user['id'];$_SESSION['username']=(string)$user['username'];$_SESSION['role']=(string)($user['role']??'viewer');$_SESSION['created_at']=$now;$_SESSION['last_seen_at']=$now;$_SESSION['rotated_at']=$now;$_SESSION['csrf']=bin2hex(random_bytes(32));
        $algo=defined('PASSWORD_ARGON2ID')?PASSWORD_ARGON2ID:PASSWORD_DEFAULT;
        if(password_needs_rehash((string)$user['password_hash'],$algo)){
            $hash=password_hash($password,$algo);if(is_string($hash)){$q=$pdo->prepare('UPDATE users SET password_hash = ? WHERE id = ?');$q->execute([$hash,(int)$user['id']]);}
        }
        $pdo->prepare('UPDATE users SET last_login_at = CURRENT_TIMESTAMP WHERE id = ?')->execute([(int)$user['id']]);return true;
    }
    public static function logout(): void {self::destroySession();}
    private static function destroySession(): void {
        $_SESSION=[];
        if(ini_get('session.use_cookies')){$p=session_get_cookie_params();setcookie(session_name(),'',['expires'=>time()-42000,'path'=>$p['path']?:'/','domain'=>$p['domain']??'','secure'=>(bool)($p['secure']??false),'httponly'=>true,'samesite'=>(string)($p['samesite']??'Lax')]);}
        if(session_status()===PHP_SESSION_ACTIVE)session_destroy();
    }
}
