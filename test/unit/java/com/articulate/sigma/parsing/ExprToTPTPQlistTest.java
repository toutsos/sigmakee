package com.articulate.sigma.parsing;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * M1 comparison test: verifies that the new {@link ExprToTPTP#getQlist(Expr)}
 * fast path produces the same output as the original
 * {@link ExprToTPTP#getQlist(String)} for a representative set of KIF formulas.
 *
 * <p>The old path re-parses the KIF string via ANTLR on every call.  The new
 * path walks an already-built {@link Expr} tree directly — zero ANTLR
 * overhead.  Both must produce identical comma-separated TPTP variable lists.</p>
 */
public class ExprToTPTPQlistTest {

    /** KIF formulas covering the main variable patterns. */
    private static final List<String> FORMULAS = Arrays.asList(
            // No variables
            "(instance Dog Animal)",
            // Single free variable
            "(instance ?X Animal)",
            // Multiple free variables
            "(=> (instance ?X Dog) (instance ?X Animal))",
            // Bound variables (quantified) — should NOT appear in qlist
            "(forall (?X) (instance ?X Animal))",
            // Mix: one bound, one free
            "(=> (forall (?X) (instance ?X Dog)) (instance ?Y Animal))",
            // Row variable
            "(instance @ROW Animal)",
            // Nested quantifier
            "(exists (?X) (and (instance ?X Dog) (exists (?Y) (instance ?Y Cat))))",
            // Biconditional
            "(<=> (instance ?A Dog) (instance ?B Cat))",
            // Function application
            "(equal (SuccessorFn ?N) ?M)",
            // Deeply nested
            "(=> (and (instance ?X Human) (instance ?Y Action)) (agent ?Y ?X))"
    );

    @Test
    public void getQlist_exprMatchesStringPath_allFormulas() {
        for (String kif : FORMULAS) {
            String expected = ExprToTPTP.getQlist(kif).toString();
            // Parse once to get the Expr tree
            Expr expr = parseExpr(kif);
            String actual   = ExprToTPTP.getQlist(expr).toString();
            assertEquals("getQlist mismatch for: " + kif, expected, actual);
        }
    }

    @Test
    public void getQlist_nullExpr_returnsEmpty() {
        assertEquals("", ExprToTPTP.getQlist((Expr) null).toString());
    }

    @Test
    public void getQlist_noFreeVars_returnsEmpty() {
        Expr expr = parseExpr("(forall (?X) (instance ?X Animal))");
        assertEquals("", ExprToTPTP.getQlist(expr).toString());
    }

    @Test
    public void getQlist_singleFreeVar_returnsTranslated() {
        Expr expr = parseExpr("(instance ?X Animal)");
        assertEquals("V__X", ExprToTPTP.getQlist(expr).toString());
    }

    @Test
    public void getQlist_multipleVars_matchesStringPath() {
        String kif = "(=> (instance ?X Dog) (instance ?Y Cat))";
        String fromString = ExprToTPTP.getQlist(kif).toString();
        String fromExpr   = ExprToTPTP.getQlist(parseExpr(kif)).toString();
        assertEquals(fromString, fromExpr);
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private static Expr parseExpr(String kif) {
        SuokifVisitor visitor = SuokifVisitor.parseSentence(kif);
        if (visitor == null || visitor.result == null || visitor.result.isEmpty())
            fail("Failed to parse: " + kif);
        FormulaAST ast = visitor.result.get(0);
        if (ast == null || ast.expr == null)
            fail("No Expr tree for: " + kif);
        return ast.expr;
    }
}
