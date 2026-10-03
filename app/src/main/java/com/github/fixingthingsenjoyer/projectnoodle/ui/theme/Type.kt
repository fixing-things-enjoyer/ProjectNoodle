package com.github.fixingthingsenjoyer.projectnoodle.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography =
    Typography(
        headlineLarge =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 34.sp,
                lineHeight = 40.sp,
                letterSpacing = (-0.7).sp,
            ),
        titleLarge =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                lineHeight = 29.sp,
                letterSpacing = (-0.3).sp,
            ),
        titleMedium =
            TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
        labelLarge =
            TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
        labelSmall =
            TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.8.sp,
            ),
    )
