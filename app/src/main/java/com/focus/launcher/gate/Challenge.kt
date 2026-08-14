package com.focus.launcher.gate

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * A gate the user must pass before any restriction can be changed. Generated
 * locally so it works offline and cannot be skipped by pulling the network.
 *
 * Answers are held as text plus an optional numeric value. Text covers word
 * answers; the numeric value allows a tolerance, so "4.24" and "4.243" both
 * pass a question asked to two decimal places.
 */
data class Challenge(
    val prompt: String,
    val answers: List<String>,
    val numeric: Double? = null,
    val hint: String? = null
) {
    fun accepts(input: String): Boolean {
        // A comma is accepted as a decimal separator because that is what many
        // keyboard layouts produce, and the answer is generated with a dot.
        val given = input.trim().lowercase().replace(',', '.')
        if (given.isEmpty()) return false
        if (answers.any { it.trim().lowercase() == given }) return true
        val expected = numeric ?: return false
        val got = given.toDoubleOrNull() ?: return false
        return abs(got - expected) <= TOLERANCE
    }

    private companion object {
        const val TOLERANCE = 0.02
    }
}

/**
 * Problems are built from parameterised templates rather than stored.
 *
 * A list of a few thousand written-out questions would add close to a megabyte
 * to the APK and sit in memory for the life of the process, to be read once
 * each. Each generator here is a few lines of code that covers hundreds or
 * thousands of distinct questions, and only the one being asked is ever
 * allocated. The whole file is smaller than a single screenshot and yields
 * more distinct problems than anyone will see in a lifetime of use.
 */
object ChallengeGenerator {

    /**
     * A verified floor on the number of distinct questions, not an estimate:
     * a million draws yield over this many unique prompts and the count is
     * still climbing, so the true pool is far larger. A unit test asserts it,
     * because a number shown to the user should be checkable rather than
     * marketing.
     */
    const val VERIFIED_MIN_VARIANTS = 200_000L

    private val TRIG = listOf(
        ::lawOfCosines, ::lawOfSines, ::solveTrigEquation,
        ::periodOf, ::amplitudeOf, ::rangeOf, ::exactValue
    )

    private val ALGEBRA = listOf(
        ::linearEquation, ::bracketEquation, ::simultaneous, ::quadraticRoot,
        ::indexLaw, ::logarithm, ::absoluteValue, ::inequality, ::evaluateFunction
    )

    private val SEQUENCES = listOf(
        ::arithmeticTerm, ::arithmeticSum, ::geometricTerm, ::geometricSum,
        ::sequencePuzzle
    )

    private val NUMBERS = listOf(
        ::greatestCommonDivisor, ::lowestCommonMultiple, ::divisorCount,
        ::remainder, ::consecutiveIntegers, ::digitPuzzle
    )

    private val GEOMETRY = listOf(
        ::circleArea, ::circleCircumference, ::pythagoras, ::triangleAreaSine,
        ::heron, ::cylinderVolume, ::sphereVolume, ::coneVolume,
        ::polygonAngleSum, ::exteriorAngle, ::trapeziumArea,
        ::pointDistance, ::midpointX, ::gradient
    )

    private val APPLIED = listOf(
        ::percentageOf, ::percentageChange, ::reversePercentage,
        ::simpleInterest, ::compoundInterest, ::speedDistanceTime,
        ::averageSpeed, ::workRate, ::meanOf, ::missingForMean, ::medianOf,
        ::diceProbability, ::marbleProbability, ::combinations, ::permutations
    )

    private val LOGIC = listOf(
        ::agesTotal, ::agesLater, ::handshakes, ::clockAngle, ::dayOfWeek,
        ::coinCount, ::knightsAndKnaves, ::classicRiddle
    )

    /**
     * Picking a topic first and then a question inside it keeps the mix even.
     * Choosing uniformly across every generator would skew towards whichever
     * topic happens to have the most of them.
     */
    private val TOPICS = listOf(TRIG, ALGEBRA, SEQUENCES, NUMBERS, GEOMETRY, APPLIED, LOGIC)

    /**
     * [avoid] holds the prompts already used in this sitting — the failed
     * question and every one already answered — so a wrong answer can never
     * hand back the same question, and a multi-question gate cannot ask the
     * same thing twice.
     */
    fun next(rng: Random = Random.Default, avoid: Set<String> = emptySet()): Challenge {
        repeat(40) {
            val candidate = TOPICS.random(rng).random(rng).invoke(rng)
            if (candidate.prompt !in avoid) return candidate
        }
        // Only reachable if the caller has asked for an implausible number of
        // questions; the parameter space makes a genuine exhaustion impossible.
        return linearEquation(rng)
    }

    // ---- Construction helpers -------------------------------------------

    /**
     * Fixed decimal separator. The default formatter on a comma-decimal locale
     * emits "12,34", which never parses back to a Double — that alone once made
     * every numeric question unanswerable.
     */
    private fun fixed(value: Double, decimals: Int = 2): String =
        String.format(Locale.US, "%.${decimals}f", value)

