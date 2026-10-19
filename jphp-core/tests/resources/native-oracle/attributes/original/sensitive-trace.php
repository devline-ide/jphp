<?php
require __DIR__ . "/helper.php";
function secret(#[SensitiveParameter] $a, $b, #[SensitiveParameter] ...$tail) {
 emit('actual',[$a,$b,$tail]);
 $frames=[(new Exception)->getTrace()[0],debug_backtrace()[0]];
 foreach ($frames as $i=>$frame) { $v=[]; foreach($frame['args'] as $arg) $v[]= $arg instanceof SensitiveParameterValue ? ['redacted',get_class($arg),$arg->getValue(),$arg->__debugInfo()] : ['plain',$arg]; emit('trace-'.$i,$v); }
}
secret('password','public','one','two'); $f='secret'; $f(a:'password2',b:'public2'); (new ReflectionFunction('secret'))->invokeArgs(['password3','public3']);
