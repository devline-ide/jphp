<?php
function row($id,$value){echo json_encode([$id,$value],JSON_THROW_ON_ERROR|JSON_UNESCAPED_SLASHES),"\n";}
function globalDefined(){ $f='defined';try{return $f('self::SECRET');}catch(Throwable $e){return [get_class($e),$e->getMessage()];} }
class ActiveDefined {
    public const PUBLIC_VALUE=null;private const SECRET=3;
    static function run(){ $f='defined';return [$f('self::SECRET'),$f('self::PUBLIC_VALUE')]; }
    static function throughGlobal(){return globalDefined();}
}
row('dynamic-class',ActiveDefined::run());
row('dynamic-global-from-class',ActiveDefined::throughGlobal());
$bound=Closure::bind(function(){ $f='defined';return [$f('self::SECRET'),$f('self::PUBLIC_VALUE')];},null,ActiveDefined::class);
row('bound-closure',$bound());
