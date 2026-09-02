package dev.local.physicalmemory.domain.parser

import dev.local.physicalmemory.domain.model.MAX_ITEM_LENGTH
import dev.local.physicalmemory.domain.model.MAX_LOCATION_LENGTH
import dev.local.physicalmemory.domain.model.normalizeItemText

sealed interface Command {
    data class Store(val item: String, val location: String) : Command
    data class Find(val item: String) : Command
    data object Unknown : Command
}

class CommandParser {
    private val storeSeparator = Regex("放在|放到|放进|在|放")
    private val findSuffix = Regex("^(.+?)(?:放在哪里|放在哪儿|放在哪|放哪了|在哪里|在哪儿|在哪)$")
    private val boundaryPunctuation = "“”‘’\"'！？?!。．.,，;；"
    private val internalPunctuation = Regex("[，,；;。！？!?]")
    private val questionLocation = Regex("^(?:哪|什么|何处|吗|不在).*|.*(?:吗|么|呢)$")
    // A verb inside an attributive location is not a second command: 放药的柜子 / 存放杂物的箱子.
    private val locationDescription = Regex("(?:存放|放在|放到|放进|放)[^在放，,；;。！？!?]+的")

    fun parse(input: String): Command {
        if (input.length > 512) return Command.Unknown
        val text = normalizeItemText(input.trim { it.isWhitespace() || it in boundaryPunctuation })
        if (text.isBlank() || internalPunctuation.containsMatchIn(text)) return Command.Unknown

        // Queries must win over the bare '放' store separator (e.g. 护照放哪了).
        findSuffix.matchEntire(text)?.let {
            val item = normalizeItemText(it.groupValues[1])
            return if (validItem(item)) Command.Find(item) else Command.Unknown
        }
        // Unsupported questions must never accidentally become writes.
        if (input.any { it == '?' || it == '？' }) return Command.Unknown

        // Find the separator once, without regex backtracking from 放在 to 放.
        val separator = storeSeparator.find(text) ?: return Command.Unknown
        val item = normalizeItemText(text.substring(0, separator.range.first))
        val location = normalizeItemText(text.substring(separator.range.last + 1))
        if (!validItem(item) || location.isBlank() || location.length > MAX_LOCATION_LENGTH ||
            questionLocation.matches(location) || hasAnotherCommand(location)
        ) return Command.Unknown
        return Command.Store(item, location)
    }

    private fun validItem(item: String) = item.isNotBlank() && item.length <= MAX_ITEM_LENGTH &&
        !storeSeparator.containsMatchIn(item)

    private fun hasAnotherCommand(location: String): Boolean =
        storeSeparator.containsMatchIn(locationDescription.replace(location, ""))
}
