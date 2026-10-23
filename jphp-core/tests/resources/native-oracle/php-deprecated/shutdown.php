<?php
error_reporting(E_ALL);
set_error_handler(function ($severity, $message) {
    echo 'warning:', json_encode([$severity, $message]), "\n";
    return true;
});
class DepShutdownOwner {
    #[Deprecated('shutdown destructor')]
    public function __destruct() { echo "destructor-body\n"; }
}
$shutdownOwner = new DepShutdownOwner;
echo "body-end\n";
