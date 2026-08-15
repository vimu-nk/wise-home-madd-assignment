package com.example.wisehome.ui.theme

import androidx.compose.ui.graphics.Color

// Brand: sky-blue primary (control/tech), amber secondary (energy/attention),
// indigo tertiary (climate/security accents). Status colors are semantic and
// deliberately separate from the brand hues so ON/OFF/ERROR never fight primary.

val PrimaryLight = Color(0xFF0284C7)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFE0F2FE)
val OnPrimaryContainerLight = Color(0xFF042C40)

val PrimaryDark = Color(0xFF38BDF8)
val OnPrimaryDark = Color(0xFF04283A)
val PrimaryContainerDark = Color(0xFF0B3A52)
val OnPrimaryContainerDark = Color(0xFFBAE6FD)

val SecondaryLight = Color(0xFFB45309)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFFEF3C7)
val OnSecondaryContainerLight = Color(0xFF451A03)

val SecondaryDark = Color(0xFFF59E0B)
val OnSecondaryDark = Color(0xFF3A2300)
val SecondaryContainerDark = Color(0xFF4A2E00)
val OnSecondaryContainerDark = Color(0xFFFCD34D)

val TertiaryLight = Color(0xFF4F46E5)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFE0E7FF)
val OnTertiaryContainerLight = Color(0xFF1E1B4B)

val TertiaryDark = Color(0xFF818CF8)
val OnTertiaryDark = Color(0xFF1E1B4B)
val TertiaryContainerDark = Color(0xFF312E81)
val OnTertiaryContainerDark = Color(0xFFE0E7FF)

val ErrorLight = Color(0xFFDC2626)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFEE2E2)
val OnErrorContainerLight = Color(0xFF450A0A)

val ErrorDark = Color(0xFFF87171)
val OnErrorDark = Color(0xFF450A0A)
val ErrorContainerDark = Color(0xFF7F1D1D)
val OnErrorContainerDark = Color(0xFFFECACA)

val BackgroundLight = Color(0xFFF8FAFC)
val OnBackgroundLight = Color(0xFF0F172A)
val SurfaceLight = Color(0xFFFFFFFF)
val OnSurfaceLight = Color(0xFF0F172A)
val SurfaceVariantLight = Color(0xFFE9EEF4)
val OnSurfaceVariantLight = Color(0xFF3F4A5A)
val OutlineLight = Color(0xFFCBD5E1)
val OutlineVariantLight = Color(0xFFDCE3EB)

// Background/surface/card are three visibly distinct steps, not alpha
// variants of the same hue — that's what was causing everything to blend.
val BackgroundDark = Color(0xFF0B1220)
val OnBackgroundDark = Color(0xFFF1F5F9)
val SurfaceDark = Color(0xFF121B2E)
val OnSurfaceDark = Color(0xFFF1F5F9)
val SurfaceVariantDark = Color(0xFF1E293B)
val OnSurfaceVariantDark = Color(0xFFCBD5E1)
val OutlineDark = Color(0xFF475569)
val OutlineVariantDark = Color(0xFF334155)

// Device status — used in floor-grid badges and detail-sheet chips. Kept
// separate from primary/secondary/tertiary so status is unambiguous.
val StatusOnLight = Color(0xFF16A34A)
val StatusOffLight = Color(0xFF64748B)
val StatusErrorLight = Color(0xFFDC2626)
val StatusDisconnectedLight = Color(0xFF94A3B8)

val StatusOnDark = Color(0xFF4ADE80)
val StatusOffDark = Color(0xFF94A3B8)
val StatusErrorDark = Color(0xFFF87171)
val StatusDisconnectedDark = Color(0xFF64748B)