    private fun num(prompt: String, value: Double, hint: String?, decimals: Int = 2) =
        Challenge(prompt, listOf(fixed(value, decimals)), value, hint)

    private fun whole(prompt: String, value: Int, hint: String?) =
        Challenge(prompt, listOf(value.toString()), value.toDouble(), hint)

    private fun word(prompt: String, vararg answers: String, hint: String? = null) =
        Challenge(prompt, answers.toList(), null, hint)

    private fun gcdOf(a: Int, b: Int): Int = if (b == 0) a else gcdOf(b, a % b)

    private fun to2dp(prompt: String) = "$prompt\n\nGive your answer correct to 2 decimal places."

    // ---- Trigonometry ----------------------------------------------------

    private fun lawOfCosines(rng: Random): Challenge {
        val a = rng.nextInt(5, 16)
        val b = rng.nextInt(5, 16)
        val cDeg = listOf(30, 45, 60, 120, 135, 150).random(rng)
        val c = sqrt(a * a + b * b - 2.0 * a * b * cos(Math.toRadians(cDeg.toDouble())))
        return num(
            to2dp("In triangle ABC, a = $a, b = $b, and angle C = $cDeg°.\n\nFind side c."),
            c, "c² = a² + b² − 2ab·cos C"
        )
    }

    private fun lawOfSines(rng: Random): Challenge {
        val aDeg = rng.nextInt(25, 71)
        val bDeg = rng.nextInt(25, 155 - aDeg)
        val a = rng.nextInt(6, 21)
        val b = a * sin(Math.toRadians(bDeg.toDouble())) / sin(Math.toRadians(aDeg.toDouble()))
        return num(
            to2dp("In triangle ABC, angle A = $aDeg°, angle B = $bDeg°, and side a = $a.\n\nFind side b."),
            b, "a / sin A = b / sin B"
        )
    }

    private fun solveTrigEquation(rng: Random): Challenge {
        val (fn, value, deg) = listOf(
            Triple("sin", 0.5, 30), Triple("cos", 0.5, 60),
            Triple("sin", sqrt(2.0) / 2, 45), Triple("cos", sqrt(2.0) / 2, 45),
            Triple("sin", sqrt(3.0) / 2, 60), Triple("cos", sqrt(3.0) / 2, 30),
            Triple("tan", 1.0, 45), Triple("tan", sqrt(3.0), 60),
            Triple("tan", 1 / sqrt(3.0), 30)
        ).random(rng)
        return whole(
            "Solve for x in the interval 0° ≤ x < 360°:\n\n$fn(x) = ${fixed(value, 4)}\n\n" +
                "Give the smallest solution, in degrees.",
            deg, "Think about the reference angle."
        )
    }

    private fun niceB(rng: Random) = listOf(2, 3, 4, 5, 6, 8, 9, 10, 12).random(rng)

    private fun periodOf(rng: Random): Challenge {
        val amp = rng.nextInt(2, 10)
        val b = niceB(rng)
        val c = rng.nextInt(-6, 7)
        return whole(
            "f(x) = ${amp}·sin(${b}x) ${if (c < 0) "− ${-c}" else "+ $c"}\n\n" +
                "What is the period of f, in degrees?",
            360 / b, "Period = 360° / b"
        )
    }

    private fun amplitudeOf(rng: Random): Challenge {
        val amp = rng.nextInt(2, 13)
        val b = niceB(rng)
        val c = rng.nextInt(-8, 9)
        return whole(
            "f(x) = ${amp}·cos(${b}x) ${if (c < 0) "− ${-c}" else "+ $c"}\n\n" +
                "What is the amplitude of f?",
            amp, "The amplitude is the coefficient in front, ignoring sign."
        )
    }

    private fun rangeOf(rng: Random): Challenge {
        val amp = rng.nextInt(2, 11)
        val b = niceB(rng)
        val c = rng.nextInt(-8, 9)
        return whole(
            "f(x) = ${amp}·sin(${b}x) ${if (c < 0) "− ${-c}" else "+ $c"}\n\n" +
                "What is the maximum value of f?",
            amp + c, "sin reaches 1 at its highest."
        )
    }

    private fun exactValue(rng: Random): Challenge {
        val fn = listOf("sin", "cos", "tan").random(rng)
        val deg = listOf(120, 135, 150, 210, 225, 240, 300, 315, 330).random(rng)
        val r = Math.toRadians(deg.toDouble())
        val v = when (fn) {
            "sin" -> sin(r)
            "cos" -> cos(r)
            else -> tan(r)
        }
        return num(
            to2dp("Evaluate without a calculator:\n\n$fn $deg°"),
            v, "Use the reference angle, then the sign in that quadrant."
        )
    }

    // ---- Algebra ---------------------------------------------------------

