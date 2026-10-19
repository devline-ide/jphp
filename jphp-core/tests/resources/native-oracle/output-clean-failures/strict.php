<?php declare(strict_types=1);
$out=[];
foreach(['plain','repeat','nested','binary'] as $mode){
    $calls=0;ob_start();
    ob_start(function($s,$p)use(&$calls){$calls++;if($calls===1)throw new Exception('clean-failed');return '['.$s.']';});
    echo 'discard';
    try{ob_clean();}catch(Throwable $e){$before=[$e::class,$e->getMessage(),ob_get_level(),ob_get_contents()];}
    if($mode==='repeat')$before[]=ob_clean();
    if($mode==='nested'){ob_start();echo 'nested';ob_end_flush();}
    echo $mode==='binary'?"\x00\xff":'after';
    $inner=ob_get_clean();$level=ob_get_level();$outer=ob_get_clean();
    $out[$mode]=[$before,$inner,$level,bin2hex($outer),$calls];
}
echo json_encode($out,JSON_THROW_ON_ERROR),"\n";
