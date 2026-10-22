<?php
$GLOBALS['events'][]='outer-start';$g=(function(){$GLOBALS['events'][]='generator-start';$value=include __DIR__.'/inner.php';yield $value;$GLOBALS['events'][]='generator-end';})();foreach($g as$v)$GLOBALS['events'][]='yield:'.$v;$GLOBALS['events'][]='outer-end';return 9;
