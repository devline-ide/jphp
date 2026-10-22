<?php
function cursor_unset_case($mode,$name,$position,$remove){
    $a=['a'=>1,'b'=>2,'c'=>3];
    for($i=0;$i<$position;$i++)next($a);
    $before=[key($a),current($a)];
    $copy=null;
    if($mode==='cow')$copy=$a;
    if($mode==='alias'){
        $alias=&$a;
        unset($alias[$remove]);
        $aliasState=[key($alias),current($alias),array_keys($alias)];
    }else{
        unset($a[$remove]);
        $aliasState=null;
    }
    $copyState=$mode==='cow'?[key($copy),current($copy),array_keys($copy)]:null;
    echo json_encode([$mode.'-'.$name,$before,[key($a),current($a),array_keys($a)],$copyState,$aliasState]),"\n";
}
foreach(['plain','cow','alias']as$mode){
    cursor_unset_case($mode,'first',0,'a');
    cursor_unset_case($mode,'middle',1,'b');
    cursor_unset_case($mode,'last',2,'c');
    cursor_unset_case($mode,'noncurrent',1,'a');
}
