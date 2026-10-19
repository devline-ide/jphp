<?php 
ob_start(function($s,$p){return 'O['.$p.':'.bin2hex($s).']';},5);ob_start(function($s,$p){return 'I['.$p.':'.bin2hex($s).']';},3);foreach(['ab','c','de','f']as $piece)echo $piece;