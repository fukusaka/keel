package io.github.fukusaka.keel.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.compileAndLint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cases that distinguish an arena someone will free from one nobody will.
 *
 * Both directions are covered deliberately. A suite that only asserted leaks would
 * have called the previous version of this rule healthy on the four correctly scoped
 * arenas it reported, and a suite that only asserted silence would have missed that
 * it never looked inside a top-level function at all.
 *
 * The `shadowing`, `guarding try`, `binding` and `receiver` groups pin the four ways
 * this rule can go wrong now that it resolves an owner: crediting a `clear()` that
 * belongs to a different binding of the same name, accepting a `finally` that does
 * not guard the arena, refusing an initializer it does not recognise, and refusing a
 * receiver spelling it does not recognise. The first two are silent false negatives —
 * the shape a leak detector must not have.
 */
class ArenaLeakRuleTest {

    private val rule = ArenaLeakRule(Config.empty)

    // ---- local arenas: the guarding try is the contract ----

    @Test
    fun `no report when a local arena is handed to a try that clears it`() {
        val code = """
            class Probe {
                fun run() {
                    val arena = Arena()
                    try {
                        use(arena)
                    } finally {
                        arena.clear()
                    }
                }
            }
        """.trimIndent()
        assertEquals(0, rule.compileAndLint(code).size)
    }

    @Test
    fun `no report when declarations sit between the arena and its try`() {
        // The shape two benchmark harnesses use: the result holders are declared
        // before the try so they outlive it.
        val code = """
            class Probe {
                fun run() {
                    val arena = Arena()
                    val refs = ArrayList<String>()
                    val wallNs: Double
                    try {
                        wallNs = use(arena, refs)
                    } finally {
                        arena.clear()
                    }
                }
            }
        """.trimIndent()
        assertEquals(0, rule.compileAndLint(code).size)
    }

    @Test
    fun `no report when a local arena in a top-level function is cleared`() {
        val code = """
            fun bench() {
                val arena = Arena()
                try {
                    use(arena)
                } finally {
                    arena.clear()
                }
            }
        """.trimIndent()
        assertEquals(0, rule.compileAndLint(code).size)
    }

    @Test
    fun `reports a local arena that is never cleared`() {
        val code = """
            class Probe {
                fun run() {
                    val arena = Arena()
                    use(arena)
                }
            }
        """.trimIndent()
        val findings = rule.compileAndLint(code)
        assertEquals(1, findings.size)
        assertTrue("never cleared" in findings[0].message, findings[0].message)
    }

    @Test
    fun `reports a local arena in a top-level function that is never cleared`() {
        // The previous rule required an enclosing class and returned silently
        // without one, so a top-level function was never examined at all.
        val code = """
            fun bench() {
                val arena = Arena()
                use(arena)
            }
        """.trimIndent()
        assertEquals(1, rule.compileAndLint(code).size)
    }

    @Test
    fun `reports a local arena cleared only on the normal path`() {
        val code = """
            class Probe {
                fun run() {
                    val arena = Arena()
                    use(arena)
                    arena.clear()
                }
            }
        """.trimIndent()
        val findings = rule.compileAndLint(code)
        assertEquals(1, findings.size)
        assertTrue("leaks when the" in findings[0].message, findings[0].message)
    }

    // ---- guarding try: a finally that does not guard the arena is not a guard ----

    @Test
    fun `reports a local arena whose clear is in a later unrelated try`() {
        // The first try can throw before the second is ever entered, so the arena
        // is unprotected across it. Requiring only "some enclosing finally" accepts
        // this, which is a silent leak.
        val code = """
            class Probe {
                fun run() {
                    val arena = Arena()
                    try {
                        risky()
                    } finally {
                    }
                    try {
                        use(arena)
                    } finally {
                        arena.clear()
                    }
                }
            }
        """.trimIndent()
        val findings = rule.compileAndLint(code)
        assertEquals(1, findings.size)
        assertTrue("not by a finally guarding it" in findings[0].message, findings[0].message)
    }

    @Test
    fun `reports a local arena whose finally clears a different arena`() {
        val code = """
            class Probe {
                fun run() {
                    val other = Arena()
                    val arena = Arena()
                    try {
                        use(arena)
                    } finally {
                        other.clear()
                    }
                }
            }
        """.trimIndent()
        // `other` is guarded by the try that follows its declarations; `arena` is not
        // cleared at all.
        val findings = rule.compileAndLint(code)
        assertEquals(1, findings.size)
        assertTrue("'arena'" in findings[0].message, findings[0].message)
    }

    // ---- property arenas: any teardown will do, but it must be this arena's ----

    @Test
    fun `no report when a property arena is cleared in close`() {
        val code = """
            class Ring {
                private val arena = Arena()
                fun close() {
                    arena.clear()
                }
            }
        """.trimIndent()
        assertEquals(0, rule.compileAndLint(code).size)
    }

