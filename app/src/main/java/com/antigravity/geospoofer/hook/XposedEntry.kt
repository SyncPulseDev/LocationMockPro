package com.antigravity.geospoofer.hook

import android.app.AppOpsManager
import android.content.Context
import android.database.Cursor
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telephony.CellIdentityLte
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellLocation
import android.telephony.CellSignalStrengthLte
import android.telephony.TelephonyManager
import android.telephony.gsm.GsmCellLocation
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.lang.ref.WeakReference
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LocationMockPro 顶级 LSPosed / Xposed 免杀 Hook 核心引擎 (Phase 3.0 终审加固版)
 *
 * 核心功能加固矩阵：
 * 1. 彻底抹除 isFromMockProvider 与 isMock 虚拟定位标志位
 * 2. 隐藏系统设置 mock_location 标志与 AppOps OP_MOCK_LOCATION 权限标记
 * 3. 硬件计步器增量叠加算法（基准开机值叠加仿真步数，杜绝清零与断崖跳跃异常）
 * 4. LocationManager 接口纳秒级单调时钟与 3.5m~8.0m 动态精度波动
 * 5. ActiveDispatcher 调度器严格基于指定 Looper/Executor 进行主线程/线程池安全投递，并在 removeUpdates 闭环清理
 * 6. ContentProvider IPC 双模容错（call + query 自动降级，绝不阻塞弄崩宿主 App）
 * 7. 企业打卡 Wi-Fi BSSID/SSID 扫描与连接指纹注入
 * 8. 完整合规 CellInfoLte（包含合法 MCC=460、MNC、TAC、CI 及非空 -70~-85dBm 信号强度）
 */
class XposedEntry : IXposedHookLoadPackage {

    companion object {
        private const val DEBUG_LOG = false
    }

    private fun logD(msg: String) {
        if (DEBUG_LOG) {
            XposedBridge.log(msg)
        }
    }

    private val hookedListeners = ConcurrentHashMap<Int, Boolean>()
    private val hookedLocationListeners = ConcurrentHashMap<Int, Boolean>()
    private val hookedGnssCallbacks = ConcurrentHashMap<Int, Boolean>()

    @Volatile private var currentPackageName: String = ""
    @Volatile private var isCampusMultiSensorApp: Boolean = false

    // 计步器开机硬件真实步数基准值（记录首次硬件回调，默认 12580f 优雅垫底）与跨会话单调累加锁
    @Volatile private var baseHardwareSteps: Float = 12580f
    @Volatile private var hasCapturedRealHardwareStep: Boolean = false
    @Volatile private var lastDispatchedStepCount: Long = -1L
    private val stepStateLock = Any()
    private var accumulatedPriorSteps: Long = 0L
    private var maxCurrentSessionSteps: Long = 0L

    // 三轴加速度计连续相位积分器（根治 f(t)*t 调频啁啾跳变与 100s 取模断相，实现 C^∞ 平滑步态波形）
    private val accelPhaseLock = Any()
    private var accelStridePhaseRad: Double = 0.0
    private var lastAccelPhaseNanos: Long = 0L
    @Volatile private var lastNativeAccelEventMs: Long = 0L
    private val isSyntheticAccelDispatch = ThreadLocal<Boolean>()
    private val isAccelPumpRunning = AtomicBoolean(false)

    // 活跃 Listener 调度封装器与调度器线程
    private val activeDispatchers = ConcurrentHashMap<Int, ListenerDispatcher>()
    private val activeStepListeners = ConcurrentHashMap<Int, StepListenerInfo>()
    private val activeAccelListeners = ConcurrentHashMap<Int, StepListenerInfo>()
    private val activeGnssCallbacks = ConcurrentHashMap<Int, GnssCallbackInfo>()
    private val activeNmeaListeners = ConcurrentHashMap<Int, NmeaListenerInfo>()
    @Volatile private var isDispatcherRunning = false
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private fun detectCampusMultiSensorApp(pkg: String): Boolean {
        val lower = pkg.lowercase(Locale.US)
        return lower.contains("zjwh") ||
            lower.contains("physicalfitness") ||
            lower.contains("yundong") ||
            lower.contains("paobu") ||
            lower.contains("lepao") ||
            lower.contains("co.runner") ||
            lower.contains("fifedu") ||
            lower.contains("wanmei") ||
            lower.contains("campus") ||
            lower.contains("sunshine") ||
            lower.contains("sportcampus")
    }

    /**
     * 跨会话严格单调递增硬件步数合成器：
     * 当用户结束并开启第二轮路线模拟（rawSessionSteps 归零）时，自动将上一轮峰值折算入历史偏移，
     * 严格遵守 Android TYPE_STEP_COUNTER 自开机单调不减契约，杜绝二次开跑丢步或负跳变风控。
     */
    private fun computeMonotonicStepCounterValue(rawSessionSteps: Long): Float {
        val safeRaw = rawSessionSteps.coerceAtLeast(0L)
        val totalSimulatedSteps = synchronized(stepStateLock) {
            if (safeRaw < maxCurrentSessionSteps) {
                accumulatedPriorSteps += maxCurrentSessionSteps
                maxCurrentSessionSteps = safeRaw
            } else if (safeRaw > maxCurrentSessionSteps) {
                maxCurrentSessionSteps = safeRaw
            }
            accumulatedPriorSteps + maxCurrentSessionSteps
        }
        return baseHardwareSteps + totalSimulatedSteps.toFloat()
    }

    private fun isFakeStepSensorInstance(sensor: Sensor): Boolean {
        return try {
            sensor.name.contains("BOSCH") ||
                sensor.vendor.contains("Bosch") ||
                XposedHelpers.getIntField(sensor, "mHandle") < 0
        } catch (_: Throwable) {
            false
        }
    }

    private class StepListenerInfo(
        val weakListener: WeakReference<SensorEventListener>,
        val sensor: Sensor,
        val targetHandler: Handler?
    ) {
        fun isAlive(): Boolean = weakListener.get() != null
    }

    private class GnssCallbackInfo(
        val weakCallback: WeakReference<android.location.GnssStatus.Callback>,
        val targetHandler: Handler?,
        val targetExecutor: Executor?
    ) {
        @Volatile var hasSentFirstFix: Boolean = false

        fun isAlive(): Boolean = weakCallback.get() != null

        fun dispatch(defaultMainHandler: Handler, action: (android.location.GnssStatus.Callback) -> Unit) {
            if (weakCallback.get() == null) return
            val runnable = Runnable {
                val cb = weakCallback.get() ?: return@Runnable
                try { action(cb) } catch (_: Throwable) {}
            }
            if (targetExecutor != null) {
                try {
                    targetExecutor.execute(runnable)
                    return
                } catch (_: Throwable) {}
            }
            if (targetHandler != null) {
                try {
                    targetHandler.post(runnable)
                    return
                } catch (_: Throwable) {}
            }
            try { defaultMainHandler.post(runnable) } catch (_: Throwable) {}
        }
    }

    private class NmeaListenerInfo(
        val rawListener: WeakReference<Any>,
        val targetHandler: Handler?,
        val targetExecutor: Executor?,
        val isModern: Boolean
    ) {
        fun isAlive(): Boolean = rawListener.get() != null

        fun dispatch(nmea: String, timestamp: Long, defaultMainHandler: Handler) {
            val listener = rawListener.get() ?: return
            val action = Runnable {
                val active = rawListener.get() ?: return@Runnable
                try {
                    if (isModern) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && active is android.location.OnNmeaMessageListener) {
                            active.onNmeaMessage(nmea, timestamp)
                        } else {
                            val method = active.javaClass.getMethod("onNmeaMessage", String::class.java, Long::class.javaPrimitiveType)
                            method.invoke(active, nmea, timestamp)
                        }
                    } else {
                        if (active is android.location.GpsStatus.NmeaListener) {
                            active.onNmeaReceived(timestamp, nmea)
                        } else {
                            val method = active.javaClass.getMethod("onNmeaReceived", Long::class.javaPrimitiveType, String::class.java)
                            method.invoke(active, timestamp, nmea)
                        }
                    }
                } catch (_: Throwable) {}
            }

