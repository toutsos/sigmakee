package com.articulate.sigma;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SymbolTable}.
 *
 * <p>Verifies core contract: intern assigns stable IDs, getName round-trips,
 * lookup is non-destructive, and capacity pre-allocation does not affect
 * correctness.</p>
 */
public class SymbolTableTest {

    @Test
    public void intern_firstString_getsIdZero() {
        SymbolTable st = new SymbolTable();
        assertEquals(0, st.intern("Dog"));
    }

    @Test
    public void intern_secondString_getsIdOne() {
        SymbolTable st = new SymbolTable();
        st.intern("Dog");
        assertEquals(1, st.intern("Cat"));
    }

    @Test
    public void intern_sameStringTwice_sameId() {
        SymbolTable st = new SymbolTable();
        int a = st.intern("Animal");
        int b = st.intern("Animal");
        assertEquals("same string must return same ID", a, b);
    }

    @Test
    public void getName_roundTrip() {
        SymbolTable st = new SymbolTable();
        int id = st.intern("Entity");
        assertEquals("getName must return the original string", "Entity", st.getName(id));
    }

    @Test
    public void getName_multipleEntries() {
        SymbolTable st = new SymbolTable();
        int dogId = st.intern("Dog");
        int catId = st.intern("Cat");
        assertEquals("Dog", st.getName(dogId));
        assertEquals("Cat", st.getName(catId));
    }

    @Test
    public void getName_outOfRange_returnsNull() {
        SymbolTable st = new SymbolTable();
        assertNull(st.getName(42));
        assertNull(st.getName(-1));
    }

    @Test
    public void lookup_knownString_returnsId() {
        SymbolTable st = new SymbolTable();
        int id = st.intern("Fido");
        assertEquals(id, st.lookup("Fido"));
    }

    @Test
    public void lookup_unknownString_returnsMinus1() {
        SymbolTable st = new SymbolTable();
        assertEquals(-1, st.lookup("Nonexistent"));
    }

    @Test
    public void lookup_doesNotRegister() {
        SymbolTable st = new SymbolTable();
        st.lookup("Ghost");
        assertEquals("lookup must not register a new symbol", 0, st.size());
    }

    @Test
    public void size_emptyTable() {
        assertEquals(0, new SymbolTable().size());
    }

    @Test
    public void size_afterInterns() {
        SymbolTable st = new SymbolTable();
        st.intern("A");
        st.intern("B");
        st.intern("A"); // duplicate — must not change size
        assertEquals(2, st.size());
    }

    @Test
    public void contains_knownString_returnsTrue() {
        SymbolTable st = new SymbolTable();
        st.intern("Dog");
        assertTrue(st.contains("Dog"));
    }

    @Test
    public void contains_unknownString_returnsFalse() {
        SymbolTable st = new SymbolTable();
        assertFalse(st.contains("Ghost"));
    }

    @Test
    public void capacityConstructor_behavesIdentically() {
        SymbolTable st = new SymbolTable(100);
        int id = st.intern("Test");
        assertEquals(0, id);
        assertEquals("Test", st.getName(id));
    }

    @Test
    public void manyInterns_allRoundTrip() {
        SymbolTable st = new SymbolTable();
        String[] terms = {"Entity", "Animal", "Dog", "Cat", "Mammal",
                "subclass", "instance", "domain", "range", "Fido"};
        int[] ids = new int[terms.length];
        for (int i = 0; i < terms.length; i++)
            ids[i] = st.intern(terms[i]);
        assertEquals(terms.length, st.size());
        for (int i = 0; i < terms.length; i++)
            assertEquals("round-trip failed for " + terms[i], terms[i], st.getName(ids[i]));
    }

    @Test
    public void ids_areSequentialFromZero() {
        SymbolTable st = new SymbolTable();
        for (int i = 0; i < 10; i++)
            assertEquals("ID must equal insertion order", i, st.intern("term_" + i));
    }
}
