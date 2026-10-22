<?php
class StrictHost {static function literal($x=2){return $x;}static function pair(int $x,$y=2){return $x;}static function required($x){echo 'BAD;';return $x;}}
echo (new ReflectionMethod('StrictHost','literal'))->invokeArgs(null,[]),';';echo (new ReflectionMethod('StrictHost','pair'))->invokeArgs(null,['40']),';';try{(new ReflectionMethod('StrictHost','required'))->invokeArgs(null,[]);}catch(ArgumentCountError $e){echo get_class($e),':',$e->getMessage(),';';}
