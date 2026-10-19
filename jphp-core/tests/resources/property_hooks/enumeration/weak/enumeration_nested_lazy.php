<?php class T {public static array $events=[];}
class I {public int $v {get {T::$events[]='I';return 1;}}}
class P {public I $i;public int $v {get {T::$events[]='P';return 2;}}function __construct(){$this->i=new I;}}
$results=[];foreach(['json','vars','export'] as $mode){T::$events=[];$p=new P;if($mode==='json')$r=json_encode($p);elseif($mode==='vars')$r=array_keys(get_object_vars($p));else $r=var_export($p,true);$results[]=[$mode,$r,T::$events];}
echo json_encode($results,JSON_UNESCAPED_SLASHES),"\n";
