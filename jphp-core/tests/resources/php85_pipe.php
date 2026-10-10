<?php
function pipePlus($x) { return $x + 2; }
function pipeDouble($x) { return $x * 2; }
function pipeIdentity($x) { return $x; }
function pipeReference(&$x) { $x = 90; return $x; }
function pipeTwo($x, $y) { return [$x, $y]; }
function pipeTyped(int $x) { return $x; }
function pipeVariadic(...$xs) { return $xs; }
function pipeRows($name, $body) {
    try { return [$name,'ok',$body()]; }
    catch (Throwable $e) {
        $message=$e->getMessage();$pos=strpos($message,', called in ');
        if ($pos !== false) $message=substr($message,0,$pos);
        return [$name,get_class($e),$e instanceof ArgumentCountError ? 'argument-count' : $message];
    }
}
class PipeBox {
    public $n = 3;
    public function plus($x) { return $x + $this->n; }
    public static function twice($x) { return $x * 2; }
    public function __invoke($x) { return $x + 7; }
    public function &byReference(&$x) { return $x; }
}
$rows=[];
foreach ([null,false,true,17,2.5,'missingPipeFunction',[],[1],['PipeBox','missing'],new stdClass] as $callback) {
    $rows[]=pipeRows('invalid',function() use ($callback) { return 3 |> $callback; });
}
$rows[]=pipeRows('string',function() { return 3 |> 'pipePlus'; });
$rows[]=pipeRows('closure',function() { return 3 |> function($x) { return $x + 2; }; });
$rows[]=pipeRows('arrow',function() { return 3 |> (fn($x) => $x + 2); });
$rows[]=pipeRows('static-arrow',function() { return 3 |> (static fn($x) => $x + 2); });
$rows[]=pipeRows('chain',function() { return 3 |> 'pipePlus' |> 'pipeDouble'; });
$rows[]=pipeRows('expression',function() { return 3 |> (true ? 'pipePlus' : 'pipeDouble'); });
$rows[]=pipeRows('method',function() { return 3 |> [new PipeBox,'plus']; });
$rows[]=pipeRows('static-array',function() { return 3 |> ['PipeBox','twice']; });
$rows[]=pipeRows('static-string',function() { return 3 |> 'PipeBox::twice'; });
$rows[]=pipeRows('invokable',function() { return 3 |> new PipeBox; });
$rows[]=pipeRows('native',function() { return 'abcd' |> 'strlen'; });
$rows[]=pipeRows('reference-literal',function() { return 3 |> 'pipeReference'; });
$rows[]=pipeRows('reference-variable',function() { $x=3;return [$x |> 'pipeReference',$x]; });
$rows[]=pipeRows('reference-method',function() { return 3 |> [new PipeBox,'byReference']; });
$rows[]=pipeRows('missing-argument',function() { return 3 |> 'pipeTwo'; });
$rows[]=pipeRows('typed',function() { return '12' |> 'pipeTyped'; });
$rows[]=pipeRows('typed-invalid',function() { return [] |> 'pipeTyped'; });
$rows[]=pipeRows('variadic',function() { return 3 |> 'pipeVariadic'; });
$rows[]=pipeRows('snapshot-scalar',function() {
    $x=3;$events=[];
    $f=function() use (&$x,&$events) { $events[]=$x;$x=20;return 'pipePlus'; };
    return [$x |> $f(),$x,$events];
});
$rows[]=pipeRows('snapshot-array',function() {
    $x=[3];$f=function() use (&$x) { $x[]=20;return 'pipeIdentity'; };
    return [$x |> $f(),$x];
});
$rows[]=pipeRows('object-identity',function() {
    $x=new PipeBox;$f=function() use ($x) { $x->n=20;return 'pipeIdentity'; };
    $result=$x |> $f();return [$result === $x,$result->n];
});
$rows[]=pipeRows('left-once',function() {
    $n=0;$f=function() use (&$n) { return ++$n; };return [$f() |> 'pipePlus',$n];
});
$rows[]=pipeRows('rhs-once',function() {
    $n=0;$f=function() use (&$n) { ++$n;return 'pipePlus'; };return [3 |> $f(),$n];
});
$rows[]=pipeRows('arithmetic',function() { return 1 + 2 * 3 |> 'pipeDouble'; });
$rows[]=pipeRows('comparison',function() { return 3 |> 'pipeDouble' > 5; });
$rows[]=pipeRows('equality',function() { return 3 |> 'pipeDouble' === 6; });
$rows[]=pipeRows('concat',function() { return 'a'.'bc' |> 'strlen'; });
$rows[]=pipeRows('shift',function() { return 1 << 2 |> 'pipeDouble'; });
$rows[]=pipeRows('bitwise',function() { return 3 |> 'pipeDouble' & 3; });
$rows[]=pipeRows('and',function() { return 3 |> 'pipeDouble' && false; });
$rows[]=pipeRows('or',function() { return 0 |> 'pipeIdentity' || true; });
$rows[]=pipeRows('coalesce',function() { return null |> 'pipeIdentity' ?? 8; });
$rows[]=pipeRows('ternary',function() { return 0 |> 'pipeIdentity' ? 8 : 9; });
$rows[]=pipeRows('assignment',function() { $x=3 |> 'pipeDouble';return $x; });
$rows[]=pipeRows('compound',function() { $x=1;$x+=3 |> 'pipeDouble';return $x; });
$rows[]=pipeRows('unused-result',function() { $x=0;3 |> function($v) use (&$x) {$x=$v;};return $x; });
$rows[]=pipeRows('throw-stops-chain',function() {
    $x=0;try { 3 |> function($v) { throw new Exception('pipe-stop'); } |> function($v) use (&$x) { ++$x;return $v; }; }
    catch (Exception $e) { return [$e->getMessage(),$x]; }
});
$rows[]=pipeRows('null-still-invoked',function() { $x=0;$r=null |> function($v) use (&$x) { ++$x;return $v; };return [$r,$x]; });
return $rows;
