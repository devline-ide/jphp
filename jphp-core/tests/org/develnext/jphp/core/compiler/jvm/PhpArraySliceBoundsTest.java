package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Environment;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.assertEquals;

/** Actual PHP calls cover list/map storage, both profiles and serialized modules. */
public class PhpArraySliceBoundsTest {
    private void check(String source) throws Throwable {
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) {
            for (boolean debug : new boolean[]{false, true}) {
                for (boolean phb : new boolean[]{false, true}) {
                    var output = new ByteArrayOutputStream();
                    var env = new Environment(PhpJsonEncodeContractTest.scope(debug), output);
                    try {
                        PhpJsonEncodeContractTest.compile(env, source, phb, debug, profile).include(env);
                        env.flushAll();
                        assertEquals("profile=" + profile + " debug=" + debug + " PHB=" + phb,
                                "ARRAY_SLICE_BOUNDS_PASS", output.toString(StandardCharsets.UTF_8));
                    } finally {
                        env.doFinal();
                    }
                }
            }
        }
    }

    @Test(timeout = 30000)
    public void listBoundsClampRatherThanDiscardShortSaveLists() throws Throwable {
        check("""
            function expect_slice($actual, $expected, $label) {
                if ($actual !== $expected) throw new Exception('array_slice mismatch: '.$label);
            }
            foreach ([
                [[1], 0, 5, [1]],
                [[1,2], 0, 5, [1,2]],
                [[1,2,3,4], 0, 5, [1,2,3,4]],
                [[1,2,3], 1, 5, [2,3]],
                [[1,2,3], -2, 5, [2,3]],
                [[1,2,3], -99, 5, [1,2,3]],
                [[1,2,3], 99, 5, []],
                [[1,2,3], 3, 5, []],
                [[1,2,3], 0, 0, []],
                [[1,2,3], 1, 0, []],
                [[1,2,3], 0, -1, [1,2]],
                [[1,2,3], 1, -1, [2]],
                [[1,2,3], -2, -1, [2]],
                [[1,2,3], 1, -99, []],
                [[1,2,3], 99, -1, []],
                [[1,2,3], -2147483648, 2147483647, [1,2,3]],
                [[1,2,3], 1, 2147483647, [2,3]],
                [[], -99, 5, []]
            ] as $case) {
                expect_slice(array_slice($case[0],$case[1],$case[2]),$case[3],'list bounds');
            }
            expect_slice(array_slice([1,2,3],-99),[1,2,3],'omitted length negative offset');
            expect_slice(array_slice([1,2,3],99),[],'omitted length past end');
            expect_slice(array_slice([1,2,3],-2,null),[2,3],'explicit null length');
            expect_slice(array_slice([1,2,3],1,99,true),[1=>2,2=>3],'preserve list keys');
            expect_slice(array_slice([1,2,3],-99,null,true),[1,2,3],'preserve clamped list keys');
            $original=[['saved'=>1],['saved'=>2]];
            $slice=array_slice($original,0,5); $slice[0]['saved']=9;
            expect_slice($original,[['saved'=>1],['saved'=>2]],'source value copy');
            echo 'ARRAY_SLICE_BOUNDS_PASS';
            """);
    }

    @Test(timeout = 30000)
    public void mapBoundsKeepStringKeysAndOnlyRenumberNumericKeys() throws Throwable {
        check("""
            function expect_slice($actual, $expected, $label) {
                if ($actual !== $expected) throw new Exception('array_slice mismatch: '.$label);
            }
            $map=[10=>'a','named'=>'b',30=>'c','tail'=>'d'];
            expect_slice(array_slice($map,0,99),['a','named'=>'b','c','tail'=>'d'],'overshoot');
            expect_slice(array_slice($map,1,2),['named'=>'b','c'],'numeric renumber');
            expect_slice(array_slice($map,1,2,true),['named'=>'b',30=>'c'],'preserve keys');
            expect_slice(array_slice($map,1,-1),['named'=>'b','c'],'negative length');
            expect_slice(array_slice($map,-99,2),['a','named'=>'b'],'clamped negative offset');
            expect_slice(array_slice($map,1,0),[],'zero length');
            expect_slice(array_slice($map,1,-99),[],'negative length before offset');
            expect_slice(array_slice($map,99,99),[],'offset past end');
            expect_slice(array_slice($map,-99,null,true),$map,'omitted remainder with keys');
            expect_slice(array_slice($map,-2),['c','tail'=>'d'],'omitted remainder renumber');
            expect_slice(array_slice([-3=>'a',7=>'b',42=>'c'],-2,5,true),[7=>'b',42=>'c'],'sparse numeric keys');
            expect_slice(array_slice([-3=>'a',7=>'b',42=>'c'],-2,5),['b','c'],'sparse numeric renumber');
            expect_slice($map,[10=>'a','named'=>'b',30=>'c','tail'=>'d'],'source untouched');
            echo 'ARRAY_SLICE_BOUNDS_PASS';
            """);
    }
}
