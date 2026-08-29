package io.github.fukusaka.keel.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.TestConfig
import io.gitlab.arturbosch.detekt.test.compileAndLint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecondAfterTestRuleTest {

    private val rule = SecondAfterTestRule(TestConfig("allowedNames" to listOf("afterEachLoopIsGivenBack")))

    @Test
    fun `reports an AfterTest beside the allowed check`() {
        val code = """
            import kotlin.test.AfterTest
            class Probe {
                @AfterTest
                fun closeLoop() {}
            }
        """.trimIndent()
        val findings = rule.compileAndLint(code)
        assertEquals(1, findings.size)
        assertTrue("closeLoop" in findings.single().message)
    }

    @Test
    fun `does not report the allowed check itself`() {
        val code = """
            import kotlin.test.AfterTest
            class Fixture {
                @AfterTest
                fun afterEachLoopIsGivenBack() {}
            }
        """.trimIndent()
        assertEquals(0, rule.compileAndLint(code).size)
    }

    @Test
    fun `reports the fully qualified annotation form too`() {
        val code = """
            class Probe {
                @kotlin.test.AfterTest
                fun tearDown() {}
            }
        """.trimIndent()
        assertEquals(1, rule.compileAndLint(code).size)
    }

    @Test
    fun `does not report Test or BeforeTest functions`() {
        val code = """
            import kotlin.test.BeforeTest
            import kotlin.test.Test
            class Probe {
                @BeforeTest
                fun setUp() {}
                @Test
                fun `a case`() {}
            }
        """.trimIndent()
        assertEquals(0, rule.compileAndLint(code).size)
    }

    @Test
    fun `reports each racing teardown, not just the first`() {
        val code = """
            import kotlin.test.AfterTest
            class Probe {
                @AfterTest
                fun aTearDown() {}
                @AfterTest
                fun bTearDown() {}
            }
        """.trimIndent()
        assertEquals(2, rule.compileAndLint(code).size)
    }

    @Test
    fun `reports a bare unimported AfterTest, which only the short-name match sees`() {
        // The guessed-FQ match needs an import to guess from; the short-name
        // arm is what catches this shape. Dropping that arm fails exactly
        // this case -- it was measured to be the union's only unpinned half.
        val code = """
            class Probe {
                @AfterTest
                fun closeLoop() {}
            }
        """.trimIndent()
        assertEquals(1, rule.compileAndLint(code).size)
    }

    @Test
    fun `reports an import-aliased AfterTest too`() {
        // Measured hole before the fully-qualified match existed: the alias
        // passed the short-name check and the gate stayed green.
        val code = """
            import kotlin.test.AfterTest as Cleanup
            class Probe {
                @Cleanup
                fun closeLoop() {}
            }
        """.trimIndent()
        assertEquals(1, rule.compileAndLint(code).size)
    }

    @Test
    fun `accepts the comma-separated string form of allowedNames`() {
        // The shape every stock list-configured detekt rule accepts; the
        // hand-rolled lookup this replaces threw on it.
        val stringForm = SecondAfterTestRule(TestConfig("allowedNames" to "afterEachLoopIsGivenBack,other"))
        val code = """
            import kotlin.test.AfterTest
            class Fixture {
                @AfterTest
                fun afterEachLoopIsGivenBack() {}
            }
        """.trimIndent()
        assertEquals(0, stringForm.compileAndLint(code).size)
    }

    @Test
    fun `an empty allowlist reports every AfterTest`() {
        val bare = SecondAfterTestRule(Config.empty)
        val code = """
            import kotlin.test.AfterTest
            class Probe {
                @AfterTest
                fun afterEachLoopIsGivenBack() {}
            }
        """.trimIndent()
        assertEquals(1, bare.compileAndLint(code).size)
    }
}
