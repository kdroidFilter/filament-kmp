package eric.bitria.samples

/** Destinations in the samples app. */
sealed class Screen {
    data object Home : Screen()
    data object Duck : Screen()
    data object Primitives : Screen()
    data object Lighting : Screen()
    data object Picking : Screen()
    data object Solar : Screen()
    data object Animation : Screen()
    data object SplitView : Screen()
    data object Texture : Screen()
    data object KTXEnvironment : Screen()
    data object HDREnvironment : Screen()
    data object Transparent : Screen()
    data object RuntimeMaterial : Screen()

    companion object {
        /** Looks a destination up by its name, case-insensitively (e.g. `animation`). */
        fun byName(name: String): Screen? =
            listOf(Home, Duck, Primitives, Lighting, Picking, Solar, Animation, SplitView, Texture,
                KTXEnvironment, HDREnvironment, Transparent, RuntimeMaterial)
                .firstOrNull { it.toString().equals(name, ignoreCase = true) }
    }
}
