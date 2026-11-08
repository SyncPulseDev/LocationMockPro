package com.antigravity.geospoofer.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.antigravity.geospoofer.MainActivity
import com.antigravity.geospoofer.R
import com.antigravity.geospoofer.core.CoordinateUtils
import com.antigravity.geospoofer.core.MockLocationEngine
import com.antigravity.geospoofer.core.RouteSimulator
import kotlinx.coroutines.*

class MockLocationService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var engine: MockLocationEngine
        private set
    lateinit var routeSimulator: RouteSimulator
        private set

    private var wakeLock: PowerManager.WakeLock? = null
    private var heartbeatJob: Job? = null

    // 非 Root 高频防跳脉冲压制模式（开启后静止心跳提速至 250ms，以 4Hz 高频虚拟信号强行压制系统网络定位拉扯）
    var isAntiJumpHighFreqEnabled: Boolean = true
        set(value) {
            field = value
            if (::engine.isInitialized && engine.isRunning && !routeSimulator.isSimulating) {
                startHeartbeat()
            }
        }

    inner class LocalBinder : Binder() {
        fun getService(): MockLocationService = this@MockLocationService
    }

    override fun onCreate() {
        super.onCreate()
        engine = MockLocationEngine(this)
        routeSimulator = RouteSimulator(engine, serviceScope)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationMockPro:WakeLock")
        wakeLock?.acquire(24 * 60 * 60 * 1000L) // 最长持有 24 小时

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely("定位模拟服务正在后台运行")
        return START_STICKY
    }

    fun startForegroundSafely(message: String) {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        try {
            val notification = buildNotification(message)
            if (hasFine || hasCoarse) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                // 若定位权限缺失，先以基础前台服务履约系统 5 秒生命周期，杜绝 ForegroundServiceDidNotStartInTimeException 闪退
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                startForeground(NOTIFICATION_ID, buildNotification(message))
            } catch (_: Exception) {}
        }
    }

    /**
     * 开启常驻模拟定位
     */
    fun startMock(lat: Double, lon: Double): Boolean {
        startForegroundSafely("正在模拟定位: $lat, $lon")
        routeSimulator.stopSimulation()
        com.antigravity.geospoofer.provider.LocationStateProvider.resetSimulationSession()
        val started = engine.start()
        if (started) {
            engine.updateLocation(lat, lon, speed = 0f, bearing = 0f)
            startHeartbeat()
        }
        return started
    }

    /**
     * 开启路线模拟运动
     */
    fun startRoute(
        waypoints: List<CoordinateUtils.LatLng>,
        mode: RouteSimulator.RouteMode = RouteSimulator.RouteMode.ONE_WAY,
        targetDistanceMeters: Double? = null
    ): Boolean {
        startForegroundSafely("正在进行路线模拟运动...")
        val started = engine.start()
        if (started) {
            stopHeartbeat()
            routeSimulator.startRouteSimulation(waypoints, mode, targetDistanceMeters)
        }
        return started
    }

    fun startRoute(waypoints: List<CoordinateUtils.LatLng>, loop: Boolean): Boolean {
        return startRoute(
            waypoints,
            if (loop) RouteSimulator.RouteMode.RING_LOOP else RouteSimulator.RouteMode.ONE_WAY
        )
    }

    /**
     * 停止路线模拟运动（同步释放模拟提供者与后台服务，保持与 UI「待命中」状态严格一致；原地驻留请使用 pauseRoute）
     */
    fun stopRoute() {
        stopMock()
    }

    /**
     * 暂停路线模拟运动
     */
    fun pauseRoute() {
        routeSimulator.pauseSimulation()
    }

    /**
     * 恢复路线模拟运动
     */
    fun resumeRoute() {
        routeSimulator.resumeSimulation()
    }

    /**
     * 动态调节运动速度
     */
    fun updateSpeed(speedMps: Float) {
        routeSimulator.setSpeed(speedMps)
    }

    /**
     * 开关真实 GPS 高斯微抖动（防检测）
     */
    fun setJitterEnabled(enabled: Boolean) {
        engine.enableNaturalJitter = enabled
    }

    /**
     * 切换拟真信号扰动环境预设
     */
    fun setJitterProfile(profile: MockLocationEngine.JitterProfile) {
        engine.currentJitterProfile = profile
    }

    /**
     * 停止模拟定位
     */
    fun stopMock() {
        stopHeartbeat()
        routeSimulator.stopSimulation()
        engine.stop()
        stopSelf()
    }

    /**
     * 心跳定时器：静止状态下高频推送坐标（默认 250ms 高频防跳模式），确保系统定位缓存不会过期恢复真实位置
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                if (engine.isRunning && !routeSimulator.isSimulating) {
                    engine.updateLocation(
                        engine.currentLatitude,
                        engine.currentLongitude,
                        speed = 0f,
                        bearing = engine.currentBearing
                    )
                }
                val interval = if (isAntiJumpHighFreqEnabled) 250L else 1000L
                delay(interval)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopHeartbeat()
        routeSimulator.stopSimulation()
        engine.stop()
        serviceScope.cancel()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "mock_location_channel"
        const val NOTIFICATION_ID = 1001
    }
}
