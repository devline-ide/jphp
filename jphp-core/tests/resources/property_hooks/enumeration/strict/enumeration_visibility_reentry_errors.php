<?php declare(strict_types=1);#[AllowDynamicProperties] class R {public int $first {get {$this->later=8;$this->added=9;return 1;}} public int $later=2;}
class V {public int $first {get=>1;} public int $ordinary=2;protected int $protected {get=>3;} private int $private {get=>4;}public int $writeOnly {set{}} public function vars(){return get_object_vars($this);}}
class Q {public int $v {get{throw new Exception('getter');}}}
class B {public int $x=2 {&get{return $this->x;}}}
$results=[];foreach(['json','vars','export'] as $mode){$r=new R;$r->old=3;$result=match($mode){'json'=>json_encode($r),'vars'=>get_object_vars($r),'export'=>var_export($r,true)};$results[]=[$mode,$result];}
$v=new V;$results[]=[get_object_vars($v),json_encode($v),var_export($v,true),$v->vars()];
foreach(['json','vars','export'] as $mode){try{$v=match($mode){'json'=>json_encode(new Q),'vars'=>get_object_vars(new Q),'export'=>var_export(new Q,true)};$results[]=[$mode,'missing-error'];}catch(Throwable $e){$results[]=[$mode,get_class($e),$e->getMessage()];}}
$b=new B;$vars=get_object_vars($b);$vars['x']=7;$results[]=['getter-cell-copy',$b->x,$vars];
$results[]=['raw-backing',(array)$b,serialize($b)];echo json_encode($results,JSON_UNESCAPED_SLASHES),"\n";
