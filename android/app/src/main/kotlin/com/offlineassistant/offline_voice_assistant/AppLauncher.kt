package com.offlineassistant.offline_voice_assistant

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log

/**
 * Dynamic app launcher that queries all installed launchable apps and
 * fuzzy-matches spoken app names to find and open the right one.
 *
 * Usage:
 *   val launcher = AppLauncher(context)
 *   val result = launcher.findAndLaunch("open whatsapp")
 *   // result?.first  = display name (e.g. "WhatsApp")
 *   // result?.second = true if launched successfully, false otherwise
 */
class AppLauncher(private val context: Context) {

    companion object {
        const val TAG = "AppLauncher"
    }

    /**
     * Holds a launchable app's display label, package name, and a
     * pre-computed lowercase/stripped version for matching.
     */
    data class AppEntry(
        val label: String,          // e.g. "WhatsApp"
        val packageName: String,    // e.g. "com.whatsapp"
        val labelLower: String,     // e.g. "whatsapp"
        val labelStripped: String   // e.g. "whatsapp" (no spaces/special chars)
    )

    // Cached list of all launchable apps – built lazily and refreshed on demand.
    private var cachedApps: List<AppEntry>? = null
    private var cacheTimestamp: Long = 0L
    private val CACHE_TTL_MS = 5 * 60 * 1000L  // 5 minutes

    // Common aliases that Vosk might recognize differently than the actual app label.
    // Maps spoken alias (lowercase, stripped) -> package name
    private val knownAliases: Map<String, String> = mapOf(
        "facebook" to "com.facebook.katana",
        "fb" to "com.facebook.katana",
        "fblite" to "com.facebook.lite",
        "facebooklite" to "com.facebook.lite",
        "messenger" to "com.facebook.orca",
        "whatsapp" to "com.whatsapp",
        "whatsappbusiness" to "com.whatsapp.w4b",
        "youtube" to "com.google.android.youtube",
        "tiktok" to "com.zhiliaoapp.musically",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "instagram" to "com.instagram.android",
        "insta" to "com.instagram.android",
        "snapchat" to "com.snapchat.android",
        "snap" to "com.snapchat.android",
        "telegram" to "org.telegram.messenger",
        "chrome" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "email" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "googlemaps" to "com.google.android.apps.maps",
        "spotify" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient",
        "calculator" to "com.google.android.calculator",
        "calc" to "com.google.android.calculator",
        "clock" to "com.google.android.deskclock",
        "alarm" to "com.google.android.deskclock",
        "timer" to "com.google.android.deskclock",
        "calendar" to "com.google.android.calendar",
        "settings" to "com.android.settings",
        "camera" to "com.android.camera",
        "playstore" to "com.android.vending",
        "googleplay" to "com.android.vending",
        "appstore" to "com.android.vending",
        "store" to "com.android.vending",
        "files" to "com.google.android.apps.nbu.files",
        "filemanager" to "com.google.android.apps.nbu.files",
        "contacts" to "com.google.android.contacts",
        "phone" to "com.google.android.dialer",
        "dialer" to "com.google.android.dialer",
        "messages" to "com.google.android.apps.messaging",
        "sms" to "com.google.android.apps.messaging",
        "texting" to "com.google.android.apps.messaging",
        "photos" to "com.google.android.apps.photos",
        "googlephotos" to "com.google.android.apps.photos",
        "gallery" to "com.google.android.apps.photos",
        "drive" to "com.google.android.apps.docs",
        "googledrive" to "com.google.android.apps.docs",
        "keep" to "com.google.android.keep",
        "googlenotes" to "com.google.android.keep",
        "notes" to "com.google.android.keep",
        "notion" to "notion.id",
        "weather" to "com.google.android.apps.weather",
        "translate" to "com.google.android.apps.translate",
        "googletranslate" to "com.google.android.apps.translate",
        "reddit" to "com.reddit.frontpage",
        "linkedin" to "com.linkedin.android",
        "pinterest" to "com.pinterest",
        "discord" to "com.discord",
        "zoom" to "us.zoom.videomeetings",
        "teams" to "com.microsoft.teams",
        "microsoftteams" to "com.microsoft.teams",
        "skype" to "com.skype.raider",
        "viber" to "com.viber.voip",
        "signal" to "org.thoughtcrime.securesms",
        "amazon" to "com.amazon.mShop.android.shopping",
        "amazonshopping" to "com.amazon.mShop.android.shopping",
        "daraz" to "com.daraz.android",
        "uber" to "com.ubercab",
        "careem" to "com.careem.acma",
        "foodpanda" to "com.global.foodpanda.android",
    )

    /**
     * Get all launchable apps, with caching.
     */
    fun getInstalledApps(): List<AppEntry> {
        val now = System.currentTimeMillis()
        if (cachedApps != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return cachedApps!!
        }

        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos: List<ResolveInfo> = pm.queryIntentActivities(mainIntent, 0)

        cachedApps = resolveInfos.mapNotNull { ri ->
            val label = ri.loadLabel(pm)?.toString() ?: return@mapNotNull null
            val pkgName = ri.activityInfo?.packageName ?: return@mapNotNull null
            AppEntry(
                label = label,
                packageName = pkgName,
                labelLower = label.lowercase(),
                labelStripped = label.lowercase().replace(Regex("[^a-z0-9]"), "")
            )
        }.distinctBy { it.packageName }

        cacheTimestamp = now
        Log.d(TAG, "Cached ${cachedApps!!.size} launchable apps")
        return cachedApps!!
    }

