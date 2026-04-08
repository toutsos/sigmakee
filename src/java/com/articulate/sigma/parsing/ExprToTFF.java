package com.articulate.sigma.parsing;

import com.articulate.sigma.Formula;
import com.articulate.sigma.KB;

import java.util.*;
import java.util.stream.Collectors;

/**
 * M6 — Expr-based TFF (Typed First-order Form) translation.
 *
 * <p>This is a NEW class alongside the existing string-based
 * {@link com.articulate.sigma.trans.SUMOtoTFAform}; it does not modify that
 * class.  When a {@link FormulaAST#expr} tree is available, callers can use
 * {@link #translate} to skip the slow ANTLR re-parse and string manipulation
 * pipeline that {@code SUMOtoTFAform.process()} performs.</p>
 *
 * <h3>Key difference from FOF (ExprToTPTP)</h3>
 * <p>TFF requires every bound and free variable to carry a sort annotation:
 * <pre>
 *   FOF:  ! [V__X,V__Y] : body
 *   TFF:  ! [V__X : s__Dog, V__Y : $i] : body
 * </pre>
 * This class infers sort annotations in a single pre-pass over the Expr tree
 * by collecting {@code (instance ?X Type)} constraints, then propagates them
 * through the quantifier traversal.  Variables with no inferred sort receive
 * the TFF catch-all sort {@code $i}.</p>
 *
 * <h3>Sort mapping</h3>
 * <ul>
 *   <li>Integer (and subclasses) → {@code $int}</li>
 *   <li>RealNumber → {@code $real}</li>
 *   <li>RationalNumber → {@code $rat}</li>
 *   <li>Any other SUMO class → {@code s__ClassName}</li>
 *   <li>Unknown / unresolved → {@code $i}</li>
 * </ul>
 *
 * <h3>Body translation</h3>
 * <p>The formula body (atoms, connectives, applications) uses the same
 * translation rules as {@link ExprToTPTP} with {@code lang="tff"}.  Quantifier
 * variable lists are the only part produced differently.</p>
 *
 * <h3>Fallback</h3>
 * <p>Callers should fall back to
 * {@link com.articulate.sigma.trans.SUMOtoTFAform#process(Formula, boolean)}
 * when {@code FormulaAST.expr == null} or when numeric type constraints
 * require the full pre-processing pipeline.</p>
 */
public class ExprToTFF {

    public static boolean debug = false;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Translate an {@link Expr} tree to a TFF formula string.
     *
     * <p>Variable sort annotations are inferred from {@code (instance ?X Type)}
     * patterns within the formula.  Variables not constrained by such patterns
     * receive the default sort {@code $i}.</p>
     *
     * @param expr  the formula tree (must not be null)
     * @param query {@code true} for query mode (free vars get {@code ?})
     * @param kb    knowledge base used to resolve numeric subclass sorts;
     *              may be {@code null} (falls back to {@code $i})
     * @return TFF formula string
     */
    public static String translate(Expr expr, boolean query, KB kb) {
        if (expr == null) return "";
        Map<String, String> varSorts = inferVarSorts(expr, kb);
        String body = translateExpr(expr, false, varSorts);
        Set<String> freeVars = ExprToTPTP.collectFreeVars(expr);
        if (!freeVars.isEmpty()) {
            String quantStr = query ? "? [" : "! [";
            String varList = freeVars.stream()
                    .map(v -> ExprToTPTP.translateVarName(v) + " : "
                            + varSorts.getOrDefault(v, "$i"))
                    .collect(Collectors.joining(","));
            return "( " + quantStr + varList + "] : (" + body + " ) )";
        }
        return body;
    }

    /**
     * Map a SUMO class name to the corresponding TFF sort string.
     *
     * <p>Mirrors {@code SUMOKBtoTFAKB.translateSort()} but is a pure static
     * function usable without an instance.  Numeric subclass checks use
     * {@link KB#isSubclass} when {@code kb} is non-null.</p>
     *
     * @param sumoType the SUMO class name (e.g. {@code "Dog"}, {@code "Integer"})
     * @param kb       KB for subclass checks; may be {@code null}
     * @return TFF sort string (e.g. {@code "$int"}, {@code "s__Dog"}, {@code "$i"})
     */
    public static String translateSortName(String sumoType, KB kb) {
        if (sumoType == null || sumoType.isEmpty()) return "$i";
        switch (sumoType) {
            case "$i":    case "$tType": return sumoType;
            case "Integer":      return "$int";
            case "RealNumber":   return "$real";
            case "RationalNumber": return "$rat";
        }
        if (kb != null) {
            if (kb.isSubclass(sumoType, "Integer"))        return "$int";
            if (kb.isSubclass(sumoType, "RationalNumber")) return "$rat";
            if (kb.isSubclass(sumoType, "RealNumber"))     return "$real";
        }
        return Formula.TERM_SYMBOL_PREFIX + sumoType;
    }

    // -----------------------------------------------------------------------
    // Sort inference
    // -----------------------------------------------------------------------

    /**
     * Single-pass BFS over {@code expr} collecting variable sort constraints
     * from {@code (instance ?X Type)} patterns.  First occurrence wins
     * (most-specific type already present).
     */
    static Map<String, String> inferVarSorts(Expr expr, KB kb) {
        Map<String, String> sorts = new LinkedHashMap<>();
        collectVarSorts(expr, sorts, kb);
        return sorts;
    }

