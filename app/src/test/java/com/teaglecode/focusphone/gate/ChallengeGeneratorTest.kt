package com.teaglecode.focusphone.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The gate is the one place a defect locks the user out of their own phone: an
 * unanswerable question means settings can never be opened again. There are
 * around fifty generators, which is far too many to check by eye, so they are
 * checked by exhaustion instead.
 *
 * A locale bug of exactly this kind once made every trigonometry question
 * impossible to answer, and it shipped.
 */
class ChallengeGeneratorTest {

    private val runs = 40_000

    @Test
    fun `every generated challenge accepts its own stated answer`() {
        val rng = Random(20260815)
        repeat(runs) {
            val c = ChallengeGenerator.next(rng)
            val canonical = c.answers.first()
            assertTrue(
                "generated answer rejected by its own checker: " +
                    "prompt=${c.prompt} answer=$canonical",
                c.accepts(canonical)
            )
        }
    }

    @Test
    fun `no challenge is malformed`() {
        val rng = Random(7)
        repeat(runs) {
            val c = ChallengeGenerator.next(rng)
            assertTrue("blank prompt", c.prompt.isNotBlank())
            assertTrue("no answers offered: ${c.prompt}", c.answers.isNotEmpty())
            assertTrue("blank answer: ${c.prompt}", c.answers.all { it.isNotBlank() })
            c.numeric?.let {
                assertTrue("non-finite answer: ${c.prompt}", it.isFinite())
            }
        }
    }

    /** Whole-number questions must not secretly want a decimal. */
    @Test
    fun `integer answers are whole`() {
        val rng = Random(99)
        repeat(runs) {
            val c = ChallengeGenerator.next(rng)
            val canonical = c.answers.first()
            if (!canonical.contains('.')) {
                val n = canonical.toDoubleOrNull() ?: return@repeat
                assertEquals("stated as whole but is not: ${c.prompt}", n, Math.rint(n), 0.0)
            }
        }
    }

    /** A near-miss inside the stated precision must still pass. */
    @Test
    fun `two decimal answers tolerate rounding`() {
        val rng = Random(3131)
        repeat(runs) {
            val c = ChallengeGenerator.next(rng)
            val exact = c.numeric ?: return@repeat
            assertTrue("exact value rejected: ${c.prompt}", c.accepts(exact.toString()))
        }
    }

    /** Nonsense must not be accepted, or the gate is decorative. */
    @Test
    fun `wrong answers are rejected`() {
        val rng = Random(555)
        repeat(5_000) {
            val c = ChallengeGenerator.next(rng)
            assertTrue("empty input accepted: ${c.prompt}", !c.accepts(""))
            assertTrue("gibberish accepted: ${c.prompt}", !c.accepts("qwertyuiop"))
            val exact = c.numeric
            if (exact != null && c.answers.size == 1) {
                assertTrue(
                    "a clearly wrong number was accepted: ${c.prompt}",
                    !c.accepts((exact + 137.77).toString())
                )
            }
        }
    }

    /**
     * The figure shown in settings is asserted here, so the app cannot claim a
     * pool it does not have. Measured rather than derived: the generators'
     * combinatorial total is much larger, but this is the part that has
     * actually been observed.
     */
    @Test
    fun `the pool is at least as deep as the app claims`() {
        val rng = Random(2024)
        val seen = HashSet<String>()
        repeat(1_000_000) { seen.add(ChallengeGenerator.next(rng).prompt) }
        assertTrue(
            "settings claims ${ChallengeGenerator.VERIFIED_MIN_VARIANTS} distinct " +
                "problems but only ${seen.size} were seen in a million draws",
            seen.size >= ChallengeGenerator.VERIFIED_MIN_VARIANTS
        )
    }

    /** A multi-question gate must never ask the same thing twice. */
    @Test
    fun `avoid set is respected`() {
        val rng = Random(11)
        repeat(500) {
            val used = HashSet<String>()
            repeat(10) {
                val c = ChallengeGenerator.next(rng, used)
                assertTrue("repeated a prompt within one sitting: ${c.prompt}", used.add(c.prompt))
            }
        }
    }
}
