set_error_handler(function($n,$m){return $n===E_DEPRECATED;});
class HexThrowingParent implements Serializable {
    private $secret='S';
    function serialize(){throw new RuntimeException($this->secret.'-serialize');}
    function unserialize($wire){$this->secret.='R';throw new RuntimeException($this->secret.'-restore');}
}
class HexThrowingChild extends HexThrowingParent {}
$out='';foreach(array('HexThrowingParent','HexThrowingChild') as $class) {
    $object=new $class;
    try{serialize($object);$out.='missing-throw';}catch(Throwable $e){$out.=get_class($e).':'.$e->getMessage().'|';}
    try{unserialize('C:'.strlen($class).':"'.$class.'":0:{}');$out.='missing-throw';}catch(Throwable $e){$out.=get_class($e).':'.$e->getMessage().'|';}
    $probe=new HexThrowingParent;
    try{$v=$probe->secret;$out.='leaked-scope';}catch(Throwable $e){$out.=get_class($e).'|';}
    $out.=bin2hex(hex2bin('ff')).';';
}restore_error_handler();return $out;
