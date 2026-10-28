package app.limitsai.core

data class Alert(val windowId: String, val title: String, val text: String)

/**
 * Сравнивает прошлый и новый снимок и решает, о чём уведомить:
 * переход через порог (по умолчанию 80 % и 100 %) и восстановление исчерпанного лимита.
 */
object Alerts {
    val DEFAULT_THRESHOLDS = listOf(80, 100)

    fun diff(previous: UsageSnapshot?, current: UsageSnapshot, thresholds: List<Int> = DEFAULT_THRESHOLDS): List<Alert> {
        if (current.error != null) return emptyList()
        val before = previous?.windows?.associateBy { it.id }.orEmpty()
        return current.windows.mapNotNull { w ->
            val old = before[w.id] ?: return@mapNotNull null
            val title = "${current.provider.title}: ${w.label}"
            val crossed = thresholds.filter { old.usedPercent < it && w.usedPercent >= it }.maxOrNull()
            when {
                crossed != null && crossed >= 100 -> Alert(w.id, title, "Лимит исчерпан${resetSuffix(w)}")
                crossed != null -> Alert(w.id, title, "Израсходовано ${w.usedPercent.toInt()} %${resetSuffix(w)}")
                old.usedPercent >= 100 && w.usedPercent < 100 -> Alert(w.id, title, "Лимит восстановлен, можно продолжать")
                else -> null
            }
        }
    }

    private fun resetSuffix(w: UsageWindow) = if (w.resetsAt != null) ". Сброс в ${Format.clock(w.resetsAt)}" else ""
}
