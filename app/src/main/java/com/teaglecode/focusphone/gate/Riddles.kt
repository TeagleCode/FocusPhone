package com.focus.launcher.gate

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.random.Random

/**
 * Logic puzzles that are genuinely generated, not templates with the names
 * swapped.
 *
 * A riddle in the classic sense — the pencil in the wooden case — cannot be
 * generated: the whole thing *is* its wording, and there are only ever as many
 * as someone has written down. A logic puzzle is different. Its content is a
 * set of constraints, and constraints can be produced at random and then
 * solved by brute force to check the answer is unique. That is what happens
 * here: nothing is asked unless exactly one arrangement satisfies it.
 *
 * So the deduction is real every time, rather than a memorable scenario the
 * user learns the answer to after the third showing.
 */
internal object Riddles {

    val GENERATORS: List<(Random) -> Challenge> = listOf(
        ::knightsAndKnaves, ::ordering, ::bridgeCrossing, ::jugMeasure,
        ::weighing, ::socksForPair, ::socksForColour, ::pigeonholeShare,
        ::countingLiars, ::mislabelledBoxes, ::truthAboutBoxes
    )

    private val NAMES = listOf(
        "Ana", "Bruno", "Carla", "Dario", "Elena", "Farid", "Giorgi", "Hana",
        "Ivan", "Jana", "Koba", "Lela", "Mira", "Nino", "Otar", "Petra",
        "Rustam", "Sofia", "Tamar", "Vera", "Yusuf", "Zara"
    )

    private fun names(rng: Random, count: Int): List<String> =
        NAMES.shuffled(rng).take(count)

    private fun word(prompt: String, vararg answers: String, hint: String? = null) =
        Challenge(prompt, answers.toList(), null, hint)

    private fun whole(prompt: String, value: Int, hint: String? = null) =
        Challenge(prompt, listOf(value.toString()), value.toDouble(), hint)

    // ---- Knights and knaves ---------------------------------------------

    private enum class Claim { IS_KNAVE, IS_KNIGHT, SAME, DIFFERENT, ALL_KNAVES, SOME_KNAVE }

    private data class Statement(val speaker: Int, val claim: Claim, val target: Int)

    private fun holds(s: Statement, mask: Int, n: Int): Boolean {
        fun knight(i: Int) = (mask shr i) and 1 == 1
        return when (s.claim) {
            Claim.IS_KNAVE -> !knight(s.target)
            Claim.IS_KNIGHT -> knight(s.target)
            Claim.SAME -> knight(s.target) == knight(s.speaker)
            Claim.DIFFERENT -> knight(s.target) != knight(s.speaker)
            Claim.ALL_KNAVES -> (0 until n).none { knight(it) }
            Claim.SOME_KNAVE -> (0 until n).any { !knight(it) }
        }
    }

    private fun say(s: Statement, who: List<String>): String = when (s.claim) {
        Claim.IS_KNAVE -> "${who[s.target]} is a knave."
        Claim.IS_KNIGHT -> "${who[s.target]} is a knight."
        Claim.SAME -> "${who[s.target]} and I are the same kind."
        Claim.DIFFERENT -> "${who[s.target]} and I are of different kinds."
        Claim.ALL_KNAVES -> "All of us are knaves."
        Claim.SOME_KNAVE -> "At least one of us is a knave."
    }

    /**
     * Random statements, then every assignment of knight/knave is tested and
     * the puzzle is only used when exactly one survives. Unsolvable and
     * ambiguous drafts are simply discarded.
     */
    private fun knightsAndKnaves(rng: Random): Challenge {
        val n = rng.nextInt(2, 5)
        val who = names(rng, n)

        repeat(60) {
            val statements = (0 until n).map { speaker ->
                val claim = Claim.entries.random(rng)
                val target = if (claim == Claim.ALL_KNAVES || claim == Claim.SOME_KNAVE) {
                    speaker
                } else {
                    var t = rng.nextInt(n)
                    while (t == speaker) t = rng.nextInt(n)
                    t
                }
                Statement(speaker, claim, target)
            }

            val solutions = (0 until (1 shl n)).filter { mask ->
                (0 until n).all { i ->
                    val isKnight = (mask shr i) and 1 == 1
                    isKnight == holds(statements[i], mask, n)
                }
            }
            if (solutions.size != 1) return@repeat

            val mask = solutions.single()
            val asked = rng.nextInt(n)
            val isKnight = (mask shr asked) and 1 == 1
            val lines = statements.joinToString("\n") { "${who[it.speaker]} says: \"${say(it, who)}\"" }

            return word(
                "On an island every inhabitant is either a knight, who always tells " +
                    "the truth, or a knave, who always lies.\n\n$lines\n\n" +
                    "What is ${who[asked]}? Answer with one word.",
                if (isKnight) "knight" else "knave",
                hint = "Try assuming one of them is a knight and follow it through."
            )
        }

        // A draft with no unique answer is never shown; this is the fallback.
        return word(
            "Knights always tell the truth, knaves always lie.\n\n" +
                "${who[0]} says: \"I am a knave.\"\n\n" +
                "What is ${who[0]}? Answer knight, knave, or impossible.",
            "impossible",
            hint = "Neither kind could say it."
        )
    }

