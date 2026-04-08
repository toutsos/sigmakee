package com.articulate.sigma.trans;

import com.articulate.sigma.*;
import com.articulate.sigma.parsing.FormulaAST;
import com.articulate.sigma.trans.TPTPGenerationManager;

import org.junit.*;

import java.util.*;

import static org.junit.Assert.*;

/**
 * Diagnostic test to determine WHY formulas in kb.formulaMap are not FormulaAST
 * or have null expr, causing the H2/Expr path to fall back to string translation.
 *
 * The test classifies every formula in formulaMap into one of three buckets:
 *   A) plain Formula   (not a FormulaAST at all)
 *   B) FormulaAST but expr == null
 *   C) FormulaAST with expr != null  (the good case)
 *
 * For buckets A and B it:
 *   1. Prints sample formulas to the console.
 *   2. Re-parses each sample via KIFAST to determine whether parsing CAN produce
 *      a FormulaAST with non-null expr (rules out a KIFAST/SuokifToExpr bug).
 *
 * Run manually:
 *   ant compile.test
 *   java -Xmx10g -cp "build/classes:build/test/classes:lib/*" \
 *     org.junit.runner.JUnitCore com.articulate.sigma.trans.FormulaASTDiagnosticTest
 */
public class FormulaASTDiagnosticTest {

    private static final int SAMPLE_SIZE = 20;

    protected static KB kb;

    @BeforeClass
    public static void setup() {

        System.out.println("\n===== FormulaASTDiagnosticTest: Initializing KB =====");
        TPTPGenerationManager.setSkipBackgroundGeneration(true);
        KBmanager.skipSerialization = true;
        KBmanager.getMgr().initializeOnce();
        kb = KBmanager.getMgr().getKB(KBmanager.getMgr().getPref("sumokbname"));
        assertNotNull("KB should be loaded", kb);
        assertFalse("KB formulaMap should not be empty", kb.formulaMap.isEmpty());
        System.out.println("===== Formula count: " + kb.formulaMap.size() + " =====");
    }

