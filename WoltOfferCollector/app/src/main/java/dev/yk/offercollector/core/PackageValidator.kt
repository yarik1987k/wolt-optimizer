package dev.yk.offercollector.core

object PackageValidator {
    private val pattern = Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$""")

    sealed interface Result {
        data object Valid : Result
        data class Invalid(val reasonHe: String) : Result
    }

    fun validate(packageName: String?, ownPackage: String, isInstalled: (String) -> Boolean): Result = when {
        packageName.isNullOrBlank() -> Result.Invalid("לא נבחרה אפליקציה")
        !pattern.matches(packageName) -> Result.Invalid("שם חבילה לא תקין")
        packageName == ownPackage -> Result.Invalid("לא ניתן לבחור את אפליקציית האיסוף עצמה")
        !isInstalled(packageName) -> Result.Invalid("האפליקציה אינה מותקנת במכשיר")
        else -> Result.Valid
    }

    /** Hint only: the user still has to pick the app. Nothing is auto-selected. */
    fun looksLikeWolt(packageName: String, label: String): Boolean =
        packageName.contains("wolt", ignoreCase = true) || label.contains("wolt", ignoreCase = true) || label.contains("וולט")
}