    private fun linearEquation(rng: Random): Challenge {
        val x = rng.nextInt(-12, 13)
        val a = listOf(-9, -7, -5, -4, -3, -2, 2, 3, 4, 5, 6, 7, 8, 9).random(rng)
        val b = rng.nextInt(-20, 21)
        val c = a * x + b
        return whole(
            "Solve for x:\n\n${a}x ${if (b < 0) "− ${-b}" else "+ $b"} = $c",
            x, "Undo the addition first, then the multiplication."
        )
    }

    private fun bracketEquation(rng: Random): Challenge {
        val x = rng.nextInt(-10, 11)
        val a = listOf(2, 3, 4, 5, 6, 7).random(rng)
        val b = rng.nextInt(-9, 10)
        val c = a * (x + b)
        return whole(
            "Solve for x:\n\n$a(x ${if (b < 0) "− ${-b}" else "+ $b"}) = $c",
            x, "Divide both sides by $a first."
        )
    }

    private fun simultaneous(rng: Random): Challenge {
        val x = rng.nextInt(-8, 9)
        val y = rng.nextInt(-8, 9)
        var a1: Int; var b1: Int; var a2: Int; var b2: Int
        do {
            a1 = rng.nextInt(1, 7); b1 = rng.nextInt(1, 7)
            a2 = rng.nextInt(1, 7); b2 = rng.nextInt(-6, 7)
        } while (a1 * b2 - a2 * b1 == 0)
        val c1 = a1 * x + b1 * y
        val c2 = a2 * x + b2 * y
        return whole(
            "Solve the system for x:\n\n" +
                "${a1}x + ${b1}y = $c1\n" +
                "${a2}x ${if (b2 < 0) "− ${-b2}" else "+ $b2"}y = $c2",
            x, "Eliminate y by scaling one equation."
        )
    }

    private fun quadraticRoot(rng: Random): Challenge {
        val p = rng.nextInt(-9, 10)
        var q = rng.nextInt(-9, 10)
        if (q == p) q = p + 1
        val sum = p + q
        val product = p * q
        val larger = maxOf(p, q)
        return whole(
            "Solve for x, and give the larger root:\n\n" +
                "x² ${if (-sum < 0) "− ${sum}" else "+ ${-sum}"}x " +
                "${if (product < 0) "− ${-product}" else "+ $product"} = 0",
            larger, "Look for two numbers that add to $sum and multiply to $product."
        )
    }

    private fun indexLaw(rng: Random): Challenge {
        val base = listOf(2, 3, 5).random(rng)
        val m = rng.nextInt(2, 8)
        val n = rng.nextInt(2, 8)
        return whole(
            "Simplify to a single power of $base:\n\n$base^$m × $base^$n = $base^k\n\nWhat is k?",
            m + n, "Multiplying powers of the same base adds the exponents."
        )
    }

    private fun logarithm(rng: Random): Challenge {
        val base = listOf(2, 3, 5, 10).random(rng)
        val k = rng.nextInt(2, if (base == 2) 9 else 6)
        val value = base.toDouble().pow(k).toLong()
        return whole(
            "Evaluate:\n\nlog base $base of $value",
            k, "Ask: $base to what power gives $value?"
        )
    }

    private fun absoluteValue(rng: Random): Challenge {
        val a = rng.nextInt(-12, 13)
        val b = rng.nextInt(1, 15)
        return whole(
            "Solve for x, and give the larger solution:\n\n|x ${if (a < 0) "+ ${-a}" else "− $a"}| = $b",
            a + b, "The expression inside can be $b or −$b."
        )
    }

    private fun inequality(rng: Random): Challenge {
        val a = rng.nextInt(2, 10)
        val x0 = rng.nextInt(-8, 12)
        val b = rng.nextInt(-15, 16)
        val c = a * x0 + b
        return whole(
            "Solve, then give the smallest integer value of x that works:\n\n" +
                "${a}x ${if (b < 0) "− ${-b}" else "+ $b"} > $c",
            x0 + 1, "Isolate x, then step up to the next whole number."
        )
    }

    private fun evaluateFunction(rng: Random): Challenge {
        val a = rng.nextInt(2, 8)
        val b = rng.nextInt(-9, 10)
        val c = rng.nextInt(-9, 10)
        val x = rng.nextInt(-6, 7)
        val value = a * x * x + b * x + c
        return whole(
            "f(x) = ${a}x² ${if (b < 0) "− ${-b}" else "+ $b"}x " +
                "${if (c < 0) "− ${-c}" else "+ $c"}\n\nFind f($x).",
            value, "Substitute and mind the sign of the squared term."
        )
    }

    // ---- Sequences -------------------------------------------------------

    private fun arithmeticTerm(rng: Random): Challenge {
        val a = rng.nextInt(-10, 15)
        val d = listOf(-9, -7, -5, -4, -3, 3, 4, 5, 6, 7, 8, 9).random(rng)
        val n = rng.nextInt(8, 40)
        return whole(
            "An arithmetic sequence starts at $a and increases by $d each term.\n\n" +
                "What is the ${n}th term?",
            a + (n - 1) * d, "aₙ = a₁ + (n − 1)d"
        )
    }