    @Test
    public void testDiagnoseFormulaASTCoverage() {

        List<String> plainFormulaExamples    = new ArrayList<>();
        List<String> nullExprExamples        = new ArrayList<>();
        List<String> goodExamples            = new ArrayList<>();

        int plainFormulaCount = 0;
        int nullExprCount     = 0;
        int goodCount         = 0;

        for (Formula f : kb.formulaMap.values()) {
            if (!(f instanceof FormulaAST)) {
                plainFormulaCount++;
                if (plainFormulaExamples.size() < SAMPLE_SIZE)
                    plainFormulaExamples.add(f.getFormula());
            } else {
                FormulaAST fa = (FormulaAST) f;
                if (fa.expr == null) {
                    nullExprCount++;
                    if (nullExprExamples.size() < SAMPLE_SIZE)
                        nullExprExamples.add(fa.getFormula());
                } else {
                    goodCount++;
                    if (goodExamples.size() < 5)
                        goodExamples.add(fa.getFormula());
                }
            }
        }

        int total = plainFormulaCount + nullExprCount + goodCount;

        System.out.println("\n========================================================");
        System.out.println("  FormulaAST Diagnostic Report");
        System.out.println("  Total formulaMap entries: " + total);
        System.out.printf("  A) plain Formula (not FormulaAST): %d (%.1f%%)%n",
                plainFormulaCount, 100.0 * plainFormulaCount / total);
        System.out.printf("  B) FormulaAST with expr==null:     %d (%.1f%%)%n",
                nullExprCount, 100.0 * nullExprCount / total);
        System.out.printf("  C) FormulaAST with expr!=null:     %d (%.1f%%)%n",
                goodCount, 100.0 * goodCount / total);
        System.out.println("========================================================\n");

        // -- Bucket A: plain Formula --
        if (!plainFormulaExamples.isEmpty()) {
            System.out.println("--- Sample plain Formula (bucket A) ---");
            for (int i = 0; i < plainFormulaExamples.size(); i++)
                System.out.printf("  [A%d] %s%n", i + 1, abbrev(plainFormulaExamples.get(i)));
            System.out.println();
            System.out.println("  Re-parsing bucket-A samples via KIFAST:");
            reparseSamples("A", plainFormulaExamples);
        } else {
            System.out.println("--- Bucket A is empty (no plain Formula objects) ---\n");
        }

        // -- Bucket B: FormulaAST with null expr --
        if (!nullExprExamples.isEmpty()) {
            System.out.println("--- Sample FormulaAST with expr==null (bucket B) ---");
            for (int i = 0; i < nullExprExamples.size(); i++)
                System.out.printf("  [B%d] %s%n", i + 1, abbrev(nullExprExamples.get(i)));
            System.out.println();
            System.out.println("  Re-parsing bucket-B samples via KIFAST:");
            reparseSamples("B", nullExprExamples);
        } else {
            System.out.println("--- Bucket B is empty (all FormulaAST have non-null expr) ---\n");
        }

        // -- Bucket C: good formulas --
        if (!goodExamples.isEmpty()) {
            System.out.println("--- Sample good FormulaAST (bucket C) ---");
            for (int i = 0; i < goodExamples.size(); i++)
                System.out.printf("  [C%d] %s%n", i + 1, abbrev(goodExamples.get(i)));
            System.out.println();
        }

        // Assertions: fail with a helpful message if the good-case coverage is too low.
        double goodFraction = (total == 0) ? 0.0 : (double) goodCount / total;
        System.out.printf("Coverage: %.1f%% of formulas are FormulaAST with non-null expr%n%n", goodFraction * 100);

        // This assertion will fail (and print the diagnostic above) until the root cause is fixed.
        assertTrue(
            "Only " + goodCount + "/" + total + " (" + String.format("%.1f", goodFraction * 100) + "%) "
            + "formulas are FormulaAST with non-null expr. "
            + "plain-Formula=" + plainFormulaCount + ", null-expr FormulaAST=" + nullExprCount
            + ". See console output for samples and re-parse results.",
            goodFraction >= 0.95);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * For each sample formula, parse it fresh via KIFAST and report whether
     * the result is FormulaAST with non-null expr.
     */
    private static void reparseSamples(String bucketTag, List<String> samples) {

        int reparsedGood = 0;
        int reparsedNullExpr = 0;
        int reparsedNotAST = 0;
        int reparsedError = 0;

        for (int i = 0; i < samples.size(); i++) {
            String kif = samples.get(i);
            KIFAST kfast = new KIFAST();
            String err = kfast.parseStatement(kif);
            if (err != null) {
                System.out.printf("    [%s%d] PARSE ERROR: %s — formula: %s%n",
                        bucketTag, i + 1, err, abbrev(kif));
                reparsedError++;
                continue;
            }
            if (kfast.formulaMap.isEmpty()) {
                System.out.printf("    [%s%d] KIFAST produced empty formulaMap for: %s%n",
                        bucketTag, i + 1, abbrev(kif));
                reparsedError++;
                continue;
            }
            FormulaAST result = kfast.formulaMap.values().iterator().next();
            if (result == null) {
                System.out.printf("    [%s%d] KIFAST returned null FormulaAST for: %s%n",
                        bucketTag, i + 1, abbrev(kif));
                reparsedNotAST++;
            } else if (result.expr == null) {
                System.out.printf("    [%s%d] KIFAST produced FormulaAST but expr==null for: %s%n",
                        bucketTag, i + 1, abbrev(kif));
                reparsedNullExpr++;
            } else {
                System.out.printf("    [%s%d] OK — KIFAST produced FormulaAST with expr: %s%n",
                        bucketTag, i + 1, abbrev(result.expr.toKifString()));
                reparsedGood++;
            }
        }
        System.out.printf("  Re-parse summary for bucket %s: good=%d  null-expr=%d  not-AST=%d  error=%d%n%n",
                bucketTag, reparsedGood, reparsedNullExpr, reparsedNotAST, reparsedError);
    }

    private static String abbrev(String s) {
        if (s == null) return "<null>";
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() <= 120 ? s : s.substring(0, 117) + "...";
    }
}