    // ---- Ordering --------------------------------------------------------

    private enum class ClueKind { BEFORE, IMMEDIATELY_BEFORE, NOT_AT, AT_START, AT_END }

    private data class Clue(val kind: ClueKind, val a: Int, val b: Int)

    /** Permutations are small and reused, so they are built once per size. */
    private val PERMUTATIONS: Map<Int, List<IntArray>> =
        (3..5).associateWith { n -> permute((0 until n).toList()).map { it.toIntArray() } }

    private fun permute(items: List<Int>): List<List<Int>> {
        if (items.size <= 1) return listOf(items)
        return items.flatMap { head ->
            permute(items - head).map { listOf(head) + it }
        }
    }

    private fun satisfies(order: IntArray, clue: Clue): Boolean {
        val pos = IntArray(order.size)
        order.forEachIndexed { index, person -> pos[person] = index }
        return when (clue.kind) {
            ClueKind.BEFORE -> pos[clue.a] < pos[clue.b]
            ClueKind.IMMEDIATELY_BEFORE -> pos[clue.a] + 1 == pos[clue.b]
            ClueKind.NOT_AT -> pos[clue.a] != clue.b
            ClueKind.AT_START -> pos[clue.a] == 0
            ClueKind.AT_END -> pos[clue.a] == order.size - 1
        }
    }

    private fun render(clue: Clue, who: List<String>, n: Int): String = when (clue.kind) {
        ClueKind.BEFORE -> "${who[clue.a]} finished somewhere ahead of ${who[clue.b]}."
        ClueKind.IMMEDIATELY_BEFORE -> "${who[clue.a]} finished immediately ahead of ${who[clue.b]}."
        ClueKind.NOT_AT -> "${who[clue.a]} did not finish ${ordinal(clue.b + 1)}."
        ClueKind.AT_START -> "${who[clue.a]} won."
        ClueKind.AT_END -> "${who[clue.a]} finished last of the $n."
    }

    private fun ordinal(k: Int): String = when (k) {
        1 -> "first"; 2 -> "second"; 3 -> "third"; 4 -> "fourth"
        5 -> "fifth"; else -> "${k}th"
    }

    /**
     * Clues are drawn from a real finishing order, then the whole permutation
     * space is checked. Only a clue set with exactly one consistent order is
     * ever asked, so the puzzle is always solvable and never ambiguous.
     */
    private fun ordering(rng: Random): Challenge {
        val n = rng.nextInt(3, 6)
        val who = names(rng, n)
        val all = PERMUTATIONS.getValue(n)

        repeat(40) {
            val truth = all.random(rng)
            val pos = IntArray(n)
            truth.forEachIndexed { index, person -> pos[person] = index }

            val candidates = mutableListOf<Clue>()
            repeat(14) {
                val a = rng.nextInt(n)
                var b = rng.nextInt(n)
                while (b == a) b = rng.nextInt(n)
                val clue = when (rng.nextInt(5)) {
                    0 -> Clue(ClueKind.BEFORE, a, b)
                    1 -> Clue(ClueKind.IMMEDIATELY_BEFORE, a, b)
                    2 -> Clue(ClueKind.NOT_AT, a, rng.nextInt(n))
                    3 -> Clue(ClueKind.AT_START, a, a)
                    else -> Clue(ClueKind.AT_END, a, a)
                }
                if (satisfies(truth, clue)) candidates.add(clue)
            }
            if (candidates.size < 2) return@repeat

            // Take the smallest prefix of clues that pins the order down, so
            // the puzzle is not padded with facts that add nothing.
            val chosen = mutableListOf<Clue>()
            for (clue in candidates.distinct()) {
                chosen.add(clue)
                val consistent = all.count { order -> chosen.all { satisfies(order, it) } }
                if (consistent == 1) break
            }
            val consistent = all.count { order -> chosen.all { satisfies(order, it) } }
            if (consistent != 1 || chosen.size < 2) return@repeat

            val asked = rng.nextInt(n)
            return word(
                "$n runners finished a race: ${who.joinToString(", ")}.\n\n" +
                    chosen.joinToString("\n") { "· ${render(it, who, n)}" } +
                    "\n\nWho finished ${ordinal(asked + 1)}? Answer with a name.",
                who[truth[asked]],
                hint = "Write out the positions and eliminate."
            )
        }

        return whole(
            "Five runners finish a race. In how many different orders could they " +
                "possibly finish?",
            120, "5 × 4 × 3 × 2 × 1"
        )
    }

    // ---- Classic puzzle shapes, parameterised ---------------------------

