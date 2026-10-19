<?php declare(strict_types=1);
$calls=0;
#[AllowDynamicProperties] class RawParent {public int $p=1;protected int $q=2;private int $same=3;public int $uninit;}
class RawChild extends RawParent {private int $same=4;public int $back=5 {get{global $calls;$calls++;return $this->back+10;}}public int $virtual {get{global $calls;$calls++;return 100;}}}
$o=new RawChild;$o->{'6'}=6;$o->dynamic=7;
$a=get_mangled_object_vars(object:$o);foreach($a as $k=>$v)echo bin2hex((string)$k),'=',json_encode($v),';';echo 'calls=',$calls,"\n";
$a['p']=9;$a['dynamic']=9;echo $o->p,':',$o->dynamic,"\n";
$r=&$o->p;$b=get_mangled_object_vars($o);$b['p']=8;echo $r,':',$o->p,"\n";
unset($o->p);echo implode(',',array_map('bin2hex',array_map('strval',array_keys(get_mangled_object_vars($o))))),"\n";
$c=new class {public int $v=1 {get {if(false)return $this->v;throw new Exception('must not run');}}};echo json_encode(get_mangled_object_vars($c)),"\n";
enum RawE:string {case A='a';}echo json_encode(get_mangled_object_vars(RawE::A)),"\n";
$f=new ReflectionFunction('get_mangled_object_vars');echo $f->getParameters()[0]->getName(),':',$f->getParameters()[0]->getType(),':',$f->getReturnType(),"\n";
foreach([null,1,1.5,true,'x',[]] as $v) {try{get_mangled_object_vars($v);}catch(TypeError $e){echo $e->getMessage(),"\n";}}
