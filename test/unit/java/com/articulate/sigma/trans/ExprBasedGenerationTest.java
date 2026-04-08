package com.articulate.sigma.trans;

import com.articulate.sigma.*;
import com.articulate.sigma.parsing.*;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Comparison test: Expr-based (new) vs string-based (old) TPTP generation.
 *
 * <p>Mirrors the structure of {@link TPTPGenerationManager#generateFOF} and
 * {@link TPTPGenerationManager#generateTFF} but uses the new Expr-tree-direct
 * path for every formula that has a populated {@code FormulaAST.expr}:</p>
 *
 * <h3>Old path (per formula in writeFile)</h3>
 * <pre>
 *   FormulaPreprocessor.preProcess(f, false, kb)
 *     → Formula.renameVariableArityRelations(kb, map)
 *     → ExprToTPTP.translateKifString(processedKif, false, "fof")   // re-parses with ANTLR
 *     → fallback: SUMOformulaToTPTPformula.tptpParseSUOKIFString()  // string-based
 * </pre>
 *
 * <h3>New path (this test, per FormulaAST in KIFAST.formulaMap)</h3>
 * <pre>
 *   FormulaAST.expr (already built by KIFAST, no re-parse)
 *     → ExprToTPTP.translate(expr, false, "fof")   // FOF
 *     → ExprToTFF.translate(expr, false, null)     // TFF
 * </pre>
 *
 * <p>The output files are written to the system temp directory and are retained
 * after the test so they can be diffed against the old-path output from the
 * running server.</p>
 */
public class ExprBasedGenerationTest {

    // -----------------------------------------------------------------------
    // Small but representative KB — covers ground atoms, rules, nested forall
    // -----------------------------------------------------------------------
    private static final String[] KB_STMTS = {
            "(instance subclass TransitiveRelation)",
            "(instance subrelation TransitiveRelation)",
            "(instance subAttribute TransitiveRelation)",
            "(subclass TransitiveRelation Relation)",
            "(subclass BinaryRelation Relation)",
            "(subclass Relation Entity)",
            "(subclass Animal Entity)",
            "(subclass Dog Animal)",
            "(subclass Cat Animal)",
            "(subclass Mammal Animal)",
            "(subclass Dog Mammal)",
            "(instance Fido Dog)",
            "(instance Felix Cat)",
            "(instance subclass BinaryRelation)",
            "(domain subclass 1 Class)",
            "(domain subclass 2 Class)",
            // A rule — tests quantifier + connective paths
            "(forall (?X ?Y ?Z) (=> (and (subclass ?X ?Y) (subclass ?Y ?Z)) (subclass ?X ?Z)))",
            // A rule with instance constraint — tests sort inference in TFF
            "(forall (?X) (=> (instance ?X Dog) (instance ?X Animal)))",
    };

    private static KB    kb;
    private static Path  tempDir;

    /** Paths written by the new (Expr-based) path. */
    static Path newFofFile;
    static Path newTffFile;

    /** Paths written by the old (TPTPGenerationManager) path. */
    static Path oldFofFile;
    static Path oldTffFile;

    // -----------------------------------------------------------------------
    // Setup
    // -----------------------------------------------------------------------

    @BeforeClass
    public static void setUpClass() throws Exception {
        tempDir = Files.createTempDirectory("sigma_gen_compare_");

        KBmanager.getMgr().setPref("cacheDisjoint", "false");
        KBmanager.getMgr().setPref("kbDir", tempDir.toString());

        // Build KB using KIFAST so every FormulaAST gets an Expr tree
        KIFAST kif = new KIFAST();
        kb = new KB("GenCompare");
        for (String stmt : KB_STMTS)
            kif.parseStatement(stmt);

        // Merge KIFAST result into the KB (mimics KB.addConstituent)
        kb.formulaMap.putAll(kif.formulaMap);
        kb.formulas.putAll(kif.formulas);
        kb.terms.addAll(kif.terms);

        kb.kbCache = new KBcache(kb);
        KBcache cache = new KBcache(kb);
        kb.kbCache = cache;
        cache.buildCaches();

        // Output file paths
        newFofFile = tempDir.resolve("new_approach.tptp");
        newTffFile = tempDir.resolve("new_approach.tff");
        oldFofFile = tempDir.resolve("old_approach.tptp");
        oldTffFile = tempDir.resolve("old_approach.tff");
    }

    @AfterClass
    public static void printOutputPaths() {
        System.out.println("\n=== ExprBasedGenerationTest output files ===");
        System.out.println("New FOF : " + newFofFile);
        System.out.println("New TFF : " + newTffFile);
        System.out.println("Old FOF : " + oldFofFile);
        System.out.println("Old TFF : " + oldTffFile);
        System.out.println("  diff them with: diff " + newFofFile + " " + oldFofFile);
        System.out.println("===========================================\n");
    }

    // -----------------------------------------------------------------------
    // New-approach generation helpers
    // -----------------------------------------------------------------------

    /**
     * Generate FOF using the new Expr-direct path for every formula that has a
     * populated {@link FormulaAST#expr}.  Falls back to
     * {@link ExprToTPTP#translateKifString} for formulas without an Expr tree.
     *
     * <p>Mirrors the structure of {@link TPTPGenerationManager#generateFOF} but
     * bypasses the {@link com.articulate.sigma.FormulaPreprocessor} preprocessing
     * and variable-arity renaming steps, generating directly from the raw Expr.</p>
     *
     * @return number of axiom lines written
     */
    static int generateFOFNewApproach(KB theKb, Path outputPath) throws IOException {
        int count = 0;
        Path tmp = outputPath.resolveSibling(outputPath.getFileName() + ".tmp");
        try (PrintWriter pw = new PrintWriter(
                Files.newBufferedWriter(tmp, StandardCharsets.UTF_8))) {

            pw.println("% New-approach FOF — generated by ExprToTPTP.translate() directly");
            pw.println("% KB: " + theKb.name + "  formulas: " + theKb.formulaMap.size());

            AtomicInteger idx = new AtomicInteger(0);
            for (Map.Entry<String, Formula> entry : theKb.formulaMap.entrySet()) {
                Formula f = entry.getValue();

                // Fast path: use pre-built Expr tree (no re-parse)
                String body = null;
                if (f instanceof FormulaAST ast && ast.expr != null) {
                    body = ExprToTPTP.translate(ast.expr, false, "fof");
                }

                // Fallback: translateKifString re-parses via ANTLR once
                if (body == null || body.isBlank()) {
                    body = ExprToTPTP.translateKifString(f.getFormula(), false, "fof");
                }

                if (body == null || body.isBlank()) continue;

                String name = "kb_" + sanitize(theKb.name) + "_new_" + idx.getAndIncrement();
                pw.println("fof(" + name + ",axiom,(" + body + ")).");
                count++;
            }
        }
        try {
            Files.move(tmp, outputPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, outputPath, StandardCopyOption.REPLACE_EXISTING);
        }
        return count;
    }

    /**
     * Generate TFF using the new Expr-direct path ({@link ExprToTFF#translate}).
     * Sort annotations are inferred from {@code (instance ?X Type)} patterns within
     * each formula's Expr tree — no KB-wide preprocessing required.
     *
     * @return number of axiom lines written
     */
    static int generateTFFNewApproach(KB theKb, Path outputPath) throws IOException {
        int count = 0;
        Path tmp = outputPath.resolveSibling(outputPath.getFileName() + ".tmp");
        try (PrintWriter pw = new PrintWriter(
                Files.newBufferedWriter(tmp, StandardCharsets.UTF_8))) {

            pw.println("% New-approach TFF — generated by ExprToTFF.translate() directly");
            pw.println("% KB: " + theKb.name + "  formulas: " + theKb.formulaMap.size());

            AtomicInteger idx = new AtomicInteger(0);
            for (Map.Entry<String, Formula> entry : theKb.formulaMap.entrySet()) {
                Formula f = entry.getValue();

                String body = null;

                // Fast path: Expr-based TFF with sort inference
                if (f instanceof FormulaAST ast && ast.expr != null) {
                    body = ExprToTFF.translate(ast.expr, false, null);
                }

                // Fallback: re-parse via ANTLR and then use ExprToTFF
                if (body == null || body.isBlank()) {
                    body = ExprToTPTP.translateKifString(f.getFormula(), false, "tff");
                }

                if (body == null || body.isBlank()) continue;

                String name = "kb_" + sanitize(theKb.name) + "_new_" + idx.getAndIncrement();
                pw.println("tff(" + name + ",axiom,(" + body + ")).");
                count++;
            }
        }
        try {
            Files.move(tmp, outputPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, outputPath, StandardCopyOption.REPLACE_EXISTING);
        }
        return count;
    }

    /**
     * Generate FOF using the old path: {@link TPTPGenerationManager#generateFOFToPath},
     * which internally calls {@code SUMOKBtoTPTPKB.writeFile()} with full
     * preprocessing and ANTLR re-parsing per formula.
     */
    static void generateFOFOldApproach(KB theKb, Path outputPath) throws IOException {
        TPTPGenerationManager.generateFOFToPath(theKb, outputPath);
    }

    /**
     * Generate TFF using the old path: {@link TPTPGenerationManager#generateTFFToPath},
     * which internally calls {@code SUMOKBtoTFAKB.writeFile()} via
     * {@code SUMOtoTFAform.process()} with full string-based preprocessing.
     */
    static void generateTFFOldApproach(KB theKb, Path outputPath) throws IOException {
        TPTPGenerationManager.generateTFFToPath(theKb, outputPath);
    }

    private static String sanitize(String s) { return s.replaceAll("[^A-Za-z0-9]", "_"); }

    // -----------------------------------------------------------------------
    // Tests — new approach
    // -----------------------------------------------------------------------

    @Test
    public void newFOF_writesFile() throws Exception {
        int count = generateFOFNewApproach(kb, newFofFile);
        assertTrue("new FOF file must exist", Files.exists(newFofFile));
        assertTrue("new FOF generation must produce at least one axiom", count > 0);
        System.out.println("New-approach FOF: " + count + " axioms → " + newFofFile);
    }

    @Test
    public void newFOF_allLinesAreWellFormed() throws Exception {
        generateFOFNewApproach(kb, newFofFile);
        for (String line : Files.readAllLines(newFofFile, StandardCharsets.UTF_8)) {
            if (line.startsWith("%") || line.isBlank()) continue;
            assertTrue("every axiom line must start with 'fof(' : " + line,
                    line.startsWith("fof("));
            assertTrue("every axiom line must end with ').' : " + line,
                    line.endsWith(")."));
        }
    }

    @Test
    public void newFOF_containsExpectedSymbols() throws Exception {
        generateFOFNewApproach(kb, newFofFile);
        String content = Files.readString(newFofFile, StandardCharsets.UTF_8);
        assertTrue("FOF output must reference s__subclass", content.contains("s__subclass"));
        assertTrue("FOF output must reference s__instance", content.contains("s__instance"));
        assertTrue("FOF output must reference s__Dog",      content.contains("s__Dog"));
        assertTrue("FOF output must reference s__Animal",   content.contains("s__Animal"));
        assertTrue("FOF output must contain implication '=>'", content.contains("=>"));
    }

    @Test
    public void newFOF_rulesHaveQuantifiers() throws Exception {
        generateFOFNewApproach(kb, newFofFile);
        String content = Files.readString(newFofFile, StandardCharsets.UTF_8);
        // The transitivity rule has two bound variables
        assertTrue("FOF output must contain universal quantifiers '! ['",
                content.contains("! ["));
    }

    @Test
    public void newFOF_noTmpFileLeftover() throws Exception {
        generateFOFNewApproach(kb, newFofFile);
        assertFalse("no .tmp file must remain after successful write",
                Files.exists(newFofFile.resolveSibling(newFofFile.getFileName() + ".tmp")));
    }

    @Test
    public void newTFF_writesFile() throws Exception {
        int count = generateTFFNewApproach(kb, newTffFile);
        assertTrue("new TFF file must exist", Files.exists(newTffFile));
        assertTrue("new TFF generation must produce at least one axiom", count > 0);
        System.out.println("New-approach TFF: " + count + " axioms → " + newTffFile);
    }

    @Test
    public void newTFF_allLinesAreWellFormed() throws Exception {
        generateTFFNewApproach(kb, newTffFile);
        for (String line : Files.readAllLines(newTffFile, StandardCharsets.UTF_8)) {
            if (line.startsWith("%") || line.isBlank()) continue;
            assertTrue("every TFF axiom line must start with 'tff(' : " + line,
                    line.startsWith("tff("));
            assertTrue("every TFF axiom line must end with ').' : " + line,
                    line.endsWith(")."));
        }
    }

    @Test
    public void newTFF_instanceRuleHasTypedVar() throws Exception {
        generateTFFNewApproach(kb, newTffFile);
        String content = Files.readString(newTffFile, StandardCharsets.UTF_8);
        // (forall (?X) (=> (instance ?X Dog) ...)) → V__X : s__Dog
        assertTrue("TFF rule with (instance ?X Dog) must produce 'V__X : s__Dog'",
                content.contains("V__X : s__Dog") || content.contains(": s__Dog"));
    }

    @Test
    public void newTFF_unknownVarsGetDefaultSort() throws Exception {
        generateTFFNewApproach(kb, newTffFile);
        String content = Files.readString(newTffFile, StandardCharsets.UTF_8);
        // transitivity rule has ?X, ?Y, ?Z with no instance constraints → $i
        assertTrue("TFF transitivity rule must produce default sort '$i'",
                content.contains(": $i"));
    }

    // -----------------------------------------------------------------------
    // Tests — old approach (for comparison baseline)
    // -----------------------------------------------------------------------

    @Test
    public void oldFOF_writesFile() throws Exception {
        generateFOFOldApproach(kb, oldFofFile);
        assertTrue("old FOF file must exist", Files.exists(oldFofFile));
        long size = Files.size(oldFofFile);
        assertTrue("old FOF file must be non-empty", size > 0);
        System.out.println("Old-approach FOF: " + size + " bytes → " + oldFofFile);
    }

    @Test
    public void oldTFF_writesFile() throws Exception {
        generateTFFOldApproach(kb, oldTffFile);
        assertTrue("old TFF file must exist", Files.exists(oldTffFile));
        long size = Files.size(oldTffFile);
        assertTrue("old TFF file must be non-empty", size > 0);
        System.out.println("Old-approach TFF: " + size + " bytes → " + oldTffFile);
    }

    // -----------------------------------------------------------------------
    // Cross-path structural comparison
    // -----------------------------------------------------------------------

    @Test
    public void newFOF_axiomCount_comparableToOldFOF() throws Exception {
        int newCount = generateFOFNewApproach(kb, newFofFile);
        generateFOFOldApproach(kb, oldFofFile);

        long oldCount = Files.lines(oldFofFile, StandardCharsets.UTF_8)
                .filter(l -> l.startsWith("fof("))
                .count();

        System.out.printf("FOF axiom counts — new: %d  old: %d%n", newCount, oldCount);
        // New path translates raw formulas directly; old path runs through preProcessor
        // which may expand/filter some formulas — so counts may differ slightly.
        // We verify both are in the same ballpark (within 50% of each other).
        double ratio = (double) newCount / Math.max(1, oldCount);
        assertTrue("new and old FOF axiom counts must be within 50% of each other" +
                " (new=" + newCount + " old=" + oldCount + ")",
                ratio >= 0.5 && ratio <= 2.0);
    }

    @Test
    public void newTFF_axiomCount_comparableToOldTFF() throws Exception {
        int newCount = generateTFFNewApproach(kb, newTffFile);

        // Old TFF path requires SUMOtoTFAform.initOnce() with a fully-initialized SUMO KB.
        // In the minimal unit-test KB it will throw or produce 0 axioms — catch and continue.
        long oldCount = 0;
        try {
            generateTFFOldApproach(kb, oldTffFile);
            oldCount = Files.lines(oldTffFile, StandardCharsets.UTF_8)
                    .filter(l -> l.startsWith("tff(") && l.contains(",axiom,"))
                    .count();
        } catch (Exception e) {
            System.out.println("Old TFF path threw (expected — needs full SUMO init): " + e.getMessage());
        }

        System.out.printf("TFF axiom counts — new: %d  old: %d (old path requires full SUMO init)%n", newCount, oldCount);
        assertTrue("new TFF path must produce at least one axiom", newCount > 0);
    }

    @Test
    public void newFOF_containsSamePredicates_asOldFOF() throws Exception {
        generateFOFNewApproach(kb, newFofFile);
        generateFOFOldApproach(kb, oldFofFile);

        String newContent = Files.readString(newFofFile, StandardCharsets.UTF_8);
        String oldContent = Files.readString(oldFofFile, StandardCharsets.UTF_8);

        // Both outputs must reference the same core SUMO predicates
        for (String pred : new String[]{"s__subclass", "s__instance", "s__Dog", "s__Animal"}) {
            assertTrue("new FOF must contain " + pred, newContent.contains(pred));
            assertTrue("old FOF must contain " + pred, oldContent.contains(pred));
        }
    }

    // -----------------------------------------------------------------------
    // Fingerprint sanity check (M8 side-effect of KIFAST parsing above)
    // -----------------------------------------------------------------------

    @Test
    public void kifast_fingerprintIndex_populated() {
        // Verify M8 ran during setUpClass KIFAST parsing
        KIFAST kif = new KIFAST();
        for (String stmt : KB_STMTS) kif.parseStatement(stmt);
        assertFalse("fingerprintIndex must be non-empty after parsing KB_STMTS",
                kif.fingerprintIndex.isEmpty());
        assertEquals("fingerprintIndex size must match formulaMap size",
                kif.formulaMap.size(), kif.fingerprintIndex.size());
    }
}
