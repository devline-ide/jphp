<?php declare(strict_types=1);
$out=[];
foreach(['flush','repeat','end','get','empty-end','empty-get','throw-flush','throw-end','throw-get'] as $mode){
    $events=[];$calls=0;ob_start();
    ob_start(function($s,$p)use(&$events,&$calls,$mode){$events[]=[$s,$p];$calls++;if(strpos($mode,'throw-')===0 && $calls===1)throw new Exception('flush-failed');return '['.$s.']';});
    if(strpos($mode,'empty-')!==0)echo 'before';
    $return=null;$caught=null;
    try{
        if($mode==='flush'||$mode==='repeat'||$mode==='throw-flush'){
            $return=ob_flush();echo 'after';
            if($mode==='repeat'){$return=ob_flush();echo 'tail';}
            $return=ob_get_flush();
        }elseif($mode==='end'||$mode==='empty-end'||$mode==='throw-end')$return=ob_end_flush();
        else $return=ob_get_flush();
    }catch(Throwable $e){$caught=[$e::class,$e->getMessage(),ob_get_level(),ob_get_contents()];echo 'after-error';}
    $level=ob_get_level();$rest=null;if($level===2)$rest=ob_get_clean();
    $outer=ob_get_clean();$out[$mode]=[$return,$caught,$level,$rest,$outer,$events,$calls];
}
$warnings=[];set_error_handler(function($n,$s)use(&$warnings){$warnings[]=[$n,$s];return true;});
$out['no-buffer']=[ob_get_flush(),ob_flush(),ob_end_flush(),ob_get_level()];restore_error_handler();$out['warnings']=$warnings;
echo json_encode($out,JSON_THROW_ON_ERROR),"\n";
