<?php
function ph_try($f){try{return ['ok',$f()];}catch(Throwable $e){return ['throw',get_class($e),$e->getMessage()];}}
$warnings=[];set_error_handler(function($n,$s)use(&$warnings){$warnings[]=[$n,$s];return true;});
$result=ph_try(function(){
class H {public int $x=2 {final get=>$this->x;set(int $v){$this->x=$v;}}public int $virtual {get=>7;}}$out=[];foreach(['x','virtual'] as $n){$r=new ReflectionProperty(H::class,$n);$hooks=[];foreach($r->getHooks() as $k=>$m)$hooks[]=[$k,$m->getName(),$m->isFinal(),$m->returnsReference(),(string)$m->getReturnType(),array_map(fn($p)=>[$p->getName(),(string)$p->getType()],$m->getParameters())];$out[]=[$n,$r->isVirtual(),$r->hasHooks(),$r->hasHook(PropertyHookType::Get),$r->hasHook(PropertyHookType::Set),$hooks];}return $out;
});restore_error_handler();echo json_encode(['result'=>$result,'warnings'=>$warnings],JSON_UNESCAPED_SLASHES|JSON_PARTIAL_OUTPUT_ON_ERROR),"\n";
