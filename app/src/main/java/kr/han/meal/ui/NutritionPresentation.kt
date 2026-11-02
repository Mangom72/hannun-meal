package kr.han.meal.ui

internal data class NutritionSection(val title: String, val entries: List<String>)

/** Presentation only: never rewrites labels, values, units, or repeated source lines. */
internal fun nutritionSections(raw: String): List<NutritionSection> {
    if (raw.isBlank()) return emptyList()
    val lines = raw.lines().filter { it.isNotBlank() }
    fun original() = listOf(NutritionSection("원문", listOf(raw)))

    // Input is already normalized by DishParser.plain. Do not guess HTML, tables,
    // inline lists, or several fields squeezed into a single source line.
    if (lines.any { line ->
            line.count { it == ':' } > 1 || line.any { it in "<>|;" } ||
                line.contains("&lt;", ignoreCase = true)
        }) return original()

    val groups = linkedMapOf(
        "주요 영양소" to mutableListOf<String>(),
        "비타민" to mutableListOf<String>(),
        "무기질" to mutableListOf<String>(),
        "기타 정보" to mutableListOf<String>(),
    )
    val labels = lines.map { nutritionLabel.matchEntire(it)?.groupValues?.get(1) }
    if (lines.size == 1 && labels.single() == null) return original()
    lines.zip(labels).forEach { (line, label) ->
        val title = when {
            label in majorNutrients -> "주요 영양소"
            label != null && (label.matches(vitaminName) || label in vitaminNames) -> "비타민"
            label in mineralNames -> "무기질"
            else -> "기타 정보"
        }
        groups.getValue(title).add(line)
    }
    return groups.filterValues { it.isNotEmpty() }.map { (title, entries) ->
        NutritionSection(title, entries.toList())
    }
}

// Only a complete label followed by an optional unit and one colon is recognized.
// Values are deliberately not parsed as numbers (signs, precision and units survive).
private val nutritionLabel = Regex("\\s*([가-힣A-Za-z0-9]+)\\s*(?:\\([^()\\r\\n]+\\))?\\s*:\\s*\\S[^:\\r\\n]*")
private val majorNutrients = setOf("탄수화물", "단백질", "지방")
private val vitaminName = Regex("비타민(?:[A-Za-z](?:[0-9]+)?|[가-힣]+)")
private val vitaminNames = setOf("비타민", "티아민", "리보플라빈", "나이아신", "엽산")
private val mineralNames = setOf(
    "칼슘", "철분", "철", "나트륨", "칼륨", "마그네슘", "아연", "인", "구리", "망간", "셀레늄", "요오드", "몰리브덴", "크롬", "불소",
)
