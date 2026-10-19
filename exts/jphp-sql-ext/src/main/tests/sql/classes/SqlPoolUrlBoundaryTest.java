package sql.classes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.develnext.jphp.ext.sql.SqlExtension;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.ext.CoreExtension;
import php.runtime.ext.SPLExtension;
import php.runtime.loader.dump.ModuleDumper;
import static org.junit.Assert.assertEquals;

public class SqlPoolUrlBoundaryTest {
    private void checkPool(String url) throws Throwable {
        String source="use php\\sql\\SqlDriverManager;SqlDriverManager::install('sqlite');"
                +"$p=SqlDriverManager::getPool('"+url+"','sqlite');"
                +"$p->setMaxPoolSize(1);$p->setMinimumIdle(0);"
                +"try{$c=$p->getConnection();try{echo $c->query('select 41 as n')->fetch()->get('n');}"
                +"finally{$c->close();}}finally{$p->close();}";
        for (var profile:new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY,PhpLanguageProfile.PHP_8_5})
            for(boolean debug:new boolean[]{false,true}) for(boolean dumped:new boolean[]{false,true}) {
                var scope=new CompileScope();scope.setPhpLanguageProfile(profile);scope.setDebugMode(debug);
                scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());scope.registerExtension(new SqlExtension());
                var env=new Environment(scope,new ByteArrayOutputStream());env.getDefaultBuffer().setChunkSize(0);
                try {
                    var context=new Context(source);context.setPhpLanguageProfile(profile);
                    var module=new JvmCompiler(env,context).compile(false);
                    if(dumped){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                    scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
                    assertEquals("41",env.getDefaultBuffer().getContents().toString());
                }finally{try{env.doFinal();}finally{scope.getClassLoader().close();}}
            }
    }
    @Test public void conventionalPoolUrlSourceAndPhb() throws Throwable {checkPool("sqlite::memory:");}
    @Test public void legacyLeadingColonPoolUrlSourceAndPhb() throws Throwable {checkPool(":sqlite::memory:");}
}
