/*
 * @author max
 */
package org.jetbrains.jet.parsing;

import com.intellij.openapi.application.PathManager;
import com.intellij.testFramework.ParsingTestCase;
import org.jetbrains.annotations.NonNls;

import java.io.File;

public class JetParsingTest extends ParsingTestCase {
    static {
        System.setProperty("idea.platform.prefix", "Idea");
    }

    public JetParsingTest() {
        super("", "jet");
    }

    @Override
    protected String getTestDataPath() {
        return getHomeDirectory() + "/idea/testData";
    }

    private static String getHomeDirectory() {
       return new File(PathManager.getResourceRoot(JetParsingTest.class, "/")).getParentFile().getParentFile().getParent();
    }

    public void testEmptyFile() throws Exception {doTest(true);}
    public void testBabySteps() throws Exception {doTest(true);}
    public void testImports() throws Exception {doTest(true);}
    public void testImports_ERR() throws Exception {doTest(true);}
    public void testImportSoftKW() throws Exception {doTest(true);}
    public void testNamespaceBlock_ERR() throws Exception {doTest(true);}
    public void testNamespaceBlock() throws Exception {doTest(true);}
    public void testSimpleModifiers() throws Exception {doTest(true);}
    public void testAttributes() throws Exception {doTest(true);}
    public void testAttributes_ERR() throws Exception {doTest(true);}
    public void testTypeDef() throws Exception {doTest(true);}
    public void testTypeDef_ERR() throws Exception {doTest(true);}
}