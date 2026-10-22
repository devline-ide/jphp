<?php
function row($id,$call){
    json_decode('{');$GLOBALS['events']=[];$result=null;$failure=null;
    set_error_handler(function($n,$s){$GLOBALS['events'][]=[$n,$s];return true;});
    try{$result=$call();}catch(Throwable $e){$failure=[get_class($e),$e->getCode(),$e->getMessage()];}
    restore_error_handler();$code=json_last_error();$message=json_last_error_msg();$events=$GLOBALS['events'];
    echo json_encode([$id,base64_encode(serialize($result)),$failure,$code,$message,$events]),"\n";
}

class EncReturnSelf implements JsonSerializable {public $x=1;public function jsonSerialize():mixed{$GLOBALS['events'][]='return-self';return $this;}}
class EncThrow implements JsonSerializable {public function jsonSerialize():mixed{$GLOBALS['events'][]='throw';throw new Exception('encode-callback');}}
class EncNestedError implements JsonSerializable {public function jsonSerialize():mixed{json_decode('{');$GLOBALS['events'][]=['nested-error',json_last_error()];return 'ok';}}
class EncNestedSuccessInvalid implements JsonSerializable {public function jsonSerialize():mixed{json_encode(1);$GLOBALS['events'][]=['nested-success',json_last_error()];return "\xC3\x28";}}
class EncMutate implements JsonSerializable {public $target;public function __construct($target){$this->target=$target;}public function jsonSerialize():mixed{$GLOBALS['events'][]='mutate';unset($this->target->drop);$this->target->late=2;return 'v';}}
row('shared-alias-not-cycle',function(){$a=[1];return json_encode([$a,$a]);});
row('array-reference-cycle',function(){$a=[];$a['self']=&$a;return json_encode($a);});
row('array-reference-cycle-partial',function(){$a=[];$a['self']=&$a;return json_encode($a,JSON_PARTIAL_OUTPUT_ON_ERROR);});
row('array-reference-cycle-throw',function(){$a=[];$a['self']=&$a;return json_encode($a,JSON_THROW_ON_ERROR);});
row('object-self-cycle',function(){$o=new stdClass;$o->self=$o;return json_encode($o);});
row('serializable-return-self',fn()=>json_encode(new EncReturnSelf));
row('serializable-throw',fn()=>json_encode(new EncThrow,JSON_THROW_ON_ERROR));
row('nested-error-outer-success',fn()=>json_encode(new EncNestedError));
row('nested-success-outer-throw',fn()=>json_encode(new EncNestedSuccessInvalid,JSON_THROW_ON_ERROR));
row('callback-mutates-object-properties',function(){$o=new stdClass;$o->first=new EncMutate($o);$o->drop=1;return json_encode($o);});
