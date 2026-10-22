<?php
$events=[];set_error_handler(function($n,$m)use(&$events){$events[]=[$n,$m];return true;});
function defaults($x=null){$f=function()use($x){return$x;};$x=99;return$f();}
function aliases(&$x){$f=function()use($x){return$x;};$x=29;return$f();}
$x=17;$alias=&$x;$f=function()use($alias){return$alias;};$alias=19;
$array=[3];$copy=function()use($array){return$array;};$array[0]=4;
$passed=23;$rows=[defaults(),defaults(7),aliases($passed),$passed,$f(),$x,$copy()];
restore_error_handler();echo json_encode([$rows,$events]);
