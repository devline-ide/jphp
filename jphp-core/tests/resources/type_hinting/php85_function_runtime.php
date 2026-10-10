<?php
interface FTI {} interface FTJ {}
class FTBoth implements FTI, FTJ {} class FTOnly implements FTI {} class FTPlain {}
class FTString {function __toString(){return "12.5";}}
$ftWarnings=[];
set_error_handler(function($severity,$message) use (&$ftWarnings){$ftWarnings[]=[$severity,$message]; return true;});
function ft_normalize($v){return is_object($v) ? ['object',get_class($v)] : $v;}
function ft_capture($label,$fn){global $ftWarnings; $ftWarnings=[]; try{$v=$fn();return [$label,'ok',ft_normalize($v),$ftWarnings];}
 catch(Throwable $e){$msg=$e->getMessage();$p=strpos($msg,', called in ');if($p!==false)$msg=substr($msg,0,$p);return [$label,get_class($e),$msg,$ftWarnings];}}
function ft_type($t){if($t===null)return null; $v=[get_class($t),(string)$t,$t->allowsNull()];
 if($t instanceof ReflectionNamedType){$v[]=$t->getName();$v[]=$t->isBuiltin();}else{$v[]=[];foreach($t->getTypes() as $p){$v[3][]=ft_type($p);}} return $v;}
