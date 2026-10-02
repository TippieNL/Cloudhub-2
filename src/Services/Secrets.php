<?php
declare(strict_types=1);
namespace CloudHub\Services;

use RuntimeException;

/**
 * Credentials at rest, sealed with APP_KEY.
 *
 * A storage server's config carries the passwords and keys of another
 * system. Kept as plain JSON they left with every database dump, backup and
 * SQL export, so the values that are credentials are sealed with libsodium's
 * secretbox (XSalsa20-Poly1305) under a key that lives in .env rather than in
 * the database: a copy of the database alone no longer hands them over.
 *
 * Only credentials are sealed -- the keys isSecretKey() recognises, and
 * everything beneath one -- so the rest of a config stays readable, and a
 * server with none needs no key at all. A sealed value is a string,
 * "enc:v1:" then base64(nonce . ciphertext), so the column stays valid JSON
 * and a value written before this existed still reads as what it is.
 */
final class Secrets {
 private const PREFIX='enc:v1:';
 /** Shown in place of a credential; a value sent back unchanged means "keep it". */
 public const MASK='••••••••';

 private ?string $key=null;
 private string $problem='';

 public function __construct(string $appKey) {
  $appKey=trim($appKey);
  if($appKey===''){$this->problem='Set APP_KEY in .env to store server credentials: 64 hexadecimal characters, e.g. from php -r "echo bin2hex(random_bytes(32));"';return;}
  if(!preg_match('/^[0-9a-fA-F]{64}$/',$appKey)){$this->problem='APP_KEY in .env must be 64 hexadecimal characters (32 random bytes)';return;}
  $this->key=(string)hex2bin($appKey);
 }

 /** Whether a usable key is configured. */
 public function available(): bool {return $this->key!==null;}

 /** What is wrong with the configured key, for a message; empty when it is usable. */
 public function problem(): string {return $this->problem;}

 /**
  * Whether a config key names a credential.
  *
  * By name, because a config is whatever JSON the administrator wrote:
  * password, passphrase, secret, token, apiKey, privateKey, auth,
  * credentials. A false positive only means a harmless value is sealed and
  * masked; a miss means a credential in plain text.
  */
 public static function isSecretKey(string|int $key): bool {
  return is_string($key)&&(bool)preg_match('/pass|secret|token|key|auth|credential/i',$key);
 }

 public static function isSealed(mixed $value): bool {return is_string($value)&&str_starts_with($value,self::PREFIX);}

 /** A value that can be a credential: a non-empty string or number, not a flag or a null. */
 private static function isCredential(mixed $value): bool {
  return (is_string($value)||is_int($value)||is_float($value))&&(string)$value!=='';
 }

 /** @throws RuntimeException 503 when no usable APP_KEY is configured */
 public function seal(string $plain): string {
  if($this->key===null)throw new RuntimeException($this->problem,503);
  $nonce=random_bytes(SODIUM_CRYPTO_SECRETBOX_NONCEBYTES);
  return self::PREFIX.base64_encode($nonce.sodium_crypto_secretbox($plain,$nonce,$this->key));
 }

 /** The plaintext, or null when this key cannot open it: none configured, a different key, or altered. */
 public function open(string $sealed): ?string {
  if($this->key===null||!self::isSealed($sealed))return null;
  $raw=base64_decode(substr($sealed,strlen(self::PREFIX)),true);
  if($raw===false||strlen($raw)<SODIUM_CRYPTO_SECRETBOX_NONCEBYTES+SODIUM_CRYPTO_SECRETBOX_MACBYTES)return null;
  $plain=sodium_crypto_secretbox_open(substr($raw,SODIUM_CRYPTO_SECRETBOX_NONCEBYTES),substr($raw,0,SODIUM_CRYPTO_SECRETBOX_NONCEBYTES),$this->key);
  return $plain===false?null:$plain;
 }

 /**
  * Seal every credential in a config, however deeply nested.
  *
  * Everything beneath a secret key counts as secret: {"auth": {"user": ..,
  * "value": ..}} is one credential whatever its parts are called. A value
  * already sealed is kept as it is -- which is how one that cannot be opened
  * (APP_KEY changed or lost) survives a toggle or an edit unharmed.
  */
 public function sealAll(array $config,bool $secret=false): array {
  foreach($config as $k=>$v){
   $inside=$secret||self::isSecretKey($k);
   if(is_array($v))$config[$k]=$this->sealAll($v,$inside);
   elseif($inside&&self::isCredential($v)&&!self::isSealed($v))$config[$k]=$this->seal((string)$v);
  }
  return $config;
 }

 /** The inverse of sealAll(). A value this key cannot open is left sealed rather than guessed at. */
 public function openAll(array $config): array {
  foreach($config as $k=>$v){
   if(is_array($v))$config[$k]=$this->openAll($v);
   elseif(self::isSealed($v))$config[$k]=$this->open($v)??$v;
  }
  return $config;
 }

 /** Whether any credential in a config is still in plain text. */
 public static function hasPlaintext(array $config,bool $secret=false): bool {
  foreach($config as $k=>$v){
   $inside=$secret||self::isSecretKey($k);
   if(is_array($v)){if(self::hasPlaintext($v,$inside))return true;}
   elseif($inside&&self::isCredential($v)&&!self::isSealed($v))return true;
  }
  return false;
 }

 /** A config fit to send to a client: every credential replaced by MASK. */
 public static function mask(array $config,bool $secret=false): array {
  foreach($config as $k=>$v){
   $inside=$secret||self::isSecretKey($k);
   if(is_array($v))$config[$k]=self::mask($v,$inside);
   elseif($inside&&self::isCredential($v))$config[$k]=self::MASK;
  }
  return $config;
 }

 /** An edited config with every MASK a client sent back replaced by the value it stood for. */
 public static function unmask(array $incoming,array $stored): array {
  foreach($incoming as $k=>$v){
   if(is_array($v))$incoming[$k]=self::unmask($v,is_array($stored[$k]??null)?$stored[$k]:[]);
   elseif($v===self::MASK&&array_key_exists($k,$stored))$incoming[$k]=$stored[$k];
  }
  return $incoming;
 }
}