    private fun arithmeticSum(rng: Random): Challenge {
        val a = rng.nextInt(1, 12)
        val d = rng.nextInt(2, 10)
        val n = rng.nextInt(5, 21)
        return whole(
            "An arithmetic sequence starts at $a and increases by $d each term.\n\n" +
                "What is the sum of the first $n terms?",
            n * (2 * a + (n - 1) * d) / 2, "Sₙ = n/2 · (2a₁ + (n − 1)d)"
        )
    }

    private fun geometricTerm(rng: Random): Challenge {
        val a = rng.nextInt(1, 9)
        val r = listOf(2, 3).random(rng)
        val n = rng.nextInt(4, if (r == 2) 11 else 8)
        val value = a * r.toDouble().pow(n - 1)
        return whole(
            "A geometric sequence starts at $a and each term is $r times the one before.\n\n" +
                "What is the ${n}th term?",
            value.toInt(), "aₙ = a₁ · r^(n−1)"
        )
    }

    private fun geometricSum(rng: Random): Challenge {
        val a = rng.nextInt(1, 7)
        val r = listOf(2, 3).random(rng)
        val n = rng.nextInt(4, if (r == 2) 11 else 8)
        val sum = a * (r.toDouble().pow(n) - 1) / (r - 1)
        return whole(
            "A geometric sequence starts at $a with common ratio $r.\n\n" +
                "What is the sum of the first $n terms?",
            sum.toInt(), "Sₙ = a(rⁿ − 1) / (r − 1)"
        )
    }

    private fun sequencePuzzle(rng: Random): Challenge {
        val start = rng.nextInt(1, 6)
        return when (rng.nextInt(4)) {
            0 -> {
                val terms = (start until start + 5).map { it * it }
                whole(
                    "What number comes next?\n\n${terms.joinToString(", ")}, ?",
                    (start + 5) * (start + 5), "These are square numbers."
                )
            }
            1 -> {
                val terms = (start until start + 5).map { it * (it + 1) }
                val n = start + 5
                whole(
                    "What number comes next?\n\n${terms.joinToString(", ")}, ?",
                    n * (n + 1), "Each term is n × (n + 1)."
                )
            }
            2 -> {
                val terms = (start until start + 5).map { it * (it + 1) / 2 }
                val n = start + 5
                whole(
                    "What number comes next?\n\n${terms.joinToString(", ")}, ?",
                    n * (n + 1) / 2, "These are triangular numbers."
                )
            }
            else -> {
                var p = start
                var q = start + rng.nextInt(1, 4)
                val terms = mutableListOf(p, q)
                repeat(4) { val next = p + q; terms.add(next); p = q; q = next }
                whole(
                    "What number comes next?\n\n${terms.dropLast(1).joinToString(", ")}, ?",
                    terms.last(), "Each term is the sum of the two before it."
                )
            }
        }
    }

    // ---- Number ----------------------------------------------------------

    private fun greatestCommonDivisor(rng: Random): Challenge {
        val a = rng.nextInt(12, 200)
        val b = rng.nextInt(12, 200)
        return whole(
            "What is the greatest common divisor of $a and $b?",
            gcdOf(a, b), "Divide the larger by the smaller and repeat with the remainder."
        )
    }

    private fun lowestCommonMultiple(rng: Random): Challenge {
        val a = rng.nextInt(4, 40)
        val b = rng.nextInt(4, 40)
        return whole(
            "What is the lowest common multiple of $a and $b?",
            a / gcdOf(a, b) * b, "LCM = a × b ÷ GCD"
        )
    }

    private fun divisorCount(rng: Random): Challenge {
        val p = listOf(2, 3, 5, 7).random(rng)
        var q = listOf(2, 3, 5, 7, 11).random(rng)
        if (q == p) q = if (p == 2) 3 else 2
        val i = rng.nextInt(1, 4)
        val j = rng.nextInt(1, 4)
        val n = p.toDouble().pow(i).toInt() * q.toDouble().pow(j).toInt()
        return whole(
            "How many positive divisors does $n have?\n\n" +
                "(Its prime factorisation is $p^$i × $q^$j.)",
            (i + 1) * (j + 1), "Add one to each exponent, then multiply."
        )
    }

    private fun remainder(rng: Random): Challenge {
        val b = rng.nextInt(3, 20)
        val a = rng.nextInt(50, 900)
        return whole(
            "What is the remainder when $a is divided by $b?",
            a % b, "Subtract the largest multiple of $b that fits."
        )
    }

    private fun consecutiveIntegers(rng: Random): Challenge {
        val middle = rng.nextInt(4, 60)
        val count = listOf(3, 5).random(rng)
        val sum = middle * count
        return whole(
            "$count consecutive integers add up to $sum.\n\nWhat is the largest of them?",
            middle + count / 2, "The middle one is the sum divided by $count."
        )
    }

