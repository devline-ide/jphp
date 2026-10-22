<?php
function report($id,$callback) {
    $events=[];set_error_handler(function($n,$s)use(&$events){$events[]=[$n,$s];return true;});
    try{$result=$callback();$error=null;}catch(Throwable $e){$result=null;$error=[get_class($e),$e->getMessage()];}
    restore_error_handler();echo json_encode([$id,$result,$error,$events],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES),"\n";
}
function chunks($s,$n=1){return array_map('bin2hex',grapheme_str_split($s,$n));}
report('dynamic-default',function(){ini_set('default_charset','ISO-8859-1');return mb_internal_encoding();});
ini_set('default_charset','UTF-8');
report('trim-default',fn()=>bin2hex(mb_trim("\0\t\u{00A0}\u{180E}\u{3000}a\u{2007}\n\0")));
report('trim-sides',fn()=>[bin2hex(mb_ltrim(' xyx ',' x')),bin2hex(mb_rtrim(' xyx ',' x'))]);
report('trim-custom',fn()=>[bin2hex(mb_trim('é😀x😀é','é😀')),bin2hex(mb_trim(' a ',''))]);
report('title-expansion',fn()=>[bin2hex(mb_ucfirst('ßabc')),bin2hex(mb_ucfirst('ǳabc')),bin2hex(mb_ucfirst('ﬃabc')),bin2hex(mb_ucfirst("\u{0345}")),bin2hex(mb_ucfirst('ǰ'))]);
report('lower-expansion',fn()=>[bin2hex(mb_lcfirst('İABC')),bin2hex(mb_lcfirst('ÉABC')),bin2hex(mb_lcfirst('𐐀ABC'))]);
report('empty',fn()=>[mb_trim(''),mb_ucfirst(''),mb_lcfirst(''),grapheme_str_split('')]);
report('latin1',fn()=>[mb_internal_encoding('latin1'),mb_internal_encoding(),bin2hex(mb_ucfirst(hex2bin('e961'))),bin2hex(mb_lcfirst(hex2bin('c941'))),mb_internal_encoding('UTF-8')]);
report('utf16',fn()=>[mb_internal_encoding('UTF-16LE'),bin2hex(mb_trim(hex2bin('200061002000'))),bin2hex(mb_ucfirst(hex2bin('61006200'),null)),bin2hex(mb_lcfirst(hex2bin('41004200'))),mb_internal_encoding('UTF-8')]);
report('encoding-alias',fn()=>[mb_internal_encoding('utf8'),mb_internal_encoding(null),bin2hex(mb_trim(' x ',null,'US-ASCII'))]);
report('invalid-state',function(){try{mb_internal_encoding('not-an-encoding');}catch(Throwable $e){return [get_class($e),$e->getMessage(),mb_internal_encoding()];}});
report('invalid-trim',fn()=>mb_trim(' x ',null,'not-an-encoding'));
report('explicit-default',function(){mb_internal_encoding('UTF-8');ini_set('default_charset','UTF-16LE');return mb_internal_encoding();});
report('null-trim',fn()=>mb_trim(null));
report('array-trim',fn()=>mb_trim([]));
report('null-length',fn()=>grapheme_str_split('a',null));
report('fraction-length',fn()=>chunks('abcd',1.5));
report('zero-length',fn()=>chunks('a',0));
report('oversize-length',fn()=>chunks('a',1073741824));
report('combining',fn()=>chunks("a\u{0301}b"));
report('emoji',fn()=>chunks("👩‍👩‍👧‍👦🇰🇿x"));
report('group-two',fn()=>chunks("a\u{0301}b🇰🇿x",2));
foreach(['61ff62','c0af','f0808080','eda080','e282','e28241','e028a1','80bf'] as $hex) report('grapheme-'.$hex,fn()=>chunks(hex2bin($hex)));
foreach(['61ff62','ff4162','c4b0ff62'] as $hex)report('mb-raw-'.$hex,fn()=>[bin2hex(mb_trim(hex2bin($hex))),bin2hex(mb_ucfirst(hex2bin($hex))),bin2hex(mb_lcfirst(hex2bin($hex)))]);
report('handler-before-state',function(){mb_internal_encoding('UTF-8');set_error_handler(function($n,$s){throw new RuntimeException('conversion-aborted');});try{mb_internal_encoding(new class {public function __toString(){trigger_error('encoding-conversion',E_USER_WARNING);return 'ISO-8859-1';}});}catch(Throwable $e){return [get_class($e),mb_internal_encoding()];}finally{restore_error_handler();}});
report('trim-malformed-set',fn()=>bin2hex(mb_trim(hex2bin('ff61ff'),hex2bin('ff'))));
report('trim-malformed-vs-replacement',fn()=>[bin2hex(mb_trim(hex2bin('efbfbd61efbfbd'),hex2bin('ff'))),bin2hex(mb_trim(hex2bin('ff61ff'),hex2bin('efbfbd'))),bin2hex(mb_trim(hex2bin('ff61ff'),hex2bin('fe')))]);
report('other-explicit-encodings',fn()=>[bin2hex(mb_ucfirst(hex2bin('00610062'),'UTF-16BE')),bin2hex(mb_ucfirst(hex2bin('6100000062000000'),'UTF-32LE')),bin2hex(mb_ucfirst(hex2bin('0000006100000062'),'UTF-32BE')),bin2hex(mb_ucfirst(hex2bin('e0e1'),'CP1251')),bin2hex(mb_ucfirst('abc','ASCII'))]);
report('mb-changed-malformed-tail',fn()=>[bin2hex(mb_ucfirst(hex2bin('61f080808062'))),bin2hex(mb_ucfirst(hex2bin('61eda08062'))),bin2hex(mb_ucfirst(hex2bin('61e28262')))]);
