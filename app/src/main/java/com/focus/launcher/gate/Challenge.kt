package com.focus.launcher.gate

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A gate the user must pass before any restriction can be changed.
 * Generated locally so it works offline and cannot be skipped by
 * pulling the network.
 */
data class Challenge(
    val prompt: String,
    val answer: String,
    val hint: String? = null
) {
    fun accepts(input: String): Boolean {
        val given = input.trim().lowercase()
        val expected = answer.trim().lowercase()
        if (given == expected) return true
        // Numeric tolerance for trig answers.
        val g = given.toDoubleOrNull() ?: return false
        val e = expected.toDoubleOrNull() ?: return false
        return abs(g - e) < 0.02
    }
}

object ChallengeGenerator {

    fun next(rng: Random = Random.Default): Challenge =
        if (rng.nextBoolean()) trig(rng) else riddle(rng)

    // ---- Trigonometry (senior secondary level) ---------------------------

    private fun trig(rng: Random): Challenge = when (rng.nextInt(5)) {
        0 -> lawOfCosines(rng)
        1 -> lawOfSines(rng)
        2 -> identitySolve(rng)
        3 -> amplitudePeriod(rng)
        else -> exactValue(rng)
    }

    private fun lawOfCosines(rng: Random): Challenge {
        val a = rng.nextInt(5, 15)
        val b = rng.nextInt(5, 15)
        val cDeg = listOf(30, 45, 60, 120, 135).random(rng)
        val cRad = Math.toRadians(cDeg.toDouble())
        val c = Math.sqrt(a * a + b * b - 2.0 * a * b * Math.cos(cRad))
        return Challenge(
            prompt = "In triangle ABC, a = $a, b = $b, and angle C = $cDeg°.\n\n" +
                "Find side c, correct to 2 decimal places.",
            answer = String.format("%.2f", c),
            hint = "c² = a² + b² − 2ab·cos C"
        )
    }

    private fun lawOfSines(rng: Random): Challenge {
        val aDeg = rng.nextInt(25, 70)
        val bDeg = rng.nextInt(25, 180 - aDeg - 20)
        val a = rng.nextInt(6, 20)
        val b = a * Math.sin(Math.toRadians(bDeg.toDouble())) /
            Math.sin(Math.toRadians(aDeg.toDouble()))
        return Challenge(
            prompt = "In triangle ABC, angle A = $aDeg°, angle B = $bDeg°, and side a = $a.\n\n" +
                "Find side b, correct to 2 decimal places.",
            answer = String.format("%.2f", b),
            hint = "a / sin A = b / sin B"
        )
    }

    private fun identitySolve(rng: Random): Challenge {
        // sin x = k on [0, 360) — ask for the smaller solution in degrees.
        val known = listOf(
            Triple("sin", 0.5, 30), Triple("cos", 0.5, 60),
            Triple("sin", Math.sqrt(2.0) / 2, 45), Triple("cos", Math.sqrt(3.0) / 2, 30),
            Triple("tan", 1.0, 45), Triple("tan", Math.sqrt(3.0), 60)
        ).random(rng)
        val (fn, value, deg) = known
        val shown = String.format("%.4f", value)
        return Challenge(
            prompt = "Solve for x in the interval 0° ≤ x < 360°:\n\n" +
                "$fn(x) = $shown\n\n" +
                "Give the smallest solution, in degrees.",
            answer = deg.toString(),
            hint = "Think about the reference angle."
        )
    }

    private fun amplitudePeriod(rng: Random): Challenge {
        val amp = rng.nextInt(2, 9)
        val bCoef = rng.nextInt(2, 7)
        val period = (360.0 / bCoef).roundToInt()
        return Challenge(
            prompt = "The function f(x) = ${amp}·sin(${bCoef}x) − 3 is given.\n\n" +
                "What is its period, in degrees? (Give a whole number.)",
            answer = period.toString(),
            hint = "Period = 360° / b"
        )
    }

    private fun exactValue(rng: Random): Challenge {
        val items = listOf(
            "sin 150°" to "0.50", "cos 240°" to "-0.50",
            "tan 225°" to "1.00", "sin 300°" to "-0.87",
            "cos 315°" to "0.71", "tan 120°" to "-1.73"
        )
        val (expr, value) = items.random(rng)
        return Challenge(
            prompt = "Evaluate without a calculator, then give the value " +
                "correct to 2 decimal places:\n\n$expr",
            answer = value,
            hint = "Use the reference angle and the sign in that quadrant."
        )
    }

    // ---- Riddles ---------------------------------------------------------

    private val riddles = listOf(
        Challenge(
            "A man has to cross a river with a fox, a chicken and a bag of grain. " +
                "His boat holds only himself and one item. Left alone, the fox eats " +
                "the chicken and the chicken eats the grain.\n\n" +
                "What must he take back on the return trip after his first crossing?",
            "chicken"
        ),
        Challenge(
            "Two doors, two guards. One guard always lies, one always tells the truth. " +
                "You may ask one guard one question.\n\n" +
                "Which single word completes this question: 'Which door would the " +
                "OTHER guard say is ___?'",
            "safe"
        ),
        Challenge(
            "I am taken from a mine and shut in a wooden case, from which I am never " +
                "released, and yet I am used by almost everybody.\n\nWhat am I?",
            "pencil"
        ),
        Challenge(
            "You have 8 identical-looking balls. One is heavier. Using a balance " +
                "scale, what is the minimum number of weighings needed to find it " +
                "with certainty?",
            "2"
        ),
        Challenge(
            "A rope ladder hangs over the side of a ship. The rungs are 30 cm apart " +
                "and the bottom rung touches the water. The tide rises 15 cm per hour.\n\n" +
                "After 4 hours, how many rungs are underwater?",
            "0"
        ),
        Challenge(
            "There are three switches outside a room and three bulbs inside it. " +
                "You may enter the room only once.\n\n" +
                "Besides light, what property of a bulb tells you which switch was " +
                "on earlier?",
            "heat"
        )
    )

    private fun riddle(rng: Random): Challenge = riddles.random(rng)
}
