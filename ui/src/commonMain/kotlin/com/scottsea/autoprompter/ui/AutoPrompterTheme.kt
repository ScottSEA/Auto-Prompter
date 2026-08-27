package com.scottsea.autoprompter.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

internal object AutoPrompterPalette {
    val ShellBackground = Color(0xFFFFFFFF)
    val ShellSurface = Color(0xFFF7F2F1)
    val ShellSurfaceStrong = Color(0xFFEEE4E1)
    val Ink = Color(0xFF1F1411)
    val InkMuted = Color(0xFF685651)
    val Primary = Color(0xFF9F3F25)
    val PrimaryDeep = Color(0xFF892504)
    val Accent = Color(0xFF004454)
    val PromptBackground = Color(0xFF020202)
    val PromptInk = Color(0xFFF3F3F3)
    val PromptPassed = Color(0xFF8D8481)
    val Error = Color(0xFFB32228)
    val Outline = Color(0xFFB8AAA6)
}

internal val PromptTextStyle =
    TextStyle(
        fontSize = 48.sp,
        lineHeight = 65.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.24.sp,
    )

private val AutoPrompterColorScheme =
    lightColorScheme(
        primary = AutoPrompterPalette.Primary,
        onPrimary = AutoPrompterPalette.ShellBackground,
        primaryContainer = AutoPrompterPalette.ShellSurfaceStrong,
        onPrimaryContainer = AutoPrompterPalette.Ink,
        secondary = AutoPrompterPalette.Accent,
        onSecondary = AutoPrompterPalette.ShellBackground,
        secondaryContainer = Color(0xFFD8EDF1),
        onSecondaryContainer = AutoPrompterPalette.Accent,
        background = AutoPrompterPalette.ShellBackground,
        onBackground = AutoPrompterPalette.Ink,
        surface = AutoPrompterPalette.ShellBackground,
        onSurface = AutoPrompterPalette.Ink,
        surfaceVariant = AutoPrompterPalette.ShellSurface,
        onSurfaceVariant = AutoPrompterPalette.InkMuted,
        outline = AutoPrompterPalette.Outline,
        error = AutoPrompterPalette.Error,
        onError = AutoPrompterPalette.ShellBackground,
    )

private val AutoPrompterTypography =
    Typography(
        headlineSmall =
            TextStyle(
                fontSize = 24.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.24).sp,
            ),
        titleMedium =
            TextStyle(
                fontSize = 18.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        titleSmall =
            TextStyle(
                fontSize = 16.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        bodyLarge =
            TextStyle(
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Normal,
            ),
        bodySmall =
            TextStyle(
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Normal,
            ),
        labelLarge =
            TextStyle(
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.14.sp,
            ),
    )

private val AutoPrompterShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(12.dp),
    )

@Composable
internal fun AutoPrompterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AutoPrompterColorScheme,
        typography = AutoPrompterTypography,
        shapes = AutoPrompterShapes,
        content = content,
    )
}
