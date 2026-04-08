package com.articulate.sigma.parsing;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ExprToTFF}.
 *
 * <p>All tests run without a live KB (null KB is acceptable — numeric subclass
 * checks skip gracefully and unknown sorts default to {@code $i}).
 * Tests verify sort inference, quantifier annotation, connective translation,
 * and application formatting — the core TFF-specific behaviour that differs
 * from the FOF path in {@link ExprToTPTP}.</p>
 */
public class ExprToTFFTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Expr.Atom     atom(String s)          { return new Expr.Atom(s); }
    private static Expr.Var      var(String s)            { return new Expr.Var(s); }
    private static Expr.RowVar   rowVar(String s)         { return new Expr.RowVar(s); }
    private static Expr.NumLiteral num(String v)          { return new Expr.NumLiteral(v); }
    private static Expr.StrLiteral str(String v)          { return new Expr.StrLiteral(v); }

    private static Expr.SExpr se(String head, Expr... args) {
        return new Expr.SExpr(new Expr.Atom(head), List.of(args));
    }
    /** Null-head SExpr (quantifier variable list). */
    private static Expr.SExpr varList(Expr... vars) {
        return new Expr.SExpr(null, List.of(vars));
    }

    // -----------------------------------------------------------------------
    // translateSortName — pure SUMO→TFF sort mapping
    // -----------------------------------------------------------------------

    @Test
    public void sortName_integer() {
        assertEquals("$int", ExprToTFF.translateSortName("Integer", null));
    }

    @Test
    public void sortName_realNumber() {
        assertEquals("$real", ExprToTFF.translateSortName("RealNumber", null));
    }

    @Test
    public void sortName_rationalNumber() {
        assertEquals("$rat", ExprToTFF.translateSortName("RationalNumber", null));
    }

    @Test
    public void sortName_sumoClass() {
        assertEquals("s__Dog", ExprToTFF.translateSortName("Dog", null));
    }

    @Test
    public void sortName_null_returnsDefaultSort() {
        assertEquals("$i", ExprToTFF.translateSortName(null, null));
    }

    @Test
    public void sortName_empty_returnsDefaultSort() {
        assertEquals("$i", ExprToTFF.translateSortName("", null));
    }

    @Test
    public void sortName_dollarI_passthrough() {
        assertEquals("$i", ExprToTFF.translateSortName("$i", null));
    }

    @Test
    public void sortName_tType_passthrough() {
        assertEquals("$tType", ExprToTFF.translateSortName("$tType", null));
    }

    // -----------------------------------------------------------------------
    // inferVarSorts — single-pass sort collection
    // -----------------------------------------------------------------------

    @Test
    public void inferVarSorts_singleInstance() {
        // (instance ?X Dog)
        Expr expr = se("instance", var("?X"), atom("Dog"));
        Map<String, String> sorts = ExprToTFF.inferVarSorts(expr, null);
        assertEquals("s__Dog", sorts.get("?X"));
    }

    @Test
    public void inferVarSorts_firstWins() {
        // (and (instance ?X Dog) (instance ?X Animal)) — first binding wins
        Expr expr = se("and",
                se("instance", var("?X"), atom("Dog")),
                se("instance", var("?X"), atom("Animal")));
        Map<String, String> sorts = ExprToTFF.inferVarSorts(expr, null);
        assertEquals("s__Dog", sorts.get("?X"));
    }

    @Test
    public void inferVarSorts_nestedQuantifier() {
        // (forall (?X ?Y) (and (instance ?X Dog) (instance ?Y Cat)))
        Expr body = se("and",
                se("instance", var("?X"), atom("Dog")),
                se("instance", var("?Y"), atom("Cat")));
        Expr expr = se("forall", varList(var("?X"), var("?Y")), body);
        Map<String, String> sorts = ExprToTFF.inferVarSorts(expr, null);
        assertEquals("s__Dog", sorts.get("?X"));
        assertEquals("s__Cat", sorts.get("?Y"));
    }

    @Test
    public void inferVarSorts_integer() {
        // (instance ?N Integer)
        Expr expr = se("instance", var("?N"), atom("Integer"));
        Map<String, String> sorts = ExprToTFF.inferVarSorts(expr, null);
        assertEquals("$int", sorts.get("?N"));
    }

    @Test
    public void inferVarSorts_noConstraints() {
        // (likes ?X ?Y) — no instance constraints
        Expr expr = se("likes", var("?X"), var("?Y"));
        Map<String, String> sorts = ExprToTFF.inferVarSorts(expr, null);
        assertTrue("no sorts inferred without (instance ...) pattern",
                sorts.isEmpty());
    }

    // -----------------------------------------------------------------------
    // translateExpr — leaf nodes
    // -----------------------------------------------------------------------

    @Test
    public void translateExpr_var_noSort() {
        Map<String, String> noSorts = Map.of();
        assertEquals("V__X", ExprToTFF.translateExpr(var("?X"), false, noSorts));
    }

    @Test
    public void translateExpr_rowVar() {
        Map<String, String> noSorts = Map.of();
        assertEquals("V__ROW", ExprToTFF.translateExpr(rowVar("@ROW"), false, noSorts));
    }

    @Test
    public void translateExpr_numLiteral() {
        Map<String, String> noSorts = Map.of();
        assertEquals("42", ExprToTFF.translateExpr(num("42"), false, noSorts));
    }

    @Test
    public void translateExpr_numLiteral_negative() {
        Map<String, String> noSorts = Map.of();
        assertEquals("-3.14", ExprToTFF.translateExpr(num("-3.14"), false, noSorts));
    }

    // -----------------------------------------------------------------------
    // translate — connectives (no KB needed)
    // -----------------------------------------------------------------------

    @Test
    public void translate_not() {
        // (not (likes a b)) → ~(s__likes(s__a,s__b))
        Expr expr = se("not", se("likes", atom("a"), atom("b")));
        String result = ExprToTFF.translate(expr, false, null);
        assertTrue("not should produce ~(...)", result.contains("~("));
    }

    @Test
    public void translate_and() {
        Expr expr = se("and",
                se("instance", atom("Fido"), atom("Dog")),
                se("instance", atom("Felix"), atom("Cat")));
        String result = ExprToTFF.translate(expr, false, null);
        assertTrue("and should produce ' & '", result.contains(" & "));
    }

    @Test
    public void translate_or() {
        Expr expr = se("or",
                se("instance", atom("Fido"), atom("Dog")),
                se("instance", atom("Fido"), atom("Cat")));
        String result = ExprToTFF.translate(expr, false, null);
        assertTrue("or should produce ' | '", result.contains(" | "));
    }

    @Test
    public void translate_implies() {
        Expr expr = se("=>",
                se("instance", var("?X"), atom("Dog")),
                se("instance", var("?X"), atom("Animal")));
        String result = ExprToTFF.translate(expr, false, null);
        assertTrue("=> should produce '=>'", result.contains(" => "));
    }

    @Test
    public void translate_biconditional() {
        Expr expr = se("<=>",
                atom("p"),
                atom("q"));
        String result = ExprToTFF.translate(expr, false, null);
        // <=> expands to (p => q) & (q => p)
        assertTrue("<=> should expand to conjunction of implications",
                result.contains(" => ") && result.contains(" & "));
    }

    @Test
    public void translate_equal() {
        Expr expr = se("equal", atom("a"), atom("b"));
        String result = ExprToTFF.translate(expr, false, null);
        assertTrue("equal should produce ' = '", result.contains(" = "));
    }

    // -----------------------------------------------------------------------
    // translate — quantifiers with typed variable lists
    // -----------------------------------------------------------------------

    @Test
    public void translate_forall_unknownSort_usesDefaultSort() {
        // (forall (?X) (likes ?X ?X)) — no instance constraint → $i
        Expr body = se("likes", var("?X"), var("?X"));
        Expr expr = se("forall", varList(var("?X")), body);
        String result = ExprToTFF.translate(expr, false, null);
        assertTrue("forall must produce '! ['", result.contains("! ["));
        assertTrue("untyped variable must use $i sort", result.contains(": $i"));
    }

    @Test
    public void translate_forall_inferredSort() {
        // (forall (?X) (and (instance ?X Dog) (likes ?X ?X)))
        Expr body = se("and",
                se("instance", var("?X"), atom("Dog")),
                se("likes", var("?X"), var("?X")));
        Expr expr = se("forall", varList(var("?X")), body);
        String result = ExprToTFF.translate(expr, false, null);
        assertTrue("typed variable must use s__Dog sort",
                result.contains("V__X : s__Dog"));
    }

    @Test
    public void translate_exists_unknownSort() {
        Expr body = se("likes", var("?X"), atom("Fido"));
        Expr expr = se("exists", varList(var("?X")), body);
        String result = ExprToTFF.translate(expr, false, null);
        assertTrue("exists must produce '? ['", result.contains("? ["));
        assertTrue("untyped variable must use $i sort", result.contains(": $i"));
    }

    @Test
    public void translate_forall_multipleVars_differentSorts() {
        // (forall (?X ?N) (and (instance ?X Dog) (instance ?N Integer) (foo ?X ?N)))
        Expr body = se("and",
                se("instance", var("?X"), atom("Dog")),
                se("instance", var("?N"), atom("Integer")),
                se("foo", var("?X"), var("?N")));
        Expr expr = se("forall", varList(var("?X"), var("?N")), body);
        String result = ExprToTFF.translate(expr, false, null);
        assertTrue("?X should be s__Dog", result.contains("V__X : s__Dog"));
        assertTrue("?N should be $int",   result.contains("V__N : $int"));
    }

    // -----------------------------------------------------------------------
    // translate — free variable wrapping
    // -----------------------------------------------------------------------

    @Test
    public void translate_freeVars_axiom_wrappedWithForall() {
        // (instance Fido ?C) has free var ?C → should be wrapped with ! [...]
        Expr expr = se("instance", atom("Fido"), var("?C"));
        String result = ExprToTFF.translate(expr, false, null);
        assertTrue("free variables in axiom mode should be wrapped with ! [",
                result.contains("! ["));
    }

    @Test
    public void translate_freeVars_query_wrappedWithExists() {
        // (instance Fido ?C) in query mode → ? [...]
        Expr expr = se("instance", atom("Fido"), var("?C"));
        String result = ExprToTFF.translate(expr, true, null);
        assertTrue("free variables in query mode should be wrapped with ? [",
                result.contains("? ["));
    }

    @Test
    public void translate_noFreeVars_noQuantifierWrapper() {
        // (instance Fido Dog) — no variables → no quantifier wrapper
        Expr expr = se("instance", atom("Fido"), atom("Dog"));
        String result = ExprToTFF.translate(expr, false, null);
        assertFalse("ground formula must not be wrapped with a quantifier",
                result.contains("! [") || result.contains("? ["));
    }

    // -----------------------------------------------------------------------
    // translate — application (relation/function call)
    // -----------------------------------------------------------------------

    @Test
    public void translate_groundAtom() {
        // (instance Fido Dog) → s__instance(s__Fido,s__Dog)
        Expr expr = se("instance", atom("Fido"), atom("Dog"));
        String result = ExprToTFF.translate(expr, false, null);
        assertTrue("should contain s__instance", result.contains("s__instance"));
        assertTrue("should contain s__Fido",     result.contains("s__Fido"));
        assertTrue("should contain s__Dog",       result.contains("s__Dog"));
    }

    @Test
    public void translate_numericLiteralInApplication() {
        // (age Fido 5) — number 5 should pass through as literal in TFF
        Expr expr = se("age", atom("Fido"), num("5"));
        String result = ExprToTFF.translate(expr, false, null);
        assertTrue("number 5 must appear as literal in TFF",
                result.contains("5"));
        assertFalse("TFF should not have n__ prefix for numbers",
                result.contains("n__5"));
    }
}