    /**
     * Invalidate the cache so the next call re-queries the system.
     */
    fun invalidateCache() {
        cachedApps = null
        cacheTimestamp = 0L
    }

    /**
     * Attempt to find and launch an app from the spoken text.
     *
     * Accepts commands like:
     *  - "open whatsapp"
     *  - "launch facebook"
     *  - "start telegram"
     *  - "whatsapp" (just the app name)
     *
     * @return Pair(displayName, success) if a match was found, null if no match.
     */
    fun findAndLaunch(spokenText: String): Pair<String, Boolean>? {
        val lower = spokenText.lowercase().trim()

        // Strip common prefixes to extract the app name
        val appName = extractAppName(lower)
        if (appName.isEmpty()) return null

        val stripped = appName.replace(Regex("[^a-z0-9]"), "")

        Log.d(TAG, "Looking for app: '$appName' (stripped: '$stripped')")

        // ── Step 1: Check known aliases first (fast path) ──
        val aliasPackage = knownAliases[stripped]
        if (aliasPackage != null) {
            val success = launchPackage(aliasPackage)
            if (success) {
                val displayName = getAppDisplayName(aliasPackage) ?: appName.replaceFirstChar { it.uppercase() }
                return Pair(displayName, true)
            }
        }

        // ── Step 2: Exact match on installed app labels ──
        val apps = getInstalledApps()

        // Exact stripped match
        val exactMatch = apps.find { it.labelStripped == stripped }
        if (exactMatch != null) {
            val success = launchPackage(exactMatch.packageName)
            return Pair(exactMatch.label, success)
        }

        // ── Step 3: "Contains" match ──
        // e.g. spoken "calculator" matches app labeled "Google Calculator"
        val containsMatches = apps.filter {
            it.labelStripped.contains(stripped) || stripped.contains(it.labelStripped)
        }.sortedBy {
            // Prefer shorter label (more specific match)
            kotlin.math.abs(it.labelStripped.length - stripped.length)
        }

        if (containsMatches.isNotEmpty()) {
            val best = containsMatches.first()
            val success = launchPackage(best.packageName)
            return Pair(best.label, success)
        }

        // ── Step 4: Fuzzy match using Levenshtein distance ──
        val fuzzyThreshold = maxOf(2, stripped.length / 3) // Allow ~33% error
        val fuzzyMatch = apps
            .map { Pair(it, levenshteinDistance(stripped, it.labelStripped)) }
            .filter { it.second <= fuzzyThreshold }
            .minByOrNull { it.second }

        if (fuzzyMatch != null) {
            Log.d(TAG, "Fuzzy matched '${appName}' -> '${fuzzyMatch.first.label}' (distance=${fuzzyMatch.second})")
            val success = launchPackage(fuzzyMatch.first.packageName)
            return Pair(fuzzyMatch.first.label, success)
        }

        // ── Step 5: Word-by-word partial match ──
        // e.g. "file manager" should match "Files by Google"
        val spokenWords = appName.split(" ").filter { it.length > 2 }
        if (spokenWords.isNotEmpty()) {
            val wordMatch = apps
                .map { app ->
                    val matchedWords = spokenWords.count { word ->
                        app.labelLower.contains(word)
                    }
                    Pair(app, matchedWords)
                }
                .filter { it.second > 0 }
                .maxByOrNull { it.second }

            if (wordMatch != null && wordMatch.second >= spokenWords.size / 2 + 1) {
                Log.d(TAG, "Word-matched '${appName}' -> '${wordMatch.first.label}' (${wordMatch.second}/${spokenWords.size} words)")
                val success = launchPackage(wordMatch.first.packageName)
                return Pair(wordMatch.first.label, success)
            }
        }

        Log.d(TAG, "No match found for: '$appName'")
        return null
    }

    /**
     * Extract the app name from the spoken command, removing prefix words.
     */
    private fun extractAppName(text: String): String {
        val prefixes = listOf(
            "open the ", "open my ", "open ",
            "launch the ", "launch my ", "launch ",
            "start the ", "start my ", "start ",
            "run the ", "run my ", "run ",
            "go to ", "switch to ", "show me ",
        )
        for (prefix in prefixes) {
            if (text.startsWith(prefix)) {
                return text.removePrefix(prefix).trim()
            }
        }
        // No prefix found, use the text as-is (just saying the app name)
        return text.trim()
    }

    /**
     * Launch an app by package name.
     */
    private fun launchPackage(packageName: String): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                context.startActivity(launchIntent)
                Log.d(TAG, "Launched: $packageName")
                true
            } else {
                Log.w(TAG, "No launch intent for: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch: $packageName", e)
            false
        }
    }

    /**
     * Get the user-facing display name of an app by package.
     */
    private fun getAppDisplayName(packageName: String): String? {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Compute the Levenshtein edit distance between two strings.
     */
    private fun levenshteinDistance(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        return dp[m][n]
    }
}
