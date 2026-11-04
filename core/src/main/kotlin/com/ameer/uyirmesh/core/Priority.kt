package com.ameer.uyirmesh.core

import kotlin.math.roundToInt

/**
 * Random Forest classifier exported from scikit-learn, evaluated on the phone.
 * Each tree stores sklearn's arrays: children_left, children_right, feature, threshold and the normalised class
 * distribution at every node. The distribution at internal nodes allows per-prediction explanations
 * (tree-path contributions: how much each feature moved the prediction).
 */
class ForestModel(
    val featureNames: List<String>,
    val classNames: List<String>,
    private val trees: List<Tree>,
    val meta: Map<String, Any?> = emptyMap(),
) {
    class Tree(
        val left: IntArray,
        val right: IntArray,
        val feature: IntArray,
        val threshold: DoubleArray,
        val value: Array<DoubleArray>,
    )

    class Explanation(val proba: DoubleArray, val bias: DoubleArray, val contributions: Array<DoubleArray>)

    val nClasses: Int get() = classNames.size
    val nFeatures: Int get() = featureNames.size
    val nTrees: Int get() = trees.size

    fun predictProba(x: DoubleArray): DoubleArray {
        require(x.size == nFeatures) { "Expected $nFeatures features, got ${x.size}" }
        val acc = DoubleArray(nClasses)
        for (t in trees) {
            val leaf = t.value[leafOf(t, x)]
            for (c in 0 until nClasses) acc[c] += leaf[c]
        }
        for (c in 0 until nClasses) acc[c] /= trees.size
        return acc
    }

    /** proba = bias + sum over features of contributions[feature]. */
    fun explain(x: DoubleArray): Explanation {
        require(x.size == nFeatures) { "Expected $nFeatures features, got ${x.size}" }
        val bias = DoubleArray(nClasses)
        val contrib = Array(nFeatures) { DoubleArray(nClasses) }
        val proba = DoubleArray(nClasses)
        for (t in trees) {
            var node = 0
            for (c in 0 until nClasses) bias[c] += t.value[0][c]
            while (t.left[node] >= 0) {
                val f = t.feature[node]
                val next = if (x[f] <= t.threshold[node]) t.left[node] else t.right[node]
                for (c in 0 until nClasses) contrib[f][c] += t.value[next][c] - t.value[node][c]
                node = next
            }
            for (c in 0 until nClasses) proba[c] += t.value[node][c]
        }
        val n = trees.size.toDouble()
        for (c in 0 until nClasses) {
            bias[c] /= n
            proba[c] /= n
        }
        for (f in 0 until nFeatures) for (c in 0 until nClasses) contrib[f][c] /= n
        return Explanation(proba, bias, contrib)
    }

    private fun leafOf(t: Tree, x: DoubleArray): Int {
        var node = 0
        while (t.left[node] >= 0) {
            node = if (x[t.feature[node]] <= t.threshold[node]) t.left[node] else t.right[node]
        }
        return node
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromJson(text: String): ForestModel {
            val m = Json.parseObject(text)
            val features = m.strList("features")
            val classes = m.strList("classes")
            val trees = m.list("trees").map { tAny ->
                val t = tAny as Map<String, Any?>
                val left = t.list("left").map { (it as Number).toInt() }.toIntArray()
                val right = t.list("right").map { (it as Number).toInt() }.toIntArray()
                val feature = t.list("feature").map { (it as Number).toInt() }.toIntArray()
                val threshold = t.list("threshold").map { (it as Number).toDouble() }.toDoubleArray()
                val value = t.list("value").map { row ->
                    (row as List<Any?>).map { (it as Number).toDouble() }.toDoubleArray()
                }.toTypedArray()
                require(left.size == right.size && left.size == feature.size && left.size == threshold.size && left.size == value.size) {
                    "Inconsistent tree arrays"
                }
                Tree(left, right, feature, threshold, value)
            }
            require(trees.isNotEmpty()) { "Model has no trees" }
            return ForestModel(features, classes, trees, m.obj("meta"))
        }
    }
}

/** Inputs that the priority model looks at, taken from an SOS. */
data class TriageInput(
    val kind: String,
    val people: Int,
    val children: Int,
    val elderly: Int,
    val disabled: Int,
    val pregnant: Int,
    val injured: Boolean,
    val needs: Collection<String>,
    val battery: Int,
) {
    companion object {
        fun from(p: SosPayload) = TriageInput(
            p.kind, p.people, p.children, p.elderly, p.disabled, p.pregnant, p.injured, p.needs, p.battery,
        )
    }
}

data class Reason(val label: String, val points: Int)

data class PriorityResult(
    val score: Int,
    val label: String,
    val modelScore: Int,
    val proba: DoubleArray,
    val reasons: List<Reason>,
)

/**
 * Turns the model output into a 0-100+ urgency score with readable reasons.
 *  score = expected urgency from the class probabilities (Low 10, Medium 40, High 70, Critical 95)
 *        + aging (1 point per minute waiting, up to 30) + repeated messages (3 points each, up to 12)
 */