function ft_p0(int|string $x){return $x;} function ft_r0($x): int|string {return $x;}
function ft_p1(float|string $x){return $x;} function ft_r1($x): float|string {return $x;}
function ft_p2(int|float $x){return $x;} function ft_r2($x): int|float {return $x;}
function ft_p3(int|bool $x){return $x;} function ft_r3($x): int|bool {return $x;}
function ft_p4(float|false $x){return $x;} function ft_r4($x): float|false {return $x;}
function ft_p5(string|false $x){return $x;} function ft_r5($x): string|false {return $x;}
function ft_p6(int|float|string|bool $x){return $x;} function ft_r6($x): int|float|string|bool {return $x;}
function ft_p7(false|null $x){return $x;} function ft_r7($x): false|null {return $x;}
function ft_p8(true|null $x){return $x;} function ft_r8($x): true|null {return $x;}
function ft_p9(int|false|null $x){return $x;} function ft_r9($x): int|false|null {return $x;}
function ft_p10(array|FTI $x){return $x;} function ft_r10($x): array|FTI {return $x;}
function ft_p11(FTI&FTJ $x){return $x;} function ft_r11($x): FTI&FTJ {return $x;}
function ft_p12((FTI&FTJ)|null $x){return $x;} function ft_r12($x): (FTI&FTJ)|null {return $x;}
function ft_p13((FTI&FTJ)|string|false $x){return $x;} function ft_r13($x): (FTI&FTJ)|string|false {return $x;}
function ft_p14(FTPlain|(FTI&FTJ) $x){return $x;} function ft_r14($x): FTPlain|(FTI&FTJ) {return $x;}
function ft_p15(iterable|string $x){return $x;} function ft_r15($x): iterable|string {return $x;}
function ft_p16(callable|int $x){return $x;} function ft_r16($x): callable|int {return $x;}
function ft_p17(null $x){return $x;} function ft_r17($x): null {return $x;}
function ft_p18(true $x){return $x;} function ft_r18($x): true {return $x;}
function ft_p19(false $x){return $x;} function ft_r19($x): false {return $x;}
function ft_p20(?FTPlain $x){return $x;} function ft_r20($x): ?FTPlain {return $x;}
function ft_p21(mixed $x){return $x;} function ft_r21($x): mixed {return $x;}
function ft_p22(int $x){return $x;} function ft_r22($x): int {return $x;}
function ft_p23(float $x){return $x;} function ft_r23($x): float {return $x;}
function ft_p24(string $x){return $x;} function ft_r24($x): string {return $x;}
function ft_p25(bool $x){return $x;} function ft_r25($x): bool {return $x;}
$rows=[]; $values=[0,1,-1,1.5,INF,NAN,true,false,null,"12","12.5","12e2","9223372036854775808","-0","-0.0","abc","",[],[1],new FTBoth,new FTOnly,new FTPlain,new FTString,new stdClass,function(){}];
foreach($values as $k=>$v){$rows[]=ft_capture("param:0:".$k,function()use($v){return ft_p0($v);});$rows[]=ft_capture("return:0:".$k,function()use($v){return ft_r0($v);});}
$r=new ReflectionFunction("ft_p0");$p=$r->getParameters()[0];$rows[]=["reflection:0",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r0"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:1:".$k,function()use($v){return ft_p1($v);});$rows[]=ft_capture("return:1:".$k,function()use($v){return ft_r1($v);});}
$r=new ReflectionFunction("ft_p1");$p=$r->getParameters()[0];$rows[]=["reflection:1",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r1"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:2:".$k,function()use($v){return ft_p2($v);});$rows[]=ft_capture("return:2:".$k,function()use($v){return ft_r2($v);});}
$r=new ReflectionFunction("ft_p2");$p=$r->getParameters()[0];$rows[]=["reflection:2",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r2"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:3:".$k,function()use($v){return ft_p3($v);});$rows[]=ft_capture("return:3:".$k,function()use($v){return ft_r3($v);});}
$r=new ReflectionFunction("ft_p3");$p=$r->getParameters()[0];$rows[]=["reflection:3",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r3"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:4:".$k,function()use($v){return ft_p4($v);});$rows[]=ft_capture("return:4:".$k,function()use($v){return ft_r4($v);});}
$r=new ReflectionFunction("ft_p4");$p=$r->getParameters()[0];$rows[]=["reflection:4",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r4"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:5:".$k,function()use($v){return ft_p5($v);});$rows[]=ft_capture("return:5:".$k,function()use($v){return ft_r5($v);});}
$r=new ReflectionFunction("ft_p5");$p=$r->getParameters()[0];$rows[]=["reflection:5",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r5"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:6:".$k,function()use($v){return ft_p6($v);});$rows[]=ft_capture("return:6:".$k,function()use($v){return ft_r6($v);});}
$r=new ReflectionFunction("ft_p6");$p=$r->getParameters()[0];$rows[]=["reflection:6",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r6"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:7:".$k,function()use($v){return ft_p7($v);});$rows[]=ft_capture("return:7:".$k,function()use($v){return ft_r7($v);});}
$r=new ReflectionFunction("ft_p7");$p=$r->getParameters()[0];$rows[]=["reflection:7",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r7"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:8:".$k,function()use($v){return ft_p8($v);});$rows[]=ft_capture("return:8:".$k,function()use($v){return ft_r8($v);});}
$r=new ReflectionFunction("ft_p8");$p=$r->getParameters()[0];$rows[]=["reflection:8",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r8"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:9:".$k,function()use($v){return ft_p9($v);});$rows[]=ft_capture("return:9:".$k,function()use($v){return ft_r9($v);});}
$r=new ReflectionFunction("ft_p9");$p=$r->getParameters()[0];$rows[]=["reflection:9",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r9"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:10:".$k,function()use($v){return ft_p10($v);});$rows[]=ft_capture("return:10:".$k,function()use($v){return ft_r10($v);});}
$r=new ReflectionFunction("ft_p10");$p=$r->getParameters()[0];$rows[]=["reflection:10",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r10"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:11:".$k,function()use($v){return ft_p11($v);});$rows[]=ft_capture("return:11:".$k,function()use($v){return ft_r11($v);});}
$r=new ReflectionFunction("ft_p11");$p=$r->getParameters()[0];$rows[]=["reflection:11",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r11"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:12:".$k,function()use($v){return ft_p12($v);});$rows[]=ft_capture("return:12:".$k,function()use($v){return ft_r12($v);});}
$r=new ReflectionFunction("ft_p12");$p=$r->getParameters()[0];$rows[]=["reflection:12",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r12"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:13:".$k,function()use($v){return ft_p13($v);});$rows[]=ft_capture("return:13:".$k,function()use($v){return ft_r13($v);});}
$r=new ReflectionFunction("ft_p13");$p=$r->getParameters()[0];$rows[]=["reflection:13",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r13"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:14:".$k,function()use($v){return ft_p14($v);});$rows[]=ft_capture("return:14:".$k,function()use($v){return ft_r14($v);});}
$r=new ReflectionFunction("ft_p14");$p=$r->getParameters()[0];$rows[]=["reflection:14",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r14"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:15:".$k,function()use($v){return ft_p15($v);});$rows[]=ft_capture("return:15:".$k,function()use($v){return ft_r15($v);});}
$r=new ReflectionFunction("ft_p15");$p=$r->getParameters()[0];$rows[]=["reflection:15",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r15"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:16:".$k,function()use($v){return ft_p16($v);});$rows[]=ft_capture("return:16:".$k,function()use($v){return ft_r16($v);});}
$r=new ReflectionFunction("ft_p16");$p=$r->getParameters()[0];$rows[]=["reflection:16",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r16"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:17:".$k,function()use($v){return ft_p17($v);});$rows[]=ft_capture("return:17:".$k,function()use($v){return ft_r17($v);});}
$r=new ReflectionFunction("ft_p17");$p=$r->getParameters()[0];$rows[]=["reflection:17",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r17"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:18:".$k,function()use($v){return ft_p18($v);});$rows[]=ft_capture("return:18:".$k,function()use($v){return ft_r18($v);});}
$r=new ReflectionFunction("ft_p18");$p=$r->getParameters()[0];$rows[]=["reflection:18",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r18"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:19:".$k,function()use($v){return ft_p19($v);});$rows[]=ft_capture("return:19:".$k,function()use($v){return ft_r19($v);});}
$r=new ReflectionFunction("ft_p19");$p=$r->getParameters()[0];$rows[]=["reflection:19",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r19"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:20:".$k,function()use($v){return ft_p20($v);});$rows[]=ft_capture("return:20:".$k,function()use($v){return ft_r20($v);});}
$r=new ReflectionFunction("ft_p20");$p=$r->getParameters()[0];$rows[]=["reflection:20",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r20"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:21:".$k,function()use($v){return ft_p21($v);});$rows[]=ft_capture("return:21:".$k,function()use($v){return ft_r21($v);});}
$r=new ReflectionFunction("ft_p21");$p=$r->getParameters()[0];$rows[]=["reflection:21",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r21"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:22:".$k,function()use($v){return ft_p22($v);});$rows[]=ft_capture("return:22:".$k,function()use($v){return ft_r22($v);});}
$r=new ReflectionFunction("ft_p22");$p=$r->getParameters()[0];$rows[]=["reflection:22",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r22"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:23:".$k,function()use($v){return ft_p23($v);});$rows[]=ft_capture("return:23:".$k,function()use($v){return ft_r23($v);});}
$r=new ReflectionFunction("ft_p23");$p=$r->getParameters()[0];$rows[]=["reflection:23",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r23"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:24:".$k,function()use($v){return ft_p24($v);});$rows[]=ft_capture("return:24:".$k,function()use($v){return ft_r24($v);});}
$r=new ReflectionFunction("ft_p24");$p=$r->getParameters()[0];$rows[]=["reflection:24",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r24"))->getReturnType())];
foreach($values as $k=>$v){$rows[]=ft_capture("param:25:".$k,function()use($v){return ft_p25($v);});$rows[]=ft_capture("return:25:".$k,function()use($v){return ft_r25($v);});}
$r=new ReflectionFunction("ft_p25");$p=$r->getParameters()[0];$rows[]=["reflection:25",ft_type($p->getType()),$p->allowsNull(),ft_type((new ReflectionFunction("ft_r25"))->getReturnType())];
function ft_ref(int|float &$v){$v='changed';return $v;}
function ft_variadic(int|float &...$v){$v[0]='changed';return $v;}
function ft_default((FTI&FTJ)|string $v=null){return $v;}
function ft_default2(int|float $v=4){return $v;}
function ft_generator(): Generator|array {yield 1;return 2;}
function ft_generator2(): Traversable|false {yield 3;return 4;}
class FTBase {static function late($v): static|false {return $v;} static function lexical($v): self|false {return $v;}}
class FTChild extends FTBase {}
trait FTTrait {function typed(self|false $v): parent|false {return $v;}}
class FTConsumer extends FTBase {use FTTrait;}
$v='12.5';$rows[]=ft_capture('ref',function()use(&$v){return ft_ref($v);});$rows[]=['ref-after',$v];
$a='12.5';$b='12';$rows[]=ft_capture('variadic',function()use(&$a,&$b){return ft_variadic($a,$b);});$rows[]=['variadic-after',$a,$b];
$rows[]=ft_capture('default-null','ft_default');$rows[]=ft_capture('default-int','ft_default2');
$r=new ReflectionFunction('ft_default');$p=$r->getParameters()[0];$rows[]=['default-reflection',ft_type($p->getType()),$p->allowsNull()];
foreach(['ft_generator','ft_generator2'] as $name){$g=$name();$v=[];foreach($g as $item){$v[]=$item;}$rows[]=['generator',$v,$g->getReturn()];}
foreach([new FTBase,new FTChild,false] as $v){$rows[]=ft_capture('static-base',function()use($v){return FTBase::late($v);});$rows[]=ft_capture('static-child',function()use($v){return FTChild::late($v);});$rows[]=ft_capture('self-child',function()use($v){return FTChild::lexical($v);});}
$c=new FTConsumer;$rows[]=ft_capture('trait',function()use($c){return $c->typed($c);});$rows[]=ft_capture('trait-false',function()use($c){return $c->typed(false);});
$arrow=fn((FTI&FTJ)|false $x): (FTI&FTJ)|false => $x;$rows[]=ft_capture('arrow',function()use($arrow){return $arrow(new FTBoth);});
$r=new ReflectionFunction($arrow);$rows[]=['arrow-reflection',ft_type($r->getParameters()[0]->getType()),ft_type($r->getReturnType())];
function ft_ref_return(&$v): int|float {return $v;}
function &ft_reference(&$v): int|float {return $v;}
function ft_optional(int|float $a=1,$b=2,...$c){}
function ft_required($a=1,$b,...$c){}
function ft_untyped($v){}
function ft_float_default(float|string $v=4){return $v;}
foreach(['ft_optional','ft_required','ft_untyped','ft_variadic'] as $name){$r=new ReflectionFunction($name);$ps=[];
 foreach($r->getParameters() as $p){$ps[]=[$p->getName(),$p->isOptional(),$p->isVariadic(),$p->allowsNull(),ft_type($p->getType())];}
 $rows[]=['signature',$name,$r->getNumberOfParameters(),$r->getNumberOfRequiredParameters(),$ps];}
foreach([new ReflectionParameter('ft_p22',0),new ReflectionParameter(fn(int $x)=>$x,0),new ReflectionParameter(['FTBase','late'],0)] as $p){$rows[]=['direct-parameter',ft_type($p->getType()),$p->allowsNull()];}
$v='12.5';$rows[]=ft_capture('return-reference',function()use(&$v){$r=&ft_reference($v);$r=7;return $v;});$rows[]=['return-reference-after',$v];
$rows[]=ft_capture('float-default','ft_float_default');$rows[]=['float-default-reflection',(new ReflectionFunction('ft_float_default'))->getParameters()[0]->getDefaultValue()];
$rows[]=ft_capture('reflection-invalid-method-string',function(){return new ReflectionParameter('FTBase::late',0);});
$rows[]=ft_capture('reflection-invoke',function(){return (new ReflectionFunction('ft_p2'))->invoke('12.5');});
$rows[]=ft_capture('reflection-invokeArgs',function(){return (new ReflectionFunction('ft_p2'))->invokeArgs(['12.5']);});
$rows[]=ft_capture('call-user-func',function(){return call_user_func('ft_p2','12.5');});
$rows[]=ft_capture('call-user-func-array',function(){return call_user_func_array('ft_p2',['12.5']);});
$rows[]=ft_capture('variable-call',function(){$f='ft_p2';return $f('12.5');});
$r=new ReflectionMethod('FTConsumer','typed');$rows[]=['trait-reflection',ft_type($r->getParameters()[0]->getType()),ft_type($r->getReturnType())];
$rows[]=ft_capture('trait-wrong',function()use($c){return $c->typed(new FTBase);});
class FTClosureScope {function closure(){return function(int|float $v): self|false {return $v;};}}
$closure=(new FTClosureScope)->closure();$r=new ReflectionFunction($closure);$rows[]=['bound-closure-reflection',ft_type($r->getParameters()[0]->getType()),ft_type($r->getReturnType())];
class FTNoReturnBase {
 static function late(): static|false {} static function nullable(): ?static {} static function atom(): static {}
 function lexical(): self|false {}
}
class FTNoReturnChild extends FTNoReturnBase {}
trait FTNoReturnTrait {function own(): self|false {} function base(): parent|false {}}
class FTNoReturnConsumer extends FTNoReturnBase {use FTNoReturnTrait;}
function ft_no_return_mixed(): mixed {} function ft_no_return_null(): null {}
function ft_no_return_nullable(): ?int {} function ft_no_return_iterable(): iterable|string {}
foreach(['late','nullable','atom'] as $name){
 $rows[]=ft_capture('missing-base-'.$name,function()use($name){return call_user_func(['FTNoReturnBase',$name]);});
 $rows[]=ft_capture('missing-child-'.$name,function()use($name){return call_user_func(['FTNoReturnChild',$name]);});
}
$nc=new FTNoReturnConsumer;
foreach(['lexical','own','base'] as $name){$rows[]=ft_capture('missing-consumer-'.$name,function()use($nc,$name){return $nc->$name();});}
foreach(['ft_no_return_mixed','ft_no_return_null','ft_no_return_nullable','ft_no_return_iterable'] as $name){$rows[]=ft_capture('missing-function-'.$name,$name);}
function ft_cached_int(): int {return 1.5;}
class FTCacheResult {static function number(): int {return 1.5;} static function absent(): static|false {}}
class FTCacheChild extends FTCacheResult {}
for($i=0;$i<2;$i++){
 $rows[]=ft_capture('cached-function-'.$i,'ft_cached_int');
 $rows[]=ft_capture('cached-method-'.$i,function(){return FTCacheResult::number();});
 $rows[]=ft_capture('missing-direct-static-'.$i,function(){return FTCacheChild::absent();});
}
restore_error_handler(); return $rows;
