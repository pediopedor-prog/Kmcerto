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
            val mins = if (json.has("totalMinutes")) json.optDouble("totalMinutes", 0.0) else null
            val source = json.optString("sourceApp", "Teste")
            createDecision(fare, dist, mins, minPerKm, source, payload)
        } catch (e: Exception) { null }
    }

    fun parse(text: String, minPerKm: Double, sourcePkg: String): OfferDecisionData? {
        if (text.isBlank()) return null
        val sourceApp = KmCertoRuntime.sourceLabel(sourcePkg)
        val fare = extractFare(text) ?: return null
        
        // Se for o gatilho "X corrida(s)" da 99, a distância pode vir em outro evento.
        // O parser tenta extrair a distância, se não encontrar, verificamos se é um gatilho.
        var distance = extractDistance(text)
        
        if (distance == null) {
            // Se contém "corrida(s)" (99) ou "COMEÇAR" (Uber), tratamos como gatilho de leitura
            if (text.contains("corrida(s)", ignoreCase = true) || text.contains("COMEÇAR", ignoreCase = true)) {
                distance = 0.0 // Valor simbólico para não descartar a oferta
            } else {
                return null
            }
        }
        
        val minutes = extractMinutes(text)
        return createDecision(fare, distance, minutes, minPerKm, sourceApp, text)
    }

    /**
     * Extrai valor monetário do texto.
     * Suporta formatos:
     *   R$ 12,20  |  R$6,70  |  R$ 7,50  |  R$ 1.250,00
     *   12,20 (sem R$ — fallback)
     */
    private fun extractFare(text: String): Double? {
        // Padrão principal: R$ seguido de valor
        // Aceita com ou sem espaço, com ou sem ponto de milhar
        val moneyPattern = Pattern.compile(
            """R\$\s*(\d{1,3}(?:\.\d{3})*[.,]\d{1,2})"""
        )
        val matcher = moneyPattern.matcher(text)
        if (matcher.find()) {
            return matcher.group(1)
                ?.replace(".", "")
                ?.replace(",", ".")
                ?.toDoubleOrNull()
        }

        // Suporte para "+R$ 3" (Uber Boost/Gatilho)
        val boostPattern = Pattern.compile("""\+R\$\s*(\d+(?:[.,]\d+)?)""")
        val boostMatcher = boostPattern.matcher(text)
        if (boostMatcher.find()) {
            return boostMatcher.group(1)?.replace(",", ".")?.toDoubleOrNull()
        }

        // Fallback: R$ seguido de valor inteiro (sem centavos) ex: R$ 15
        val intPattern = Pattern.compile("""R\$\s*(\d{1,5})(?:\s|$|\|)""")
        val intMatcher = intPattern.matcher(text)
        if (intMatcher.find()) {
            return intMatcher.group(1)?.toDoubleOrNull()
        }

        return null
    }

    /**
     * Extrai distância em km do texto.
     * Suporta formatos de TODOS os apps:
     *
     * iFood:   "3,25 km"  ou  "3.25 km"
     * Uber:    "Total: 30 min (13.9 km)"  ou  "(13,9 km)"  ou  "13.9 km"
     * 99:      "13min (5,4km)"  ou  "10min (4,7km)"  ou  "5,4km"
     *          "Distância total 5,2 km"
     * inDrive: "5,2 km"
     */
    private fun extractDistance(text: String): Double? {
        // Lista de padrões ordenados do mais específico ao mais genérico

        // Padrão 1: "Distância total X km" (99 formato antigo)
        val pattern99 = Pattern.compile(
            """Dist[âa]ncia\s+total\s+(\d+(?:[.,]\d+)?)\s*km""",
            Pattern.CASE_INSENSITIVE
        )
        val matcher99 = pattern99.matcher(text)
        if (matcher99.find()) {
            return matcher99.group(1)?.replace(",", ".")?.toDoubleOrNull()
        }

        // Padrão 2: "Total: Xmin (Y km)" ou "Total: X min (Y,Z km)" (Uber)
        val patternUberTotal = Pattern.compile(
            """[Tt]otal[:\s]+\d+\s*min\w*\s*\((\d+(?:[.,]\d+)?)\s*km\)""",
            Pattern.CASE_INSENSITIVE
        )
        val matcherUberTotal = patternUberTotal.matcher(text)
        if (matcherUberTotal.find()) {
            return matcherUberTotal.group(1)?.replace(",", ".")?.toDoubleOrNull()
        }

        // Padrão 3: "Xmin (Y,Zkm)" ou "Xmin(Y,Zkm)" (99 formato novo)
        // Ex: "13min (5,4km)" ou "10min (4,7km)"
        val pattern99new = Pattern.compile(
            """\d+\s*min\w*\s*\((\d+(?:[.,]\d+)?)\s*km\)""",
            Pattern.CASE_INSENSITIVE
        )
        val matcher99new = pattern99new.matcher(text)
        // Coletar TODAS as ocorrências e somar (99 mostra pickup + dropoff)
        val distances99 = mutableListOf<Double>()
        while (matcher99new.find()) {
            matcher99new.group(1)?.replace(",", ".")?.toDoubleOrNull()?.let {
                distances99.add(it)
            }
        }
        if (distances99.isNotEmpty()) {
            // Para 99: soma pickup + dropoff = distância total
            return distances99.sum()
        }

        // Padrão 4: "(X,Y km)" ou "(X.Y km)" entre parênteses genérico
        val patternParen = Pattern.compile(
            """\((\d+(?:[.,]\d+)?)\s*km\)""",
            Pattern.CASE_INSENSITIVE
        )
        val matcherParen = patternParen.matcher(text)
        if (matcherParen.find()) {
            return matcherParen.group(1)?.replace(",", ".")?.toDoubleOrNull()
        }

        // Padrão 5: "X,Y km" ou "X.Y km" genérico (iFood, inDrive, etc.)
        // Evita capturar números que são claramente preços (precedidos por R$)
        val patternGeneric = Pattern.compile(
            """(?<!R\$\s?)(?<!R\$)(\d+(?:[.,]\d+)?)\s*km""",
            Pattern.CASE_INSENSITIVE
        )
        val matcherGeneric = patternGeneric.matcher(text)
        if (matcherGeneric.find()) {
            return matcherGeneric.group(1)?.replace(",", ".")?.toDoubleOrNull()
        }

        // Suporte para "X corrida(s)" (99 Driver gatilho)
        // Se encontrar isso e não tiver distância, podemos retornar um valor simbólico ou null
        // O PDF diz que isso é um gatilho para ler o próximo texto.

        return null
    }

    /**
     * Extrai tempo em minutos do texto.
     * Suporta formatos:
     *   "30 min"  |  "13min"  |  "Total: 30 min"
     *   "12 minutos"  |  "12 mins"
     */
    private fun extractMinutes(text: String): Double? {
        // Padrão "Total: X min (Y km)" — pega o tempo do Total
        val patternTotal = Pattern.compile(
            """[Tt]otal[:\s]+(\d+)\s*min""",
            Pattern.CASE_INSENSITIVE
        )
        val matcherTotal = patternTotal.matcher(text)
        if (matcherTotal.find()) {
            return matcherTotal.group(1)?.toDoubleOrNull()
        }

        // Coletar todos os "Xmin" encontrados (99 mostra múltiplos)
        val patternMin = Pattern.compile(
            """(\d+)\s*min(?:uto|utos|s)?""",
            Pattern.CASE_INSENSITIVE
        )
        val matcherMin = patternMin.matcher(text)
        val allMinutes = mutableListOf<Double>()
        while (matcherMin.find()) {
            matcherMin.group(1)?.toDoubleOrNull()?.let { allMinutes.add(it) }
        }

        if (allMinutes.isNotEmpty()) {
            // Se houver múltiplos (ex: 99 com pickup + dropoff), somar
            return allMinutes.sum()
        }

        return null
    }

    private fun createDecision(
        fare: Double,
        distance: Double,
        minutes: Double?,
        minPerKm: Double,
        sourceApp: String,
        rawText: String
    ): OfferDecisionData {
        val perKm = if (distance > 0) fare / distance else 0.0
        val perMinute = if (minutes != null && minutes > 0) fare / minutes else null
        val perHour = if (minutes != null && minutes > 0) fare / (minutes / 60.0) else null
        val isGood = perKm >= minPerKm

        return OfferDecisionData(
            totalFare = fare,
            totalFareLabel = String.format(Locale("pt", "BR"), "R$ %.2f", fare),
            status = if (isGood) "ACEITAR" else "RECUSAR",
            statusColor = if (isGood) "#4CAF50" else "#F44336",
            perKm = perKm,
            perHour = perHour,
            perMinute = perMinute,
            minimumPerKm = minPerKm,
            sourceApp = sourceApp,
            rawText = rawText,
            distanceKm = distance,
            totalMinutes = minutes
        )
    }
}
