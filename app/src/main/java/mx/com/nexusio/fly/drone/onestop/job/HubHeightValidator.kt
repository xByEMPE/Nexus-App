package mx.com.nexusio.fly.drone.onestop.job

/** Validación de la altura de buje que escribe el piloto (obligatoria, sin valor por defecto). */
object HubHeightValidator {
    const val MIN_M = 20
    const val MAX_M = 300

    sealed interface Result {
        data class Valid(val meters: Double) : Result
        data object Empty : Result
        data object OutOfRange : Result
        /** La altura de buje debe superar la longitud de pala. */
        data object BelowBlade : Result
    }

    fun validate(text: String, bladeLengthM: Double): Result {
        val trimmed = text.trim().replace(',', '.')
        if (trimmed.isEmpty()) return Result.Empty
        val v = trimmed.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return Result.OutOfRange
        if (v < MIN_M || v > MAX_M) return Result.OutOfRange
        if (bladeLengthM > 0.0 && v <= bladeLengthM) return Result.BelowBlade
        return Result.Valid(v)
    }
}
