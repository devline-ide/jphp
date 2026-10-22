<?php
date_default_timezone_set('UTC');
foreach([['!Y-m-d','2024-aa-03'],['!Y/m/d','2024-02-29'],['!Y-m-d','2024-02'],['!Y-m-d+','2024-02-29tail']]as[$format,$input]){$value=DateTimeImmutable::createFromFormat($format,$input);echo json_encode([$format,$input,$value===false?false:$value->format('Y-m-d H:i:s.u e'),DateTimeImmutable::getLastErrors()],JSON_UNESCAPED_SLASHES),"\n";}
