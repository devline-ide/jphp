package file;

import org.develnext.jphp.zend.ZendJvmTestCase;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.MethodSorters;

@RunWith(JUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class FileTest extends ZendJvmTestCase {

    @Test
    public void testDirname() {
        check("ext/file/dirname_001.php");
    }

    @Test
    public void testResourceDirname() {
        org.junit.Assert.assertEquals("res://package/php", org.develnext.jphp.zend.ext.standard.FileFunctions.dirname("res://package/php/Reader.php"));
        org.junit.Assert.assertEquals("res://", org.develnext.jphp.zend.ext.standard.FileFunctions.dirname("res://Reader.php"));
        org.junit.Assert.assertEquals("res://", org.develnext.jphp.zend.ext.standard.FileFunctions.dirname("res://"));
        org.junit.Assert.assertEquals("res://package", org.develnext.jphp.zend.ext.standard.FileFunctions.dirname("res://package/php/"));
    }
}
