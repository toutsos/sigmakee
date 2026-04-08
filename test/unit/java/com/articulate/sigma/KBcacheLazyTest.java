package com.articulate.sigma;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.*;

/**
 * M3 comparison test: verifies that the new lazy accessor methods
 * ({@link KBcache#getParentsLazy}, {@link KBcache#getChildrenLazy},
 * {@link KBcache#getInstanceOfLazy}) produce the same results as the
 * existing eager maps ({@code parents}, {@code children}, {@code instanceOf})
 * built by the full {@code buildCaches()} pipeline.
 *
 * <p>Uses the same small KB as {@link KBcacheParallelBuildTest} so the
 * expected values can be reasoned about without loading SUMO.</p>
 */
public class KBcacheLazyTest {

    // -----------------------------------------------------------------------
    // Shared KB — built once, read-only during tests
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
    };

    /** A fully-built KBcache (eager). */
    private static KBcache cache;

    @BeforeClass
    public static void setUpClass() {
        KB kb = new KB("TestLazyKB");
        kb.kbCache = new KBcache(kb);
        KBmanager.getMgr().setPref("cacheDisjoint", "false");
        KIF kif = new KIF();
        for (String stmt : KB_STMTS)
            kif.parseStatement(stmt);
        kb.merge(kif, "");
        for (Formula f : kb.formulaMap.values())
            f.sourceFile = "test";
        cache = new KBcache(kb);
        kb.kbCache = cache;
        cache.buildCaches();
    }

    // -----------------------------------------------------------------------
    // getParentsLazy vs eager parents map
    // -----------------------------------------------------------------------

    @Test
    public void parentsLazy_dog_subclass_matchesEager() {
        Set<String> eager = cache.parents.get("subclass").get("Dog");
        Set<String> lazy  = cache.getParentsLazy("Dog", "subclass");
        assertNotNull("eager parents[subclass][Dog] must not be null", eager);
        assertFalse("lazy parents[Dog,subclass] must not be empty", lazy.isEmpty());
        assertEquals("lazy must equal eager for Dog/subclass", eager, lazy);
    }

    @Test
    public void parentsLazy_mammal_subclass_matchesEager() {
        Set<String> eager = cache.parents.get("subclass").get("Mammal");
        Set<String> lazy  = cache.getParentsLazy("Mammal", "subclass");
        assertNotNull("eager must not be null", eager);
        assertEquals("lazy must equal eager for Mammal/subclass", eager, lazy);
    }

    @Test
    public void parentsLazy_transitiveRelation_subclass_matchesEager() {
        Set<String> eager = cache.parents.get("subclass").get("TransitiveRelation");
        Set<String> lazy  = cache.getParentsLazy("TransitiveRelation", "subclass");
        assertNotNull("eager must not be null", eager);
        assertEquals("lazy must equal eager for TransitiveRelation/subclass", eager, lazy);
    }

    @Test
    public void parentsLazy_unknownTerm_returnsEmpty() {
        Set<String> lazy = cache.getParentsLazy("NoSuchTerm", "subclass");
        assertTrue("unknown term should yield empty set", lazy.isEmpty());
    }

    @Test
    public void parentsLazy_nullInputs_returnEmpty() {
        assertTrue(cache.getParentsLazy(null, "subclass").isEmpty());
        assertTrue(cache.getParentsLazy("Dog", null).isEmpty());
        assertTrue(cache.getParentsLazy("", "subclass").isEmpty());
    }

    // -----------------------------------------------------------------------
    // getChildrenLazy vs eager children map
    // -----------------------------------------------------------------------

    @Test
    public void childrenLazy_animal_subclass_matchesEager() {
        Set<String> eager = cache.children.get("subclass").get("Animal");
        Set<String> lazy  = cache.getChildrenLazy("Animal", "subclass");
        assertNotNull("eager children[subclass][Animal] must not be null", eager);
        assertFalse("lazy children[Animal,subclass] must not be empty", lazy.isEmpty());
        assertEquals("lazy must equal eager for Animal/subclass", eager, lazy);
    }

    @Test
    public void childrenLazy_entity_subclass_matchesEager() {
        Set<String> eager = cache.children.get("subclass").get("Entity");
        Set<String> lazy  = cache.getChildrenLazy("Entity", "subclass");
        assertNotNull("eager must not be null", eager);
        assertEquals("lazy must equal eager for Entity/subclass", eager, lazy);
    }

    @Test
    public void childrenLazy_leaf_returnsEmpty() {
        // Dog has no subclasses in our test KB
        Set<String> lazy = cache.getChildrenLazy("Dog", "subclass");
        assertTrue("Dog has no subclasses so lazy should be empty", lazy.isEmpty());
    }

    // -----------------------------------------------------------------------
    // getInstanceOfLazy vs eager instanceOf map
    // -----------------------------------------------------------------------

    @Test
    public void instanceOfLazy_fido_matchesEager() {
        Set<String> eager = cache.instanceOf.get("Fido");
        Set<String> lazy  = cache.getInstanceOfLazy("Fido");
        assertNotNull("eager instanceOf[Fido] must not be null", eager);
        assertFalse("lazy instanceOf[Fido] must not be empty", lazy.isEmpty());
        assertEquals("lazy must equal eager for Fido", eager, lazy);
    }

    @Test
    public void instanceOfLazy_felix_matchesEager() {
        Set<String> eager = cache.instanceOf.get("Felix");
        Set<String> lazy  = cache.getInstanceOfLazy("Felix");
        assertNotNull("eager must not be null", eager);
        assertEquals("lazy must equal eager for Felix", eager, lazy);
    }

    @Test
    public void instanceOfLazy_subclass_matchesEager() {
        // (instance subclass TransitiveRelation) and (instance subclass BinaryRelation)
        Set<String> eager = cache.instanceOf.get("subclass");
        Set<String> lazy  = cache.getInstanceOfLazy("subclass");
        assertNotNull("eager instanceOf[subclass] must not be null", eager);
        assertEquals("lazy must equal eager for subclass", eager, lazy);
    }

    @Test
    public void instanceOfLazy_unknownTerm_returnsEmpty() {
        assertTrue(cache.getInstanceOfLazy("NoSuchInstance").isEmpty());
    }

    // -----------------------------------------------------------------------
    // Caching behaviour: repeated calls return the same object
    // -----------------------------------------------------------------------

    @Test
    public void getParentsLazy_returnsCachedInstance() {
        Set<String> first  = cache.getParentsLazy("Dog", "subclass");
        Set<String> second = cache.getParentsLazy("Dog", "subclass");
        assertSame("repeated calls must return the same cached Set instance", first, second);
    }

    @Test
    public void getChildrenLazy_returnsCachedInstance() {
        Set<String> first  = cache.getChildrenLazy("Animal", "subclass");
        Set<String> second = cache.getChildrenLazy("Animal", "subclass");
        assertSame("repeated calls must return the same cached Set instance", first, second);
    }
}
