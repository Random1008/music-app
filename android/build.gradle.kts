// Racine du projet : on déclare les plugins sans les appliquer ici.
// Depuis AGP 9, le support Kotlin est intégré au plugin Android : le plugin
// « org.jetbrains.kotlin.android » ne doit PLUS être appliqué (il échoue).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
