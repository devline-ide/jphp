<?php
function mc_pack($v){
    if(is_string($v))return ['bytes',bin2hex($v)];
    if(is_array($v)){$r=[];foreach($v as $k=>$x)$r[]=[is_int($k)?['int',$k]:['bytes',bin2hex($k)],mc_pack($x)];return ['array',$r];}
    return $v;
}
function mc_view($m){
    $walk=[];foreach($m as $key=>$value)$walk[]=[$key,$value];
    return ['lookup'=>$m['MARK'],'count'=>count($m),'keys'=>array_keys($m),'foreach'=>$walk];
}
function mc_trial($id,$route,$flags){
    $input='a';$count=777;$events=[];$seen=null;
    set_error_handler(function($n,$s)use(&$events){$events[]=[$n,$s];return true;});
    if($route==='match')$r=preg_match('~(?<MARK>a)(*MARK:tag)~',$input,$m,$flags);
    elseif($route==='all'){$r=preg_match_all('~(?<MARK>a)(*MARK:tag)~',$input,$sets,PREG_SET_ORDER|$flags);$m=$sets[0];}
    else{$r=preg_replace_callback('~(?<MARK>a)(*MARK:tag)~',function($captures)use(&$seen){$seen=$captures;return 'X';},$input,-1,$count,$flags);$m=$seen;}
    $before=mc_view($m);
    $copy=$m;$copy['MARK']='clone';$afterClone=[mc_view($m),mc_view($copy)];
    $m['MARK']='original';$afterOriginal=[mc_view($m),mc_view($copy)];
    $input='input-after';$afterInput=[mc_view($m),mc_view($copy),$input];
    restore_error_handler();
    echo $id,':',json_encode(mc_pack([$r,$count,$before,$afterClone,$afterOriginal,$afterInput,preg_last_error(),$events]),JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE),"\n";
}
echo 'engine:',json_encode([PHP_VERSION,PCRE_VERSION,PCRE_JIT_SUPPORT,ini_get('pcre.backtrack_limit'),ini_get('pcre.recursion_limit')]),"\n";
mc_trial('match-flags0','match',0);
mc_trial('match-offset-null','match',PREG_OFFSET_CAPTURE|PREG_UNMATCHED_AS_NULL);
mc_trial('all-set-flags0','all',0);
mc_trial('all-set-offset-null','all',PREG_OFFSET_CAPTURE|PREG_UNMATCHED_AS_NULL);
mc_trial('callback-flags0','callback',0);
mc_trial('callback-offset-null','callback',PREG_OFFSET_CAPTURE|PREG_UNMATCHED_AS_NULL);
