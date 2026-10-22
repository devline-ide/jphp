<?php
function reset_probe($label,$a){
    $row=[];
    $row[]=['reset',reset($a),key($a),current($a)];
    $row[]=['next',next($a),key($a),current($a)];
    $row[]=['reset',reset($a),key($a),current($a)];
    $row[]=['end',end($a),key($a),current($a)];
    $row[]=['reset',reset($a),key($a),current($a)];
    $row[]=['next',next($a),key($a),current($a)];
    $row[]=['next',next($a),key($a),current($a)];
    $row[]=['next',next($a),key($a),current($a)];
    $row[]=['reset',reset($a),key($a),current($a)];
    $row[]=['next',next($a),key($a),current($a)];
    echo json_encode([$label,$row]),"\n";
}
reset_probe('list',[11,22,33]);
reset_probe('map',['a'=>11,'b'=>22,'c'=>33]);
reset_probe('holey',[0=>11,2=>33]);
reset_probe('empty',[]);
reset_probe('false-first',[false,0,'']);
$a=['a'=>11,'b'=>22,'c'=>33];reset($a);$b=$a;
$x=next($a);$y=next($b);echo json_encode(['cow',$x,$y,key($a),current($a),key($b),current($b)]),"\n";
$a=['a'=>11,'b'=>22,'c'=>33];$b=&$a;reset($b);$x=next($a);
echo json_encode(['alias',$x,key($a),current($a),key($b),current($b)]),"\n";
