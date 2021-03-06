package org.jetbrains.jet.resolve;

import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.testFramework.LightCodeInsightTestCase;
import junit.framework.Test;
import junit.framework.TestSuite;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.JetTestCaseBuilder;
import org.jetbrains.jet.plugin.PluginTestCaseBase;

import java.io.File;

/**
 * @author Nikolay Krasko
 */
public class ResolveBaseTest extends LightCodeInsightTestCase {

    private final String myPath;
    private final String myName;

    protected ResolveBaseTest(@NotNull String path, @NotNull String name) {
        myPath = path;
        myName = name;

        // Set name explicitly because otherwise there will be "TestCase.fName cannot be null"
        setName("testResolve");
    }

    @Override
    protected Sdk getProjectJDK() {
        return PluginTestCaseBase.jdkFromIdeaHome();
    }

    @Override
    protected String getTestDataPath() {
        return new File(PluginTestCaseBase.getTestDataPathBase(), myPath).getPath() +
               File.separator;
    }

    @NotNull
    @Override
    public String getName() {
        return "test" + myName;
    }

    public void testResolve() throws Exception {
        doTest();
    }

    // TODO: Currently this test is only for KT-763 bug - it should be extended to framework for testing references
    protected void doTest() throws Exception {
        final String testName = getTestName(false);
        configureByFile(testName + ".kt");

        final PsiReference psiReference =
                getFile().findReferenceAt(getEditor().getCaretModel().getOffset());

        assertTrue(psiReference instanceof PsiPolyVariantReference);

        final PsiPolyVariantReference variantReference = (PsiPolyVariantReference) psiReference;

        final ResolveResult[] results = variantReference.multiResolve(true);
        for (ResolveResult result : results) {
            assertNotNull(result);
        }
    }

    @NotNull
    public static TestSuite suite() {
        TestSuite suite = new TestSuite();

        JetTestCaseBuilder.appendTestsInDirectory(
                PluginTestCaseBase.getTestDataPathBase(), "/resolve/", false,
                JetTestCaseBuilder.emptyFilter, new JetTestCaseBuilder.NamedTestFactory() {


            @NotNull
            @Override
            public Test createTest(@NotNull String dataPath, @NotNull String name, @NotNull File file) {
                return new ResolveBaseTest(dataPath, name);
            }
        }, suite);

        return suite;
    }
}
