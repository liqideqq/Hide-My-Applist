package icu.nullptr.hidemyapplist.xposed.hook

import android.os.Binder
import android.os.Build
import androidx.annotation.RequiresApi
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.findMethodOrNull
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import icu.nullptr.hidemyapplist.common.Constants
import icu.nullptr.hidemyapplist.xposed.*

abstract class BasePmsHook(private val service: HMAService) : IFrameworkHook {
    // 公共逻辑抽象
    protected abstract val targetClass: String
    protected abstract fun getCallingApps(snapshot: Any, callingUid: Int): Array<String>?
    
    override fun load() {
        // 公共Hook逻辑
        findMethod(targetClass, findSuper = true) { 
            name == "shouldFilterApplication" 
        }.hookBefore { param ->
            runCatching {
                val snapshot = param.args[0]
                val callingUid = param.args[1] as Int
                if (callingUid == Constants.UID_SYSTEM) return@hookBefore
                
                val callingApps = getCallingApps(snapshot, callingUid) ?: return@hookBefore
                val targetApp = Utils.getPackageNameFromPackageSettings(param.args[3])

                callingApps.firstOrNull { service.shouldHide(it, targetApp) }?.let {
                    param.result = true
                    service.filterCount++
                    logFilteredApp(callingUid, it, targetApp, "shouldFilterApplication")
                    return@hookBefore
                }
            }.onFailure { handleError(it) }
        }
    }

    // 公共工具方法
    protected fun logFilteredApp(uid: Int, caller: String, target: String, method: String) {
        logD("PmsHook", "@$method caller: $uid $caller, target: $target")
    }

    protected fun handleError(t: Throwable) {
        logE("PmsHook", "Fatal error occurred", t)
        unload()
    }

    override fun unload() {}
}

// SDK 34+ 实现
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PmsHookTarget34(service: HMAService) : BasePmsHook(service) {
    override val targetClass = "com.android.server.pm.AppsFilterImpl"

    private val getPackagesForUidMethod by lazy {
        findMethod("com.android.server.pm.Computer") { 
            name == "getPackagesForUid" 
        }
    }

    private var exphook: XC_MethodHook.Unhook? = null

    override fun getCallingApps(snapshot: Any, callingUid: Int): Array<String>? {
        return Utils.binderLocalScope {
            getPackagesForUidMethod.invoke(snapshot, callingUid) as? Array<String>
        }
    }

    override fun load() {
        super.load()
        // Android 14特有逻辑
        findMethodOrNull("com.android.server.pm.PackageManagerService") { 
            name == "getArchivedPackageInternal" 
        }?.hookBefore { param ->
            runCatching {
                val callingUid = Binder.getCallingUid()
                if (callingUid == Constants.UID_SYSTEM) return@hookBefore
                
                service.pms.getPackagesForUid(callingUid)?.firstOrNull { 
                    service.shouldHide(it, param.args[0].toString()) 
                }?.let {
                    param.result = null
                    service.filterCount++
                    logFilteredApp(callingUid, it, param.args[0].toString(), "getArchivedPackageInternal")
                }
            }.onFailure { handleError(it) }
        }?.also { exphook = it }
    }

    override fun unload() {
        super.unload()
        exphook?.unhook()
    }
}

// SDK 29-33 实现
class PmsHookTarget29(service: HMAService) : BasePmsHook(service) {
    override val targetClass = "com.android.server.pm.PackageManagerService"

    override fun getCallingApps(snapshot: Any, callingUid: Int): Array<String>? {
        return Utils.binderLocalScope {
            // SDK29中getPackagesForUid直接位于PackageManagerService
            findMethod(targetClass) { 
                name == "getPackagesForUid" && parameterTypes.contentEquals(arrayOf(Int::class.java)) 
            }.invoke(snapshot, callingUid) as? Array<String>
        }
    }
}

// 入口加载逻辑
fun loadHook(service: HMAService) {
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> 
            PmsHookTarget34(service).load()
        Build.VERSION.SDK_INT in 29..33 -> 
            PmsHookTarget29(service).load()
        else -> 
            logE("PmsHook", "Unsupported Android version: ${Build.VERSION.SDK_INT}")
    }
}
