package com.articulate.sigma.parsing;

import com.articulate.sigma.KIFAST;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * M8 — Tests for {@link FormulaAST#computeFingerprint} and the alpha-equivalence
 * deduplication in {@link KIFAST#integrateVisitorResult}.
 *
 * <p>All tests run without a live KB.  The Guava MurmurHash3 dependency is
 * exercised directly via the static {@code computeFingerprint} method.</p>
 */
public class FingerprintTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Expr.Atom   atom(String s)          { return new Expr.Atom(s); }
    private static Expr.Var    var(String s)            { return new Expr.Var(s); }
    private static Expr.RowVar rowVar(String s)         { return new Expr.RowVar(s); }
    private static Expr.NumLiteral num(String v)        { return new Expr.NumLiteral(v); }
    private static Expr.StrLiteral str(String v)        { return new Expr.StrLiteral(v); }

    private static Expr.SExpr se(String head, Expr... args) {
        return new Expr.SExpr(new Expr.Atom(head), List.of(args));
    }
    private static Expr.SExpr varList(Expr... vars) {
        return new Expr.SExpr(null, List.of(vars));
    }

    // -----------------------------------------------------------------------
    // Basic sanity
    // -----------------------------------------------------------------------

    @Test
    public void nullExpr_returnsOne() {
        assertEquals(1L, FormulaAST.computeFingerprint(null));
    }

    @Test
    public void sameExpr_sameFingerprint() {
        Expr e1 = se("instance", atom("Fido"), atom("Dog"));
        Expr e2 = se("instance", atom("Fido"), atom("Dog"));
        assertEquals("identical trees must have the same fingerprint",
                FormulaAST.computeFingerprint(e1),
                FormulaAST.computeFingerprint(e2));
    }

    @Test
    public void differentStructure_differentFingerprint() {
        Expr e1 = se("instance", atom("Fido"), atom("Dog"));
        Expr e2 = se("subclass", atom("Dog"), atom("Animal"));
        assertNotEquals("structurally different trees must have different fingerprints",
                FormulaAST.computeFingerprint(e1),
                FormulaAST.computeFingerprint(e2));
    }

    @Test
    public void deterministic_multipleCallsSameResult() {
        Expr expr = se("and",
                se("instance", var("?X"), atom("Dog")),
                se("instance", var("?Y"), atom("Cat")));
        long fp1 = FormulaAST.computeFingerprint(expr);
        long fp2 = FormulaAST.computeFingerprint(expr);
        assertEquals("fingerprint must be deterministic", fp1, fp2);
    }

    // -----------------------------------------------------------------------
    // Alpha-equivalence: different variable names → same fingerprint
    // -----------------------------------------------------------------------

    @Test
    public void alphaEquivalent_differentVarNames_sameFingerprint() {
        // (instance ?X Dog) and (instance ?Y Dog) are alpha-equivalent
        Expr e1 = se("instance", var("?X"), atom("Dog"));
        Expr e2 = se("instance", var("?Y"), atom("Dog"));
        assertEquals("alpha-equivalent formulas must share a fingerprint",
                FormulaAST.computeFingerprint(e1),
                FormulaAST.computeFingerprint(e2));
    }

    @Test
    public void alphaEquivalent_forall_differentVarNames_sameFingerprint() {
        // (forall (?X) (instance ?X Dog))
        // (forall (?Z) (instance ?Z Dog))
        Expr e1 = se("forall", varList(var("?X")),
                se("instance", var("?X"), atom("Dog")));
        Expr e2 = se("forall", varList(var("?Z")),
                se("instance", var("?Z"), atom("Dog")));
        assertEquals("alpha-equivalent forall formulas must share a fingerprint",
                FormulaAST.computeFingerprint(e1),
                FormulaAST.computeFingerprint(e2));
    }

    @Test
    public void alphaEquivalent_multiVar_sameFingerprint() {
        // (=> (instance ?A Dog) (instance ?A Mammal))
        // (=> (instance ?B Dog) (instance ?B Mammal))
        Expr e1 = se("=>",
                se("instance", var("?A"), atom("Dog")),
                se("instance", var("?A"), atom("Mammal")));
        Expr e2 = se("=>",
                se("instance", var("?B"), atom("Dog")),
                se("instance", var("?B"), atom("Mammal")));
        assertEquals("alpha-equivalent => formulas must share a fingerprint",
                FormulaAST.computeFingerprint(e1),
                FormulaAST.computeFingerprint(e2));
    }

    @Test
    public void notAlphaEquivalent_differentStructure_differentFingerprint() {
        // (=> (instance ?X Dog) (instance ?X Mammal))
        // (=> (instance ?X Mammal) (instance ?X Dog))  — args swapped in consequent
        Expr e1 = se("=>",
                se("instance", var("?X"), atom("Dog")),
                se("instance", var("?X"), atom("Mammal")));
        Expr e2 = se("=>",
                se("instance", var("?X"), atom("Mammal")),
                se("instance", var("?X"), atom("Dog")));
        assertNotEquals("structurally different formulas must have different fingerprints",
                FormulaAST.computeFingerprint(e1),
                FormulaAST.computeFingerprint(e2));
    }

    @Test
    public void notAlphaEquivalent_differentVarSharing_differentFingerprint() {
        // (=> (instance ?X Dog) (instance ?X Mammal))  — ?X appears twice
        // (=> (instance ?X Dog) (instance ?Y Mammal))  — two distinct vars
        Expr e1 = se("=>",
                se("instance", var("?X"), atom("Dog")),
                se("instance", var("?X"), atom("Mammal")));
        Expr e2 = se("=>",
                se("instance", var("?X"), atom("Dog")),
                se("instance", var("?Y"), atom("Mammal")));
        assertNotEquals("different variable sharing patterns must produce different fingerprints",
                FormulaAST.computeFingerprint(e1),
                FormulaAST.computeFingerprint(e2));
    }

    // -----------------------------------------------------------------------
    // Leaf nodes
    // -----------------------------------------------------------------------

    @Test
    public void numLiteral_same_sameFingerprint() {
        assertEquals(FormulaAST.computeFingerprint(num("42")),
                     FormulaAST.computeFingerprint(num("42")));
    }

    @Test
    public void numLiteral_different_differentFingerprint() {
        assertNotEquals(FormulaAST.computeFingerprint(num("42")),
                        FormulaAST.computeFingerprint(num("43")));
    }

    @Test
    public void strLiteral_same_sameFingerprint() {
        assertEquals(FormulaAST.computeFingerprint(str("hello")),
                     FormulaAST.computeFingerprint(str("hello")));
    }

    @Test
    public void rowVar_alphaEquivalent() {
        // Two row vars in the same position are alpha-equivalent
        Expr e1 = se("foo", rowVar("@ROW"));
        Expr e2 = se("foo", rowVar("@OTHER"));
        assertEquals("alpha-equivalent row vars must share a fingerprint",
                FormulaAST.computeFingerprint(e1),
                FormulaAST.computeFingerprint(e2));
    }

    // -----------------------------------------------------------------------
    // FormulaAST.fingerprint field is populated
    // -----------------------------------------------------------------------

    @Test
    public void fingerprintField_defaultZero() {
        FormulaAST f = new FormulaAST();
        assertEquals("fingerprint field must default to 0", 0L, f.fingerprint);
    }

    // -----------------------------------------------------------------------
    // KIFAST alpha-dedup via fingerprintIndex
    // -----------------------------------------------------------------------

    @Test
    public void kifast_stringDuplicate_stillDetected() {
        // String-level duplicate must still be caught even with M8 code present
        KIFAST kif = new KIFAST();
        kif.parseStatement("(instance Fido Dog)");
        kif.parseStatement("(instance Fido Dog)");
        assertEquals("string-level duplicate must not be added twice",
                1, kif.formulaMap.size());
    }

    @Test
    public void kifast_alphaDuplicate_detectedViaDifferentVarNames() {
        // (instance ?X Dog) and (instance ?Y Dog) are alpha-equivalent
        KIFAST kif = new KIFAST();
        kif.parseStatement("(instance ?X Dog)");
        kif.parseStatement("(instance ?Y Dog)");
        // Both are free-variable formulas: ?X and ?Y are just differently-named
        // placeholders — the second should be detected as alpha-equivalent
        assertEquals("alpha-equivalent duplicate must be filtered out",
                1, kif.formulaMap.size());
    }

    @Test
    public void kifast_nonAlphaDuplicate_bothAdmitted() {
        // (instance Fido Dog) and (instance Felix Cat) — structurally distinct
        KIFAST kif = new KIFAST();
        kif.parseStatement("(instance Fido Dog)");
        kif.parseStatement("(instance Felix Cat)");
        assertEquals("structurally distinct formulas must both be added",
                2, kif.formulaMap.size());
    }

    @Test
    public void kifast_fingerprintIndex_populated() {
        KIFAST kif = new KIFAST();
        kif.parseStatement("(subclass Dog Animal)");
        assertFalse("fingerprintIndex must be non-empty after parse",
                kif.fingerprintIndex.isEmpty());
    }

    @Test
    public void kifast_fingerprintIndex_containsCorrectMapping() {
        KIFAST kif = new KIFAST();
        kif.parseStatement("(subclass Dog Animal)");
        // The only formula's fingerprint must map back to its KIF string
        for (Long fp : kif.fingerprintIndex.keySet()) {
            String kifStr = kif.fingerprintIndex.get(fp);
            assertTrue("fingerprintIndex value must be in formulaMap",
                    kif.formulaMap.containsKey(kifStr));
        }
    }

    @Test
    public void kifast_alphaDedup_warningEmitted() {
        KIFAST kif = new KIFAST();
        kif.parseStatement("(instance ?X Dog)");
        kif.parseStatement("(instance ?Y Dog)");
        boolean hasAlphaWarning = kif.warningSet.stream()
                .anyMatch(w -> w.contains("Alpha-equivalent") || w.contains("duplicate"));
        assertTrue("a warning must be emitted for alpha-equivalent duplicate",
                hasAlphaWarning);
    }

    @Test
    public void kifast_alphaDedup_forall_singleEntry() {
        // The two forall formulas are alpha-equivalent under M8
        KIFAST kif = new KIFAST();
        kif.parseStatement("(forall (?X) (instance ?X Dog))");
        kif.parseStatement("(forall (?Z) (instance ?Z Dog))");
        assertEquals("alpha-equivalent forall formulas must yield a single formulaMap entry",
                1, kif.formulaMap.size());
    }
}
