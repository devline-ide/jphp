<?php
$cases=[
    ['empty',''],['ascii','abcd'],['numeric-leading-zero','01'],['numeric-integer','123'],['numeric-decimal','12.5'],
    ['space',' '],['quote','"'],['backslash',"\\"],['slash','/'],['html',"<>&'"],
    ['controls',"\0\n\t"],['del',"\x7f"],['cyrillic',"\u{0451}\u{0436}"],['emoji',"\u{1f600}"]
];
$flagSets=[
    ['plain',0],['numeric',JSON_NUMERIC_CHECK],
    ['hex',JSON_HEX_TAG|JSON_HEX_AMP|JSON_HEX_APOS|JSON_HEX_QUOT],
    ['unicode-slashes',JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE]
];
$hasPublicStringProducer=class_exists('php\\lib\\str',false);
foreach($cases as [$id,$original]){
    $produced=$hasPublicStringProducer?\php\lib\str::format('%s',$original):$original;
    $inputHex=bin2hex($produced);
    if(!is_string($produced)||$inputHex!==bin2hex($original))throw new Exception('Public string producer changed bytes: '.$id);
    foreach($flagSets as [$label,$flags]){
        json_decode('{');
        $encoded=json_encode($produced,$flags);$code=json_last_error();$message=json_last_error_msg();
        if(!is_string($encoded))throw new Exception('Valid UTF8 scalar encoding failed: '.$id.'/'.$label);
        echo json_encode([$id,$label,$inputHex,base64_encode($encoded),$code,$message]),"\n";
    }
}
