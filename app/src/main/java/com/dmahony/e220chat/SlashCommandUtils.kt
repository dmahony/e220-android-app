package com.dmahony.e220chat

private val clickableLinkRegex = Regex("""(https?://[^\s]+)""")

data class ClickableMessageLink(
    val start: Int,
    val end: Int,
    val url: String
)

fun buildMapsLink(latitude: Double, longitude: Double): String {
    return "https://maps.google.com/?q=$latitude,$longitude"
}

fun buildGpsMessage(latitude: Double, longitude: Double): String {
    return "My location: ${buildMapsLink(latitude, longitude)}"
}

fun extractClickableMessageLinks(text: String): List<ClickableMessageLink> {
    return clickableLinkRegex.findAll(text).map { match ->
        ClickableMessageLink(
            start = match.range.first,
            end = match.range.last + 1,
            url = match.value
        )
    }.toList()
}
