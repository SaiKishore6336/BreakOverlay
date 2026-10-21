package com.breakoverlay.app.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import com.breakoverlay.app.domain.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Lists launchable applications only. Package visibility is declared in the manifest with
 * a MAIN/LAUNCHER intent query, avoiding the broad QUERY_ALL_PACKAGES permission.
 */
class AppListRepository(private val context: Context) {
    private val packageManager: PackageManager = context.packageManager

    suspend fun loadLaunchableApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        try {
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolved: List<ResolveInfo> = packageManager.queryIntentActivities(
                launcherIntent,
                0,
            )

            val byPackage = LinkedHashMap<String, InstalledApp>()
            for (resolveInfo in resolved) {
                val activityInfo = resolveInfo.activityInfo ?: continue
                val packageName = activityInfo.packageName ?: continue
                if (packageName == context.packageName) continue
                if (byPackage.containsKey(packageName)) continue

                val label = try {
                    resolveInfo.loadLabel(packageManager)?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?: packageName
                } catch (_: RuntimeException) {
                    packageName
                }
                val icon = try {
                    resolveInfo.loadIcon(packageManager)
                } catch (_: RuntimeException) {
                    null
                }
                byPackage[packageName] = InstalledApp(
                    packageName = packageName,
                    label = label,
                    icon = icon,
                )
            }

            byPackage.values.sortedBy { it.label.lowercase(Locale.ROOT) }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to query launchable applications", error)
            emptyList()
        }
    }

    fun appLabel(packageName: String): String {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        } catch (_: RuntimeException) {
            packageName
        }
    }

    /**
     * Home applications and System UI are never counted toward a monitored continuous
     * session, even in Monitor All Apps mode.
     */
    fun excludedFromBreakMonitoring(packageName: String): Boolean {
        if (packageName == context.packageName) return true
        if (packageName == SYSTEM_UI_PACKAGE) return true
        return launcherPackages().contains(packageName)
    }

    fun isSystemOrLauncher(packageName: String): Boolean =
        packageName == SYSTEM_UI_PACKAGE || launcherPackages().contains(packageName)

    private fun launcherPackages(): Set<String> {
        return try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            packageManager.queryIntentActivities(homeIntent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        } catch (_: RuntimeException) {
            emptySet()
        }
    }

    companion object {
        private const val TAG = "AppListRepository"
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}
