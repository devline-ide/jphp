set_error_handler(function($n,$m){return $n===E_DEPRECATED;});
class HexSerializable implements Serializable {
    private $value;
    function __construct($value){$this->value=$value;}
    function serialize(){return $this->value;}
    function unserialize($wire){$this->value=$wire;}
    function get(){return $this->value;}
}
class HexSerializableChild extends HexSerializable {}
$out='';foreach(array('HexSerializable','HexSerializableChild') as $class) foreach(array('c3a9','fffe80','00017f80ffc328') as $hex) {
    $s=new $class(hex2bin($hex));$wire=serialize($s);$copy=unserialize($wire);
    $out.=bin2hex($wire).':'.bin2hex($copy->get()).';';
}restore_error_handler();return $out;
