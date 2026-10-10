<?php
error_reporting(E_ALL);
function tf_case($label,$run) {
    $log=[]; $result=null; $chain=[];
    try {$result=$run($log);} catch(Throwable $e) {
        $seen=[];
        while($e!==null) {
            foreach($seen as $prior) if($e===$prior) { $chain[]=['cycle']; break 2; }
            $seen[]=$e; $chain[]=[get_class($e),$e->getMessage()]; $e=$e->getPrevious();
        }
    }
    echo serialize([$label,$result,$log,$chain])."\n";
}
function &tf_reference(&$log,&$value) {
    try {throw new Exception('try');} catch(Exception $e) {return $value;}
    finally {$log[]='finally';$value=8;}
}
tf_case('break-exits-try',function(&$l){while(true) {try {$l[]='try';break;} finally {$l[]='finally';}}return 1;});
tf_case('continue-exits-try',function(&$l){$i=0;while($i<2) {$i++;try {$l[]=$i;continue;} finally {$l[]='finally';}}return $i;});
tf_case('break-inside-try',function(&$l){try {while(true){$l[]='loop';break;}$l[]='after-loop';} finally {$l[]='finally';}return 1;});
tf_case('switch-break-exits-try',function(&$l){switch(1) {case 1:try {$l[]='switch';break;} finally {$l[]='finally';}}return 1;});
tf_case('goto-exits-try',function(&$l){try {$l[]='try';goto tf_out;} finally {$l[]='finally';}tf_out:return 1;});
tf_case('catch-break-exits-try',function(&$l){while(true) {try {throw new Exception('a');} catch(Exception $e) {$l[]='catch';break;} finally {$l[]='finally';}}return 1;});
tf_case('nested-break-two',function(&$l){while(true){try {while(true){try {$l[]='try';break 2;} finally {$l[]='inner';}}} finally {$l[]='outer';}}return 1;});
tf_case('nested-continue-two',function(&$l){$i=0;while($i<2){$i++;try {while(true){try {$l[]=$i;continue 2;} finally {$l[]='inner';}}} finally {$l[]='outer';}}return $i;});
tf_case('break-two-stays-in-outer-try',function(&$l){try {while(true){while(true){try {break 2;} finally {$l[]='inner';}}}$l[]='after';} finally {$l[]='outer';}return 1;});
tf_case('continue-two-stays-in-outer-try',function(&$l){$i=0;try {while($i<2){$i++;while(true){try {continue 2;} finally {$l[]='inner';}}}$l[]='after';} finally {$l[]='outer';}return $i;});
tf_case('switch-break-two',function(&$l){while(true){switch(1){case 1:try {break 2;} finally {$l[]='finally';}}}return 1;});
tf_case('foreach-break',function(&$l){foreach([1,2] as $v){try {$l[]=$v;break;} finally {$l[]='finally';}}return 1;});
tf_case('for-continue',function(&$l){for($i=0;$i<2;$i++){try {$l[]=$i;continue;} finally {$l[]='finally';}}return $i;});
tf_case('do-continue',function(&$l){$i=0;do{$i++;try {continue;} finally {$l[]='finally';}}while($i<2);return $i;});
tf_case('break-finally-return',function(&$l){while(true){try {break;} finally {$l[]='finally';return 7;}}return 1;});
tf_case('continue-finally-throw',function(&$l){while(true){try {continue;} finally {$l[]='finally';throw new Error('stop');}}});
tf_case('goto-finally-return',function(&$l){try {goto tf_return;} finally {$l[]='finally';return 7;}tf_return:return 1;});
tf_case('goto-finally-throw',function(&$l){try {goto tf_throw;} finally {throw new Error('stop');}tf_throw:return 1;});
tf_case('goto-forward-same-try',function(&$l){try {$l[]='before';goto tf_forward;$l[]='wrong';tf_forward:$l[]='target';} finally {$l[]='finally';}return 1;});
tf_case('goto-backward-same-try',function(&$l){$i=0;try {tf_back:$i++;$l[]=$i;if($i<2)goto tf_back;} finally {$l[]='finally';}return $i;});
tf_case('goto-exits-inner-try',function(&$l){try {try {goto tf_outer;} finally {$l[]='inner';}tf_outer:$l[]='target';} finally {$l[]='outer';}return 1;});
tf_case('goto-exits-nested-tries',function(&$l){try {try {goto tf_both;} finally {$l[]='inner';}} finally {$l[]='outer';}tf_both:return 1;});
tf_case('goto-exits-catch',function(&$l){try {throw new Exception('a');}catch(Exception $e){$l[]='catch';goto tf_catch;}finally{$l[]='finally';}tf_catch:return 1;});
tf_case('finally-goto-forward-normal',function(&$l){try {$l[]='try';}finally{goto tf_fnormal;$l[]='wrong';tf_fnormal:$l[]='target';}return 1;});
tf_case('finally-goto-forward-return',function(&$l){try {return 7;}finally{goto tf_freturn;$l[]='wrong';tf_freturn:$l[]='target';}});
tf_case('finally-goto-forward-error',function(&$l){try {throw new Exception('pending');}finally{goto tf_ferror;$l[]='wrong';tf_ferror:$l[]='target';}});
tf_case('finally-goto-backward',function(&$l){$i=0;try {return 7;}finally{tf_fback:$i++;$l[]=$i;if($i<2)goto tf_fback;}});
tf_case('finally-inner-loop-break',function(&$l){try {return 7;}finally{while(true){$l[]='loop';break;}$l[]='after';}});
tf_case('nested-finally-goto-labels',function(&$l){try {return 7;}finally{try {$l[]='outer';}finally{goto tf_nested;$l[]='wrong';tf_nested:$l[]='inner';}}});
tf_case('goto-enters-try',function(&$l){goto tf_entry;try{$l[]='wrong';tf_entry:$l[]='target';}finally{$l[]='finally';}return 1;});
tf_case('goto-backward-exits-try',function(&$l){$i=0;tf_retry:$i++;try{$l[]=$i;if($i<2)goto tf_retry;}finally{$l[]='finally';}return $i;});
tf_case('sibling-try-identity',function(&$l){try {$l[]='first';}finally{$l[]='first-finally';}try{goto tf_identity;}finally{$l[]='second-finally';}tf_identity:return 1;});
tf_case('finally-backward-goto-normal',function(&$l){$i=0;try{$l[]='try';}finally{tf_bnormal:$i++;$l[]=$i;if($i<2)goto tf_bnormal;}return $i;});
tf_case('finally-backward-goto-error',function(&$l){$i=0;try{throw new Error('pending');}finally{tf_berror:$i++;$l[]=$i;if($i<2)goto tf_berror;}});
tf_case('finally-backward-goto-break',function(&$l){$i=0;while(true){try{break;}finally{tf_bbreak:$i++;$l[]=$i;if($i<2)goto tf_bbreak;}}return $i;});
tf_case('goto-inner-try-within-finally',function(&$l){try{return 7;}finally{try{goto tf_inside_finally;}finally{$l[]='inner';}tf_inside_finally:$l[]='target';}});
tf_case('goto-enters-catch',function(&$l){goto tf_enter_catch;try{$l[]='wrong';}catch(Exception $e){tf_enter_catch:$l[]='catch';}finally{$l[]='finally';}return 1;});
tf_case('goto-try-to-catch',function(&$l){try{$l[]='try';goto tf_try_catch;}catch(Exception $e){tf_try_catch:$l[]='catch';}finally{$l[]='finally';}return 1;});
tf_case('plain-do-continue-condition',function(&$l){$i=0;do{$i++;$l[]=$i;continue;}while(($l[]='condition')&&$i<2);return $i;});
tf_case('continue-two-targets-outer-do',function(&$l){$i=0;do{$i++;while(true){$l[]=$i;continue 2;}}while(($l[]='condition')&&$i<2);return $i;});
