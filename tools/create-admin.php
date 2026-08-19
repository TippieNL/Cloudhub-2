<?php
declare(strict_types=1);
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require dirname(__DIR__).'/config/bootstrap.php';
$username=trim((string)($argv[1]??'admin'));
if($username===''||strlen($username)>100){fwrite(STDERR,"Invalid username\n");exit(1);}
fwrite(STDOUT,"Password: ");
if(function_exists('shell_exec')){@shell_exec('stty -echo 2>/dev/null');}
$password=rtrim((string)fgets(STDIN),"\r\n");
if(function_exists('shell_exec')){@shell_exec('stty echo 2>/dev/null');}
fwrite(STDOUT,PHP_EOL);
if(strlen($password)<12){fwrite(STDERR,"Password must be at least 12 characters.\n");exit(1);}
$c=require dirname(__DIR__).'/config/database.php';
$pdo=new PDO($c['dsn'],$c['user'],$c['pass'],[PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION]);
$algo=defined('PASSWORD_ARGON2ID')?PASSWORD_ARGON2ID:PASSWORD_DEFAULT;
$hash=password_hash($password,$algo);
$stmt=$pdo->prepare("INSERT INTO users(username,password_hash,is_active,role) VALUES(?,?,1,'admin') ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash), is_active=1, role='admin'");
$stmt->execute([$username,$hash]);
fwrite(STDOUT,"Administrator account created/updated.\n");
