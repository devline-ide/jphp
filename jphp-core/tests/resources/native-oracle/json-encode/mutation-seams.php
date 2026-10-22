<?php
function row($id,$call){
    json_decode('{');$GLOBALS['events']=[];$result=null;$failure=null;
    set_error_handler(function($n,$s){$GLOBALS['events'][]=[$n,$s];return true;});
    try{$result=$call();}catch(Throwable $e){$failure=[get_class($e),$e->getCode(),$e->getMessage()];}
    restore_error_handler();$code=json_last_error();$message=json_last_error_msg();$events=$GLOBALS['events'];
    echo json_encode([$id,base64_encode(serialize($result)),$failure,$code,$message,$events]),"\n";
}


class EncDeclaredHolder {public $first;public $drop=1;}
class EncDeclaredMutator implements JsonSerializable {public $target;public function __construct($target){$this->target=$target;}public function jsonSerialize():mixed{$GLOBALS['events'][]='declared-mutate';unset($this->target->drop);$this->target->late=2;return 'v';}}
row('callback-mutates-declared-properties',function(){$o=new EncDeclaredHolder;$o->first=new EncDeclaredMutator($o);return json_encode($o);});
row('flags-warning-cow-values-and-cursor',function(){
    $a=['keep'=>1,'drop'=>2,'tail'=>3];$alias=&$a;next($a);$GLOBALS['events'][]=['cursor-before',key($a),current($a)];
    set_error_handler(function($n,$s)use(&$alias){$GLOBALS['events'][]=[$n,$s];$alias['keep']=9;unset($alias['drop']);$alias['late']=4;return true;});
    try{$r=json_encode($alias,1.5);$GLOBALS['events'][]=['cursor-after',key($a),current($a),serialize($a)];return $r;}finally{restore_error_handler();}
});
row('ordinary-array-cursor-preserved',function(){$a=['a'=>1,'b'=>2];next($a);$GLOBALS['events'][]=['cursor-before',key($a),current($a)];$r=json_encode($a);$GLOBALS['events'][]=['cursor-after',key($a),current($a)];return $r;});
