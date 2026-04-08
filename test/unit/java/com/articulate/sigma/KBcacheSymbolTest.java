package com.articulate.sigma;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * M5 — Comparison tests for symbol-indexed vs string-indexed taxonomy lookups.
 *
 * <p>Builds a small KB, runs {@link KBcache#buildCaches()} (string path), then
 * calls {@link KBcache#buildSymbolTaxonomy()} (M5 int path), and verifies that
 * {@link KBcache#getParentsSymbol} and {@link KBcache#getChildrenSymbol} return
 * the same results as the original {@code parents} and {@code children} maps.</p>
 */
public class KBcacheSymbolTest {

    private static final String[] KB_STMTS = {
            "(instance subclass TransitiveRelation)",
            "(instance subrelation TransitiveRelation)",
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
    };

    private static KBcache cache;

    @BeforeClass
    public static void setUpClass() throws Exception {
        KBmanager.getMgr().setPref("cacheDisjoint", "false");
        KB kb = new KB("SymbolTest");
        kb.kbCache = new KBcache(kb);
        KIF kif = new KIF();
        for (String stmt : KB_STMTS)
            kif.parseStatement(stmt);
        kb.merge(kif, "");
        cache = new KBcache(kb);
        kb.kbCache = cache;
        cache.buildCaches();
        // Build the symbol taxonomy after caches are ready
        cache.buildSymbolTaxonomy();
    }

    // -----------------------------------------------------------------------
    // SymbolTable itself is populated
    // -----------------------------------------------------------------------

    @Test
    public void symbolTable_isNonNull() {
        assertNotNull("buildSymbolTaxonomy must populate symbolTable", cache.getSymbolTable());
    }

    @Test
    public void symbolTable_containsKnownTerms() {
        SymbolTable st = cache.getSymbolTable();
        assertTrue("Dog must be interned", st.contains("Dog"));
        assertTrue("Animal must be interned", st.contains("Animal"));
        assertTrue("Entity must be interned", st.contains("Entity"));
    }

    @Test
    public void symbolTable_sizePositive() {
        assertTrue("symbol table must have at least one entry",
                cache.getSymbolTable().size() > 0);
    }

    // -----------------------------------------------------------------------
    // getParentsSymbol matches parents map — subclass
    // -----------------------------------------------------------------------

    @Test
    public void getParentsSymbol_matchesStringMap_subclass_Dog() {
        String rel = "subclass";
        String term = "Dog";
        Set<String> strResult = getStringParents(rel, term);
        Set<String> symResult = cache.getParentsSymbol(term, rel);
        assertEquals("getParentsSymbol must match string map for Dog/subclass",
                strResult, symResult);
    }

    @Test
    public void getParentsSymbol_matchesStringMap_subclass_Mammal() {
        String rel = "subclass";
        String term = "Mammal";
        Set<String> strResult = getStringParents(rel, term);
        Set<String> symResult = cache.getParentsSymbol(term, rel);
        assertEquals("getParentsSymbol must match string map for Mammal/subclass",
                strResult, symResult);
    }

    @Test
    public void getParentsSymbol_matchesStringMap_subclass_Cat() {
        String rel = "subclass";
        String term = "Cat";
        assertEquals(getStringParents(rel, term), cache.getParentsSymbol(term, rel));
    }

    @Test
    public void getParentsSymbol_allTermsAllRels_matchStringMap() {
        for (Map.Entry<String, Map<String, Set<String>>> relEntry : cache.parents.entrySet()) {
            String rel = relEntry.getKey();
            for (Map.Entry<String, Set<String>> termEntry : relEntry.getValue().entrySet()) {
                String term = termEntry.getKey();
                Set<String> expected = termEntry.getValue();
                Set<String> actual   = cache.getParentsSymbol(term, rel);
                assertEquals("parents mismatch for term=" + term + " rel=" + rel,
                        expected, actual);
            }
        }
    }

    // -----------------------------------------------------------------------
    // getChildrenSymbol matches children map — subclass
    // -----------------------------------------------------------------------

    @Test
    public void getChildrenSymbol_matchesStringMap_subclass_Animal() {
        String rel = "subclass";
        String term = "Animal";
        Set<String> strResult = getStringChildren(rel, term);
        Set<String> symResult = cache.getChildrenSymbol(term, rel);
        assertEquals("getChildrenSymbol must match string map for Animal/subclass",
                strResult, symResult);
    }

    @Test
    public void getChildrenSymbol_matchesStringMap_subclass_Entity() {
        String rel = "subclass";
        String term = "Entity";
        assertEquals(getStringChildren(rel, term), cache.getChildrenSymbol(term, rel));
    }

    @Test
    public void getChildrenSymbol_allTermsAllRels_matchStringMap() {
        for (Map.Entry<String, Map<String, Set<String>>> relEntry : cache.children.entrySet()) {
            String rel = relEntry.getKey();
            for (Map.Entry<String, Set<String>> termEntry : relEntry.getValue().entrySet()) {
                String term = termEntry.getKey();
                Set<String> expected = termEntry.getValue();
                Set<String> actual   = cache.getChildrenSymbol(term, rel);
                assertEquals("children mismatch for term=" + term + " rel=" + rel,
                        expected, actual);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    public void getParentsSymbol_unknownTerm_returnsEmpty() {
        assertTrue("unknown term must return empty set",
                cache.getParentsSymbol("NoSuchTerm", "subclass").isEmpty());
    }

    @Test
    public void getParentsSymbol_unknownRelation_returnsEmpty() {
        assertTrue("unknown relation must return empty set",
                cache.getParentsSymbol("Dog", "noSuchRelation").isEmpty());
    }

    @Test
    public void getChildrenSymbol_unknownTerm_returnsEmpty() {
        assertTrue(cache.getChildrenSymbol("NoSuchTerm", "subclass").isEmpty());
    }

    @Test
    public void getChildrenSymbol_unknownRelation_returnsEmpty() {
        assertTrue(cache.getChildrenSymbol("Dog", "noSuchRelation").isEmpty());
    }

    @Test
    public void buildSymbolTaxonomy_idempotent() {
        // Calling buildSymbolTaxonomy a second time must not break lookups
        cache.buildSymbolTaxonomy();
        Set<String> result = cache.getParentsSymbol("Dog", "subclass");
        assertEquals(getStringParents("subclass", "Dog"), result);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Set<String> getStringParents(String rel, String term) {
        Map<String, Set<String>> relMap = cache.parents.get(rel);
        if (relMap == null) return Set.of();
        Set<String> result = relMap.get(term);
        return result != null ? result : Set.of();
    }

    private static Set<String> getStringChildren(String rel, String term) {
        Map<String, Set<String>> relMap = cache.children.get(rel);
        if (relMap == null) return Set.of();
        Set<String> result = relMap.get(term);
        return result != null ? result : Set.of();
    }
}
