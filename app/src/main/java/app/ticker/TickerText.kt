package app.ticker

internal object TickerText {
    fun line(label: String, title: String?, body: String?): String {
        val app = clean(label).ifEmpty { "App" }
        val heading = clean(title)
        val message = clean(body)
        val rest = when {
            heading.isNotEmpty() && message.isNotEmpty() &&
                !message.equals(heading, ignoreCase = true) -> "$heading: $message"
            heading.isNotEmpty() -> heading
            else -> message
        }
        return if (rest.isEmpty()) app else "$app — $rest"
    }

    private fun clean(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value.replace(Regex("\\s+"), " ").trim()
    }
}