            if (targetExecutor != null) {
                try { targetExecutor.execute(action) } catch (_: Throwable) {}
            } else if (targetHandler != null) {
                try { targetHandler.post(action) } catch (_: Throwable) {}
            } else {
                try { defaultMainHandler.post(action) } catch (_: Throwable) {}
            }
        }
    }

    // 进程内无锁缓存模拟状态（由后台 StateSyncDaemon 异步拉取更新，主线程/100Hz 传感器回调 O(1) 纳秒级无阻塞读取）
    @Volatile private var cachedState: RemoteState = RemoteState(
        isMocking = false,
        latitude = 39.908823,
        longitude = 116.397470,
        altitude = 35.0,
        speed = 0f,
        bearing = 0f,
        accuracy = 3.2f,
        satellites = 16,
        meanCn0 = 33.5f,
        totalSteps = 0L,
        cadenceSpm = 0,
        wifiMockEnabled = false,
        wifiSsid = "Company-Office-5G",
        wifiBssid = "00:1a:2b:3c:4d:5e",
        wifiRssi = -52,
        cellMockEnabled = false,
        cellMcc = 460,
        cellMnc = 0,
        cellLac = 41021,
        cellCid = 1892341
    )
    private val initialSyncLatch = CountDownLatch(1)
    private val isStateSyncRunning = AtomicBoolean(false)

    data class RemoteState(
        val isMocking: Boolean,
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val speed: Float,
        val bearing: Float,
        val accuracy: Float,
        val satellites: Int,
        val meanCn0: Float,
        val totalSteps: Long,
        val cadenceSpm: Int,
        // Wi-Fi 伪装
        val wifiMockEnabled: Boolean,
        val wifiSsid: String,
        val wifiBssid: String,
        val wifiRssi: Int,
        // 基站伪装
        val cellMockEnabled: Boolean,
        val cellMcc: Int,
        val cellMnc: Int,
        val cellLac: Int,
        val cellCid: Int
    )

    private val defaultState = RemoteState(
        isMocking = false,
        latitude = 39.908823,
        longitude = 116.397470,
        altitude = 35.0,
        speed = 0f,
        bearing = 0f,
        accuracy = 3.2f,
        satellites = 16,
        meanCn0 = 33.5f,
        totalSteps = 0L,
        cadenceSpm = 0,
        wifiMockEnabled = false,
        wifiSsid = "Company-Office-5G",
        wifiBssid = "00:1a:2b:3c:4d:5e",
        wifiRssi = -52,
        cellMockEnabled = false,
        cellMcc = 460,
        cellMnc = 0,
        cellLac = 41021,
        cellCid = 1892341
    )

    // LSPosed 原生 XSharedPreferences 实例（用于 Android 11+ 包隔离下的终极只读 IPC 兜底）
    @Volatile private var xSharedPrefs: XSharedPreferences? = null

    private fun getXSharedPrefs(): XSharedPreferences? {
        if (xSharedPrefs == null) {
            synchronized(this) {
                if (xSharedPrefs == null) {
                    try {
                        val prefs = XSharedPreferences("com.antigravity.geospoofer", "location_mock_pro_stealth_prefs")
                        prefs.makeWorldReadable()
                        xSharedPrefs = prefs
                    } catch (_: Throwable) {}
                }
            }
        }
        return xSharedPrefs
    }

    /**
     * Listener 线程安全调度包装器：
     * 1. 采用 WeakReference 持有宿主 LocationListener，提供弱引用双保险，严防第三方 App 忘记注销引发宿主 Activity 内存泄漏
     * 2. 在分发闭包内部动态二次判空 weakRef.get()，避免投递到 Handler/Executor 消息队列时因强引用闭包阻碍宿主 GC 回收
     * 3. 严格按照宿主注册时传入的 Looper 或 Executor 原生上下文进行投递，严禁后台线程裸调引发 CalledFromWrongThreadException
     */
    private class ListenerDispatcher(
        listener: LocationListener,
        looper: Looper?,
        executorObj: Any?
    ) {
        val weakListener = WeakReference(listener)
        val targetHandler: Handler? = if (looper != null) Handler(looper) else null
        val targetExecutor = executorObj as? Executor

        fun isAlive(): Boolean = weakListener.get() != null

        fun dispatch(location: Location, defaultMainHandler: Handler): Boolean {
            if (weakListener.get() == null) return false
            val weakRef = weakListener

            if (targetExecutor != null) {
                try {
                    targetExecutor.execute {
                        val activeListener = weakRef.get() ?: return@execute
                        try { activeListener.onLocationChanged(location) } catch (_: Throwable) {}
                    }
                    return true
                } catch (_: Throwable) {
                    return false
                }
            } else if (targetHandler != null) {
                try {
                    targetHandler.post {
                        val activeListener = weakRef.get() ?: return@post
                        try { activeListener.onLocationChanged(location) } catch (_: Throwable) {}
                    }
                    return true
                } catch (_: Throwable) {
                    return false
                }
            } else {
                try {
                    defaultMainHandler.post {
                        val activeListener = weakRef.get() ?: return@post
                        try { activeListener.onLocationChanged(location) } catch (_: Throwable) {}
                    }
                    return true
                } catch (_: Throwable) {
                    return false
                }
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 排除宿主应用自身（主进程与任意子进程/渲染进程）以及系统底层框架
        if (lpparam.packageName == "com.antigravity.geospoofer" ||
            lpparam.processName == "com.antigravity.geospoofer" ||
            lpparam.processName.startsWith("com.antigravity.geospoofer:") ||
            lpparam.appInfo?.packageName == "com.antigravity.geospoofer") {
            return
        }
        if (lpparam.packageName == "android" || lpparam.packageName == "com.android.systemui") {
            return
        }

        currentPackageName = lpparam.packageName
        isCampusMultiSensorApp = detectCampusMultiSensorApp(lpparam.packageName)

        try {
            // ================= 0. 预热异步多通道状态同步守护线程 =================
            ensureStateSyncDaemon()

            // ================= 1. 抹除 Android 虚拟定位一切底层标志位 =================
            hookMockFlags(lpparam)

            // ================= 2. 隐藏系统设置与 AppOps 开发者模拟配置 =================
            hookSettingsAndAppOps(lpparam)

            // ================= 3. 硬件步数与运动传感器 Hook 注入 (增量叠加算法) =================
            hookSensors(lpparam)

            // ================= 4. LocationManager 全维接口劫持与动态流主动推送 =================
            hookLocationManager(lpparam)

            // ================= 5. 企业打卡 Wi-Fi BSSID/SSID 环境无线电指纹伪装 =================
            hookWifi(lpparam)

            // ================= 6. 移动蜂窝基站 CellInfoLte 合规构造与伪装 =================
            hookTelephony(lpparam)

            // ================= 7. Android 11+ 包可见性 (Package Visibility) 终极穿透 =================
            hookPackageVisibility(lpparam)

            XposedBridge.log("[LocationMockPro-Hook] Phase 3.0 加固与 Release 级防护已成功注入: ${lpparam.packageName}")
        } catch (t: Throwable) {
            XposedBridge.log("[LocationMockPro-Hook] 注入目标应用 ${lpparam.packageName} 发生异常: ${t.message}")
        }
    }

    /**
     * 抹除 isFromMockProvider() / isMock() 以及 Bundle 中的 mockLocation 标志
     */
    private fun hookMockFlags(lpparam: XC_LoadPackage.LoadPackageParam) {
        // A. 拦截 Location.isFromMockProvider() -> 永真返回 false
        try {
            XposedHelpers.findAndHookMethod(
                Location::class.java,
                "isFromMockProvider",
                XC_MethodReplacement.returnConstant(false)
            )
        } catch (_: Throwable) {}

        // B. 拦截 Android 12+ (API 31) Location.isMock() -> 永真返回 false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                XposedHelpers.findAndHookMethod(
                    Location::class.java,
                    "isMock",
                    XC_MethodReplacement.returnConstant(false)
                )
            } catch (_: Throwable) {}
        }

        // C. 拦截 Location.getExtras()，剔除 mock 判定键值
        try {
            XposedHelpers.findAndHookMethod(
                Location::class.java,
                "getExtras",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val bundle = param.result as? Bundle ?: return
                        bundle.remove("mockLocation")
                        bundle.remove("isMock")
                        bundle.putBoolean("mockLocation", false)
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    /**
     * 隐藏系统开发者选项 mock_location 标志位与 AppOps 权限判定
     */
    private fun hookSettingsAndAppOps(lpparam: XC_LoadPackage.LoadPackageParam) {
        // A. Settings.Secure & Settings.Global getString / getInt (覆盖 2 参数与 3 参数重载)
        val settingsClasses = listOf(Settings.Secure::class.java, Settings.Global::class.java)
        for (clazz in settingsClasses) {
            try {
                // getString(ContentResolver, String)
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "getString",
                    android.content.ContentResolver::class.java,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val key = param.args[1] as? String
                            if (key == "mock_location" || key == "allow_mock_location") {
                                param.result = "0"
                            }
                        }
                    }
                )

                // getInt(ContentResolver, String) - 双参数原生标准重载
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "getInt",
                    android.content.ContentResolver::class.java,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val key = param.args[1] as? String
                            if (key == "mock_location" || key == "allow_mock_location") {
                                param.result = 0
                            }
                        }
                    }
                )

                // getInt(ContentResolver, String, Int) - 三参数带默认值重载
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "getInt",
                    android.content.ContentResolver::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val key = param.args[1] as? String
                            if (key == "mock_location" || key == "allow_mock_location") {
                                param.result = 0
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        // B. AppOpsManager.checkOp / checkOpNoThrow / noteOp (覆盖 Int 与 String 两种操作符体系)
        val opMethods = listOf(
            "checkOp", "checkOpNoThrow", "noteOp", "noteOpNoThrow",
            "noteProxyOp", "noteProxyOpNoThrow", "unsafeCheckOp",
            "unsafeCheckOpNoThrow", "unsafeCheckOpRaw", "unsafeCheckOpRawNoThrow"
        )
        for (m in opMethods) {
            // 1. 拦截 Int 参数体系 (OP_MOCK_LOCATION = 58)
            try {
                XposedHelpers.findAndHookMethod(
                    AppOpsManager::class.java,
                    m,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val op = param.args[0] as? Int
                            if (op == 58) { // OP_MOCK_LOCATION
                                param.result = AppOpsManager.MODE_IGNORED
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}

            // 2. 拦截现代 Android (API 23~35) String 参数体系 (OPSTR_MOCK_LOCATION = "android:mock_location")
            try {
                XposedHelpers.findAndHookMethod(
                    AppOpsManager::class.java,
                    m,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val op = param.args[0] as? String
                            if (op == "android:mock_location" || op == "mock_location" || op == "OPSTR_MOCK_LOCATION") {
                                param.result = AppOpsManager.MODE_IGNORED
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}
        }
    }

    /**
     * 硬件传感器 Hook：基于真实开机步数 baseHardwareSteps 进行增量平滑叠加，支持无传感器设备/模拟器主动虚拟仿真
     */
    private fun hookSensors(lpparam: XC_LoadPackage.LoadPackageParam) {
        // A. 拦截 getDefaultSensor：在无物理计步器的模拟器或低端设备中伪装成博世高精度计步传感器
        try {
            XposedHelpers.findAndHookMethod(
                SensorManager::class.java,
                "getDefaultSensor",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val type = param.args[0] as? Int ?: return
                        if (type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_STEP_DETECTOR) {
                            if (param.result == null) {
                                param.result = createFakeStepSensor(type)
                                logD("[LocationMockPro-Sensor] getDefaultSensor($type): Created fake sensor ${param.result}")
                            } else {
                                logD("[LocationMockPro-Sensor] getDefaultSensor($type): Hardware sensor found ${param.result}")
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // B. 拦截 registerListener：捕获目标应用注册的计步器回调并开启主动仿真推送
        try {
            val registerMethods = SensorManager::class.java.declaredMethods.filter {
                it.name == "registerListener" && it.parameterTypes.isNotEmpty() &&
                        SensorEventListener::class.java.isAssignableFrom(it.parameterTypes[0])
            }

            for (method in registerMethods) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args[0] as? SensorEventListener ?: return
                        val sensor = param.args.getOrNull(1) as? Sensor ?: return
                        var targetHandler: Handler? = null
                        for (arg in param.args) {
                            if (arg is Handler) targetHandler = arg
                        }

                        logD("[LocationMockPro-Sensor] registerListener: listener=${listener.javaClass.name}, sensor=${sensor.type} (${sensor.name})")

                        if (sensor.type == Sensor.TYPE_STEP_COUNTER || sensor.type == Sensor.TYPE_STEP_DETECTOR || sensor.type == Sensor.TYPE_ACCELEROMETER) {
                            hookSensorEventListener(listener)
                            val key = System.identityHashCode(listener) xor (sensor.type shl 16)
                            if (sensor.type == Sensor.TYPE_ACCELEROMETER) {
                                activeAccelListeners[key] = StepListenerInfo(
                                    WeakReference(listener),
                                    sensor,
                                    targetHandler
                                )
                                ensureAccelFallbackPump()
                            } else {
                                activeStepListeners[key] = StepListenerInfo(
                                    WeakReference(listener),
                                    sensor,
                                    targetHandler
                                )
                                logD("[LocationMockPro-Sensor] activeStepListeners added: key=$key, sensorType=${sensor.type}, totalListeners=${activeStepListeners.size}")
                                ensureActiveDispatcher()

                                // 极其关键：拦截底层注册，严禁将伪传感器透传给系统引发误绑物理加速度计 (mHandle=0)
                                if (isFakeStepSensorInstance(sensor)) {
                                    // 模拟器/虚拟计步器：立即向宿主 Listener 投递硬件开机基准步数，满足传感器校准契约
                                    if (sensor.type == Sensor.TYPE_STEP_COUNTER) {
                                        val h = targetHandler ?: mainHandler
                                        h.postDelayed({
                                            try {
                                                val state = getSimState()
                                                val currentSteps = if (state?.isMocking == true) state.totalSteps else 0L
                                                val ev = createFakeSensorEvent(sensor, computeMonotonicStepCounterValue(currentSteps))
                                                if (ev != null) {
                                                    listener.onSensorChanged(ev)
                                                }
                                            } catch (_: Throwable) {}
                                        }, 150L)
                                    }
                                    param.result = true
                                    return
                                }
                            }
                        }
                    }
                })
            }

            // 拦截 unregisterListener 及时注销
            val unregisterMethods = SensorManager::class.java.declaredMethods.filter {
                it.name == "unregisterListener" && it.parameterTypes.isNotEmpty() &&
                        SensorEventListener::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            for (method in unregisterMethods) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args[0] as? SensorEventListener ?: return
                        val sensor = param.args.getOrNull(1) as? Sensor
                        val listenerHash = System.identityHashCode(listener)
                        if (sensor != null) {
                            val key = listenerHash xor (sensor.type shl 16)
                            activeStepListeners.remove(key)
                            activeAccelListeners.remove(key)
                        } else {
                            activeStepListeners.remove(listenerHash xor (Sensor.TYPE_STEP_COUNTER shl 16))
                            activeStepListeners.remove(listenerHash xor (Sensor.TYPE_STEP_DETECTOR shl 16))
                            activeStepListeners.remove(listenerHash)
                            activeAccelListeners.remove(listenerHash xor (Sensor.TYPE_ACCELEROMETER shl 16))
                        }
                    }
                })
            }
        } catch (_: Throwable) {}

        // C. 拦截 PackageManager.hasSystemFeature：在无物理计步传感器或模拟器环境下伪装支持 STEP_COUNTER 与 STEP_DETECTOR
        try {
            val appPkgMgrClass = Class.forName("android.app.ApplicationPackageManager")
            XposedHelpers.findAndHookMethod(
                appPkgMgrClass,
                "hasSystemFeature",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val feature = param.args[0] as? String
                        if (feature == "android.hardware.sensor.stepcounter" ||
                            feature == "android.hardware.sensor.stepdetector") {
                            param.result = true
                        }
                    }
                }
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                XposedHelpers.findAndHookMethod(
                    appPkgMgrClass,
                    "hasSystemFeature",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val feature = param.args[0] as? String
                            if (feature == "android.hardware.sensor.stepcounter" ||
                                feature == "android.hardware.sensor.stepdetector") {
                                param.result = true
                            }
                        }
                    }
                )
            }
        } catch (_: Throwable) {}

        // D. 拦截 SensorManager.getSensorList：当系统未检测到物理步数传感器时注入仿真传感器实例
        try {
            XposedHelpers.findAndHookMethod(
                SensorManager::class.java,
                "getSensorList",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    @Suppress("UNCHECKED_CAST")
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val type = param.args[0] as? Int ?: return
                        if (type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_STEP_DETECTOR || type == Sensor.TYPE_ALL) {
                            val list = (param.result as? List<Sensor>)?.toMutableList() ?: mutableListOf()
                            val hasCounter = list.any { it.type == Sensor.TYPE_STEP_COUNTER }
                            val hasDetector = list.any { it.type == Sensor.TYPE_STEP_DETECTOR }
                            if ((type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_ALL) && !hasCounter) {
                                createFakeStepSensor(Sensor.TYPE_STEP_COUNTER)?.let { list.add(it) }
                            }
                            if ((type == Sensor.TYPE_STEP_DETECTOR || type == Sensor.TYPE_ALL) && !hasDetector) {
                                createFakeStepSensor(Sensor.TYPE_STEP_DETECTOR)?.let { list.add(it) }
                            }
                            param.result = list
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    /**
     * Hook 目标应用实现的 SensorEventListener.onSensorChanged
     */
    private fun hookSensorEventListener(listener: SensorEventListener) {
        val hash = System.identityHashCode(listener)
        if (hookedListeners.putIfAbsent(hash, true) != null) return

        try {
            val listenerClass = listener.javaClass
            logD("[LocationMockPro-Sensor] hookSensorEventListener attached to ${listenerClass.name}")
            val onSensorChangedMethod = listenerClass.getMethod("onSensorChanged", SensorEvent::class.java)

            XposedBridge.hookMethod(onSensorChangedMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val event = param.args[0] as? SensorEvent ?: return
                    val state = getSimState() ?: return
                    if (!state.isMocking) return

                    if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                        val spoofedVal = computeMonotonicStepCounterValue(state.totalSteps)
                        // 首次捕获并永久锁定真实物理硬件开机步数（排除模块自身虚拟传感器与合成值）
                        if (!hasCapturedRealHardwareStep &&
                            !isFakeStepSensorInstance(event.sensor) &&
                            event.values.isNotEmpty() &&
                            event.values[0] > 0f
                        ) {
                            val rawVal = event.values[0]
                            if (rawVal != spoofedVal && rawVal < 2000000f) {
                                baseHardwareSteps = rawVal
                            }
                            hasCapturedRealHardwareStep = true
                        }
                        if (event.values.isNotEmpty()) {
                            event.values[0] = computeMonotonicStepCounterValue(state.totalSteps)
                        }
                    } else if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
                        // 若宿主已监听权威 STEP_COUNTER，屏蔽物理微动脉冲；仅监听 STEP_DETECTOR 时允许合成单步脉冲通过
                        val hasCounter = activeStepListeners.values.any { it.sensor.type == Sensor.TYPE_STEP_COUNTER && it.isAlive() }
                        if (hasCounter || !isFakeStepSensorInstance(event.sensor)) {
                            param.result = null
                            return
                        }
                    } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                        if (isSyntheticAccelDispatch.get() != true) {
                            lastNativeAccelEventMs = System.currentTimeMillis()
                        }
                        if (event.values.size >= 3) {
                            val sampleNanos = if (event.timestamp > 0L) event.timestamp else SystemClock.elapsedRealtimeNanos()
                            populateSpoofedAccelerometer(event.values, sampleNanos, state)
                        }
                    }
                }
            })
        } catch (_: Throwable) {}
    }

    /**
     * 连续相位积分器：确保速度与步频实时微幅波动时瞬时频率严格等于目标步频 (dφ/dt = 2π·f(t))，
     * 彻底根治传统 2π·f(t)·t 引发的 t·df/dt 调频相位跳变（231~428 SPM 鬼畜步频真凶）与 100s 取模断相。
     */
    private fun advanceAndGetStridePhase(nowNanos: Long, cadenceSpm: Int): Double {
        synchronized(accelPhaseLock) {
            if (lastAccelPhaseNanos != 0L) {
                val dtNanos = nowNanos - lastAccelPhaseNanos
                if (dtNanos > 500_000L) { // > 0.5ms 才推进相位，防止同帧多 Listener 重复积分倍频
                    val clampedDtSec = dtNanos.coerceAtMost(200_000_000L) / 1_000_000_000.0
                    val strideFreqHz = cadenceSpm.coerceIn(75, 205) / 120.0
                    accelStridePhaseRad = (accelStridePhaseRad + 2.0 * Math.PI * strideFreqHz * clampedDtSec) % (2.0 * Math.PI)
                    lastAccelPhaseNanos = nowNanos
                }
            } else {
                lastAccelPhaseNanos = nowNanos
            }
            return accelStridePhaseRad
        }
    }

    /**
     * 智能分场景三轴加速度生物力学合成：
     * 1. 校园多源融合跑类 App（如运动世界校园、步道乐跑等同时监听 STEP_COUNTER + ACCELEROMETER 累加计步）：
     *    在已存在权威 STEP_COUNTER 时输出低于计步阈值的手持生理微颤（方差 > 0 防冻结检测，且 0 额外计步冲突）。
     * 2. 专业运动步态分析类 App（如 Keep 后台注册每日 STEP_COUNTER，户外跑由 100Hz 加速度计解算实时步频/步幅）：
     *    输出连续相位积分的三轴步态冲击波形（Y/Z 轴对齐单步触地频率 Cadence/60 Hz，X 轴对齐跨步侧摆半频 Cadence/120 Hz）。
     */
    private fun populateSpoofedAccelerometer(values: FloatArray, sampleNanos: Long, state: RemoteState) {
        val tSec = (sampleNanos % 3_600_000_000_000L) / 1_000_000_000.0
        val microNoise = (((sampleNanos / 1_000_000L) % 19L).toFloat() - 9f) * 0.0018f
        val hasCounter = activeStepListeners.values.any { it.sensor.type == Sensor.TYPE_STEP_COUNTER && it.isAlive() }

        if ((isCampusMultiSensorApp && hasCounter) || state.speed <= 0.4f || state.cadenceSpm <= 0) {
            // 亚阈值手持呼吸/姿态自然微幅漂移（幅值 ±0.05 m/s² << 计步器触发阈值 1.2 m/s²，消除死板 0 方差风控特征）
            values[0] = (0.13 + Math.sin(tSec * 2.1) * 0.038 + microNoise).toFloat()
            values[1] = (9.78 + Math.sin(tSec * 1.4) * 0.045 - microNoise).toFloat()
            values[2] = (0.68 + Math.cos(tSec * 1.8) * 0.035 + microNoise * 0.7f).toFloat()
            return
        }

        val stridePhase = advanceAndGetStridePhase(sampleNanos, state.cadenceSpm)
        val stepPhase = stridePhase * 2.0 // 单步触地相位 (Cadence / 60.0 Hz)

        val vertAmp = when {
            state.speed <= 1.8f -> (0.95f + (state.speed - 0.4f) * 0.42f).coerceIn(0.95f, 1.55f)
            state.speed <= 3.5f -> (1.55f + (state.speed - 1.8f) * 0.72f).coerceIn(1.55f, 2.80f)
            else -> (2.80f + (state.speed - 3.5f) * 0.35f).coerceIn(2.80f, 3.60f)
        }

        // X 轴：骨盆与手臂左右交替摆动（严格对齐跨步半频）
        values[0] = (0.12 + Math.sin(stridePhase) * (vertAmp * 0.22f) + microNoise).toFloat()
        // Y 轴：垂直落地反作用力主波（含轻微触地/腾空非对称二次谐波，单周期严格单峰单谷）
        values[1] = (9.78 + Math.sin(stepPhase) * vertAmp + Math.sin(stepPhase * 2.0 - 0.785) * (vertAmp * 0.14f) - microNoise).toFloat()
        // Z 轴：前后蹬伸俯仰分量（与主波同相，保持三维合加速度模长严格每步单峰）
        values[2] = (0.65 + Math.sin(stepPhase) * (vertAmp * 0.20f) + microNoise).toFloat()
    }

    /**
     * 模拟器静音加速度计兜底泵：
     * 若真机或模拟器底层已正常推送加速度事件（间隔 < 180ms），本线程完全休眠零开销；
     * 若无头/静止模拟器虚拟加速度计沉默不发回调，则自动以 50Hz (20ms) 补齐步态采样流。
     */
    private fun ensureAccelFallbackPump() {
        if (!isAccelPumpRunning.compareAndSet(false, true)) return
        val pumpThread = Thread({
            val scratch = FloatArray(3)
            while (true) {
                try {
                    val state = getSimState()
                    val nowMs = System.currentTimeMillis()
                    if (state == null || !state.isMocking || activeAccelListeners.isEmpty() || (nowMs - lastNativeAccelEventMs) < 180L) {
                        Thread.sleep(250L)
                        continue
                    }

                    val nowNanos = SystemClock.elapsedRealtimeNanos()
                    populateSpoofedAccelerometer(scratch, nowNanos, state)

                    val entries = activeAccelListeners.entries.toList()
                    for (entry in entries) {
                        val info = entry.value
                        val listener = info.weakListener.get()
                        if (listener == null) {
                            activeAccelListeners.remove(entry.key)
                            continue
                        }
                        val ev = createFakeSensorEvent(info.sensor, scratch, nowNanos) ?: continue
                        val h = info.targetHandler ?: mainHandler
                        h.post {
                            isSyntheticAccelDispatch.set(true)
                            try {
                                listener.onSensorChanged(ev)
                            } catch (_: Throwable) {
                            } finally {
                                isSyntheticAccelDispatch.set(false)
                            }
                        }
                    }
                    Thread.sleep(20L) // 50Hz 标准运动采样率
                } catch (_: Throwable) {
                    try { Thread.sleep(250L) } catch (_: Throwable) {}
                }
            }
        }, "HwSensorSampling")
        pumpThread.isDaemon = true
        pumpThread.start()
    }

    /**
     * 劫持 LocationManager 核心接口，包含单次请求与动态流主动推送调度器
     */
    private fun hookLocationManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        // A. getLastKnownLocation
        try {
            XposedHelpers.findAndHookMethod(
                LocationManager::class.java,
                "getLastKnownLocation",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val state = getSimState() ?: return
                        if (!state.isMocking) return

                        val provider = param.args[0] as? String ?: LocationManager.GPS_PROVIDER
                        val mockLoc = buildSpoofedLocation(provider, state)
                        param.result = mockLoc
                    }
                }
            )
        } catch (_: Throwable) {}

        // B. Android 12+ getLastLocation()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                XposedHelpers.findAndHookMethod(
                    LocationManager::class.java,
                    "getLastLocation",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val state = getSimState() ?: return
                            if (!state.isMocking) return

                            val mockLoc = buildSpoofedLocation(LocationManager.FUSED_PROVIDER, state)
                            param.result = mockLoc
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        // C. Android 11+ (API 30+) getCurrentLocation 拦截注入（解决钉钉/企微/飞书/高德等打卡首选单次精准定位穿透风险）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val currentLocMethods = LocationManager::class.java.declaredMethods.filter {
                    it.name == "getCurrentLocation"
                }
                for (method in currentLocMethods) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val state = getSimState() ?: return
                            if (!state.isMocking) return

                            var executor: Executor? = null
                            var consumer: Any? = null
                            var providerName = LocationManager.GPS_PROVIDER

                            for (arg in param.args) {
                                if (arg is String) {
                                    providerName = arg
                                } else if (arg is Executor) {
                                    executor = arg
                                } else if (arg != null && (arg.javaClass.name.contains("Consumer") || arg is java.util.function.Consumer<*>)) {
                                    consumer = arg
                                }
                            }

                            if (consumer != null) {
                                val spoofedLoc = buildSpoofedLocation(providerName, state)
                                val targetExec = executor ?: Executor { r -> mainHandler.post(r) }
                                try {
                                    targetExec.execute {
                                        try {
                                            val acceptMethod = consumer.javaClass.methods.firstOrNull {
                                                it.name == "accept" && it.parameterTypes.size == 1
                                            }
                                            acceptMethod?.invoke(consumer, spoofedLoc)
                                        } catch (_: Throwable) {}
                                    }
                                } catch (_: Throwable) {
                                    try {
                                        XposedHelpers.callMethod(consumer, "accept", spoofedLoc)
                                    } catch (_: Throwable) {}
                                }
                                param.result = null // 阻断系统原方法执行，杜绝真实基站与物理 GPS 穿透
                            }
                        }
                    })
                }
            } catch (_: Throwable) {}
        }

        // C. Hook requestLocationUpdates 全部重载：捕获 Listener/Looper/Executor 并启动闭环线程安全分发
        try {
            val requestMethods = LocationManager::class.java.declaredMethods.filter {
                it.name == "requestLocationUpdates"
            }

            for (method in requestMethods) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        var targetListener: LocationListener? = null
                        var targetLooper: Looper? = null
                        var targetExecutor: Any? = null

                        for (arg in param.args) {
                            if (arg is LocationListener) {
                                targetListener = arg
                            } else if (arg is Looper) {
                                targetLooper = arg
                            } else if (arg != null && arg.javaClass.name.contains("Executor")) {
                                targetExecutor = arg
                            }
                        }

                        if (targetListener != null) {
                            val dispatcher = ListenerDispatcher(targetListener, targetLooper, targetExecutor)
                            activeDispatchers[System.identityHashCode(targetListener)] = dispatcher
                            hookLocationListenerCallbacks(targetListener)
                            ensureActiveDispatcher()
                        }
                    }
                })
            }

            // Hook removeUpdates 及时注销监听，彻底消除内存泄漏
            val removeMethods = LocationManager::class.java.declaredMethods.filter {
                it.name == "removeUpdates"
            }
            for (method in removeMethods) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args.firstOrNull() as? LocationListener ?: return
                        activeDispatchers.remove(System.identityHashCode(listener))
                    }
                })
            }

            // D. Hook registerGnssStatusCallback 模拟急速冷启动与 3D 卫星定位锁定，并阻断模拟器 0 卫星真实状态反扑覆写
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val gnssMethods = LocationManager::class.java.declaredMethods.filter {
                    it.name == "registerGnssStatusCallback"
                }
                for (method in gnssMethods) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val callback = param.args.firstOrNull { it is android.location.GnssStatus.Callback } as? android.location.GnssStatus.Callback ?: return
                            var targetHandler: Handler? = null
                            var targetExecutor: Executor? = null
                            for (arg in param.args) {
                                if (arg is Handler) targetHandler = arg
                                else if (arg is Executor) targetExecutor = arg
                            }

                            val info = GnssCallbackInfo(
                                WeakReference(callback),
                                targetHandler,
                                targetExecutor
                            )
                            activeGnssCallbacks[System.identityHashCode(callback)] = info
                            hookGnssStatusCallbackInstance(callback)
                            ensureActiveDispatcher()

                            mainHandler.postDelayed({
                                val state = getSimState()
                                if (state != null && state.isMocking) {
                                    val status = buildSyntheticGnssStatus(state)
                                    info.dispatch(mainHandler) { cb ->
                                        if (!info.hasSentFirstFix) {
                                            info.hasSentFirstFix = true
                                            try { cb.onStarted() } catch (_: Throwable) {}
                                            try { cb.onFirstFix(480) } catch (_: Throwable) {}
                                        }
                                        if (status != null) {
                                            try { cb.onSatelliteStatusChanged(status) } catch (_: Throwable) {}
                                        }
                                    }
                                }
                            }, 180L)
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            val state = getSimState() ?: return
                            if (state.isMocking && (param.method as? java.lang.reflect.Method)?.returnType == Boolean::class.javaPrimitiveType) {
                                param.result = true
                            }
                        }
                    })
                }

                // Hook unregisterGnssStatusCallback
                try {
                    val unregisterMethod = LocationManager::class.java.getMethod("unregisterGnssStatusCallback", android.location.GnssStatus.Callback::class.java)
                    XposedBridge.hookMethod(unregisterMethod, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val callback = param.args[0] as? android.location.GnssStatus.Callback ?: return
                            activeGnssCallbacks.remove(System.identityHashCode(callback))
                        }
                    })
                } catch (_: Throwable) {}
            }

            // E. Hook addGpsStatusListener (针对旧版地图 SDK)
            try {
                val method = LocationManager::class.java.getMethod("addGpsStatusListener", android.location.GpsStatus.Listener::class.java)
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args[0] as? android.location.GpsStatus.Listener ?: return
                        mainHandler.postDelayed({
                            try {
                                val state = getSimState()
                                if (state != null && state.isMocking) {
                                    listener.onGpsStatusChanged(android.location.GpsStatus.GPS_EVENT_STARTED)
                                    listener.onGpsStatusChanged(android.location.GpsStatus.GPS_EVENT_FIRST_FIX)
                                    listener.onGpsStatusChanged(android.location.GpsStatus.GPS_EVENT_SATELLITE_STATUS)
                                }
                            } catch (_: Throwable) {}
                        }, 400L)
                    }
                })
            } catch (_: Throwable) {}

            // F. Hook addNmeaListener / removeNmeaListener (捕获高德/百度/Keep等监听 NMEA 原始卫星报文)
            try {
                val addNmeaMethods = LocationManager::class.java.declaredMethods.filter { it.name == "addNmeaListener" }
                for (method in addNmeaMethods) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            var targetListener: Any? = null
                            var targetHandler: Handler? = null
                            var targetExecutor: Executor? = null
                            var isModern = false

                            for (arg in param.args) {
                                if (arg == null) continue
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && arg is android.location.OnNmeaMessageListener) {
                                    targetListener = arg
                                    isModern = true
                                } else if (arg is android.location.GpsStatus.NmeaListener) {
                                    targetListener = arg
                                    isModern = false
                                } else if (arg is Handler) {
                                    targetHandler = arg
                                } else if (arg is Executor) {
                                    targetExecutor = arg
                                } else if (arg.javaClass.name.contains("NmeaMessageListener")) {
                                    targetListener = arg
                                    isModern = true
                                } else if (arg.javaClass.name.contains("NmeaListener")) {
                                    targetListener = arg
                                    isModern = false
                                }
                            }

                            if (targetListener != null) {
                                val info = NmeaListenerInfo(
                                    WeakReference(targetListener),
                                    targetHandler,
                                    targetExecutor,
                                    isModern
                                )
                                activeNmeaListeners[System.identityHashCode(targetListener)] = info
                                ensureActiveDispatcher()
                            }
                        }
                    })
                }

                val removeNmeaMethods = LocationManager::class.java.declaredMethods.filter { it.name == "removeNmeaListener" }
                for (method in removeNmeaMethods) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val listener = param.args.firstOrNull { it != null && (it.javaClass.name.contains("Nmea") || it is android.location.GpsStatus.NmeaListener) } ?: return
                            activeNmeaListeners.remove(System.identityHashCode(listener))
                        }
                    })
                }
            } catch (_: Throwable) {}

            // G. Hook isProviderEnabled (确保在模拟开启时 gps/network/fused 恒定可用)
            try {
                XposedHelpers.findAndHookMethod(
                    LocationManager::class.java,
                    "isProviderEnabled",
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val state = getSimState() ?: return
                            if (state.isMocking) {
                                val p = param.args[0] as? String
                                if (p == LocationManager.GPS_PROVIDER ||
                                    p == LocationManager.NETWORK_PROVIDER ||
                                    p == LocationManager.PASSIVE_PROVIDER ||
                                    p == "fused") {
                                    param.result = true
                                }
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}

            // H. Hook getProviders & getAllProviders
            try {
                XposedHelpers.findAndHookMethod(
                    LocationManager::class.java,
                    "getProviders",
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        @Suppress("UNCHECKED_CAST")
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val state = getSimState() ?: return
                            if (state.isMocking) {
                                val list = (param.result as? List<String>)?.toMutableList() ?: mutableListOf()
                                if (!list.contains(LocationManager.GPS_PROVIDER)) list.add(LocationManager.GPS_PROVIDER)
                                if (!list.contains(LocationManager.NETWORK_PROVIDER)) list.add(LocationManager.NETWORK_PROVIDER)
                                if (!list.contains(LocationManager.PASSIVE_PROVIDER)) list.add(LocationManager.PASSIVE_PROVIDER)
                                if (!list.contains("fused")) list.add("fused")
                                param.result = list
                            }
                        }
                    }
                )

                XposedHelpers.findAndHookMethod(
                    LocationManager::class.java,
                    "getAllProviders",
                    object : XC_MethodHook() {
                        @Suppress("UNCHECKED_CAST")
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val state = getSimState() ?: return
                            if (state.isMocking) {
                                val list = (param.result as? List<String>)?.toMutableList() ?: mutableListOf()
                                if (!list.contains(LocationManager.GPS_PROVIDER)) list.add(LocationManager.GPS_PROVIDER)
                                if (!list.contains(LocationManager.NETWORK_PROVIDER)) list.add(LocationManager.NETWORK_PROVIDER)
                                if (!list.contains(LocationManager.PASSIVE_PROVIDER)) list.add(LocationManager.PASSIVE_PROVIDER)
                                if (!list.contains("fused")) list.add("fused")
                                param.result = list
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}

            // I. Hook getBestProvider
            try {
                XposedHelpers.findAndHookMethod(
                    LocationManager::class.java,
                    "getBestProvider",
                    android.location.Criteria::class.java,
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val state = getSimState() ?: return
                            if (state.isMocking) {
                                param.result = LocationManager.GPS_PROVIDER
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    /**
     * 针对目标应用的 LocationListener 回调进行实时位置劫持与单调时钟补齐
     */
    private fun hookLocationListenerCallbacks(listener: LocationListener) {
        val hash = System.identityHashCode(listener)
        if (hookedLocationListeners.putIfAbsent(hash, true) != null) return

        try {
            val listenerClass = listener.javaClass

            // Hook onLocationChanged(Location)
            try {
                val method = listenerClass.getMethod("onLocationChanged", Location::class.java)
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val state = getSimState() ?: return
                        if (!state.isMocking) return
                        val originalLoc = param.args[0] as? Location
                        val provider = originalLoc?.provider ?: LocationManager.GPS_PROVIDER
                        param.args[0] = buildSpoofedLocation(provider, state)
                    }
                })
            } catch (_: NoSuchMethodException) {}

            // Hook API 30+ onLocationChanged(List<Location>)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val listMethod = listenerClass.getMethod("onLocationChanged", List::class.java)
                    XposedBridge.hookMethod(listMethod, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val state = getSimState() ?: return
                            if (!state.isMocking) return
                            val spoofedLoc = buildSpoofedLocation(LocationManager.GPS_PROVIDER, state)
                            param.args[0] = listOf(spoofedLoc)
                        }
                    })
                } catch (_: NoSuchMethodException) {}
            }
        } catch (_: Throwable) {}
    }

    /**
     * 拦截 GnssStatus.Callback.onSatelliteStatusChanged，防止模拟器/室内真实 0 卫星回调将模拟满格信号覆写为弱信号
     */
    private fun hookGnssStatusCallbackInstance(callback: android.location.GnssStatus.Callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val hash = System.identityHashCode(callback)
        if (hookedGnssCallbacks.putIfAbsent(hash, true) != null) return

        try {
            val method = callback.javaClass.getMethod("onSatelliteStatusChanged", android.location.GnssStatus::class.java)
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val state = getSimState() ?: return
                    if (!state.isMocking) return
                    val incoming = param.args[0] as? android.location.GnssStatus
                    if (incoming == null || incoming.satelliteCount < 10) {
                        val synthetic = buildSyntheticGnssStatus(state)
                        if (synthetic != null) {
                            param.args[0] = synthetic
                        } else {
                            param.result = null
                        }
                    }
                }
            })
        } catch (_: Throwable) {}
    }

    /**
     * 启动进程内主动定时推送调度器（严格基于目标线程上下文投递，拒绝裸调造成 WrongThreadException）
     * 采用伪装的系统硬件线程名 "HwSensorEventAck" 与自适应休眠策略，兼顾 2.8Hz 丝滑轨迹推送与后台零功耗隐身
     */
    private fun ensureActiveDispatcher() {
        if (isDispatcherRunning) return
        synchronized(this) {
            if (isDispatcherRunning) return
            isDispatcherRunning = true

            val dispatcherThread = Thread({
                var lastStepAndGnssDispatchTime = 0L
                while (true) {
                    try {
                        val state = getSimState()
                        val isActive = state != null && state.isMocking
                        Thread.sleep(if (isActive) 350L else 1200L)

                        val currentState = getSimState()
                        if (currentState != null && currentState.isMocking) {
                            // 定期清理已被 GC 回收的无效引用（弱引用双保险）
                            val iterator = activeDispatchers.entries.iterator()
                            while (iterator.hasNext()) {
                                val entry = iterator.next()
                                if (!entry.value.isAlive()) {
                                    iterator.remove()
                                }
                            }

                            val activeEntries = activeDispatchers.entries.toList()
                            if (activeEntries.isNotEmpty()) {
                                val spoofedLoc = buildSpoofedLocation(LocationManager.GPS_PROVIDER, currentState)
                                for (entry in activeEntries) {
                                    val success = entry.value.dispatch(spoofedLoc, mainHandler)
                                    if (!success || !entry.value.isAlive()) {
                                        activeDispatchers.remove(entry.key)
                                    }
                                }
                            }

                            val nowMillis = System.currentTimeMillis()
                            if (nowMillis - lastStepAndGnssDispatchTime >= 700L) {
                                lastStepAndGnssDispatchTime = nowMillis

                                // 计步器主动仿真事件分发（为无物理计步器的模拟器与静止真机主动推送严格单调递增步数事件）
                                if (activeStepListeners.isNotEmpty() && (currentState.totalSteps != lastDispatchedStepCount || currentState.speed > 0.35f)) {
                                    if (currentState.totalSteps < lastDispatchedStepCount) {
                                        lastDispatchedStepCount = -1L // 新会话重置增量跟踪，但全局步数保持单调累加
                                    }
                                    if (!hasCapturedRealHardwareStep) {
                                        hasCapturedRealHardwareStep = true // 永久锁定基准，严禁派发过程中突跳
                                    }
                                    val stepDelta = if (lastDispatchedStepCount >= 0 && currentState.totalSteps > lastDispatchedStepCount) {
                                        (currentState.totalSteps - lastDispatchedStepCount).coerceIn(1L, 4L).toInt()
                                    } else {
                                        if (currentState.speed > 0.35f) 1 else 0
                                    }
                                    lastDispatchedStepCount = currentState.totalSteps
                                    val monotonicStepVal = computeMonotonicStepCounterValue(currentState.totalSteps)

                                    val activeStepEntries = activeStepListeners.entries.toList()
                                    val hasCounterListener = activeStepEntries.any { it.value.sensor.type == Sensor.TYPE_STEP_COUNTER && it.value.isAlive() }

                                    for (entry in activeStepEntries) {
                                        val info = entry.value
                                        val listener = info.weakListener.get()
                                        if (listener == null) {
                                            activeStepListeners.remove(entry.key)
                                            continue
                                        }
                                        if (info.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                                            val event = createFakeSensorEvent(info.sensor, monotonicStepVal)
                                            if (event != null) {
                                                val h = info.targetHandler ?: mainHandler
                                                h.post {
                                                    try { listener.onSensorChanged(event) } catch (_: Throwable) {}
                                                }
                                            }
                                        } else if (info.sensor.type == Sensor.TYPE_STEP_DETECTOR && stepDelta > 0) {
                                            // 核心防冲突：若宿主 App 已注册 TYPE_STEP_COUNTER，由权威计数器独占更新，严禁同时推送 STEP_DETECTOR
                                            if (hasCounterListener) {
                                                continue
                                            }
                                            val h = info.targetHandler ?: mainHandler
                                            for (subStep in 1..stepDelta) {
                                                val subDelay = (subStep - 1) * (700L / stepDelta)
                                                h.postDelayed({
                                                    val ev = createFakeSensorEvent(info.sensor, 1.0f)
                                                    if (ev != null) {
                                                        try { listener.onSensorChanged(ev) } catch (_: Throwable) {}
                                                    }
                                                }, subDelay)
                                            }
                                        }
                                    }
                                }

                                // GNSS 卫星状态主动仿真事件推流（严格遵循注册 Handler/Executor，自动补发首次锁定事件）
                                if (activeGnssCallbacks.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    val status = buildSyntheticGnssStatus(currentState)
                                    if (status != null) {
                                        val gnssEntries = activeGnssCallbacks.entries.toList()
                                        for (entry in gnssEntries) {
                                            val info = entry.value
                                            if (!info.isAlive()) {
                                                activeGnssCallbacks.remove(entry.key)
                                                continue
                                            }
                                            info.dispatch(mainHandler) { cb ->
                                                if (!info.hasSentFirstFix) {
                                                    info.hasSentFirstFix = true
                                                    try { cb.onStarted() } catch (_: Throwable) {}
                                                    try { cb.onFirstFix(480) } catch (_: Throwable) {}
                                                }
                                                try { cb.onSatelliteStatusChanged(status) } catch (_: Throwable) {}
                                            }
                                        }
                                    }
                                }

                                // NMEA 0183 原始卫星报文主动仿真流推流 ($GPRMC & $GPGGA)
                                if (activeNmeaListeners.isNotEmpty()) {
                                    val sentences = buildNmeaSentences(currentState, nowMillis)
                                    val nmeaEntries = activeNmeaListeners.entries.toList()
                                    for (entry in nmeaEntries) {
                                        val info = entry.value
                                        if (!info.isAlive()) {
                                            activeNmeaListeners.remove(entry.key)
                                            continue
                                        }
                                        for (sentence in sentences) {
                                            info.dispatch(sentence, nowMillis, mainHandler)
                                        }
                                    }
                                }
                            }
                        } else {
                            for (info in activeGnssCallbacks.values) {
                                info.hasSentFirstFix = false
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }, "HwSensorEventAck")
            dispatcherThread.isDaemon = true
            dispatcherThread.start()
        }
    }

    /**
     * 企业打卡 Wi-Fi BSSID/SSID 扫描与连接指纹注入（仅在模拟激活且开启 Wi-Fi 仿真时生效）
     */
    private fun hookWifi(lpparam: XC_LoadPackage.LoadPackageParam) {
        // A. WifiInfo 劫持 (SSID, BSSID, RSSI)
        try {
            XposedHelpers.findAndHookMethod(
                WifiInfo::class.java,
                "getSSID",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val state = getSimState() ?: return
                        if (state.isMocking && state.wifiMockEnabled && state.wifiSsid.isNotBlank()) {
                            param.result = "\"${state.wifiSsid}\""
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                WifiInfo::class.java,
                "getBSSID",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val state = getSimState() ?: return
                        if (state.isMocking && state.wifiMockEnabled && state.wifiBssid.isNotBlank()) {
                            param.result = state.wifiBssid
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                WifiInfo::class.java,
                "getRssi",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val state = getSimState() ?: return
                        if (state.isMocking && state.wifiMockEnabled) {
                            param.result = state.wifiRssi
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // B. WifiManager.getScanResults 注入目标考勤 AP 节点
        try {
            XposedHelpers.findAndHookMethod(
                WifiManager::class.java,
                "getScanResults",
                object : XC_MethodHook() {
                    @Suppress("UNCHECKED_CAST")
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val state = getSimState() ?: return
                        if (!state.isMocking || !state.wifiMockEnabled || state.wifiBssid.isBlank()) return

                        val originalList = (param.result as? List<ScanResult>)?.toMutableList() ?: mutableListOf()

                        try {
                            val fakeAp = ScanResult().apply {
                                SSID = state.wifiSsid
                                BSSID = state.wifiBssid
                                capabilities = "[WPA2-PSK-CCMP][ESS]"
                                level = state.wifiRssi
                                frequency = 5180
                                timestamp = SystemClock.elapsedRealtime() * 1000
                            }
                            originalList.add(0, fakeAp)
                            param.result = originalList
                        } catch (_: Throwable) {}
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                WifiManager::class.java,
                "isWifiEnabled",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val state = getSimState() ?: return
                        if (state.isMocking && state.wifiMockEnabled) {
                            param.result = true
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    /**
     * 移动蜂窝基站 CellInfoLte 合规构造与伪装（包含同步 getAllCellInfo 与 Android 10+ 异步 requestCellInfoUpdate）
     */
    private fun hookTelephony(lpparam: XC_LoadPackage.LoadPackageParam) {
        // A. 拦截 TelephonyManager.getCellLocation
        try {
            XposedHelpers.findAndHookMethod(
                TelephonyManager::class.java,
                "getCellLocation",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val state = getSimState() ?: return
                        if (!state.isMocking || !state.cellMockEnabled) return

                        val fakeCell = GsmCellLocation().apply {
                            setLacAndCid(state.cellLac, state.cellCid)
                        }
                        param.result = fakeCell
                    }
                }
            )
        } catch (_: Throwable) {}

        // B. 拦截 TelephonyManager.getAllCellInfo：完整构造 CellInfoLte（MCC=460、MNC、TAC、CI 及非空 -70~-85dBm 信号）
        try {
            XposedHelpers.findAndHookMethod(
                TelephonyManager::class.java,
                "getAllCellInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val state = getSimState() ?: return
                        if (!state.isMocking || !state.cellMockEnabled) return

                        val fakeLte = buildFakeCellInfoLte(state)
                        if (fakeLte != null) {
                            param.result = listOf(fakeLte)
                        } else {
                            param.result = emptyList<CellInfo>()
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // C. 拦截 Android 10+ (API 29+) TelephonyManager.requestCellInfoUpdate 异步基站扫描回调
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val methods = TelephonyManager::class.java.declaredMethods.filter { it.name == "requestCellInfoUpdate" }
                for (method in methods) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val state = getSimState() ?: return
                            if (!state.isMocking || !state.cellMockEnabled) return

                            val executor = param.args.firstOrNull { it is Executor } as? Executor
                            val callback = param.args.firstOrNull { it is TelephonyManager.CellInfoCallback } as? TelephonyManager.CellInfoCallback
                            if (callback != null) {
                                val fakeLte = buildFakeCellInfoLte(state)
                                val cellList = if (fakeLte != null) listOf(fakeLte) else emptyList()
                                val task = Runnable {
                                    try { callback.onCellInfo(cellList) } catch (_: Throwable) {}
                                }
                                if (executor != null) {
                                    try { executor.execute(task) } catch (_: Throwable) { mainHandler.post(task) }
                                } else {
                                    mainHandler.post(task)
                                }
                                param.result = null
                            }
                        }
                    })
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * 动态构造符合 AOSP 规范的合法 CellInfoLte，防止目标 App 读取信号强度时产生空指针闪退
     */
    private fun buildFakeCellInfoLte(state: RemoteState): CellInfo? {
        return try {
            val cellInfo = XposedHelpers.newInstance(CellInfoLte::class.java) as CellInfoLte

            // 1. 设置注册标记为 Primary 驻网小区
            try {
                XposedHelpers.setBooleanField(cellInfo, "mRegistered", true)
            } catch (_: Throwable) {
                try {
                    val method = CellInfo::class.java.getDeclaredMethod("setRegistered", Boolean::class.javaPrimitiveType)
                    method.isAccessible = true
                    method.invoke(cellInfo, true)
                } catch (_: Throwable) {}
            }

            // 2. 构造或反射填充 CellIdentityLte
            val cellIdentity = try {
                XposedHelpers.newInstance(CellIdentityLte::class.java) as CellIdentityLte
            } catch (_: Throwable) {
                null
            }

            if (cellIdentity != null) {
                val mccStr = state.cellMcc.toString()
                val mncStr = String.format("%02d", state.cellMnc)

                try { XposedHelpers.setObjectField(cellIdentity, "mMccStr", mccStr) } catch (_: Throwable) {}
                try { XposedHelpers.setObjectField(cellIdentity, "mMncStr", mncStr) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(cellIdentity, "mMcc", state.cellMcc) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(cellIdentity, "mMnc", state.cellMnc) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(cellIdentity, "mCi", state.cellCid) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(cellIdentity, "mTac", state.cellLac) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(cellIdentity, "mPci", 168) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(cellIdentity, "mEarfcn", 1825) } catch (_: Throwable) {}
                try { XposedHelpers.setObjectField(cellIdentity, "mAlphaLong", if (state.cellMnc == 0) "China Mobile" else "China Telecom") } catch (_: Throwable) {}
                try { XposedHelpers.setObjectField(cellIdentity, "mAlphaShort", if (state.cellMnc == 0) "CMCC" else "CT") } catch (_: Throwable) {}

                try {
                    XposedHelpers.setObjectField(cellInfo, "mCellIdentityLte", cellIdentity)
                } catch (_: Throwable) {}
            }

            // 3. 构造并注入合法的 CellSignalStrengthLte (Dbm 控制在 -70 ~ -85 dBm 极优区间)
            val cellSignal = try {
                XposedHelpers.newInstance(CellSignalStrengthLte::class.java) as CellSignalStrengthLte
            } catch (_: Throwable) {
                null
            }

            if (cellSignal != null) {
                val randomDbm = -75 - (System.currentTimeMillis() % 10).toInt() // -75 ~ -84 dBm 真实轻微波动
                try { XposedHelpers.setIntField(cellSignal, "mDbm", randomDbm) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(cellSignal, "mRssi", randomDbm) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(cellSignal, "mRsrp", randomDbm - 8) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(cellSignal, "mRsrq", -9) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(cellSignal, "mRssnr", 18) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(cellSignal, "mSignalStrength", 26) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(cellSignal, "mLevel", 4) } catch (_: Throwable) {}

                try {
                    XposedHelpers.setObjectField(cellInfo, "mCellSignalStrengthLte", cellSignal)
                } catch (_: Throwable) {}
            }

            cellInfo
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 动态构造符合 AOSP 规范的合法虚拟硬件计步器传感器（供模拟器环境免杀注入）
     */
    private fun createFakeStepSensor(type: Int): Sensor? {
        return try {
            val sensor = XposedHelpers.newInstance(Sensor::class.java) as Sensor
            try { XposedHelpers.setIntField(sensor, "mType", type) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(sensor, "mName", if (type == Sensor.TYPE_STEP_COUNTER) "BOSCH Step Counter Sensor" else "BOSCH Step Detector Sensor") } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(sensor, "mVendor", "Bosch Sensortec") } catch (_: Throwable) {}
            try { XposedHelpers.setIntField(sensor, "mVersion", 1) } catch (_: Throwable) {}
            try { XposedHelpers.setFloatField(sensor, "mMaxRange", 100000.0f) } catch (_: Throwable) {}
            try { XposedHelpers.setFloatField(sensor, "mResolution", 1.0f) } catch (_: Throwable) {}
            try { XposedHelpers.setFloatField(sensor, "mPower", 0.05f) } catch (_: Throwable) {}
            try { XposedHelpers.setIntField(sensor, "mMinDelay", 0) } catch (_: Throwable) {}
            try { XposedHelpers.setIntField(sensor, "mHandle", if (type == Sensor.TYPE_STEP_COUNTER) -101 else -102) } catch (_: Throwable) {}
            sensor
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 构造用于主动回调投递的虚拟 SensorEvent 事件实例（兼容单维计步器与三维加速度计数组）
     */
    private fun createFakeSensorEvent(sensor: Sensor, stepValue: Float): SensorEvent? {
        return createFakeSensorEvent(sensor, floatArrayOf(stepValue), SystemClock.elapsedRealtimeNanos())
    }

    private fun createFakeSensorEvent(
        sensor: Sensor,
        sourceValues: FloatArray,
        timestampNanos: Long = SystemClock.elapsedRealtimeNanos()
    ): SensorEvent? {
        return try {
            val minSize = sourceValues.size.coerceAtLeast(3)
            val event = try {
                XposedHelpers.newInstance(SensorEvent::class.java, minSize) as SensorEvent
            } catch (_: Throwable) {
                try {
                    XposedHelpers.newInstance(
                        SensorEvent::class.java,
                        sensor,
                        SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                        timestampNanos,
                        sourceValues.copyOf(minSize)
                    ) as SensorEvent
                } catch (_: Throwable) {
                    XposedHelpers.newInstance(SensorEvent::class.java) as SensorEvent
                }
            }
            try { XposedHelpers.setObjectField(event, "sensor", sensor) } catch (_: Throwable) {}
            try { XposedHelpers.setIntField(event, "accuracy", SensorManager.SENSOR_STATUS_ACCURACY_HIGH) } catch (_: Throwable) {}
            try { XposedHelpers.setLongField(event, "timestamp", timestampNanos) } catch (_: Throwable) {}
            var values = event.values
            if (values == null || values.size < sourceValues.size) {
                values = FloatArray(minSize)
                try { XposedHelpers.setObjectField(event, "values", values) } catch (_: Throwable) {}
            }
            for (i in sourceValues.indices) {
                if (i < values.size) {
                    values[i] = sourceValues[i]
                }
            }
            event
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 构造用于主动回调投递的高保真 3D 锁定 GnssStatus 实例 (支持 12 颗 GPS + 5 颗北斗卫星，含自然载噪比微幅呼吸)
     */
    private fun buildSyntheticGnssStatus(state: RemoteState? = null): android.location.GnssStatus? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val builder = android.location.GnssStatus.Builder()
                val baseCn0 = (state?.meanCn0 ?: 34.5f).coerceIn(31.5f, 39.5f)
                val secTick = (System.currentTimeMillis() / 1000L)
                // 12 GPS 卫星 (PRN 1~12)
                for (i in 1..12) {
                    val jitter = Math.sin((secTick + i * 3L) * 0.45).toFloat() * 1.1f
                    val cn0 = (baseCn0 + (i % 5) * 1.3f + jitter).coerceIn(31.0f, 42.5f)
                    builder.addSatellite(
                        android.location.GnssStatus.CONSTELLATION_GPS,
                        i,
                        cn0,
                        (32.0f + (i * 4) % 45).toFloat(),   // elevation
                        ((i * 29.0f) % 360f).toFloat(),     // azimuth
                        true,                   // hasEphemeris
                        true,                   // hasAlmanac
                        true,                   // usedInFix (高德/运动世界判定 GPS_ACCURACY_GOOD 满格绿条的核心依据)
                        true,                   // hasCarrierFrequency
                        1575420000.0f,          // carrierFrequencyHz (L1)
                        true,                   // hasBasebandCn0DbHz
                        (cn0 - 3.0f).coerceAtLeast(28.0f)
                    )
                }
                // 5 北斗卫星 (PRN 1~5)
                for (i in 1..5) {
                    val jitter = Math.cos((secTick + i * 5L) * 0.4).toFloat() * 1.0f
                    val cn0 = (baseCn0 + 1.5f + (i % 3) * 1.5f + jitter).coerceIn(32.5f, 43.0f)
                    builder.addSatellite(
                        android.location.GnssStatus.CONSTELLATION_BEIDOU,
                        i,
                        cn0,
                        (50.0f + (i * 5) % 30).toFloat(),
                        ((i * 71.0f) % 360f).toFloat(),
                        true,
                        true,
                        true,
                        true,
                        1561098000.0f,          // carrierFrequencyHz (B1)
                        true,
                        (cn0 - 2.8f).coerceAtLeast(29.0f)
                    )
                }
                return builder.build()
            } catch (_: Throwable) {}
        }
        return null
    }

    /**
     * 7. Android 包可见性穿透与自身应用包名隐藏 (Package Visibility & Anti-Scan)
     * - 确保宿主应用无障碍访问 LocationStateProvider
     * - 同时在宿主调用 getInstalledPackages / getInstalledApplications 扫描黑名单时隐去自身包名
     */
    private fun hookPackageVisibility(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val appPkgMgrClass = Class.forName("android.app.ApplicationPackageManager")

            // A. 隐藏模块自身包名，防止宿主通过已安装应用列表扫描虚拟定位软件
            val getPkgsMethods = appPkgMgrClass.declaredMethods.filter { it.name == "getInstalledPackages" }
            for (method in getPkgsMethods) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    @Suppress("UNCHECKED_CAST")
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val list = param.result as? List<android.content.pm.PackageInfo> ?: return
                        param.result = list.filter { it.packageName != "com.antigravity.geospoofer" }
                    }
                })
            }

            val getAppsMethods = appPkgMgrClass.declaredMethods.filter { it.name == "getInstalledApplications" }
            for (method in getAppsMethods) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    @Suppress("UNCHECKED_CAST")
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val list = param.result as? List<android.content.pm.ApplicationInfo> ?: return
                        param.result = list.filter { it.packageName != "com.antigravity.geospoofer" }
                    }
                })
            }

            // A2. 核心防探测：拦截 getPackageInfo 与 getApplicationInfo 单点定向探测，针对自身包名抛出 NameNotFoundException
            val getPkgInfoMethods = appPkgMgrClass.declaredMethods.filter { it.name == "getPackageInfo" }
            for (method in getPkgInfoMethods) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkg = param.args.firstOrNull() as? String
                        if (pkg == "com.antigravity.geospoofer") {
                            val curPkg = lpparam.packageName
                            val curProc = lpparam.processName
                            if (curPkg == "com.antigravity.geospoofer" ||
                                curProc == "com.antigravity.geospoofer" ||
                                curProc.startsWith("com.antigravity.geospoofer:")) {
                                return
                            }
                            param.throwable = android.content.pm.PackageManager.NameNotFoundException(pkg)
                        }
                    }
                })
            }

            val getAppInfoMethods = appPkgMgrClass.declaredMethods.filter { it.name == "getApplicationInfo" }
            for (method in getAppInfoMethods) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkg = param.args.firstOrNull() as? String
                        if (pkg == "com.antigravity.geospoofer") {
                            val curPkg = lpparam.packageName
                            val curProc = lpparam.processName
                            if (curPkg == "com.antigravity.geospoofer" ||
                                curProc == "com.antigravity.geospoofer" ||
                                curProc.startsWith("com.antigravity.geospoofer:")) {
                                return
                            }
                            param.throwable = android.content.pm.PackageManager.NameNotFoundException(pkg)
                        }
                    }
                })
            }

            // A3. 拦截 queryIntentActivities 与 queryIntentServices 意图查询黑名单探测
            val queryActivitiesMethods = appPkgMgrClass.declaredMethods.filter { it.name == "queryIntentActivities" }
            for (method in queryActivitiesMethods) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    @Suppress("UNCHECKED_CAST")
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val list = param.result as? List<android.content.pm.ResolveInfo> ?: return
                        param.result = list.filter { it.activityInfo?.packageName != "com.antigravity.geospoofer" }
                    }
                })
            }

            val queryServicesMethods = appPkgMgrClass.declaredMethods.filter { it.name == "queryIntentServices" }
            for (method in queryServicesMethods) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    @Suppress("UNCHECKED_CAST")
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val list = param.result as? List<android.content.pm.ResolveInfo> ?: return
                        param.result = list.filter { it.serviceInfo?.packageName != "com.antigravity.geospoofer" }
                    }
                })
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

            // B. 放行 canPackageQuery 跨包查询鉴权
            val canQueryMethods = appPkgMgrClass.declaredMethods.filter { it.name == "canPackageQuery" }
            for (method in canQueryMethods) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        for (arg in param.args) {
                            if (arg == "com.antigravity.geospoofer") {
                                param.result = true
                                return
                            }
                        }
                    }
                })
            }

            // C. 拦截 resolveContentProvider，防止宿主查询 com.antigravity.geospoofer.provider 返回 null
            val resolveMethods = appPkgMgrClass.declaredMethods.filter { it.name == "resolveContentProvider" }
            for (method in resolveMethods) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val auth = param.args.firstOrNull() as? String
                        if (auth == "com.antigravity.geospoofer.provider" && param.result == null) {
                            try {
                                val pi = android.content.pm.ProviderInfo().apply {
                                    authority = "com.antigravity.geospoofer.provider"
                                    packageName = "com.antigravity.geospoofer"
                                    name = "com.antigravity.geospoofer.provider.LocationStateProvider"
                                    exported = true
                                    grantUriPermissions = true
                                }
                                param.result = pi
                            } catch (_: Throwable) {}
                        }
                    }
                })
            }
        } catch (_: Throwable) {}
    }

    /**
     * 纳秒级单调时钟与精度补齐：使用主引擎同步的 2.6m ~ 4.8m 极优精度与星历 Extras，保证 GPS_ACCURACY_GOOD 满格信号
     */
    private fun buildSpoofedLocation(provider: String, state: RemoteState): Location {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val nowMillis = System.currentTimeMillis()
        val baseAcc = state.accuracy.coerceIn(2.6f, 4.8f)
        val microJitter = (((nowNanos % 140).toFloat() - 70f) / 1000.0f)
        val dynamicAccuracy = (baseAcc + microJitter).coerceIn(2.5f, 4.9f)
        val satCount = state.satellites.coerceIn(12, 24)
        val cn0Val = state.meanCn0.coerceIn(31.0f, 42.0f)

        val extras = Bundle().apply {
            putInt("satellites", satCount)
            putInt("Satellites", satCount)
            putInt("maxSatellites", 28)
            putFloat("meanCn0", cn0Val)
            putFloat("maxCn0", cn0Val + 5.2f)
            putInt("numSatellitesInView", satCount + 4)
            putInt("numSatellitesUsedInFix", satCount)
            putBoolean("mockLocation", false)
            putBoolean("isMock", false)
        }

        return Location(provider).apply {
            latitude = state.latitude
            longitude = state.longitude
            altitude = state.altitude
            speed = state.speed
            bearing = state.bearing
            accuracy = dynamicAccuracy
            time = nowMillis
            elapsedRealtimeNanos = nowNanos
            this.extras = extras
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                bearingAccuracyDegrees = 1.0f
                speedAccuracyMetersPerSecond = 0.2f
                verticalAccuracyMeters = 1.5f
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    mslAltitudeMeters = state.altitude
                    mslAltitudeAccuracyMeters = 1.0f
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * 合成标准 NMEA 0183 卫星报文 ($GPRMC 与 $GPGGA)
     */
    private fun buildNmeaSentences(state: RemoteState, timestamp: Long): List<String> {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = timestamp
        }
        val utcTime = String.format(
            Locale.US,
            "%02d%02d%02d.%02d",
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND),
            calendar.get(Calendar.MILLISECOND) / 10
        )
        val utcDate = String.format(
            Locale.US,
            "%02d%02d%02d",
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.YEAR) % 100
        )

        val latDeg = Math.abs(state.latitude).toInt()
        val latMin = (Math.abs(state.latitude) - latDeg) * 60.0
        val latHem = if (state.latitude >= 0) "N" else "S"
        val latStr = String.format(Locale.US, "%02d%07.4f,%s", latDeg, latMin, latHem)

        val lonDeg = Math.abs(state.longitude).toInt()
        val lonMin = (Math.abs(state.longitude) - lonDeg) * 60.0
        val lonHem = if (state.longitude >= 0) "E" else "W"
        val lonStr = String.format(Locale.US, "%03d%07.4f,%s", lonDeg, lonMin, lonHem)

        val speedKnots = state.speed * 1.943844f
        val speedStr = String.format(Locale.US, "%05.1f", speedKnots)
        val bearingStr = String.format(Locale.US, "%05.1f", state.bearing)
        val altStr = String.format(Locale.US, "%.1f", state.altitude)
        val satCount = state.satellites.coerceIn(12, 24)

        val rmcPayload = "GPRMC,$utcTime,A,$latStr,$lonStr,$speedStr,$bearingStr,$utcDate,,,A"
        val rmc = formatNmeaSentence(rmcPayload)

        val ggaPayload = "GPGGA,$utcTime,$latStr,$lonStr,1,$satCount,0.8,$altStr,M,0.0,M,,"
        val gga = formatNmeaSentence(ggaPayload)

        return listOf(rmc, gga)
    }

    private fun formatNmeaSentence(payload: String): String {
        var checksum = 0
        for (char in payload) {
            checksum = checksum xor char.code
        }
        return "\$$payload*${String.format(Locale.US, "%02X", checksum)}"
    }

    // IPC 通道熔断与回环套接字状态（仅由后台 StateSyncDaemon 线程访问）
    @Volatile private var providerCooldownUntilMs: Long = 0L
    @Volatile private var udpPermissionDenied: Boolean = false
    private var preferredUdpPort: Int = 39527

    /**
     * O(1) 无锁纳秒级获取最新模拟状态：
     * 绝不在主线程或 100Hz 传感器回调线程内执行 Binder IPC、Socket 或磁盘 I/O，
     * 彻底根除 HMA 拦截场景下 ActivityThread 日志洪泛、卡顿掉帧与 StrictMode 违例。
     */
    private fun getSimState(): RemoteState? {
        ensureStateSyncDaemon()
        if (initialSyncLatch.count > 0L) {
            try {
                initialSyncLatch.await(40L, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {}
        }
        return cachedState
    }

    private fun ensureStateSyncDaemon() {
        if (!isStateSyncRunning.compareAndSet(false, true)) return
        val syncThread = Thread({
            var reusableSocket: DatagramSocket? = null
            val sendMagic = byteArrayOf(0x4C, 0x4D, 0x50, 0x5F, 0x53, 0x59, 0x4E, 0x43) // "LMP_SYNC"
            val recvBuffer = ByteArray(512)
            val loopbackAddr by lazy { InetAddress.getByName("127.0.0.1") }

            while (true) {
                try {
                    val now = System.currentTimeMillis()
                    var synced: RemoteState? = null

                    // ================= Tier 1: Loopback UDP 微内核直连 (< 0.2ms, 100% 穿透 HMA 与 Android 15 SELinux) =================
                    if (!udpPermissionDenied) {
                        try {
                            if (reusableSocket == null || reusableSocket.isClosed) {
                                reusableSocket = DatagramSocket().apply { soTimeout = 45 }
                            }
                            synced = queryLoopbackPort(reusableSocket, loopbackAddr, preferredUdpPort, sendMagic, recvBuffer)
                            if (synced == null) {
                                val altPort = if (preferredUdpPort == 39527) 39528 else 39527
                                val altResult = queryLoopbackPort(reusableSocket, loopbackAddr, altPort, sendMagic, recvBuffer)
                                if (altResult != null) {
                                    preferredUdpPort = altPort
                                    synced = altResult
                                }
                            }
                        } catch (_: SecurityException) {
                            udpPermissionDenied = true
                            try { reusableSocket?.close() } catch (_: Throwable) {}
                            reusableSocket = null
                        } catch (_: Throwable) {
                            try { reusableSocket?.close() } catch (_: Throwable) {}
                            reusableSocket = null
                        }
                    }

                    // ================= Tier 2: LSPosed XSharedPreferences 只读快照 (带心跳鲜活度校验防残留) =================
                    if (synced == null) {
                        synced = fetchFromXSharedPrefs(now)
                    }

                    // ================= Tier 3: ContentProvider (call + query 双模，带 25 秒熔断器零日志污染) =================
                    if (synced == null && now >= providerCooldownUntilMs) {
                        synced = fetchFromContentProvider()
                        if (synced == null) {
                            providerCooldownUntilMs = now + 25_000L
                        }
                    }

                    if (synced != null) {
                        cachedState = synced
                    } else if (cachedState.isMocking) {
                        // 所有通道均不可达（宿主已退出或强行停止），安全回落为非模拟态，防止目标 App 卡死在旧坐标
                        cachedState = cachedState.copy(isMocking = false, speed = 0f, cadenceSpm = 0)
                    }
                } catch (_: Throwable) {
                } finally {
                    if (initialSyncLatch.count > 0L) {
                        initialSyncLatch.countDown()
                    }
                }

                try {
                    Thread.sleep(if (cachedState.isMocking) 180L else 750L)
                } catch (_: Throwable) {}
            }
        }, "HwSensorStateSync")
        syncThread.isDaemon = true
        syncThread.start()
    }

    private fun queryLoopbackPort(
        socket: DatagramSocket,
        loopbackAddr: InetAddress,
        port: Int,
        sendMagic: ByteArray,
        recvBuffer: ByteArray
    ): RemoteState? {
        return try {
            val req = DatagramPacket(sendMagic, sendMagic.size, loopbackAddr, port)
            socket.send(req)
            val resp = DatagramPacket(recvBuffer, recvBuffer.size)
            socket.receive(resp)
            if (resp.length < 64) return null

            DataInputStream(ByteArrayInputStream(recvBuffer, 0, resp.length)).use { dis ->
                val magic = dis.readInt()
                if (magic != 0x4C4D5031) return null // "LMP1"
                val isMocking = dis.readBoolean()
                val lat = dis.readDouble()
                val lon = dis.readDouble()
                val alt = dis.readDouble()
                val spd = dis.readFloat()
                val brg = dis.readFloat()
                val acc = dis.readFloat()
                val sats = dis.readInt()
                val cn0 = dis.readFloat()
                val steps = dis.readLong()
                val cadence = dis.readInt()
                val ts = dis.readLong()
                val wifiEnabled = dis.readBoolean()
                val wifiSsid = dis.readUTF()
                val wifiBssid = dis.readUTF()
                val wifiRssi = dis.readInt()
                val cellEnabled = dis.readBoolean()
                val cellMcc = dis.readInt()
                val cellMnc = dis.readInt()
                val cellLac = dis.readInt()
                val cellCid = dis.readInt()

                val freshMocking = isMocking && (ts <= 0L || kotlin.math.abs(System.currentTimeMillis() - ts) < 15_000L)
                RemoteState(
                    isMocking = freshMocking,
                    latitude = lat,
                    longitude = lon,
                    altitude = alt,
                    speed = if (freshMocking) spd else 0f,
                    bearing = brg,
                    accuracy = acc,
                    satellites = sats,
                    meanCn0 = cn0,
                    totalSteps = steps,
                    cadenceSpm = if (freshMocking) cadence else 0,
                    wifiMockEnabled = wifiEnabled,
                    wifiSsid = wifiSsid.ifBlank { "Company-Office-5G" },
                    wifiBssid = wifiBssid.ifBlank { "00:1a:2b:3c:4d:5e" },
                    wifiRssi = wifiRssi,
                    cellMockEnabled = cellEnabled,
                    cellMcc = cellMcc,
                    cellMnc = cellMnc,
                    cellLac = cellLac,
                    cellCid = cellCid
                )
            }
        } catch (_: SecurityException) {
            throw SecurityException("UDP disallowed")
        } catch (_: Throwable) {
            null
        }
    }

    private fun fetchFromXSharedPrefs(now: Long): RemoteState? {
        val prefs = getXSharedPrefs() ?: return null
        return try {
            prefs.reload()
            if (!prefs.contains("is_mocking") && !prefs.contains("latitude_f") && !prefs.contains("latitude_str")) {
                return null
            }
            val rawMocking = prefs.getBoolean("is_mocking", false)
            val ts = prefs.getLong("timestamp", 0L)
            // 若 XML 中记录正在模拟但时间戳超过 8 秒未刷新，判定宿主已被杀进程，自动熔断停止模拟
            val effectiveMocking = rawMocking && (ts > 0L && (now - ts) < 8_000L)

            val latStr = prefs.getString("latitude_str", null)
            val lonStr = prefs.getString("longitude_str", null)
            val altStr = prefs.getString("altitude_str", null)

            val lat = latStr?.toDoubleOrNull() ?: prefs.getFloat("latitude_f", 39.908823f).toDouble()
            val lon = lonStr?.toDoubleOrNull() ?: prefs.getFloat("longitude_f", 116.397470f).toDouble()
            val alt = altStr?.toDoubleOrNull() ?: prefs.getFloat("altitude_f", 35.0f).toDouble()

            RemoteState(
                isMocking = effectiveMocking,
                latitude = lat,
                longitude = lon,
                altitude = alt,
                speed = if (effectiveMocking) prefs.getFloat("speed", 0f) else 0f,
                bearing = prefs.getFloat("bearing", 0f),
                accuracy = prefs.getFloat("accuracy", 3.2f),
                satellites = prefs.getInt("satellites", 16),
                meanCn0 = prefs.getFloat("mean_cn0", 35.5f),
                totalSteps = prefs.getLong("total_steps", 0L),
                cadenceSpm = if (effectiveMocking) prefs.getInt("cadence_spm", 0) else 0,
                wifiMockEnabled = prefs.getBoolean("wifi_mock_enabled", false),
                wifiSsid = prefs.getString("wifi_ssid", "Company-Office-5G") ?: "Company-Office-5G",
                wifiBssid = prefs.getString("wifi_bssid", "00:1a:2b:3c:4d:5e") ?: "00:1a:2b:3c:4d:5e",
                wifiRssi = prefs.getInt("wifi_rssi", -52),
                cellMockEnabled = prefs.getBoolean("cell_mock_enabled", false),
                cellMcc = prefs.getInt("cell_mcc", 460),
                cellMnc = prefs.getInt("cell_mnc", 0),
                cellLac = prefs.getInt("cell_lac", 41021),
                cellCid = prefs.getInt("cell_cid", 1892341)
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun fetchFromContentProvider(): RemoteState? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentAppMethod = activityThreadClass.getMethod("currentApplication")
            val context = currentAppMethod.invoke(null) as? Context ?: return null

            val bundle = try {
                context.contentResolver.call(
                    Uri.parse("content://com.antigravity.geospoofer.provider"),
                    "getState",
                    null,
                    null
                )
            } catch (_: Throwable) {
                null
            }

            if (bundle != null) {
                return RemoteState(
                    isMocking = bundle.getBoolean("is_mocking", false),
                    latitude = bundle.getDouble("latitude", 39.908823),
                    longitude = bundle.getDouble("longitude", 116.397470),
                    altitude = bundle.getDouble("altitude", 35.0),
                    speed = bundle.getFloat("speed", 0f),
                    bearing = bundle.getFloat("bearing", 0f),
                    accuracy = bundle.getFloat("accuracy", 3.2f),
                    satellites = bundle.getInt("satellites", 16),
                    meanCn0 = bundle.getFloat("mean_cn0", 35.5f),
                    totalSteps = bundle.getLong("total_steps", 0L),
                    cadenceSpm = bundle.getInt("cadence_spm", 0),
                    wifiMockEnabled = bundle.getBoolean("wifi_mock_enabled", false),
                    wifiSsid = bundle.getString("wifi_ssid", "Company-Office-5G") ?: "Company-Office-5G",
                    wifiBssid = bundle.getString("wifi_bssid", "00:1a:2b:3c:4d:5e") ?: "00:1a:2b:3c:4d:5e",
                    wifiRssi = bundle.getInt("wifi_rssi", -52),
                    cellMockEnabled = bundle.getBoolean("cell_mock_enabled", false),
                    cellMcc = bundle.getInt("cell_mcc", 460),
                    cellMnc = bundle.getInt("cell_mnc", 0),
                    cellLac = bundle.getInt("cell_lac", 41021),
                    cellCid = bundle.getInt("cell_cid", 1892341)
                )
            }

            var queryCursor: Cursor? = null
            try {
                queryCursor = context.contentResolver.query(
                    Uri.parse("content://com.antigravity.geospoofer.provider/state"),
                    null,
                    null,
                    null,
                    null
                )
                if (queryCursor != null && queryCursor.moveToFirst()) {
                    val accIdx = queryCursor.getColumnIndex("accuracy")
                    val satsIdx = queryCursor.getColumnIndex("satellites")
                    val cn0Idx = queryCursor.getColumnIndex("mean_cn0")
                    return RemoteState(
                        isMocking = queryCursor.getInt(queryCursor.getColumnIndexOrThrow("is_mocking")) == 1,
                        latitude = queryCursor.getDouble(queryCursor.getColumnIndexOrThrow("latitude")),
                        longitude = queryCursor.getDouble(queryCursor.getColumnIndexOrThrow("longitude")),
                        altitude = queryCursor.getDouble(queryCursor.getColumnIndexOrThrow("altitude")),
                        speed = queryCursor.getFloat(queryCursor.getColumnIndexOrThrow("speed")),
                        bearing = queryCursor.getFloat(queryCursor.getColumnIndexOrThrow("bearing")),
                        accuracy = if (accIdx >= 0) queryCursor.getFloat(accIdx) else 3.2f,
                        satellites = if (satsIdx >= 0) queryCursor.getInt(satsIdx) else 16,
                        meanCn0 = if (cn0Idx >= 0) queryCursor.getFloat(cn0Idx) else 35.5f,
                        totalSteps = queryCursor.getLong(queryCursor.getColumnIndexOrThrow("total_steps")),
                        cadenceSpm = queryCursor.getInt(queryCursor.getColumnIndexOrThrow("cadence_spm")),
                        wifiMockEnabled = queryCursor.getInt(queryCursor.getColumnIndexOrThrow("wifi_mock_enabled")) == 1,
                        wifiSsid = queryCursor.getString(queryCursor.getColumnIndexOrThrow("wifi_ssid")) ?: "Company-Office-5G",
                        wifiBssid = queryCursor.getString(queryCursor.getColumnIndexOrThrow("wifi_bssid")) ?: "00:1a:2b:3c:4d:5e",
                        wifiRssi = queryCursor.getInt(queryCursor.getColumnIndexOrThrow("wifi_rssi")),
                        cellMockEnabled = queryCursor.getInt(queryCursor.getColumnIndexOrThrow("cell_mock_enabled")) == 1,
                        cellMcc = queryCursor.getInt(queryCursor.getColumnIndexOrThrow("cell_mcc")),
                        cellMnc = queryCursor.getInt(queryCursor.getColumnIndexOrThrow("cell_mnc")),
                        cellLac = queryCursor.getInt(queryCursor.getColumnIndexOrThrow("cell_lac")),
                        cellCid = queryCursor.getInt(queryCursor.getColumnIndexOrThrow("cell_cid"))
                    )
                }
            } catch (_: Throwable) {
            } finally {
                queryCursor?.close()
            }
            null
        } catch (_: Throwable) {
            null
        }
    }
}
