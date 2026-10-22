<?php
$f=function(){return function(){return function(){return 41;};};};
echo json_encode($f()()());
