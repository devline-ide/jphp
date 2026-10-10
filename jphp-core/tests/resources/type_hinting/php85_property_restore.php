<?php
class Restore { public int $v=7; public float $f=1.0; public ?int $n; private int $secret=9; function secret(){return $this->secret;} }
class RestoreWake {public int $v=7;public int $seen=0;function __wakeup(){$this->seen=$this->v;}}
class RestoreHook {public int $v=7;function __unserialize(array $data){$this->v=$data['v'];}}
class RestoreArg {public int|string $v='1';public float $f=2.0;}
function restoreInt(int &$r){$r=99;}
function restoreFloat(float &$r){$r=99.0;}
$rows=[];
foreach(['O:7:"Restore":0:{}','O:7:"Restore":1:{s:1:"v";i:3;}','O:7:"Restore":1:{s:1:"v";s:1:"3";}','O:7:"Restore":1:{s:1:"v";s:3:"abc";}','O:7:"Restore":1:{s:1:"v";d:3.5;}','O:7:"Restore":1:{s:1:"v";N;}','O:7:"Restore":1:{s:1:"f";i:3;}','O:7:"Restore":1:{s:1:"n";N;}'] as $s){
 try{$o=unserialize($s);$rows[]=[$o->v,$o->f,$o->secret(),isset($o->n)];}catch(Throwable $e){$rows[]=explode(', called in ',$e->getMessage())[0];}
}
$o=unserialize('O:7:"Restore":1:{s:1:"v";i:3;}');try{$o->v='abc';}catch(Throwable $e){$rows[]=[explode(', called in ',$e->getMessage())[0],$o->v];}
$o=new Restore;$r=&$o->v;$a=unserialize(serialize([$o,&$r]));try{$a[1]='abc';}catch(Throwable $e){$rows[]=[explode(', called in ',$e->getMessage())[0],$a[0]->v,$a[1]];}$a[1]=5;$rows[]=[$a[0]->v,$a[1]];
$o=unserialize('O:11:"RestoreWake":1:{s:1:"v";i:3;}');$rows[]=[$o->v,$o->seen];
$o=unserialize('O:11:"RestoreHook":1:{s:1:"v";s:1:"3";}');$rows[]=$o->v;
$o=(new ReflectionClass('Restore'))->newInstanceWithoutConstructor();$rows[]=[$o->v,$o->f,$o->secret(),isset($o->n)];try{$o->v='abc';}catch(Throwable $e){$rows[]=explode(', called in ',$e->getMessage())[0];}
$o=new RestoreArg;try{restoreInt($o->v);}catch(Throwable $e){$rows[]=[explode(', called in ',$e->getMessage())[0],$o->v];}
try{restoreInt($o->f);}catch(Throwable $e){$rows[]=[explode(', called in ',$e->getMessage())[0],$o->f];}
$o=new Restore;try{restoreFloat($o->v);}catch(Throwable $e){$rows[]=[explode(', called in ',$e->getMessage())[0],$o->v];}
class RestoreFloat {public float $f=7.0;}
$a=unserialize('a:2:{i:0;O:12:"RestoreFloat":1:{s:1:"f";i:3;}i:1;R:3;}');$rows[]=[$a[0]->f,$a[1]];$a[1]='4';$rows[]=[$a[0]->f,$a[1]];
try{unserialize('a:2:{i:0;O:7:"Restore":1:{s:1:"v";i:3;}i:1;O:12:"RestoreFloat":1:{s:1:"f";R:3;}}');$rows[]='wrong';}catch(Throwable $e){$rows[]=$e->getMessage();}
$o=unserialize("O:7:\"Restore\":1:{s:15:\"\0Restore\0secret\";i:4;}");$rows[]=$o->secret();
try{unserialize("O:7:\"Restore\":1:{s:15:\"\0Restore\0secret\";s:3:\"abc\";}");$rows[]='wrong';}catch(Throwable $e){$rows[]=$e->getMessage();}
class RestorePrivate {private int $v=7;function value(){return $this->v;}}
class RestoreProtected {protected int $v=7;function value(){return $this->v;}}
class RestorePublic {public int $v=7;function value(){return $this->v;}}
foreach(['RestorePrivate','RestoreProtected','RestorePublic'] as $class){
 foreach(['v',"\0*\0v","\0".$class."\0v","\0".strtolower($class)."\0v"] as $key){
  $prefix='O:'.strlen($class).':"'.$class.'":1:{s:'.strlen($key).':"'.$key.'";';
  $o=unserialize($prefix.'i:3;}');$rows[]=[$o->value(),serialize($o)];
  try{unserialize($prefix.'s:3:"abc";}');$rows[]='wrong';}catch(Throwable $e){$rows[]=$e->getMessage();}
 }
}
class RestoreKeyParent {private int $v=1;function parentValue(){return $this->v;}}
class RestoreKeyChild extends RestoreKeyParent {private int $v=2;function childValue(){return $this->v;}}
foreach(['v',"\0*\0v","\0RestoreKeyParent\0v"] as $key){
 $o=unserialize('O:15:"RestoreKeyChild":1:{s:'.strlen($key).':"'.$key.'";i:3;}');$rows[]=[$o->parentValue(),$o->childValue(),serialize($o)];
}
echo serialize($rows);