object Triage {
    val FEATURES = listOf(
        "kind_trapped", "kind_medical", "kind_fire", "kind_flood", "kind_need",
        "people", "children", "elderly", "disabled", "pregnant", "injured",
        "need_water", "need_food", "need_medicine", "need_baby_food", "need_boat", "battery",
    )
    val CLASSES = listOf("Low", "Medium", "High", "Critical")
    private val CLASS_POINTS = doubleArrayOf(10.0, 40.0, 70.0, 95.0)

    fun features(i: TriageInput): DoubleArray = doubleArrayOf(
        if (i.kind == EmergencyKind.TRAPPED) 1.0 else 0.0,
        if (i.kind == EmergencyKind.MEDICAL) 1.0 else 0.0,
        if (i.kind == EmergencyKind.FIRE) 1.0 else 0.0,
        if (i.kind == EmergencyKind.FLOOD) 1.0 else 0.0,
        if (i.kind == EmergencyKind.NEED) 1.0 else 0.0,
        i.people.coerceIn(1, 50).toDouble(),
        i.children.coerceIn(0, 50).toDouble(),
        i.elderly.coerceIn(0, 50).toDouble(),
        i.disabled.coerceIn(0, 50).toDouble(),
        i.pregnant.coerceIn(0, 50).toDouble(),
        if (i.injured) 1.0 else 0.0,
        if (Needs.WATER in i.needs) 1.0 else 0.0,
        if (Needs.FOOD in i.needs) 1.0 else 0.0,
        if (Needs.MEDICINE in i.needs) 1.0 else 0.0,
        if (Needs.BABY_FOOD in i.needs) 1.0 else 0.0,
        if (Needs.BOAT in i.needs) 1.0 else 0.0,
        (if (i.battery < 0) 50 else i.battery.coerceIn(0, 100)).toDouble(),
    )

    fun labelFor(score: Int): String = when {
        score >= 80 -> "Critical"
        score >= 60 -> "High"
        score >= 35 -> "Medium"
        else -> "Low"
    }

    fun assess(model: ForestModel, input: TriageInput, waitingMinutes: Double, repeats: Int): PriorityResult {
        val x = features(input)
        val e = model.explain(x)
        val expected = (0 until model.nClasses).sumOf { e.proba[it] * CLASS_POINTS[it] }
        // Points each feature added to the expected urgency.
        val featurePoints = DoubleArray(model.nFeatures) { f ->
            (0 until model.nClasses).sumOf { c -> e.contributions[f][c] * CLASS_POINTS[c] }
        }
        val reasons = ArrayList<Reason>()
        val kindPoints = (0..4).sumOf { featurePoints[it] }
        reasons.add(Reason(kindLabel(input.kind), kindPoints.roundToInt()))
        fun add(idx: Int, label: String) {
            reasons.add(Reason(label, featurePoints[idx].roundToInt()))
        }
        add(5, "${input.people} ${if (input.people == 1) "person" else "people"}")
        if (input.children > 0) add(6, "${input.children} ${if (input.children == 1) "child" else "children"}")
        if (input.elderly > 0) add(7, "${input.elderly} elderly")
        if (input.disabled > 0) add(8, "${input.disabled} disabled")
        if (input.pregnant > 0) add(9, "${input.pregnant} pregnant")
        if (input.injured) add(10, "Injured")
        if (Needs.WATER in input.needs) add(11, "Needs water")
        if (Needs.FOOD in input.needs) add(12, "Needs food")
        if (Needs.MEDICINE in input.needs) add(13, "Needs medicine")
        if (Needs.BABY_FOOD in input.needs) add(14, "Needs baby food")
        if (Needs.BOAT in input.needs) add(15, "Needs a boat")
        if (input.battery in 0..100) add(16, "Battery ${input.battery}%")

        val aging = minOf(30, waitingMinutes.toInt().coerceAtLeast(0))
        if (aging > 0) reasons.add(Reason("Waiting ${waitingMinutes.toInt()} min", aging))
        val repeatPts = minOf(12, repeats.coerceAtLeast(0) * 3)
        if (repeatPts > 0) reasons.add(Reason("Sent ${repeats + 1} times", repeatPts))

        val modelScore = expected.roundToInt()
        val score = modelScore + aging + repeatPts
        val sorted = reasons.filter { it.points > 0 }.sortedByDescending { it.points }
        return PriorityResult(score, labelFor(score), modelScore, e.proba, sorted)
    }

    fun kindLabel(kind: String): String = when (kind) {
        EmergencyKind.TRAPPED -> "Trapped"
        EmergencyKind.MEDICAL -> "Medical emergency"
        EmergencyKind.FIRE -> "Fire"
        EmergencyKind.FLOOD -> "Flood water rising"
        EmergencyKind.NEED -> "Needs supplies"
        else -> kind
    }
}