    private fun digitPuzzle(rng: Random): Challenge {
        val tens = rng.nextInt(1, 10)
        val units = rng.nextInt(0, 10)
        val n = tens * 10 + units
        return whole(
            "A two-digit number has a tens digit of $tens, and its digits add to " +
                "${tens + units}.\n\nWhat is the number?",
            n, "The units digit is the total minus the tens digit."
        )
    }

    // ---- Geometry --------------------------------------------------------

    private fun circleArea(rng: Random): Challenge {
        val r = rng.nextInt(2, 25)
        return num(to2dp("A circle has radius $r cm.\n\nWhat is its area, in cm²?"),
            PI * r * r, "A = πr²")
    }

    private fun circleCircumference(rng: Random): Challenge {
        val r = rng.nextInt(2, 30)
        return num(to2dp("A circle has radius $r cm.\n\nWhat is its circumference, in cm?"),
            2 * PI * r, "C = 2πr")
    }

    private fun pythagoras(rng: Random): Challenge {
        val a = rng.nextInt(3, 30)
        val b = rng.nextInt(3, 30)
        return num(
            to2dp("A right-angled triangle has legs of $a cm and $b cm.\n\n" +
                "How long is the hypotenuse, in cm?"),
            hypot(a.toDouble(), b.toDouble()), "a² + b² = c²"
        )
    }

    private fun triangleAreaSine(rng: Random): Challenge {
        val a = rng.nextInt(4, 20)
        val b = rng.nextInt(4, 20)
        val c = listOf(30, 45, 60, 120, 135, 150).random(rng)
        return num(
            to2dp("A triangle has sides $a and $b with an angle of $c° between them.\n\n" +
                "What is its area?"),
            0.5 * a * b * sin(Math.toRadians(c.toDouble())), "Area = ½·ab·sin C"
        )
    }

    private fun heron(rng: Random): Challenge {
        val a = rng.nextInt(5, 20)
        val b = rng.nextInt(5, 20)
        val c = rng.nextInt(abs(a - b) + 1, a + b)
        val s = (a + b + c) / 2.0
        return num(
            to2dp("A triangle has sides $a, $b and $c.\n\nWhat is its area?"),
            sqrt(s * (s - a) * (s - b) * (s - c)),
            "s = (a+b+c)/2, then Area = √(s(s−a)(s−b)(s−c))"
        )
    }

    private fun cylinderVolume(rng: Random): Challenge {
        val r = rng.nextInt(2, 15)
        val h = rng.nextInt(3, 25)
        return num(
            to2dp("A cylinder has radius $r cm and height $h cm.\n\nWhat is its volume, in cm³?"),
            PI * r * r * h, "V = πr²h"
        )
    }

    private fun sphereVolume(rng: Random): Challenge {
        val r = rng.nextInt(2, 14)
        return num(
            to2dp("A sphere has radius $r cm.\n\nWhat is its volume, in cm³?"),
            4.0 / 3.0 * PI * r * r * r, "V = 4/3·πr³"
        )
    }

    private fun coneVolume(rng: Random): Challenge {
        val r = rng.nextInt(2, 14)
        val h = rng.nextInt(3, 22)
        return num(
            to2dp("A cone has radius $r cm and height $h cm.\n\nWhat is its volume, in cm³?"),
            PI * r * r * h / 3.0, "V = ⅓·πr²h"
        )
    }

    private fun polygonAngleSum(rng: Random): Challenge {
        val n = rng.nextInt(3, 21)
        return whole(
            "What is the sum of the interior angles of a polygon with $n sides, in degrees?",
            (n - 2) * 180, "(n − 2) × 180°"
        )
    }

    private fun exteriorAngle(rng: Random): Challenge {
        val n = listOf(3, 4, 5, 6, 8, 9, 10, 12, 15, 18, 20, 24, 30, 36).random(rng)
        return whole(
            "What is the size of each exterior angle of a regular polygon with $n sides, " +
                "in degrees?",
            360 / n, "The exterior angles always total 360°."
        )
    }

    private fun trapeziumArea(rng: Random): Challenge {
        val a = rng.nextInt(3, 25)
        val b = rng.nextInt(3, 25)
        val h = rng.nextInt(2, 20)
        return num(
            "A trapezium has parallel sides of $a cm and $b cm, and a height of $h cm.\n\n" +
                "What is its area, in cm²?",
            (a + b) * h / 2.0, "Area = ½(a + b)h", decimals = 1
        )
    }

