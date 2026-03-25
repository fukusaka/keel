package io.github.fukusaka.keel.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.compileAndLint
import kotlin.test.Test
import kotlin.test.assertEquals

class StableRefLeakRuleTest {

    private val rule = StableRefLeakRule(Config.empty)

    @Test
    fun `reports StableRef create without dispose`() {
        val code = """
            class Foo {
                fun start() {
                    val ref = StableRef.create(this)
                }
            }
        """.trimIndent()
        val findings = rule.compileAndLint(code)
        assertEquals(1, findings.size)
    }

    @Test
    fun `no report when dispose is present`() {
        val code = """
            class Foo {
                fun start() {
                    val ref = StableRef.create(this)
                    ref.dispose()
                }
            }
        """.trimIndent()
        val findings = rule.compileAndLint(code)
        assertEquals(0, findings.size)
    }

    @Test
    fun `no report when asCPointer is present`() {
        val code = """
            class Foo {
                fun start() {
                    val ref = StableRef.create(this)
                    doSomething(ref.asCPointer())
                }
                fun doSomething(ptr: Any) {}
            }
        """.trimIndent()
        val findings = rule.compileAndLint(code)
        assertEquals(0, findings.size)
    }

    @Test
    fun `no report when keel_ cinterop call is present`() {
        val code = """
            class Foo {
                fun start() {
                    val ref = StableRef.create(this)
                    keel_nw_start(ref)
                }
                fun keel_nw_start(ref: Any) {}
            }
        """.trimIndent()
        val findings = rule.compileAndLint(code)
        assertEquals(0, findings.size)
    }
}
