package app.clairix.analysis

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import app.clairix.HealthFact
import app.clairix.Severity

/**
 * The keyboard sees every character typed into every app, passwords and
 * messages included, before any app can encrypt it. A keyboard that can reach
 * the internet can send what it saw anywhere; one that learns from typing
 * keeps a model of it, and some sync that to the cloud. Clairix asks every
 * keyboard not to learn from what is typed into it (see NoKeyboardLearning),
 * which Gboard and Samsung's keyboard honour; that request covers nothing
 * typed in other apps, and a keyboard is free to ignore it.
 */
object KeyboardExposure {

    data class Keyboard(val packageName: String, val label: String, val internet: Boolean, val system: Boolean)

    /** The keyboard in use, or null when the phone does not say. */
    fun current(context: Context): Keyboard? {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.takeIf { it.isNotBlank() } ?: return null
        val pkg = id.substringBefore('/')
        val pm = context.packageManager
        val info = runCatching {
            context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.firstOrNull { it.id == id }
        }.getOrNull()
        val label = runCatching { info?.loadLabel(pm)?.toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
        val pkgInfo = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) }.getOrNull() ?: return Keyboard(pkg, label, internet = true, system = false)
        val internet = pkgInfo.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
        val system = ((pkgInfo.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0
        return Keyboard(pkg, label, internet, system)
    }

    fun fact(k: Keyboard?): HealthFact? {
        k ?: return null
        val id = "keyboard"
        return if (k.internet) {
            HealthFact(
                id, "Apps", "Keyboard", "${k.label}: can reach the internet", Severity.MEDIUM,
                "${k.label} sees everything you type in every app, before any app encrypts it, and has internet access. " +
                    "Clairix tells it not to learn from what you type in Clairix (Gboard and Samsung's keyboard honour that); " +
                    "nothing tells it that in other apps. In ${k.label}'s settings turn off personalisation, typing-history " +
                    "sync and any \"improve\" or voice-data sharing, or switch to a keyboard with no internet access " +
                    "(FlorisBoard, or the stock keyboard on GrapheneOS). If you did not choose this keyboard, treat it as spyware." +
                    if (!k.system) " It is not part of the phone's own system." else "",
                fixAction = Settings.ACTION_INPUT_METHOD_SETTINGS
            )
        } else {
            HealthFact(
                id, "Apps", "Keyboard", "${k.label}: no internet access",
                detail = "${k.label} cannot send what you type anywhere: it has no internet permission."
            )
        }
    }
}
