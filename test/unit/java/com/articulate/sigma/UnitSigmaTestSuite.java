package com.articulate.sigma;

import com.articulate.sigma.persistence.KBPersistenceTest;
import com.articulate.sigma.trans.RegenerateFromH2Test;
import com.articulate.sigma.trans.ExprBasedGenerationTest;
import com.articulate.sigma.SymbolTableTest;
import com.articulate.sigma.KBcacheSymbolTest;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

//This software is released under the GNU Public License
//<http://www.gnu.org/copyleft/gpl.html>.
// Copyright 2019 Infosys
// adam.pease@infosys.com

@RunWith(Suite.class)
@Suite.SuiteClasses({
    FormulaArityCheckTest.class,
    FormulaDeepEqualsTest.class,
    FormulaLogicalEqualityTest.class,
    FormulaPreprocessorComputeVariableTypesTest.class,
    FormulaPreprocessorExprTest.class,
    FormulaPreprocessorFindExplicitTypesTest.class,
    FormulaPreprocessorTest.class,
    FormulaTest.class,
    FormulaUnificationTest.class,
    FormulaUtilTest.class,
    GraphTest.class,
    KBTest.class,
    AffectedFormulasTest.class,
    KBcacheUnitTest.class,
    KBcacheIncrementalTest.class,
    KBcacheParallelBuildTest.class,
    KBcacheLazyTest.class,
    KBPersistenceTest.class,
    KIFTest.class,
    KifFileCheckerTest.class,
    LeoExecutableConfigTest.class,
    // TPTPFileCheckerTest.class,
    KButilitiesTest.class,
    KBmanagerInitTest.class,
    PredVarInstTest.class,
    RowVarTest.class,
    SessionCleanupListenerTest.class,
    TPTPPatchTest.class,
    IncrementalTellPipelineTest.class,
    RegenerateFromH2Test.class,
    ExprBasedGenerationTest.class,
    SymbolTableTest.class,
    KBcacheSymbolTest.class
})
public class UnitSigmaTestSuite extends UnitTestBase {

}
