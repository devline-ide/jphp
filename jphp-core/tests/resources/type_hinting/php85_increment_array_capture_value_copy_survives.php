<?php
error_reporting(0);$a=[];$ev=[];set_error_handler(function($n,$s)use(&$a,&$ev){$ev[]=[$n,$s];$GLOBALS["copy"]=$a;return true;});$r=$a[2]++;restore_error_handler();echo serialize([$r,$a,array_key_exists("copy",$GLOBALS)?$GLOBALS["copy"]:"absent",$GLOBALS["peek"]??null,$ev]);
