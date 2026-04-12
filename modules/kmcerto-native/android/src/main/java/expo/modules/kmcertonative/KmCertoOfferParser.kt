package expo.modules.kmcertonative

import org.json.JSONObject
import java.util.Locale
import java.util.regex.Pattern

object KmCertoOfferParser {
    fun fromJsonPayload(payload: String?, minPerKm: Double): OfferDecisionData? {
        if (payload.isNullOrBlank()) return null
        return try {
            val json = JSONObject(payload)
            val fare = json.optDouble("totalFare", 0.0)
            val dist = json.optDouble("distanceKm", 0.0)
            val source = json.optString("sourceApp", "Teste")
            createDecision(fare, dist, minPerKm, source, payload)
        } catch (e: Exception) { null }
    }

    fun parse(text: String, minPerKm: Double, sourcePkg: String): OfferDecisionData? {
        if (text.isBlank()) return null
        val sourceApp = KmCertoRuntime.sourceLabel(sourcePkg)
        val fare = extractFare(text) ?: return null
        val distance = extractDistance(text) ?: return null
        return createDecision(fare, distance, minPerKm, sourceApp, text)
    }

    private fun extractFare(text: String): Double? {
        val moneyPattern = Pattern.compile("""R\$\s*(\d{1,3}(?:\.\d{3})*[.,]\d{2})""")
        val matcher = moneyPattern.matcher(text)
        if (matcher.find()) {
            return matcher.group(1)?.replace(".", "")?.replace(",", ".")?.toDoubleOrNull()
        }
        return null
    }

    private fun extractDistance(text: String): Double? {
        val pattern99 = Pattern.compile("""Dist[âa]ncia\s+total\s+(\d+(?:[.,]\d+)?)\s*km""", Pattern.CASE_INSENSITIVE)
        val matcher99 = pattern99.matcher(text)
        if (matcher99.find()) return matcher99.group(1)?.replace(",", ".")?.toDoubleOrNull()

        val patternUber = Pattern.compile("""\(?(\d+(?:[.,]\d+)?)\s*km\)?""", Pattern.CASE_INSENSITIVE)
        val matcherUber = patternUber.matcher(text)
        if (matcherUber.find()) return matcherUber.group(1)?.replace(",", ".")?.toDoubleOrNull()

        return null
    }

    private fun createDecision(fare: Double, distance: Double, minPerKm: Double, sourceApp: String, rawText: String): OfferDecisionData {
        val perKm = if (distance > 0) fare / distance else 0.0
        val isGood = perKm >= minPerKm
        return OfferDecisionData(
            totalFare = fare,
            totalFareLabel = String.format(Locale("pt", "BR"), "R$ %.2f", fare),
            status = if (isGood) "ACEITAR" else "REJEITAR",
            statusColor = if (isGood) "#4CAF50" else "#F44336",
            perKm = perKm,
            perHour = null,
            perMinute = null,
            minimumPerKm = minPerKm,
            sourceApp = sourceApp,
            rawText = rawText,
            distanceKm = distance,
            totalMinutes = null
        )
    }
}