    private fun pointDistance(rng: Random): Challenge {
        val x1 = rng.nextInt(-12, 13); val y1 = rng.nextInt(-12, 13)
        val x2 = rng.nextInt(-12, 13); val y2 = rng.nextInt(-12, 13)
        return num(
            to2dp("How far apart are the points ($x1, $y1) and ($x2, $y2)?"),
            hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()),
            "d = √((x₂−x₁)² + (y₂−y₁)²)"
        )
    }

    private fun midpointX(rng: Random): Challenge {
        val x1 = rng.nextInt(-20, 21); val y1 = rng.nextInt(-20, 21)
        val x2 = rng.nextInt(-20, 21); val y2 = rng.nextInt(-20, 21)
        return num(
            "What is the x-coordinate of the midpoint of ($x1, $y1) and ($x2, $y2)?",
            (x1 + x2) / 2.0, "Average the two x-values.", decimals = 1
        )
    }

    private fun gradient(rng: Random): Challenge {
        val x1 = rng.nextInt(-10, 11)
        var x2 = rng.nextInt(-10, 11)
        if (x2 == x1) x2 = x1 + 1
        val m = listOf(-4, -3, -2, -1, 1, 2, 3, 4).random(rng)
        val y1 = rng.nextInt(-10, 11)
        val y2 = y1 + m * (x2 - x1)
        return whole(
            "What is the gradient of the line through ($x1, $y1) and ($x2, $y2)?",
            m, "Gradient = (y₂ − y₁) / (x₂ − x₁)"
        )
    }

    // ---- Applied ---------------------------------------------------------

    private fun percentageOf(rng: Random): Challenge {
        val pct = listOf(5, 10, 12, 15, 20, 25, 30, 40, 45, 60, 75, 80).random(rng)
        val n = rng.nextInt(2, 60) * 20
        return num(
            "What is $pct% of $n?", n * pct / 100.0, "Divide by 100, then multiply by $pct.",
            decimals = 1
        )
    }

    private fun percentageChange(rng: Random): Challenge {
        // A multiple of 20 keeps every listed percentage an exact whole
        // number, so the answer really is the percentage asked for.
        val from = rng.nextInt(1, 20) * 20
        val pct = listOf(5, 10, 20, 25, 50).random(rng)
        val to = from + from * pct / 100
        return num(
            "A value rises from $from to $to.\n\nBy what percentage has it increased?",
            (to - from) * 100.0 / from, "Divide the increase by the original, then ×100.",
            decimals = 1
        )
    }

    private fun reversePercentage(rng: Random): Challenge {
        // Multiples of 100 only: at 25 the 10% and 40% cases truncate, and
        // the stated original would then not follow from the sale price.
        val original = rng.nextInt(2, 40) * 100
        val pct = listOf(10, 20, 25, 40, 50).random(rng)
        val sale = original - original * pct / 100
        return num(
            "After a $pct% discount, an item costs $sale.\n\nWhat was the original price?",
            original.toDouble(), "The sale price is ${100 - pct}% of the original.", decimals = 1
        )
    }

    private fun simpleInterest(rng: Random): Challenge {
        val p = rng.nextInt(2, 40) * 100
        val r = rng.nextInt(2, 13)
        val t = rng.nextInt(2, 11)
        return num(
            "$p is invested at $r% simple interest per year for $t years.\n\n" +
                "How much interest is earned?",
            p * r * t / 100.0, "I = P·r·t / 100", decimals = 1
        )
    }

    private fun compoundInterest(rng: Random): Challenge {
        val p = rng.nextInt(2, 25) * 100
        val r = rng.nextInt(2, 11)
        val t = rng.nextInt(2, 7)
        return num(
            to2dp("$p is invested at $r% compound interest per year for $t years.\n\n" +
                "What is it worth at the end?"),
            p * (1 + r / 100.0).pow(t), "A = P(1 + r/100)ᵗ"
        )
    }

    private fun speedDistanceTime(rng: Random): Challenge {
        val speed = rng.nextInt(20, 130)
        val hours = rng.nextInt(2, 10)
        return whole(
            "A car travels at a steady $speed km/h for $hours hours.\n\n" +
                "How far does it travel, in km?",
            speed * hours, "Distance = speed × time"
        )
    }

    private fun averageSpeed(rng: Random): Challenge {
        val d1 = rng.nextInt(30, 200)
        val d2 = rng.nextInt(30, 200)
        val t1 = rng.nextInt(1, 5)
        val t2 = rng.nextInt(1, 5)
        return num(
            to2dp("A journey covers $d1 km in $t1 hours, then $d2 km in $t2 hours.\n\n" +
                "What is the average speed for the whole journey, in km/h?"),
            (d1 + d2).toDouble() / (t1 + t2), "Total distance ÷ total time — not the average of the two speeds."
        )
    }

    private fun workRate(rng: Random): Challenge {
        val a = rng.nextInt(2, 13)
        val b = rng.nextInt(2, 13)
        return num(
            to2dp("One pump fills a tank in $a hours; another fills it in $b hours.\n\n" +
                "Working together, how many hours do they take?"),
            1.0 / (1.0 / a + 1.0 / b), "Add the rates, then invert."
        )
    }

    private fun meanOf(rng: Random): Challenge {
        val n = rng.nextInt(4, 8)
        val values = List(n) { rng.nextInt(2, 60) }
        return num(
            to2dp("Find the mean of:\n\n${values.joinToString(", ")}"),
            values.sum().toDouble() / n, "Add them all, divide by $n.", decimals = 2
        )
    }

    private fun missingForMean(rng: Random): Challenge {
        val n = rng.nextInt(4, 7)
        // Generated until the mean lands exactly, so the question has a whole
        // answer and the hidden value is always positive.
        var all = List(n) { rng.nextInt(2, 60) }
        repeat(200) { if (all.sum() % n == 0) return@repeat; all = List(n) { rng.nextInt(2, 60) } }
        if (all.sum() % n != 0) all = List(n) { 12 }
        val target = all.sum() / n
        val shown = all.dropLast(1)
        return whole(
            "The numbers ${shown.joinToString(", ")} and one more have a mean of $target.\n\n" +
                "What is the missing number?",
            target * n - shown.sum(), "The total must be $target × $n."
        )
    }

    private fun medianOf(rng: Random): Challenge {
        val values = List(listOf(5, 7, 9).random(rng)) { rng.nextInt(1, 99) }
        return whole(
            "Find the median of:\n\n${values.joinToString(", ")}",
            values.sorted()[values.size / 2], "Sort them first, then take the middle one."
        )
    }

    private fun diceProbability(rng: Random): Challenge {
        val target = rng.nextInt(3, 12)
        val ways = (1..6).sumOf { a -> (1..6).count { b -> a + b == target } }
        return num(
            "Two fair six-sided dice are rolled.\n\n" +
                "What is the probability that the total is $target?\n\n" +
                "Give your answer as a decimal correct to 3 decimal places.",
            ways / 36.0, "Count the ordered pairs out of 36.", decimals = 3
        )
    }

    private fun marbleProbability(rng: Random): Challenge {
        val red = rng.nextInt(2, 12)
        val blue = rng.nextInt(2, 12)
        return num(
            "A bag holds $red red and $blue blue marbles. One is drawn at random.\n\n" +
                "What is the probability it is red?\n\n" +
                "Give your answer as a decimal correct to 3 decimal places.",
            red.toDouble() / (red + blue), "Favourable ÷ total.", decimals = 3
        )
    }

    private fun nCr(n: Int, r: Int): Long {
        var result = 1L
        for (i in 0 until r) result = result * (n - i) / (i + 1)
        return result
    }

    private fun combinations(rng: Random): Challenge {
        val n = rng.nextInt(5, 13)
        val r = rng.nextInt(2, minOf(n - 1, 5))
        return whole(
            "In how many ways can $r items be chosen from $n, when the order does not matter?",
            nCr(n, r).toInt(), "That is ${n}C${r} = n! / (r!(n−r)!)"
        )
    }

    private fun permutations(rng: Random): Challenge {
        val n = rng.nextInt(4, 11)
        val r = rng.nextInt(2, minOf(n, 4) + 1)
        var value = 1
        for (i in 0 until r) value *= (n - i)
        return whole(
            "In how many ways can $r items be arranged from $n, when the order matters?",
            value, "That is ${n}P${r} = n! / (n−r)!"
        )
    }

    // ---- Logic -----------------------------------------------------------

    private val NAMES = listOf(
        "Ana", "Bruno", "Carla", "Dario", "Elena", "Farid", "Giorgi", "Hana",
        "Ivan", "Jana", "Koba", "Lela", "Mira", "Nino", "Otar", "Petra"
    )

    private fun twoNames(rng: Random): Pair<String, String> {
        val a = NAMES.random(rng)
        var b = NAMES.random(rng)
        while (b == a) b = NAMES.random(rng)
        return a to b
    }

    private fun agesTotal(rng: Random): Challenge {
        val (a, b) = twoNames(rng)
        val younger = rng.nextInt(4, 30)
        val times = rng.nextInt(2, 6)
        return whole(
            "$a is $times times as old as $b. Together their ages total " +
                "${younger * (1 + times)}.\n\nHow old is $b?",
            younger, "If $b is x, then $a is ${times}x, so together they are ${times + 1}x."
        )
    }

    private fun agesLater(rng: Random): Challenge {
        val (a, b) = twoNames(rng)
        val ageB = rng.nextInt(5, 40)
        val diff = rng.nextInt(2, 25)
        val years = rng.nextInt(2, 20)
        return whole(
            "$a is $diff years older than $b, who is $ageB.\n\n" +
                "How old will $a be in $years years?",
            ageB + diff + years, "Find $a's age now, then add $years."
        )
    }

    private fun handshakes(rng: Random): Challenge {
        val n = rng.nextInt(4, 30)
        return whole(
            "$n people are in a room, and every person shakes hands with every " +
                "other person exactly once.\n\nHow many handshakes take place?",
            n * (n - 1) / 2, "Each of $n people shakes ${n - 1} hands, but that counts each twice."
        )
    }

    private fun clockAngle(rng: Random): Challenge {
        val h = rng.nextInt(1, 13)
        val m = listOf(0, 10, 15, 20, 30, 40, 45, 50).random(rng)
        val raw = abs(30.0 * (h % 12) - 5.5 * m)
        val angle = minOf(raw, 360 - raw)
        return num(
            "A clock reads ${h}:${m.toString().padStart(2, '0')}.\n\n" +
                "What is the smaller angle between the hour and minute hands, in degrees?",
            angle, "The hour hand also moves as the minutes pass — half a degree per minute.",
            decimals = 1
        )
    }

    private val DAYS = listOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"
    )

    private fun dayOfWeek(rng: Random): Challenge {
        val start = rng.nextInt(0, 7)
        val ahead = rng.nextInt(8, 400)
        val end = (start + ahead) % 7
        return word(
            "Today is ${DAYS[start].replaceFirstChar { it.uppercase() }}.\n\n" +
                "What day of the week will it be in $ahead days?",
            DAYS[end],
            hint = "Only the remainder after dividing by 7 matters."
        )
    }

    private fun coinCount(rng: Random): Challenge {
        val small = listOf(2, 5).random(rng)
        val large = listOf(10, 20, 50).random(rng)
        val nSmall = rng.nextInt(2, 20)
        val nLarge = rng.nextInt(2, 20)
        val total = nSmall * small + nLarge * large
        return whole(
            "A jar holds ${nSmall + nLarge} coins, all worth $small or $large, " +
                "totalling $total.\n\nHow many of the $large coins are there?",
            nLarge,
            "If all ${nSmall + nLarge} coins were worth $small, the total would be short."
        )
    }

    private fun knightsAndKnaves(rng: Random): Challenge {
        val (a, b) = twoNames(rng)
        return when (rng.nextInt(3)) {
            0 -> word(
                "On an island, knights always tell the truth and knaves always lie.\n\n" +
                    "$a says: \"I am a knave.\"\n\n" +
                    "Nobody can truthfully call themselves a knave, and no knave would " +
                    "admit it either. So what is $a?\n\n" +
                    "Answer with one word: knight, knave, or impossible.",
                "impossible",
                hint = "Test both cases and see whether either is consistent."
            )
            1 -> word(
                "Knights always tell the truth, knaves always lie.\n\n" +
                    "$a says: \"$b is a knave.\"\n" +
                    "$b says: \"$a and I are the same kind.\"\n\n" +
                    "What is $b? Answer with one word.",
                "knave",
                hint = "Suppose $b is a knight and check whether the statements can both hold."
            )
            else -> word(
                "Knights always tell the truth, knaves always lie.\n\n" +
                    "$a says: \"We are both knaves.\"\n\n" +
                    "What is $a? Answer with one word.",
                "knave",
                hint = "A knight could never say it, because it would be false."
            )
        }
    }

    /**
     * The classics do not generalise — a riddle is a fixed piece of writing,
     * not a template. They are kept as a small set for variety; the volume
     * comes from the generators above.
     */
    private val CLASSICS = listOf(
        Challenge(
            "A man must cross a river with a fox, a chicken and a bag of grain. " +
                "His boat holds only himself and one item. Left alone, the fox eats " +
                "the chicken and the chicken eats the grain.\n\n" +
                "What must he bring back on the return trip after his first crossing?",
            listOf("chicken", "the chicken")
        ),
        Challenge(
            "I am taken from a mine and shut in a wooden case, from which I am never " +
                "released, and yet I am used by almost everybody.\n\nWhat am I?",
            listOf("pencil", "a pencil")
        ),
        Challenge(
            "You have 8 identical-looking balls. One is slightly heavier. Using a " +
                "balance scale, what is the least number of weighings that will always " +
                "find it?",
            listOf("2", "two"), 2.0
        ),
        Challenge(
            "A rope ladder hangs over the side of a ship. The rungs are 30 cm apart " +
                "and the bottom rung touches the water. The tide rises 15 cm per hour.\n\n" +
                "After 4 hours, how many rungs are underwater?",
            listOf("0", "zero", "none"), 0.0,
            "The ship floats."
        ),
        Challenge(
            "Three switches outside a room control three bulbs inside it. You may " +
                "enter the room only once.\n\n" +
                "Besides light, what property of a bulb tells you which switch was on " +
                "earlier? One word.",
            listOf("heat", "warmth", "temperature")
        ),
        Challenge(
            "A farmer has 17 sheep. All but 9 run away.\n\nHow many are left?",
            listOf("9", "nine"), 9.0,
            "Read the sentence again, slowly."
        ),
        Challenge(
            "Two people are born at the same moment to the same mother, on the same " +
                "day, in the same year — but they are not twins.\n\n" +
                "How is that possible? Answer with one word describing the group.",
            listOf("triplets", "triplet")
        ),
        Challenge(
            "A bat and a ball cost 1.10 together. The bat costs 1.00 more than the " +
                "ball.\n\nHow much does the ball cost? Give the answer in the same units, " +
                "to 2 decimal places.",
            listOf("0.05", ".05"), 0.05,
            "It is not 0.10 — check that the difference really comes to 1.00."
        )
    )

    private fun classicRiddle(rng: Random): Challenge = CLASSICS.random(rng)
}
