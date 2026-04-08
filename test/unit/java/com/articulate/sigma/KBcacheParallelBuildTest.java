package com.articulate.sigma;

import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * M2 comparison test: verifies that the new {@code _p_buildCaches()} parallel
 * implementation produces the same cache structures as the original sequential
 * {@code _buildCaches()} for the same KB content.
 *
 * <p>Strategy: build two identical KBs.  Build one with the sequential path
 * (by calling the private {@code _buildCaches()} via reflection) and one via
 * the public {@code buildCaches()} which now routes to {@code _p_buildCaches()}.
 * Assert that all major cache collections are equal.</p>
 */
public class KBcacheParallelBuildTest {

    // -----------------------------------------------------------------------
    // Test KB — a small but non-trivial taxonomy covering the main code paths
    // -----------------------------------------------------------------------

    private static final String[] KB_STMTS = {
            // Transitive relations (required for buildParents/buildChildren)
            "(instance subclass TransitiveRelation)",
            "(instance subrelation TransitiveRelation)",
            "(instance subAttribute TransitiveRelation)",
            "(subclass TransitiveRelation Relation)",
            "(subclass BinaryRelation Relation)",
            "(subclass Relation Entity)",
            // Simple taxonomy
            "(subclass Animal Entity)",
            "(subclass Dog Animal)",
            "(subclass Cat Animal)",
            "(subclass Mammal Animal)",
            "(subclass Dog Mammal)",
            // Instances
            "(instance Fido Dog)",
            "(instance Felix Cat)",
            "(instance subclass BinaryRelation)",
            // Domain declarations (exercise collectDomains)
            "(domain subclass 1 Class)",
            "(domain subclass 2 Class)",
    };

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Build a fresh KB + KBcache from the shared statements. */
    private static KB buildKB() {
        KB kb = new KB("TestParallelBuildKB");
        kb.kbCache = new KBcache(kb);
        KBmanager.getMgr().setPref("cacheDisjoint", "false"); // keep test fast
        KIF kif = new KIF();
        for (String stmt : KB_STMTS)
            kif.parseStatement(stmt);
        kb.merge(kif, "");
        for (Formula f : kb.formulaMap.values())
            f.sourceFile = "test"; // mark non-cached so KBcache processes them
        return kb;
    }

    /** Build sequential reference cache (calls private _buildCaches via clearCaches + sequential). */
    private static KBcache buildSequential(KB kb) {
        KBcache cache = new KBcache(kb);
        kb.kbCache = cache;
        cache.clearCaches();
        cache.buildInsts();
        cache.buildRelationsSet();
        cache.buildTransitiveRelationsSet();
        cache.buildDirectParentTerms();
        cache.buildParents();
        cache.buildChildren();
        cache.collectDomains();
        cache.buildDirectInstances();
        cache.buildInstTransRels();
        cache.addTransitiveInstances();
        cache.buildTransInstOf();
        cache.correctValences();
        cache.buildFunctionsSet();
        // skip disjoint (disabled above)
        cache.storeCacheAsFormulas();
        return cache;
    }

    /** Build using the public buildCaches() — now routes to _p_buildCaches(). */
    private static KBcache buildParallel(KB kb) {
        KBcache cache = new KBcache(kb);
        kb.kbCache = cache;
        cache.buildCaches();
        return cache;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    public void parents_subclass_matchesSequential() {
        KB kb1 = buildKB();
        KB kb2 = buildKB();
        KBcache seq = buildSequential(kb1);
        KBcache par = buildParallel(kb2);

        Map<String, Set<String>> seqParents = seq.parents.get("subclass");
        Map<String, Set<String>> parParents = par.parents.get("subclass");
        assertNotNull("sequential parents[subclass] must not be null", seqParents);
        assertNotNull("parallel parents[subclass] must not be null",   parParents);
        assertEquals("parents[subclass] must match", seqParents, parParents);
    }

    @Test
    public void children_subclass_matchesSequential() {
        KB kb1 = buildKB();
        KB kb2 = buildKB();
        KBcache seq = buildSequential(kb1);
        KBcache par = buildParallel(kb2);

        Map<String, Set<String>> seqChildren = seq.children.get("subclass");
        Map<String, Set<String>> parChildren = par.children.get("subclass");
        assertNotNull("sequential children[subclass] must not be null", seqChildren);
        assertNotNull("parallel children[subclass] must not be null",   parChildren);
        assertEquals("children[subclass] must match", seqChildren, parChildren);
    }

    @Test
    public void instanceOf_matchesSequential() {
        KB kb1 = buildKB();
        KB kb2 = buildKB();
        KBcache seq = buildSequential(kb1);
        KBcache par = buildParallel(kb2);
        assertEquals("instanceOf must match sequential", seq.instanceOf, par.instanceOf);
    }

    @Test
    public void relations_matchesSequential() {
        KB kb1 = buildKB();
        KB kb2 = buildKB();
        KBcache seq = buildSequential(kb1);
        KBcache par = buildParallel(kb2);
        assertEquals("relations set must match", seq.relations, par.relations);
    }

    @Test
    public void transRels_matchesSequential() {
        KB kb1 = buildKB();
        KB kb2 = buildKB();
        KBcache seq = buildSequential(kb1);
        KBcache par = buildParallel(kb2);
        assertEquals("transRels must match", seq.transRels, par.transRels);
    }

    @Test
    public void signatures_matchesSequential() {
        KB kb1 = buildKB();
        KB kb2 = buildKB();
        KBcache seq = buildSequential(kb1);
        KBcache par = buildParallel(kb2);
        assertEquals("signatures must match", seq.signatures, par.signatures);
    }

    @Test
    public void insts_matchesSequential() {
        KB kb1 = buildKB();
        KB kb2 = buildKB();
        KBcache seq = buildSequential(kb1);
        KBcache par = buildParallel(kb2);
        assertEquals("insts must match", seq.insts, par.insts);
    }

    @Test
    public void directParentTerms_matchesSequential() {
        KB kb1 = buildKB();
        KB kb2 = buildKB();
        KBcache seq = buildSequential(kb1);
        KBcache par = buildParallel(kb2);
        assertEquals("directParentTerms must match", seq.directParentTerms, par.directParentTerms);
    }
}
