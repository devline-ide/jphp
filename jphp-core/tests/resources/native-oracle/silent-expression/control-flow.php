<?php
error_reporting(E_ALL);$events=[];
function mark($name,$value=1){$GLOBALS['events'][]=[$name,error_reporting()];return$value;}
$left=mark('left',10)+@(function(){return mark('right',2);})();mark('after-binary');
$null=null;$skip=@$null?->missing(mark('skipped'));mark('after-nullsafe');
$skipFalse=false&&@(function(){mark('skipped-false');return true;})();mark('after-false');
$skipTrue=true||@(function(){mark('skipped-true');return false;})();mark('after-true');
$ternary=true?@(function(){return mark('selected',3);})():mark('skipped-ternary');mark('after-ternary');
$array=[7];$indexed=@$array[mark('index',0)];mark('after-index');
$thrown=new RuntimeException('identity');$same=false;try{@(function()use($thrown){try{mark('before-throw');throw$thrown;}finally{mark('throw-finally');}})();}catch(RuntimeException$e){$same=$e===$thrown;mark('outside-catch');}
echo json_encode([$left,$skip,$skipFalse,$skipTrue,$ternary,$indexed,$same,$events,error_reporting()]);
