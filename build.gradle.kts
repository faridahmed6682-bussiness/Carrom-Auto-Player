// Top-level build file for Carrom-Auto-Player (carrom-only export of Auto-Agent5).
// Only the plugins the carrom engine needs: Android app + Kotlin Compose.
// (Auto-Agent5 also applied ksp, secrets and google-services — the carrom
// code does not use them, so they are intentionally NOT applied here.)
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
}
