package io.github.avinash006.pocketinferencekit.core

internal class StopSequenceFilter(private val sequences: List<String>) {
    private var pending = ""
    var stopped: Boolean = false
        private set

    fun append(chunk: String): String {
        if (stopped) return ""
        if (sequences.isEmpty()) return chunk

        val text = pending + chunk
        val stopIndex = sequences.map { text.indexOf(it) }.filter { it >= 0 }.minOrNull()
        if (stopIndex != null) {
            stopped = true
            pending = ""
            return text.substring(0, stopIndex)
        }

        val held = sequences.maxOf { sequence ->
            var length = minOf(sequence.length - 1, text.length)
            while (length > 0 && !text.regionMatches(text.length - length, sequence, 0, length)) length--
            length
        }
        pending = text.takeLast(held)
        return text.dropLast(held)
    }

    fun finish(): String = pending.also { pending = "" }
}
