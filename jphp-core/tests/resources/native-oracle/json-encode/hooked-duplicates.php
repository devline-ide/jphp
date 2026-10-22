<?php

error_reporting(E_ALL);
class JDHookPrivate {private int $hook {get { $GLOBALS['hookEvents'][]='get:private';return 1; }}}
function jd_hook_private_duplicate($key,&$events) {
    $o=new JDHookPrivate;
    set_error_handler(function($severity,$message)use($o,$key,&$events){
        $events[]=[$severity,$message];
        set_error_handler(function($innerSeverity,$innerMessage)use(&$events){$events[]=[$innerSeverity,$innerMessage];return true;});
        try {$o->$key=4;} finally {restore_error_handler();}
        return true;
    });
    try {$o->$key=1;} finally {restore_error_handler();}
    return $o;
}
$GLOBALS['hookEvents']=[];
$events=[];$o=jd_hook_private_duplicate('x',$events);
$numericEvents=[];$numeric=jd_hook_private_duplicate('0',$numericEvents);
echo json_encode($o),"\n";
echo json_encode($numeric),"\n";
echo json_encode($events),"\n";
echo json_encode($numericEvents),"\n";
echo json_encode($GLOBALS['hookEvents']),"\n";

error_reporting(E_ALL);
class JDHookVirtual {public int $hook {get { $GLOBALS['hookEvents'][]='get:virtual';return 1; }}}
function jd_hook_virtual_duplicate($key,&$events) {
    $o=new JDHookVirtual;
    set_error_handler(function($severity,$message)use($o,$key,&$events){
        $events[]=[$severity,$message];
        set_error_handler(function($innerSeverity,$innerMessage)use(&$events){$events[]=[$innerSeverity,$innerMessage];return true;});
        try {$o->$key=4;} finally {restore_error_handler();}
        return true;
    });
    try {$o->$key=1;} finally {restore_error_handler();}
    return $o;
}
$GLOBALS['hookEvents']=[];
$events=[];$o=jd_hook_virtual_duplicate('x',$events);
$numericEvents=[];$numeric=jd_hook_virtual_duplicate('0',$numericEvents);
echo json_encode($o),"\n";
echo json_encode($numeric),"\n";
echo json_encode($events),"\n";
echo json_encode($numericEvents),"\n";
echo json_encode($GLOBALS['hookEvents']),"\n";

error_reporting(E_ALL);
class JDHookBacked {public int $hook=7 {get { $GLOBALS['hookEvents'][]='get:backed';return $this->hook; }}}
function jd_hook_backed_duplicate($key,&$events) {
    $o=new JDHookBacked;
    set_error_handler(function($severity,$message)use($o,$key,&$events){
        $events[]=[$severity,$message];
        set_error_handler(function($innerSeverity,$innerMessage)use(&$events){$events[]=[$innerSeverity,$innerMessage];return true;});
        try {$o->$key=4;} finally {restore_error_handler();}
        return true;
    });
    try {$o->$key=1;} finally {restore_error_handler();}
    return $o;
}
$GLOBALS['hookEvents']=[];
$events=[];$o=jd_hook_backed_duplicate('x',$events);
$numericEvents=[];$numeric=jd_hook_backed_duplicate('0',$numericEvents);
echo json_encode($o),"\n";
echo json_encode($numeric),"\n";
echo json_encode($events),"\n";
echo json_encode($numericEvents),"\n";
echo json_encode($GLOBALS['hookEvents']),"\n";