    private fun bridgeCrossing(rng: Random): Challenge {
        val who = names(rng, 4)
        val times = generateSequence { rng.nextInt(1, 16) }.distinct().take(4).sorted().toList()
        val (a, b, c, d) = times
        // Either the fastest person ferries the torch back every time, or the
        // two slowest cross together once. Nothing else can beat both.
        val best = minOf(2 * a + b + c + d, a + 3 * b + d)
        return whole(
            "Four people must cross a bridge at night. They have one torch, the " +
                "bridge holds at most two at a time, and anyone crossing must carry " +
                "the torch. A pair crosses at the slower person's pace.\n\n" +
                "${who[0]} takes $a ${if (a == 1) "minute" else "minutes"}, " +
                "${who[1]} takes $b, ${who[2]} takes $c, and ${who[3]} takes $d.\n\n" +
                "What is the least total time, in minutes, for all four to get across?",
            best,
            "Sending the fastest back each time is not always best."
        )
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private fun jugMeasure(rng: Random): Challenge {
        val a = rng.nextInt(2, 20)
        var b = rng.nextInt(2, 20)
        while (b == a) b = rng.nextInt(2, 20)
        return whole(
            "You have a $a-litre jug, a $b-litre jug and a river. The jugs have no " +
                "markings, but you may fill, empty and pour between them freely.\n\n" +
                "What is the smallest whole number of litres you can measure exactly?",
            gcd(a, b),
            "Every amount you can reach is a multiple of the same number."
        )
    }

    private fun weighing(rng: Random): Challenge {
        val n = rng.nextInt(3, 82)
        val weighings = ceil(ln(n.toDouble()) / ln(3.0) - 1e-9).toInt()
        return whole(
            "You have $n identical-looking balls. Exactly one is slightly heavier " +
                "than the rest. You have a balance scale, which tells you only which " +
                "side is heavier or that the two sides match.\n\n" +
                "What is the smallest number of weighings that is guaranteed to find " +
                "the heavy ball?",
            weighings,
            "Each weighing has three possible outcomes, not two."
        )
    }

    private fun socksForPair(rng: Random): Challenge {
        val colours = rng.nextInt(2, 9)
        return whole(
            "A drawer holds plenty of socks in $colours different colours, all mixed " +
                "together. The room is completely dark.\n\n" +
                "How many socks must you take out to be certain of having a matching " +
                "pair?",
            colours + 1,
            "Think about the worst possible luck."
        )
    }

    private fun socksForColour(rng: Random): Challenge {
        val wanted = rng.nextInt(4, 20)
        val other = rng.nextInt(4, 20)
        return whole(
            "In the dark, a drawer holds $wanted red socks and $other blue socks.\n\n" +
                "How many socks must you take out to be certain of having two red ones?",
            other + 2,
            "You could draw every blue sock first."
        )
    }

    private fun pigeonholeShare(rng: Random): Challenge {
        val (label, groups) = listOf(
            "born in the same month" to 12,
            "born on the same day of the week" to 7,
            "with the same star sign" to 12,
            "holding the same suit" to 4
        ).random(rng)
        val share = rng.nextInt(2, 5)
        return whole(
            "How many people must be in a room to guarantee that at least $share of " +
                "them were $label?",
            groups * (share - 1) + 1,
            "Fill every group as full as you can without succeeding, then add one."
        )
    }

    private fun countingLiars(rng: Random): Challenge {
        val n = rng.nextInt(4, 30)
        return whole(
            "$n people stand in a row. The first says \"exactly 1 of us is a liar\", " +
                "the second says \"exactly 2 of us are liars\", and so on, up to the " +
                "last, who says \"exactly $n of us are liars\".\n\n" +
                "Liars always lie; everyone else always tells the truth.\n\n" +
                "How many liars are there?",
            n - 1,
            "At most one of those statements can be true."
        )
    }

    private fun mislabelledBoxes(rng: Random): Challenge {
        val (one, two) = listOf(
            "apples" to "oranges", "nuts" to "raisins",
            "red beads" to "blue beads", "coins" to "buttons"
        ).random(rng)
        return word(
            "Three boxes are labelled \"$one\", \"$two\" and \"mixed\". Every single " +
                "label is wrong.\n\n" +
                "You may draw one item, from one box, without looking inside.\n\n" +
                "Which box must you draw from to work out all three? Answer with the " +
                "word on its label.",
            "mixed",
            hint = "Which label tells you the most by being wrong?"
        )
    }

    private fun truthAboutBoxes(rng: Random): Challenge {
        val total = rng.nextInt(3, 9)
        val truthful = rng.nextInt(1, total)
        return whole(
            "$total sealed boxes sit on a table. Exactly one holds a prize.\n\n" +
                "Each box carries a note. $truthful of the notes are true and the rest " +
                "are false.\n\n" +
                "How many of the notes are false?",
            total - truthful,
            "Everything not true is false."
        )
    }
}
