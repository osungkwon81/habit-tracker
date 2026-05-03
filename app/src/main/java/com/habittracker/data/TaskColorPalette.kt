package com.habittracker.data

import kotlin.math.absoluteValue

object TaskColorPalette {
    val presets: List<String> = listOf(
        "#2563EB",
        "#16A34A",
        "#D97706",
        "#DC2626",
        "#7C3AED",
        "#0F766E",
    )

    fun defaultColor(seed: String): String {
        return presets[seed.hashCode().absoluteValue % presets.size]
    }

    fun sanitize(colorHex: String?, fallbackSeed: String): String {
        val value = colorHex?.trim()?.uppercase()
        return if (value != null && value.matches(Regex("^#[0-9A-F]{6}$"))) {
            value
        } else {
            defaultColor(fallbackSeed)
        }
    }
}
