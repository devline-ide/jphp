<?php
function emit($tag,$value){echo $tag,'=',bin2hex(serialize($value)),"\n";}
function attempt($tag,$fn){try{emit($tag,$fn());}catch(Throwable $e){emit($tag.'-error',[get_class($e),$e->getMessage()]);}}
set_error_handler(function($s,$m){emit('warning',[$s,$m]);return true;});
