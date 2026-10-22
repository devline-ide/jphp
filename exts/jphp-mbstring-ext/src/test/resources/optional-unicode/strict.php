<?php declare(strict_types=1);
function report($id,$callback){try{$v=$callback();$e=null;}catch(Throwable $x){$v=null;$e=[get_class($x),$x->getMessage()];}echo json_encode([$id,$v,$e],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES),"\n";}
report('strict-null-trim',fn()=>mb_trim(null));
report('strict-null-encoding',fn()=>mb_internal_encoding(null));
report('strict-int-string',fn()=>mb_ucfirst(123));
report('strict-string-length',fn()=>grapheme_str_split('ab','2'));
