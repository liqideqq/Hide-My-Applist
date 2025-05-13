package icu.nullptr.hidemyapplist.xposed.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Bundle
import com.github.kyuubiran.ezxhelper.utils.findMethodOrNull
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import icu.nullptr.hidemyapplist.xposed.HMAService
import icu.nullptr.hidemyapplist.xposed.logD
import icu.nullptr.hidemyapplist.xposed.logE
import icu.nullptr.hidemyapplist.xposed.logI

class StartActivityHook(private val service: HMAService) : IFrameworkHook {

    companion object {
        private const val TAG = "StartActivityHook"
    }

    private val hooks = mutableListOf<XC_MethodHook.Unhook>()

    private fun Intent.isWebIntent(): Boolean {
        return Intent.ACTION_VIEW == action && data != null &&
                (data?.scheme.equals("http", ignoreCase = true) ||
                        data?.scheme.equals("https", ignoreCase = true))
    }

    override fun load() {
        logI(TAG, "Load StartActivityHook")

        val classesToHook = listOf(
            Context::class.java,
            Activity::class.java,
            ContextWrapper::class.java
        )

        val methodSignatures = listOf(
            arrayOf<Class<*>>(Intent::class.java),
            arrayOf<Class<*>>(Intent::class.java, Bundle::class.java),
            arrayOf<Class<*>>(Intent::class.java, Integer.TYPE),
            arrayOf<Class<*>>(Intent::class.java, Integer.TYPE, Bundle::class.java)
        )
        val methodNamesToHook = listOf("startActivity", "startActivityForResult")

        classesToHook.forEach { clazz ->
            methodNamesToHook.forEach { methodName ->
                methodSignatures.forEach { signature ->
                    runCatching {
                        val method = findMethodOrNull(clazz, true) {
                            this.name == methodName && this.parameterTypes.contentEquals(signature)
                        } ?: return@runCatching

                        hooks += method.hookBefore { param: XC_MethodHook.MethodHookParam ->
                            runCatching {
                                val context = param.thisObject as? Context ?: return@hookBefore
                                val callerPackageName = context.packageName
                                val intent = param.args[0] as? Intent ?: return@hookBefore

                                val appConfig = service.config.scope[callerPackageName]
                                val isCallerHooked = appConfig != null
                                val isInWhitelistMode = appConfig?.useWhitelist == true

                                if (!isCallerHooked) {
                                    return@hookBefore
                                }

                                if (isInWhitelistMode) {
                                    logD(TAG, "Allowing startActivity from $callerPackageName (whitelist mode). Intent: $intent. PMS hooks will handle resolution filtering if applicable.")
                                    return@hookBefore
                                }

                                val targetPackageNameFromIntent: String? = intent.component?.packageName ?: intent.`package`
                                if (service.shouldHide(callerPackageName, targetPackageNameFromIntent)) {
                                    val intentDescription = intent.toString()
                                    val targetDescription = targetPackageNameFromIntent!!

                                    if (intent.isWebIntent()) {
                                        logI(TAG, "Blocked web intent from $callerPackageName to $targetDescription. Intent: $intentDescription. (HMA Policy for non-whitelist mode)")
                                        param.throwable = ActivityNotFoundException("No Activity found to handle $intent (web intent blocked by HMA rules for $callerPackageName)")
                                    } else {
                                        logI(TAG, "Blocked non-web intent from $callerPackageName to $targetDescription. Intent: $intentDescription. (HMA Policy for non-whitelist mode)")
                                        param.throwable = ActivityNotFoundException("No Activity found to handle $intent (intent blocked by HMA rules for $callerPackageName)")
                                    }
                                }
                            }.onFailure { error ->
                                val intentInfo = param.args?.getOrNull(0)?.toString() ?: "N/A"
                                val currentCaller = (param.thisObject as? Context)?.packageName ?: "UnknownCaller"
                                logE(TAG, "Error in StartActivityHook's hooked logic for $currentCaller (Intent: $intentInfo)", error)
                            }
                        }
                        logD(TAG, "Hooked ${clazz.name}#$methodName(${signature.joinToString { it.simpleName }})")
                    }.onFailure { error ->
                        logE(TAG, "Failed to hook ${clazz.name}#$methodName(${signature.joinToString { it.simpleName }})", error)
                    }
                }
            }
        }
        if (hooks.isEmpty()) {
            logI(TAG, "StartActivityHook loaded, but no methods were successfully hooked.")
        } else {
            logI(TAG, "StartActivityHook loaded ${hooks.size} method hooks.")
        }
    }

    override fun unload() {
        if (hooks.isNotEmpty()) {
            logI(TAG, "Unload StartActivityHook, unhooking ${hooks.size} methods.")
            hooks.forEach { it.unhook() }
            hooks.clear()
        } else {
            logI(TAG, "Unload StartActivityHook, no hooks were active to unhook.")
        }
    }
}
