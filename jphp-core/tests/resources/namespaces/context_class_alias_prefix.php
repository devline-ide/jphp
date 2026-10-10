<?php
namespace Demo;
use Vendor\Widget;
use Other\Widget as Alias;
use Vendor\class as KeywordAlias;
$result = Widget::class.'|'.Widget\Child::class.'|'.\GlobalName\Widget::class.'|'.namespace\Widget::class
    .'|'.Alias\Nested::class.'|'.KeywordAlias::class;
echo $result, "\n";
return $result;
