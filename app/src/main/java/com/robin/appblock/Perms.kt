package com.robin.appblock

import android.app.AppOpsManager
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

// Live state of each Storage.Requirement, shared by the home screen (buttons,
// paused note), the About dialog, and the service starters (boot, app open).

/** True when the user has granted "Usage access" (a special app op with its own
 *  Settings page — there is no runtime prompt for it). */
fun usageAccessGranted(ctx: Context): Boolean {
    @Suppress("DEPRECATION")
    return ctx.getSystemService(AppOpsManager::class.java).checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
    ) == AppOpsManager.MODE_ALLOWED
}

fun overlayGranted(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

fun batteryExempt(ctx: Context): Boolean =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

/** The `when` is exhaustive: a new Storage.Requirement won't compile until it's checked here. */
fun requirementMet(ctx: Context, req: Storage.Requirement): Boolean = when (req) {
    Storage.Requirement.USAGE_ACCESS -> usageAccessGranted(ctx)
    Storage.Requirement.OVERLAY -> overlayGranted(ctx)
    Storage.Requirement.BATTERY -> batteryExempt(ctx)
}

fun missingRequirements(ctx: Context): List<Storage.Requirement> =
    Storage.Requirement.entries.filter { !requirementMet(ctx, it) }

fun allRequirementsMet(ctx: Context): Boolean = missingRequirements(ctx).isEmpty()

/** "package:com.robin.appblock", the form the per-app Settings pages take. */
fun packageUri(ctx: Context): Uri = Uri.parse("package:${ctx.packageName}")