    private static void collectVarSorts(Expr expr, Map<String, String> sorts, KB kb) {
        if (!(expr instanceof Expr.SExpr se)) return;
        String head = se.headName();
        // (instance ?X Type) → type annotation for ?X
        if ("instance".equals(head) && se.args().size() == 2) {
            Expr arg1 = se.args().get(0);
            Expr arg2 = se.args().get(1);
            if (arg1 instanceof Expr.Var v && arg2 instanceof Expr.Atom a) {
                sorts.putIfAbsent(v.name(), translateSortName(a.name(), kb));
            }
        }
        // Recurse into all sub-expressions
        if (se.head() != null) collectVarSorts(se.head(), sorts, kb);
        for (Expr a : se.args())  collectVarSorts(a, sorts, kb);
    }

    // -----------------------------------------------------------------------
    // Translation — fully recursive (own traversal so quantifier overrides
    // propagate into nested forall/exists without calling back into ExprToTPTP)
    // -----------------------------------------------------------------------

    /**
     * Recursively translate an {@link Expr} node to TFF.
     *
     * @param isHead {@code true} when this node is in predicate/function head position
     */
    static String translateExpr(Expr expr, boolean isHead, Map<String, String> varSorts) {
        return switch (expr) {
            case Expr.Var      v  -> ExprToTPTP.translateVarName(v.name());
            case Expr.RowVar   rv -> ExprToTPTP.translateVarName(rv.name());
            case Expr.NumLiteral n -> n.value(); // TFF: numbers pass through as literals
            case Expr.StrLiteral s ->
                    s.value().replaceAll("[\n\t\r\f]", " ").replaceAll("'", "");
            case Expr.Atom     a  -> ExprToTPTP.translateAtom(a.name(), isHead, "tff");
            case Expr.SExpr    se -> translateSExpr(se, varSorts);
        };
    }

    private static String translateSExpr(Expr.SExpr se, Map<String, String> varSorts) {
        String headName = se.headName();
        if (headName == null) {
            // Null-head var list (inside quantifier) — translate args as comma list
            return se.args().stream()
                    .map(a -> translateExpr(a, false, varSorts))
                    .collect(Collectors.joining(","));
        }
        return switch (headName) {
            case "not" -> {
                if (se.args().size() != 1) yield tffError("not", se);
                yield "~(" + translateExpr(se.args().get(0), false, varSorts) + ")";
            }
            case "and" -> {
                if (se.args().size() < 2) yield tffError("and", se);
                yield "(" + se.args().stream()
                        .map(a -> translateExpr(a, false, varSorts))
                        .collect(Collectors.joining(" & ")) + ")";
            }
            case "or" -> {
                if (se.args().size() < 2) yield tffError("or", se);
                yield "(" + se.args().stream()
                        .map(a -> translateExpr(a, false, varSorts))
                        .collect(Collectors.joining(" | ")) + ")";
            }
            case "xor" -> {
                if (se.args().size() < 2) yield tffError("xor", se);
                yield "(" + se.args().stream()
                        .map(a -> translateExpr(a, false, varSorts))
                        .collect(Collectors.joining(" <~> ")) + ")";
            }
            case "=>" -> {
                if (se.args().size() != 2) yield tffError("=>", se);
                String ant = translateExpr(se.args().get(0), false, varSorts);
                String con = translateExpr(se.args().get(1), false, varSorts);
                yield "(" + ant + " => " + con + ")";
            }
            case "<=>" -> {
                if (se.args().size() != 2) yield tffError("<=>", se);
                String lhs = translateExpr(se.args().get(0), false, varSorts);
                String rhs = translateExpr(se.args().get(1), false, varSorts);
                yield "((" + lhs + " => " + rhs + ") & (" + rhs + " => " + lhs + "))";
            }
            case "equal" -> {
                if (se.args().size() != 2) yield tffError("equal", se);
                String lhs = translateExpr(se.args().get(0), false, varSorts);
                String rhs = translateExpr(se.args().get(1), false, varSorts);
                yield "(" + lhs + " = " + rhs + ")";
            }
            case "forall" -> translateQuantifier("! ", se, varSorts);
            case "exists" -> translateQuantifier("? ", se, varSorts);
            default       -> translateApplication(se, varSorts);
        };
    }

    /**
     * Translate a quantified sentence with typed variable list.
     * {@code (forall (?X ?Y) body)} → {@code ( ! [V__X : sort, V__Y : $i] : (body))}
     */
    private static String translateQuantifier(String quantOp, Expr.SExpr se,
                                               Map<String, String> varSorts) {
        if (se.args().size() != 2) return tffError(se.headName(), se);
        Expr varListExpr = se.args().get(0);
        Expr body        = se.args().get(1);

        List<String> tptpVars = new ArrayList<>();
        if (varListExpr instanceof Expr.SExpr varSe) {
            for (Expr v : varSe.args()) {
                String raw = switch (v) {
                    case Expr.Var   vv -> vv.name();
                    case Expr.RowVar rv -> rv.name();
                    default            -> null;
                };
                if (raw != null) {
                    String sort = varSorts.getOrDefault(raw, "$i");
                    tptpVars.add(ExprToTPTP.translateVarName(raw) + " : " + sort);
                }
            }
        }
        if (tptpVars.isEmpty()) return translateExpr(body, false, varSorts);
        String varList = String.join(",", tptpVars);
        return "(" + quantOp + "[" + varList + "] : ("
                + translateExpr(body, false, varSorts) + "))";
    }

    /** Translate a relation / function application. */
    private static String translateApplication(Expr.SExpr se, Map<String, String> varSorts) {
        String head = translateExpr(se.head(), true, varSorts);
        if (se.args().isEmpty()) return head + "()";
        String args = se.args().stream()
                .map(a -> translateExpr(a, false, varSorts))
                .collect(Collectors.joining(","));
        return head + "(" + args + ")";
    }

    private static String tffError(String op, Expr.SExpr se) {
        System.err.println("ExprToTFF: malformed " + op + ": " + se.toKifString());
        return "/* tff-error:" + op + " */";
    }
}
