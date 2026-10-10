<?php
error_reporting(E_ALL);
class JDWire {}
class JDDeclared {public int $unset; public $nil=null; private $p=1; protected $q=2;}
function jd_encode($value) {return json_encode($value);}
function jd_duplicate($key,&$events) {
    $o=new JDWire;
    set_error_handler(function($severity,$message)use($o,$key,&$events){
        $events[]=[$severity,$message];
        set_error_handler(function($innerSeverity,$innerMessage)use(&$events){$events[]=[$innerSeverity,$innerMessage];return true;});
        try {$o->$key=4;} finally {restore_error_handler();}
        return true;
    });
    try {$o->$key=1;} finally {restore_error_handler();}
    return $o;
}
$events=[];$o=jd_duplicate('x',$events);
$numericEvents=[];$numeric=jd_duplicate('0',$numericEvents);
$declared=new JDDeclared;
return [
    jd_encode($o),
    jd_encode((array)$o),
    jd_encode(get_object_vars($o)),
    jd_encode([$o,$o]),
    jd_encode(['o'=>$o,'a'=>(array)$o]),
    jd_encode($declared),
    jd_encode((array)$declared),
    jd_encode(json_decode('{"x":4,"x":1}',true)),
    jd_encode($numeric),
    jd_encode((array)$numeric),
    jd_encode($events),
    jd_encode($numericEvents)
];
