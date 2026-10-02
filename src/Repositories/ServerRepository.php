<?php
namespace CloudHub\Repositories;
use PDO;
use CloudHub\Services\Secrets;
/**
 * Storage server records. Credentials in a config are sealed on the way in and
 * opened on the way out (see Secrets), so the table never holds them in plain
 * text; everything else in a config is stored as written.
 */
final class ServerRepository {
 private PDO $db;
 public function __construct(private Secrets $secrets){ $this->db=\CloudHub\Helpers\Db::connection(); }
 public function all(bool $activeOnly=false): array { $sql='SELECT * FROM storage_servers'.($activeOnly?' WHERE is_active=1':'').' ORDER BY created_at'; $rows=$this->db->query($sql)->fetchAll(); return array_map([$this,'decode'],$rows); }
 public function get(int $id): ?array { $s=$this->db->prepare('SELECT * FROM storage_servers WHERE id=?');$s->execute([$id]);$r=$s->fetch();return $r?$this->decode($r):null; }
 public function create(array $d): array { $config=$this->encode($d['config']); $this->db->beginTransaction(); try { if(!empty($d['isDefault']))$this->db->exec('UPDATE storage_servers SET is_default=0'); $s=$this->db->prepare('INSERT INTO storage_servers(name,type,is_active,is_default,config) VALUES(?,?,?,?,?)');$s->execute([$d['name'],$d['type'],$d['isActive']?1:0,$d['isDefault']?1:0,$config]);$id=(int)$this->db->lastInsertId();$this->db->commit();return $this->get($id); } catch(\Throwable $e){$this->db->rollBack();throw $e;} }
 /**
  * The config is rewritten only when the change carries one. A toggle or a new
  * default leaves it as stored, so neither needs APP_KEY -- nor re-seals a
  * credential this key cannot open.
  */
 public function update(int $id,array $d): ?array { $old=$this->get($id); if(!$old)return null; $m=array_merge($old,$d); if(array_key_exists('config',$d)){$s=$this->db->prepare('UPDATE storage_servers SET name=?,type=?,is_active=?,config=?,updated_at=CURRENT_TIMESTAMP WHERE id=?');$s->execute([$m['name'],$m['type'],$m['isActive']?1:0,$this->encode($m['config']),$id]);}else{$s=$this->db->prepare('UPDATE storage_servers SET name=?,type=?,is_active=?,updated_at=CURRENT_TIMESTAMP WHERE id=?');$s->execute([$m['name'],$m['type'],$m['isActive']?1:0,$id]);} if(!empty($d['isDefault']))$this->setDefault($id); return $this->get($id); }
 public function delete(int $id): void { $s=$this->db->prepare('DELETE FROM storage_servers WHERE id=?');$s->execute([$id]); }
 public function setDefault(int $id): void { $this->db->beginTransaction();$this->db->exec('UPDATE storage_servers SET is_default=0');$s=$this->db->prepare('UPDATE storage_servers SET is_default=1 WHERE id=?');$s->execute([$id]);$this->db->commit(); }
 private function encode(mixed $config): string { return (string)json_encode(is_array($config)?$this->secrets->sealAll($config):$config); }
 private function decode(array $r): array { $c=json_decode((string)$r['config'],true); return ['id'=>(int)$r['id'],'name'=>$r['name'],'type'=>$r['type'],'isActive'=>(bool)$r['is_active'],'isDefault'=>(bool)$r['is_default'],'config'=>is_array($c)?$this->secrets->openAll($c):($c?:[]),'createdAt'=>$r['created_at'],'updatedAt'=>$r['updated_at']]; }
}
