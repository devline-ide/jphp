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
tf_case('try-return',function(&$l){try {$l[]='try';return 3;} finally {$l[]='finally';}});
tf_case('catch-return',function(&$l){try {throw new Exception('a');} catch(Exception $e) {$l[]='catch';return 7;} finally {$l[]='finally';}});
tf_case('catch-throw',function(&$l){try {throw new Exception('a');} catch(Exception $e) {$l[]='catch';throw new Exception('b');} finally {$l[]='finally';}});
tf_case('catch-fallthrough',function(&$l){try {throw new Exception('a');} catch(Exception $e) {$l[]='catch';} finally {$l[]='finally';} return 9;});
tf_case('unmatched-catch',function(&$l){try {throw new Error('a');} catch(Exception $e) {$l[]='wrong';} finally {$l[]='finally';}});
tf_case('catch-sibling-does-not-catch',function(&$l){try {throw new Exception('a');} catch(Exception $e) {$l[]='first';throw new Error('b');} catch(Error $e) {$l[]='wrong';} finally {$l[]='finally';}});
tf_case('multi-catch',function(&$l){try {throw new Error('a');} catch(Exception|Error $e) {$l[]='multi';return 4;} finally {$l[]='finally';}});
tf_case('nested-try-return',function(&$l){try {try {return 5;} finally {$l[]='inner';}} finally {$l[]='outer';}});
tf_case('nested-catch-return',function(&$l){try {try {throw new Exception('a');} catch(Exception $e) {return 6;} finally {$l[]='inner';}} finally {$l[]='outer';}});
tf_case('nested-catch-throw',function(&$l){try {try {throw new Exception('a');} catch(Exception $e) {throw new Error('b');} finally {$l[]='inner';}} finally {$l[]='outer';}});
tf_case('finally-overrides-return',function(&$l){try {return 1;} finally {$l[]='finally';return 2;}});
tf_case('finally-overrides-catch-return',function(&$l){try {throw new Exception('a');} catch(Exception $e) {return 1;} finally {return 2;}});
tf_case('finally-return-discards-error',function(&$l){try {throw new Error('a');} finally {$l[]='finally';return 8;}});
tf_case('finally-throw-overrides-return',function(&$l){try {return 1;} finally {$l[]='finally';throw new Error('b');}});
tf_case('finally-throw-previous-exception',function(&$l){try {throw new Exception('a');} finally {$l[]='finally';throw new Exception('b');}});
tf_case('finally-throw-previous-error',function(&$l){try {throw new Error('a');} finally {throw new Error('b');}});
tf_case('finally-throw-previous-catch-error',function(&$l){try {throw new Exception('a');} catch(Exception $e) {throw new Error('b');} finally {throw new Exception('c');}});
tf_case('finally-existing-previous-tail',function(&$l){try {throw new Exception('a');} finally {throw new Exception('b',0,new Error('c'));}});
tf_case('finally-same-object',function(&$l){$e=new Exception('same');try {throw $e;} finally {throw $e;}});
tf_case('finally-overlapping-previous',function(&$l){$e=new Exception('a');try {throw $e;} finally {throw new Exception('b',0,$e);}});
tf_case('finally-pending-links-replacement',function(&$l){$e=new Exception('b');try {throw new Exception('a',0,$e);} finally {throw $e;}});
tf_case('nested-finally-previous-chain',function(&$l){try {try {throw new Exception('a');} finally {throw new Error('b');}} finally {throw new Exception('c');}});
tf_case('finally-inner-catch-preserved',function(&$l){try {throw new Exception('a');} finally {try {throw new Error('b');} catch(Error $e) {$l[]=$e->getMessage();}}});
tf_case('finally-inner-return-overrides-error',function(&$l){try {throw new Exception('a');} finally {try {return 2;} finally {$l[]='inner';}}});
tf_case('outer-catch-receives-finally-error',function(&$l){try {try {return 1;} finally {throw new Error('b');}} catch(Error $e) {$l[]=$e->getMessage();return 5;} finally {$l[]='outer';}});
tf_case('outer-catch-receives-catch-error',function(&$l){try {try {throw new Exception('a');} catch(Exception $e) {throw new Error('b');} finally {$l[]='inner';}} catch(Error $e) {$l[]=$e->getMessage();return 5;} finally {$l[]='outer';}});
tf_case('empty-finally-pending-error',function(&$l){try {throw new Error('a');} finally {}});
tf_case('empty-try-finally',function(&$l){try {} finally {$l[]='finally';}return 2;});
tf_case('byref-catch-return',function(&$l){$v=1;$r=&tf_reference($l,$v);$r=9;return [$v,$r];});
tf_case('finally-mutates-pending-value',function(&$l){$v=[1];try {return $v;} finally {$v[0]=2;$l[]=$v;}});
tf_case('catch-variable-survives-finally',function(&$l){try {throw new Exception('a');} catch(Exception $e) {return $e->getMessage();} finally {$l[]=$e->getMessage();}});
tf_case('return-finally-inner-catch-return',function(&$l){try {return 1;} finally {try {throw new Error('b');} catch(Error $e) {return 2;} finally {$l[]='inner';}}});