    @Test
    fun `no report when a property arena is cleared in a differently named teardown`() {
        // FakeIoUringRing frees its arena from dispose(); requiring close() by name
        // reported it as a leak.
        val code = """
            class Ring {
                private val arena = Arena()
                fun dispose() {
                    arena.clear()
                }
            }
        """.trimIndent()
        assertEquals(0, rule.compileAndLint(code).size)
    }

    @Test
    fun `reports a property arena that no member clears`() {
        val code = """
            class Ring {
                private val arena = Arena()
                fun use() {
                    arena.alloc()
                }
            }
        """.trimIndent()
        val findings = rule.compileAndLint(code)
        assertEquals(1, findings.size)
        assertTrue("never cleared" in findings[0].message, findings[0].message)
    }

    @Test
    fun `reports a property arena when close clears something else`() {
        // The previous rule accepted any clear() inside close(), so a class tearing
        // down a different resource looked as if it had freed its arena.
        val code = """
            class Ring {
                private val arena = Arena()
                private val other = Arena()
                fun close() {
                    other.clear()
                }
            }
        """.trimIndent()
        val findings = rule.compileAndLint(code)
        assertEquals(1, findings.size)
        assertTrue("'arena'" in findings[0].message, findings[0].message)
    }

    // ---- shadowing: a local of the same name is not the field's teardown ----

    @Test
    fun `reports a property arena when only a same-named local is cleared`() {
        // Searching the whole class for `arena.clear()` credits the local, and the
        // field — which nothing frees — passes. Resolving an owner is what makes
        // this reachable, so it is the regression the owner model has to not have.
        val code = """
            class Probe {
                private val arena = Arena()
                fun testOne() {
                    val arena = Arena()
                    try {
                        use(arena)
                    } finally {
                        arena.clear()
                    }
                }
            }
        """.trimIndent()
        val findings = rule.compileAndLint(code)
        assertEquals(1, findings.size)
        assertTrue("property" in findings[0].message, findings[0].message)
    }

    @Test
    fun `reports a property arena when only a same-named parameter is cleared`() {
        val code = """
            class Probe {
                private val arena = Arena()
                fun helper(arena: Arena) {
                    arena.clear()
                }
            }
        """.trimIndent()
        assertEquals(1, rule.compileAndLint(code).size)
    }

    // ---- binding: the call need not be the initializer itself ----

    @Test
    fun `no report when the initializer is parenthesised`() {
        val code = """
            class Ring {
                private val arena = (Arena())
                fun close() {
                    arena.clear()
                }
            }
        """.trimIndent()
        assertEquals(0, rule.compileAndLint(code).size)
    }

    @Test
    fun `no report when the arena is bound through also`() {
        val code = """
            class Ring {
                private val arena = Arena().also { register(it) }
                fun close() {
                    arena.clear()
                }
            }
        """.trimIndent()
        assertEquals(0, rule.compileAndLint(code).size)
    }

    @Test
    fun `no report when the arena is bound by lazy`() {
        val code = """
            class Ring {
                private val arena by lazy { Arena() }
                fun close() {
                    arena.clear()
                }
            }
        """.trimIndent()
        assertEquals(0, rule.compileAndLint(code).size)
    }

    @Test
    fun `still reports a lazily bound arena that nothing clears`() {
        val code = """
            class Ring {
                private val arena by lazy { Arena() }
                fun use() {
                    arena.alloc()
                }
            }
        """.trimIndent()
        assertEquals(1, rule.compileAndLint(code).size)
    }

    // ---- receiver spellings ----

    @Test
    fun `no report when clear is called through this`() {
        val code = """
            class Ring {
                private val arena = Arena()
                fun close() {
                    this.arena.clear()
                }
            }
        """.trimIndent()
        assertEquals(0, rule.compileAndLint(code).size)
    }

    @Test
    fun `no report when clear is called with a safe call`() {
        val code = """
            class Ring {
                private val arena = Arena()
                fun close() {
                    arena?.clear()
                }
            }
        """.trimIndent()
        assertEquals(0, rule.compileAndLint(code).size)
    }

    // ---- unbound ----

    @Test
    fun `reports an arena whose result is never bound`() {
        val code = """
            class Probe {
                fun run() {
                    Arena().alloc()
                }
            }
        """.trimIndent()
        val findings = rule.compileAndLint(code)
        assertEquals(1, findings.size)
        assertTrue("not bound to a name" in findings[0].message, findings[0].message)
    }

    @Test
    fun `ignores unrelated constructor calls`() {
        val code = """
            class Probe {
                fun run() {
                    val arena = ChunkArena()
                }
            }
        """.trimIndent()
        assertEquals(0, rule.compileAndLint(code).size)
    }
}
