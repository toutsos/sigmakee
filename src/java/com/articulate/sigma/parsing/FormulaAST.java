package com.articulate.sigma.parsing;

import com.articulate.sigma.Formula;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import org.antlr.v4.runtime.ParserRuleContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FormulaAST extends Formula {

    // arguments to relations in order to find the types of arg in a second pass
    // first key is a relation name, interior key is argument number starting at 1
    // transient: ANTLR ParserRuleContext objects are not Kryo-serializable
    public transient Map<String, Map<Integer, Set<SuokifParser.ArgumentContext>>> argMap = new HashMap<>();

    // all the equality statements in a formula.  The interior ArrayList must have
    // only two elements, one for each side of the equation
    // transient: ANTLR ParserRuleContext objects are not Kryo-serializable
    public transient List<List<SuokifParser.TermContext>> eqList = new ArrayList<>();

    // a map of all variables that have an explicit type declaration
    public Map<String,Set<String>> explicitTypes = new HashMap<>();

    // a map of variables and all their inferred types
    public Map<String,Set<String>> varTypes = new HashMap<>();

    // transient: ANTLR ParserRuleContext objects are not Kryo-serializable
    public transient Set<ParserRuleContext> rowvarLiterals = new HashSet<>(); // this can have a RelsentContext, FuntermContext,
      // as well as ForallContext or ExistsContext for vars in a quantifier list

    public Map<String,ArgStruct> constants = new HashMap<>(); // constants as arguments and their enclosing literal

    public Map<String,Set<RowStruct>> rowVarStructs = new HashMap<>(); // row var keys

    //public HashMap<String,String> predVarSub = new HashMap<>();

    // transient: ANTLR SentenceContext is not Kryo-serializable
    public transient SuokifParser.SentenceContext parsedFormula = null;

    /** Structured AST representation of this formula (Phase 1+). Null until SuokifVisitor populates it. */
    public Expr expr = null;

    /**
     * M8 — Alpha-equivalence fingerprint.
     *
     * <p>Computed by {@link #computeFingerprint(Expr)} after the Expr tree is
     * built.  Two formulas with the same fingerprint are alpha-equivalent (same
     * logical structure, differing only in variable names).  A value of
     * {@code 0L} means the fingerprint has not been computed yet.</p>
     */
    public long fingerprint = 0L;

    public boolean isDoc = false; // a documentation statement that is excluded from theorem proving
    public boolean isRule = false;
    public boolean containsNumber = false;

    public Set<String> antecedentTerms = new HashSet<>();
    public Set<String> consequentTerms = new HashSet<>();

    public String strForm = null; // a String version of this modified formula

    /** ***************************************************************
     */
    public FormulaAST() {

    }

    /** ***************************************************************
     */
    public FormulaAST(FormulaAST f) {

        this.endLine = f.endLine;
        this.startLine = f.startLine;
        this.sourceFile = f.sourceFile;
        this.setFormula(f.getFormula());
        this.expr = f.expr; // Expr nodes are immutable records — safe to share reference
        this.allVarsPairCache.addAll(f.allVarsPairCache);
        this.quantVarsCache.addAll(f.quantVarsCache);
        this.unquantVarsCache.addAll(f.unquantVarsCache);
        this.existVarsCache.addAll(f.existVarsCache);
        this.univVarsCache.addAll(f.univVarsCache);
        this.termCache.addAll(f.termCache);
        if (f.predVarCache != null) {
            this.predVarCache = new HashSet<>();
            this.predVarCache.addAll(f.predVarCache);
        }
        if (f.rowVarCache != null) {
            this.rowVarCache = new HashSet<>();
            this.rowVarCache.addAll(f.rowVarCache);
        }
        this.varTypeCache.putAll(f.varTypeCache);

        Map<Integer, Set<SuokifParser.ArgumentContext>> argnummap, newargnummap;
        Set<SuokifParser.ArgumentContext> largs, newargs;
        for (String pred : f.argMap.keySet()) {
            argnummap = f.argMap.get(pred);
            newargnummap = new HashMap<>();
            for (Integer argnum : argnummap.keySet()) {
                largs = argnummap.get(argnum);
                newargs = new HashSet<>();
                newargs.addAll(largs);
                newargnummap.put(argnum, newargs);
            }
            this.argMap.put(pred, newargnummap);
        }

        this.eqList.addAll(f.eqList);

        Set<String> newtypes, existingTypes;
        for (String var : f.explicitTypes.keySet()) {
            newtypes = f.explicitTypes.get(var);
            if (explicitTypes.containsKey(var))
                explicitTypes.get(var).addAll(newtypes);
            else {
                existingTypes = new HashSet<>();
                explicitTypes.put(var,existingTypes);
                existingTypes.addAll(newtypes);
            }
        }

        for (String var : f.varTypes.keySet()) {
            newtypes = f.varTypes.get(var);
            if (varTypes.containsKey(var))
                varTypes.get(var).addAll(newtypes);
            else {
                existingTypes = new HashSet<>();
                varTypes.put(var,existingTypes);
                existingTypes.addAll(newtypes);
            }
        }

        this.isRule = this.isRule || f.isRule;
        this.isDoc = this.isDoc || f.isDoc;
        if (f.containsNumber)
            this.containsNumber = true;
        this.rowvarLiterals.addAll(f.rowvarLiterals);
        this.constants.putAll(f.constants);
        Set<RowStruct> hsrs;
        for (String var : f.rowVarStructs.keySet()) {
            hsrs = f.rowVarStructs.get(var);
            if (debug) System.out.println("merge from rowVarStructs: " + hsrs);
            for (RowStruct rs : hsrs)
                this.addRowVarStruct(var, new RowStruct(rs));
        }
    }

    /** ***************************************************************
     * Merge arguments to a predicate, which may themselves be complex
     * formulas, with an existing formula.
     */
    public FormulaAST mergeFormulaAST(FormulaAST f2) {

        this.allVarsCache.addAll(f2.allVarsCache);
        this.allVarsPairCache.addAll(f2.allVarsPairCache);
        this.quantVarsCache.addAll(f2.quantVarsCache);
        this.unquantVarsCache.addAll(f2.unquantVarsCache);
        this.existVarsCache.addAll(f2.existVarsCache);
        this.univVarsCache.addAll(f2.univVarsCache);
        this.termCache.addAll(f2.termCache);
        if (this.rowVarCache == null)
            this.rowVarCache = new HashSet<>();
        if (f2.rowVarCache == null)
            f2.rowVarCache = new HashSet<>();
        this.rowVarCache.addAll(f2.rowVarCache);
        if (this.predVarCache == null)
            this.predVarCache = new HashSet<>();
        if (f2.predVarCache == null)
            f2.predVarCache = new HashSet<>();
        this.predVarCache.addAll(f2.predVarCache);

        Map<Integer, Set<SuokifParser.ArgumentContext>> argnummap, newargnummap;
        Set<SuokifParser.ArgumentContext> largs, newargs;
        for (String pred : f2.argMap.keySet()) {
            argnummap = f2.argMap.get(pred);
            newargnummap = new HashMap<>();
            for (Integer argnum : argnummap.keySet()) {
                largs = argnummap.get(argnum);
                newargs = new HashSet<>();
                newargs.addAll(largs);
                newargnummap.put(argnum,newargs);
            }
            this.argMap.put(pred,newargnummap);
        }
        Set<String> newtypes, existingTypes;
        for (String var : f2.explicitTypes.keySet()) {
            newtypes = f2.explicitTypes.get(var);
            if (explicitTypes.containsKey(var))
                explicitTypes.get(var).addAll(newtypes);
            else {
                existingTypes = new HashSet<>();
                explicitTypes.put(var,existingTypes);
                existingTypes.addAll(newtypes);
            }
        }

        for (String var : f2.varTypes.keySet()) {
            newtypes = f2.varTypes.get(var);
            if (varTypes.containsKey(var))
                varTypes.get(var).addAll(newtypes);
            else {
                existingTypes = new HashSet<>();
                varTypes.put(var,existingTypes);
                existingTypes.addAll(newtypes);
            }
        }

        this.eqList.addAll(f2.eqList);
        this.isRule = this.isRule || f2.isRule;
        this.isDoc = this.isDoc || f2.isDoc;
        if (f2.containsNumber)
            this.containsNumber = true;
        this.rowvarLiterals.addAll(f2.rowvarLiterals);
        this.constants.putAll(f2.constants);
        Set<RowStruct> hsrs;
        for (String var : f2.rowVarStructs.keySet()) {
            hsrs = f2.rowVarStructs.get(var);
            if (debug) System.out.println("merge from rowVarStructs: " + hsrs);
            for (RowStruct rs : hsrs)
                this.addRowVarStruct(var, new RowStruct(rs));
        }
        return this;
    }

    /** ***************************************************************
     * Merge arguments to a predicate, which may themselves be complex
     * formulas, with an existing formula.
     */
    public FormulaAST mergeFormulaAST(List<FormulaAST> ar) {

        if (this.predVarCache == null)
            this.predVarCache = new HashSet<>();
        if (this.rowVarCache == null)
            this.rowVarCache = new HashSet<>();
        Map<Integer, Set<SuokifParser.ArgumentContext>> argnummap, newargnummap;
        Set<SuokifParser.ArgumentContext> largs, newargs;
        Set<String> newtypes, existingTypes;
        for (FormulaAST arf : ar) {
            this.allVarsCache.addAll(arf.allVarsCache);
            this.allVarsPairCache.addAll(arf.allVarsPairCache);
            this.quantVarsCache.addAll(arf.quantVarsCache);
            this.unquantVarsCache.addAll(arf.unquantVarsCache);
            this.existVarsCache.addAll(arf.existVarsCache);
            this.univVarsCache.addAll(arf.univVarsCache);
            this.termCache.addAll(arf.termCache);
            if (arf.rowVarCache == null)
                arf.rowVarCache = new HashSet<>();
            this.rowVarCache.addAll(arf.rowVarCache);
            if (arf.predVarCache == null)
                arf.predVarCache = new HashSet<>();
            this.predVarCache.addAll(arf.predVarCache);
            for (String pred : arf.argMap.keySet()) {
                argnummap = arf.argMap.get(pred);
                newargnummap = new HashMap<>();
                for (Integer argnum : argnummap.keySet()) {
                    largs = argnummap.get(argnum);
                    newargs = new HashSet<>();
                    newargs.addAll(largs);
                    newargnummap.put(argnum,newargs);
                }
                this.argMap.put(pred,newargnummap);
            }

            this.eqList.addAll(arf.eqList);
            for (String var : arf.explicitTypes.keySet()) {
                newtypes = arf.explicitTypes.get(var);
                if (explicitTypes.containsKey(var))
                    explicitTypes.get(var).addAll(newtypes);
                else {
                    existingTypes = new HashSet<>();
                    explicitTypes.put(var,existingTypes);
                    existingTypes.addAll(newtypes);
                }
            }

            for (String var : arf.varTypes.keySet()) {
                newtypes = arf.varTypes.get(var);
                if (varTypes.containsKey(var))
                    varTypes.get(var).addAll(newtypes);
                else {
                    existingTypes = new HashSet<>();
                    varTypes.put(var,existingTypes);
                    existingTypes.addAll(newtypes);
                }
            }
            this.isRule = this.isRule || arf.isRule;
            this.isDoc = this.isDoc || arf.isDoc;
            if (arf.containsNumber)
                this.containsNumber = true;
            this.rowvarLiterals.addAll(arf.rowvarLiterals);
            this.constants.putAll(arf.constants);
            Set<RowStruct> hsrs;
            for (String var : arf.rowVarStructs.keySet()) {
                hsrs = arf.rowVarStructs.get(var);
                if (debug) System.out.println("merge from rowVarStructs: " + hsrs);
                for (RowStruct rs : hsrs)
                    this.addRowVarStruct(var, new RowStruct(rs));
            }
        }
        return this;
    }

    /** *****************************************************************
     * A class for holding information about constants (non-variables) and the literal
     * in which they appear
     */
    public static class ArgStruct {
        public String pred = "";
        public String literal = "";
        public String constant = "";
        public int argPos = -1;
        private StringBuilder sb;

        public ArgStruct() {
            sb = new StringBuilder();
        }

        @Override
        public String toString() {
            sb.setLength(0);
            sb.append(pred).append(":").append(constant).append("::").append(argPos).append(":").append(literal);
            return sb.toString();
        }
    }

    /** *****************************************************************
     * A class for holding information about row variables and the literal
     * in which they appear
     */
    public static class RowStruct {
        public String rowvar = "";
        public String pred = "";
        public String literal = "";
        public int arity = 0; // number of actual arguments in the literal
        private StringBuilder sb;

        public RowStruct() {
            sb = new StringBuilder();
        }
        public RowStruct(RowStruct rs) {
            this();
            this.rowvar = rs.rowvar;
            this.pred = rs.pred;
            this.literal = rs.literal;
            this.arity = rs.arity;
        }

        @Override
        public String toString() {
            sb.setLength(0);
            sb.append(pred).append(":").append(rowvar).append(":").append(literal);
            return sb.toString();
        }
    }

    /** *****************************************************************
     */
    public void addRowVarStruct(String var, RowStruct rs) {

        Set<RowStruct> hrs;
        if (!rowVarStructs.containsKey(var)) {
            hrs = new HashSet<>();
            rowVarStructs.put(var,hrs);
        }
        else
            hrs = rowVarStructs.get(var);
        hrs.add(rs);
    }

    /** *****************************************************************
     * the textual version of the formula
     */
    public static FormulaAST createComment(String input) {

        FormulaAST f = new FormulaAST();
        f.setFormula(input);
        f.comment = true;
        return f;
    }

    /** *****************************************************************
     * the textual version of the formula
     */
    @Override
    public void printCaches() {

        super.printCaches();
        System.out.println("argMap: ");
        for (String pred : argMap.keySet()) {
            System.out.print("\t" + pred + "\t");
            for (Integer i : argMap.get(pred).keySet()) {
                System.out.print(i + ": ");
                for (SuokifParser.ArgumentContext c : argMap.get(pred).get(i)) {
                    System.out.print(c.getText() + ", ");
                }
            }
            System.out.println();
        }
        System.out.println("varTypes: " + varTypes);
        System.out.println("predVarCache: " + predVarCache);
        System.out.println("explicitTypes: " + explicitTypes);

        System.out.println("containsNumber: " + containsNumber);
        System.out.println("higherOrder: " + higherOrder);

        System.out.println("eqlist: ");
        for (List<SuokifParser.TermContext> al : eqList) {
            System.out.println(al.get(0).getText() + " = " + al.get(1).getText());
        }
        System.out.println("row var literal: ");
        for (ParserRuleContext lit : rowvarLiterals) {
            System.out.println(lit.getText());
        }

        System.out.println("constants: ");
        ArgStruct as;
        String lit;
        for (String c : constants.keySet()) {
            as = constants.get(c);
            lit = as.literal;
            System.out.println(c + " : " + lit);
        }

        System.out.println("row var struct: ");
        Set<RowStruct> rvs;
        for (String var : rowVarStructs.keySet()) {
            rvs = rowVarStructs.get(var);
            System.out.print(var + ":");
            for (RowStruct rv : rvs)
                System.out.print(rv.toString() + ", ");
            System.out.println();
        }
        System.out.println();
    }

    // -----------------------------------------------------------------------
    // M8 — Alpha-equivalence fingerprinting
    // -----------------------------------------------------------------------

    /**
     * Computes a 64-bit MurmurHash3 fingerprint for {@code expr} that is
     * invariant under alpha-renaming of variables.
     *
     * <h3>Algorithm</h3>
     * <p>A single DFS walk over the {@link Expr} tree normalises variable names
     * to positional tokens ({@code Var_0}, {@code Var_1}, …) in the order of
     * first encounter, then hashes the resulting token sequence with
     * {@link Hashing#murmur3_128()}.  Two formulas that differ only in their
     * variable names produce the same hash.</p>
     *
     * <h3>Example</h3>
     * <pre>
     *   (forall (?X) (instance ?X Dog))
     *   (forall (?Y) (instance ?Y Dog))
     *   → both normalise to: [forall, Var_0, instance, Var_0, Dog]
     *   → same fingerprint
     * </pre>
     *
     * @param expr the Expr tree to fingerprint; returns {@code 1L} when null
     * @return 64-bit fingerprint (lower half of the 128-bit Murmur hash)
     */
    @SuppressWarnings("UnstableApiUsage")
    public static long computeFingerprint(Expr expr) {
        if (expr == null) return 1L;
        Hasher hasher = Hashing.murmur3_128().newHasher();
        Map<String, Integer> varOrder = new HashMap<>();
        hashExpr(expr, hasher, varOrder);
        HashCode hc = hasher.hash();
        return hc.asLong();
    }

    /**
     * Recursive DFS helper for {@link #computeFingerprint}.
     * Feeds a token sequence derived from {@code expr} into {@code hasher},
     * substituting variable names with their positional index.
     */
    private static void hashExpr(Expr expr, Hasher hasher, Map<String, Integer> varOrder) {
        switch (expr) {
            case Expr.Var v -> {
                // Normalise: replace variable name with its encounter order
                int idx = varOrder.computeIfAbsent(v.name(), k -> varOrder.size());
                hasher.putString("Var_" + idx, StandardCharsets.UTF_8);
            }
            case Expr.RowVar rv -> {
                int idx = varOrder.computeIfAbsent(rv.name(), k -> varOrder.size());
                hasher.putString("RowVar_" + idx, StandardCharsets.UTF_8);
            }
            case Expr.NumLiteral n -> hasher.putString("Num:" + n.value(), StandardCharsets.UTF_8);
            case Expr.StrLiteral s -> hasher.putString("Str:" + s.value(), StandardCharsets.UTF_8);
            case Expr.Atom a       -> hasher.putString(a.name(), StandardCharsets.UTF_8);
            case Expr.SExpr se -> {
                hasher.putByte((byte) '(');
                if (se.head() != null) hashExpr(se.head(), hasher, varOrder);
                for (Expr arg : se.args()) hashExpr(arg, hasher, varOrder);
                hasher.putByte((byte) ')');
            }
        }
    }
}
