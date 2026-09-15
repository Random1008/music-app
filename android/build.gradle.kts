// Racine du projet.
//
// Deux particularités d'AGP 9 qu'il faut connaître :
//  1. Le support Kotlin est INTÉGRÉ : le plugin « org.jetbrains.kotlin.android »
//     ne doit plus être appliqué (sinon le build échoue).
//  2. AGP embarque Kotlin 2.2.10, trop ancien pour les bibliothèques récentes
//     (kotlin-stdlib 2.4.x -> « incompatible version of Kotlin »). La
//     documentation AGP indique que la montée de version passe PAR ICI :
//     un classpath KGP dans le buildscript du build racine.
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
