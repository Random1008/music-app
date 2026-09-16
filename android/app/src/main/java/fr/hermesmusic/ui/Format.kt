package fr.hermesmusic.ui

/*
 * Formatage pur, sans aucune dépendance Compose ni Android : c'est ce qui
 * permet de le tester sur la JVM, sans appareil.
 */

/** Durée d'un morceau à partir de secondes : « 3:42 ». */
fun fmtDuration(seconds: Int): String =
    "%d:%02d".format(seconds / 60, seconds % 60)

/** Durée à partir de millisecondes (position du lecteur) : « 1:42 ». */
fun fmtMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** Taille lisible : « 812 Ko », « 24 Mo », « 1,4 Go ». */
fun fmtBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f Go".format(bytes / 1_073_741_824.0).replace('.', ',')
    bytes >= 1_048_576L -> "%.0f Mo".format(bytes / 1_048_576.0)
    bytes >= 1024L -> "%.0f Ko".format(bytes / 1024.0)
    else -> "$bytes o"
}
