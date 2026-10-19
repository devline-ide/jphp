set_error_handler(function($n,$m){return $n===E_DEPRECATED;});
class HexDefaults implements Serializable {
    public static $constructs=0;
    private $value='D';
    function __construct(){self::$constructs++;}
    function serialize(){return $this->value;}
    function unserialize($wire){$this->value.=$wire;}
    function get(){return $this->value;}
}
class HexDefaultsChild extends HexDefaults {}
$out='';foreach(array('HexDefaults','HexDefaultsChild') as $class) {
    $object=unserialize('C:'.strlen($class).':"'.$class.'":1:{X}');
    $out.=$class.':'.$object->get().':'.HexDefaults::$constructs.';';
}restore_error_handler();return $out;
