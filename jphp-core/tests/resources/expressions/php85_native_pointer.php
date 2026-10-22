<?php
function state($operation, $value, &$a) { return [$operation,$value,key($a),current($a)]; }
function sequence($label, $a) {
    $rows=[];
    $rows[]=state('reset',reset($a),$a);
    $rows[]=state('next',next($a),$a);
    $rows[]=state('prev',prev($a),$a);
    $rows[]=state('end',end($a),$a);
    $rows[]=state('prev',prev($a),$a);
    $rows[]=state('end',end($a),$a);
    $rows[]=state('next-past-end',next($a),$a);
    $rows[]=state('prev-invalid',prev($a),$a);
    $rows[]=state('reset',reset($a),$a);
    $rows[]=state('prev-before-first',prev($a),$a);
    $rows[]=state('next-invalid',next($a),$a);
    echo json_encode([$label,$rows]),"\n";
}
sequence('list',[11,22,33]);
sequence('map',['a'=>11,'b'=>22,'c'=>33]);
$a=[11,22,33];unset($a[1]);sequence('holey',$a);
sequence('empty',[]);
sequence('false-value',[false,0,'']);
$a=['a'=>11,'b'=>22,'c'=>33];next($a);$b=$a;$b['c']=44;
$x=prev($b);$y=next($a);
echo json_encode(['cow-detach',state('copy-prev',$x,$b),state('original-next',$y,$a)]),"\n";
$a=['a'=>11,'b'=>22,'c'=>33];$b=&$a;end($a);$x=prev($b);
echo json_encode(['alias-prev',state('alias',$x,$a),state('same',$x,$b)]),"\n";
$a=['x'=>1,'y'=>2];end($a);next($a);unset($a['y']);$a['z']=3;$a['w']=4;
echo json_encode(['invalid-append-twice-after-last-removed',key($a),current($a)]),"\n";
$a=['x'=>1,'y'=>2];end($a);next($a);$a['z']=3;$a['w']=4;unset($a['z']);
echo json_encode(['remove-revived-current',key($a),current($a)]),"\n";
$a=['x'=>1,'y'=>2];reset($a);$a['z']=3;$a['w']=4;end($a);$x=prev($a);
echo json_encode(['end-after-append',state('prev',$x,$a)]),"\n";
