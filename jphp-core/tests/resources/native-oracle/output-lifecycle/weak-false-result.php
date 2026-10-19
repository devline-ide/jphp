<?php 
ob_start(function($s,$p){return false;},3);foreach(['ab','c','de']as $piece)echo $piece;