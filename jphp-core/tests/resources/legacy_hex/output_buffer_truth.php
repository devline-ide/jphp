<?php
foreach (['','30','3030','3031','3000','30312e','31','ff'] as $hex) {
    ob_start(); echo hex2bin($hex); $value=ob_get_clean();
    echo json_encode([$hex,bin2hex($value),(bool)$value,is_array(json_decode('{}',$value))]),"\n";
}
