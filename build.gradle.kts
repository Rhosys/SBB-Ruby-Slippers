plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// ── Version ───────────────────────────────────────────────────────────────────
// One version for both apps, from -PversionName (e.g. "1.0.42") and -PbuildNumber (the CI
// pipeline number). Play needs a unique version code per uploaded bundle across the whole
// app, so each device type gets its own (Google's recommended segmented layout, minus the
// SDK prefix):  major · minor (2 digits) · build number (5 digits) · device (1 digit).
// 1.0 build 42 → phone 100000420, watch 100000421. Play's ceiling (2 100 000 000) allows
// majors up to 20.
val appVersionName = (findProperty("versionName") as? String) ?: "1.0.0"
val buildNumber = (findProperty("buildNumber") as? String)?.toIntOrNull() ?: 1

fun playVersionCode(device: Int): Int {
    val parts = appVersionName.split(".")
    val major = parts.getOrNull(0)?.toIntOrNull() ?: error("versionName '$appVersionName' has no major version")
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    require(major in 0..20) { "major version $major doesn't fit Play's version code ceiling" }
    require(minor in 0..99) { "minor version $minor needs more than 2 digits" }
    require(buildNumber in 0..99_999) { "build number $buildNumber needs more than 5 digits" }
    return major * 100_000_000 + minor * 1_000_000 + buildNumber * 10 + device
}

val phoneVersionCode = playVersionCode(device = 0)
extra["appVersionName"] = appVersionName
extra["phoneVersionCode"] = phoneVersionCode
extra["wearVersionCode"] = playVersionCode(device = 1)

// CI reads the phone's code from here (deploy notification link) instead of re-deriving it.
tasks.register("printPhoneVersionCode") {
    doLast { println(phoneVersionCode) }
}
