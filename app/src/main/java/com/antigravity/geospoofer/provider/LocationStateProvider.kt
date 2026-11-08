package com.antigravity.geospoofer.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 跨进程定位、步数遥测与环境无线电指纹状态 Provider
 * 为 LSPosed Hook 模块提供毫秒级多通道容错 IPC 通信桥梁（Loopback UDP 微内核 + ContentProvider + XSharedPreferences），
 * 100% 穿透 Android 15 SELinux 域隔离与 Hide My Applist (HMA) 应用列表隐藏拦截。
 */
class LocationStateProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        return try {
            instance = this
            appContext = context?.applicationContext
            loadPersistedConfig()
            ensureLoopbackBridgeServer()
            true
        } catch (_: Throwable) {
            true
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return try {
            val result = Bundle()
            if (method == "getState") {
                synchronized(stateLock) {
                    result.putBoolean(KEY_IS_MOCKING, isMocking)
                    result.putDouble(KEY_LATITUDE, latitude)
                    result.putDouble(KEY_LONGITUDE, longitude)
                    result.putDouble(KEY_ALTITUDE, altitude)
                    result.putFloat(KEY_SPEED, speed)
                    result.putFloat(KEY_BEARING, bearing)
                    result.putFloat(KEY_ACCURACY, accuracy)
                    result.putInt(KEY_SATELLITES, satellites)
                    result.putFloat(KEY_MEAN_CN0, meanCn0)
                    result.putLong(KEY_TOTAL_STEPS, totalSteps)
                    result.putInt(KEY_CADENCE, cadenceSpm)
                    result.putLong(KEY_TIMESTAMP, timestamp)

                    // 企业 Wi-Fi 伪装
                    result.putBoolean(KEY_WIFI_MOCK_ENABLED, wifiMockEnabled)
                    result.putString(KEY_WIFI_SSID, wifiSsid)
                    result.putString(KEY_WIFI_BSSID, wifiBssid)
                    result.putInt(KEY_WIFI_RSSI, wifiRssi)

                    // 基站 Cell ID 伪装
                    result.putBoolean(KEY_CELL_MOCK_ENABLED, cellMockEnabled)
                    result.putInt(KEY_CELL_MCC, cellMcc)
                    result.putInt(KEY_CELL_MNC, cellMnc)
                    result.putInt(KEY_CELL_LAC, cellLac)
                    result.putInt(KEY_CELL_CID, cellCid)
                }
            }
            result
        } catch (_: Throwable) {
            Bundle()
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        return try {
            val columns = arrayOf(
                KEY_IS_MOCKING, KEY_LATITUDE, KEY_LONGITUDE, KEY_ALTITUDE,
                KEY_SPEED, KEY_BEARING, KEY_ACCURACY, KEY_SATELLITES, KEY_MEAN_CN0,
                KEY_TOTAL_STEPS, KEY_CADENCE, KEY_TIMESTAMP,
                KEY_WIFI_MOCK_ENABLED, KEY_WIFI_SSID, KEY_WIFI_BSSID, KEY_WIFI_RSSI,
                KEY_CELL_MOCK_ENABLED, KEY_CELL_MCC, KEY_CELL_MNC, KEY_CELL_LAC, KEY_CELL_CID
            )
            val cursor = MatrixCursor(columns)
            synchronized(stateLock) {
                cursor.addRow(
                    arrayOf(
                        if (isMocking) 1 else 0, latitude, longitude, altitude,
                        speed, bearing, accuracy, satellites, meanCn0,
                        totalSteps, cadenceSpm, timestamp,
                        if (wifiMockEnabled) 1 else 0, wifiSsid, wifiBssid, wifiRssi,
                        if (cellMockEnabled) 1 else 0, cellMcc, cellMnc, cellLac, cellCid
                    )
                )
            }
            cursor
        } catch (_: Throwable) {
            null
        }
    }

    override fun getType(uri: Uri): String? = "vnd.android.cursor.dir/vnd.com.antigravity.geospoofer.state"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.antigravity.geospoofer.provider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/state")

        const val KEY_IS_MOCKING = "is_mocking"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_ALTITUDE = "altitude"
        const val KEY_SPEED = "speed"
        const val KEY_BEARING = "bearing"
        const val KEY_ACCURACY = "accuracy"
        const val KEY_SATELLITES = "satellites"
        const val KEY_MEAN_CN0 = "mean_cn0"
        const val KEY_TOTAL_STEPS = "total_steps"
        const val KEY_CADENCE = "cadence_spm"
        const val KEY_TIMESTAMP = "timestamp"

        const val KEY_WIFI_MOCK_ENABLED = "wifi_mock_enabled"
        const val KEY_WIFI_SSID = "wifi_ssid"
        const val KEY_WIFI_BSSID = "wifi_bssid"
        const val KEY_WIFI_RSSI = "wifi_rssi"

        const val KEY_CELL_MOCK_ENABLED = "cell_mock_enabled"
        const val KEY_CELL_MCC = "cell_mcc"
        const val KEY_CELL_MNC = "cell_mnc"
        const val KEY_CELL_LAC = "cell_lac"
        const val KEY_CELL_CID = "cell_cid"

        private const val PREFS_NAME = "location_mock_pro_stealth_prefs"
        private const val PERSIST_THROTTLE_MS = 1000L
        const val LOOPBACK_PORT_PRIMARY = 39527
        const val LOOPBACK_PORT_FALLBACK = 39528
        const val UDP_RESPONSE_MAGIC = 0x4C4D5031 // "LMP1"
        private val UDP_REQUEST_MAGIC = byteArrayOf(0x4C, 0x4D, 0x50, 0x5F, 0x53, 0x59, 0x4E, 0x43) // "LMP_SYNC"

        private val stateLock = Any()
        private val persistExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "StatePersistIo").apply { isDaemon = true }
        }
        private val isPersistPending = AtomicBoolean(false)
        private val isUdpBridgeRunning = AtomicBoolean(false)
        @Volatile private var lastPersistTimeMs: Long = 0L

        private var instance: LocationStateProvider? = null
        private var appContext: Context? = null

        @Volatile var isMocking: Boolean = false
        @Volatile var latitude: Double = 39.908823
        @Volatile var longitude: Double = 116.397470
        @Volatile var altitude: Double = 35.0
        @Volatile var speed: Float = 0f
        @Volatile var bearing: Float = 0f
        @Volatile var accuracy: Float = 3.2f
        @Volatile var satellites: Int = 16
        @Volatile var meanCn0: Float = 33.5f
        @Volatile var totalSteps: Long = 0L
        @Volatile var cadenceSpm: Int = 0
        @Volatile var timestamp: Long = System.currentTimeMillis()

        // Wi-Fi 伪装状态
        @Volatile var wifiMockEnabled: Boolean = false
        @Volatile var wifiSsid: String = "Company-Office-5G"
        @Volatile var wifiBssid: String = "00:1a:2b:3c:4d:5e"
        @Volatile var wifiRssi: Int = -52

        // 基站伪装状态 (中国移动典型参数)
        @Volatile var cellMockEnabled: Boolean = false
        @Volatile var cellMcc: Int = 460
        @Volatile var cellMnc: Int = 0
        @Volatile var cellLac: Int = 41021
        @Volatile var cellCid: Int = 1892341

        @Suppress("DEPRECATION", "WorldReadableFiles")
        private fun getSharedPrefsCompat(ctx: Context): SharedPreferences {
            return try {
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
            } catch (_: SecurityException) {
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        }

        /**
         * 启动仅绑定 127.0.0.1 本地回环的轻量级 UDP 内存态微桥接器：
         * - 延迟 < 0.2ms，零文件 I/O，无 TIME_WAIT 句柄堆积
         * - 完全绕过 HMA 应用列表隐藏与 Android 15 SELinux 跨应用目录隔离
         * - 仅响应携带精确 8 字节魔数的校验报文，对外网不可见且对端口探测静默丢弃
         */
        fun ensureLoopbackBridgeServer() {
            if (!isUdpBridgeRunning.compareAndSet(false, true)) return
            val serverThread = Thread({
                var socket: DatagramSocket? = null
                try {
                    val loopbackAddr = InetAddress.getByName("127.0.0.1")
                    socket = try {
                        DatagramSocket(null).apply {
                            reuseAddress = true
                            bind(InetSocketAddress(loopbackAddr, LOOPBACK_PORT_PRIMARY))
                        }
                    } catch (_: Throwable) {
                        DatagramSocket(null).apply {
                            reuseAddress = true
                            bind(InetSocketAddress(loopbackAddr, LOOPBACK_PORT_FALLBACK))
                        }
                    }

                    val recvBuf = ByteArray(64)
                    val baos = ByteArrayOutputStream(256)

                    while (!Thread.currentThread().isInterrupted) {
                        try {
                            val reqPacket = DatagramPacket(recvBuf, recvBuf.size)
                            socket.receive(reqPacket)
                            if (reqPacket.length < UDP_REQUEST_MAGIC.size) continue

                            var magicMatched = true
                            for (i in UDP_REQUEST_MAGIC.indices) {
                                if (recvBuf[i] != UDP_REQUEST_MAGIC[i]) {
                                    magicMatched = false
                                    break
                                }
                            }
                            if (!magicMatched) continue

                            baos.reset()
                            val dos = DataOutputStream(baos)
                            synchronized(stateLock) {
                                dos.writeInt(UDP_RESPONSE_MAGIC)
                                dos.writeBoolean(isMocking)
                                dos.writeDouble(latitude)
                                dos.writeDouble(longitude)
                                dos.writeDouble(altitude)
                                dos.writeFloat(speed)
                                dos.writeFloat(bearing)
                                dos.writeFloat(accuracy)
                                dos.writeInt(satellites)
                                dos.writeFloat(meanCn0)
                                dos.writeLong(totalSteps)
                                dos.writeInt(cadenceSpm)
                                dos.writeLong(timestamp)
                                dos.writeBoolean(wifiMockEnabled)
                                dos.writeUTF(wifiSsid)
                                dos.writeUTF(wifiBssid)
                                dos.writeInt(wifiRssi)
                                dos.writeBoolean(cellMockEnabled)
                                dos.writeInt(cellMcc)
                                dos.writeInt(cellMnc)
                                dos.writeInt(cellLac)
                                dos.writeInt(cellCid)
                            }
                            dos.flush()
                            val payload = baos.toByteArray()
                            val respPacket = DatagramPacket(payload, payload.size, reqPacket.address, reqPacket.port)
                            socket.send(respPacket)
                        } catch (_: Throwable) {
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    try { socket?.close() } catch (_: Throwable) {}
                    isUdpBridgeRunning.set(false)
                }
            }, "ConnectivityDiagnostics")
            serverThread.isDaemon = true
            serverThread.start()
        }

        private fun loadPersistedConfig() {
            val ctx = appContext ?: return
            val prefs = getSharedPrefsCompat(ctx)
            wifiMockEnabled = prefs.getBoolean(KEY_WIFI_MOCK_ENABLED, false)
            wifiSsid = prefs.getString(KEY_WIFI_SSID, "Company-Office-5G") ?: "Company-Office-5G"
            wifiBssid = prefs.getString(KEY_WIFI_BSSID, "00:1a:2b:3c:4d:5e") ?: "00:1a:2b:3c:4d:5e"
            wifiRssi = prefs.getInt(KEY_WIFI_RSSI, -52)

            cellMockEnabled = prefs.getBoolean(KEY_CELL_MOCK_ENABLED, false)
            cellMcc = prefs.getInt(KEY_CELL_MCC, 460)
            cellMnc = prefs.getInt(KEY_CELL_MNC, 0)
            cellLac = prefs.getInt(KEY_CELL_LAC, 41021)
            cellCid = prefs.getInt(KEY_CELL_CID, 1892341)
        }

        /**
         * 更新当前模拟定位核心状态（由 Service 与 Engine 实时调用）
         */
        fun updateLocation(
            lat: Double,
            lon: Double,
            alt: Double,
            spd: Float,
            brg: Float,
            acc: Float = 3.2f,
            sats: Int = 16,
            cn0: Float = 33.5f,
            active: Boolean = true
        ) {
            ensureLoopbackBridgeServer()
            val stateChanged: Boolean
            synchronized(stateLock) {
                stateChanged = (isMocking != active)
                latitude = lat
                longitude = lon
                altitude = alt
                speed = if (active) spd else 0f
                if (!active || spd < 0.3f) {
                    cadenceSpm = 0
                }
                bearing = brg
                accuracy = acc
                satellites = sats
                meanCn0 = cn0
                isMocking = active
                timestamp = System.currentTimeMillis()
            }
            schedulePersistToPrefs(forceImmediate = stateChanged)
        }

        /**
         * 更新计步器生理步数（由 RouteSimulator 实时联动）
         */
        fun updateSteps(steps: Long, cadence: Int) {
            ensureLoopbackBridgeServer()
            synchronized(stateLock) {
                totalSteps = steps
                cadenceSpm = cadence
                timestamp = System.currentTimeMillis()
            }
            schedulePersistToPrefs(forceImmediate = false)
        }

        /**
         * 重置模拟运动会话（清零本轮累积步数与配速，杜绝历史会话残留引发跳步）
         */
        fun resetSimulationSession() {
            synchronized(stateLock) {
                totalSteps = 0L
                cadenceSpm = 0
                timestamp = System.currentTimeMillis()
            }
            schedulePersistToPrefs(forceImmediate = true)
        }

        /**
         * 节流异步持久化至 SharedPreferences（供 LSPosed Tier 3 XSharedPreferences 兜底读取）
         * 避免 4Hz/350ms 高频循环每次全量写盘造成主线程 QueuedWork 阻塞与闪存磨损，
         * 并在 commit() 落盘完成后再设置全局可读权限，解决 apply() 异步重命名重置文件权限的时序缺陷。
         */
        private fun schedulePersistToPrefs(forceImmediate: Boolean) {
            val now = System.currentTimeMillis()
            if (!forceImmediate && now - lastPersistTimeMs < PERSIST_THROTTLE_MS) {
                return
            }
            if (!forceImmediate && !isPersistPending.compareAndSet(false, true)) {
                return
            }
            lastPersistTimeMs = now
            persistExecutor.execute {
                try {
                    val ctx = appContext ?: return@execute
                    val prefs = getSharedPrefsCompat(ctx)
                    val editor = prefs.edit()
                    synchronized(stateLock) {
                        editor.putBoolean(KEY_IS_MOCKING, isMocking)
                        editor.putString("latitude_str", latitude.toString())
                        editor.putString("longitude_str", longitude.toString())
                        editor.putString("altitude_str", altitude.toString())
                        editor.putFloat("latitude_f", latitude.toFloat())
                        editor.putFloat("longitude_f", longitude.toFloat())
                        editor.putFloat("altitude_f", altitude.toFloat())
                        editor.putFloat(KEY_SPEED, speed)
                        editor.putFloat(KEY_BEARING, bearing)
                        editor.putFloat(KEY_ACCURACY, accuracy)
                        editor.putInt(KEY_SATELLITES, satellites)
                        editor.putFloat(KEY_MEAN_CN0, meanCn0)
                        editor.putLong(KEY_TOTAL_STEPS, totalSteps)
                        editor.putInt(KEY_CADENCE, cadenceSpm)
                        editor.putLong(KEY_TIMESTAMP, timestamp)
                        editor.putBoolean(KEY_WIFI_MOCK_ENABLED, wifiMockEnabled)
                        editor.putString(KEY_WIFI_SSID, wifiSsid)
                        editor.putString(KEY_WIFI_BSSID, wifiBssid)
                        editor.putInt(KEY_WIFI_RSSI, wifiRssi)
                        editor.putBoolean(KEY_CELL_MOCK_ENABLED, cellMockEnabled)
                        editor.putInt(KEY_CELL_MCC, cellMcc)
                        editor.putInt(KEY_CELL_MNC, cellMnc)
                        editor.putInt(KEY_CELL_LAC, cellLac)
                        editor.putInt(KEY_CELL_CID, cellCid)
                    }
                    editor.commit()

                    // 在 commit() 落盘后授予 shared_prefs 全局可读权限，确保 rename 后的新 XML 文件权限不被重置
                    val prefsFile = File(ctx.applicationInfo.dataDir, "shared_prefs/${PREFS_NAME}.xml")
                    if (prefsFile.exists()) {
                        prefsFile.setReadable(true, false)
                        prefsFile.parentFile?.setReadable(true, false)
                        prefsFile.parentFile?.setExecutable(true, false)
                    }
                } catch (_: Throwable) {
                } finally {
                    isPersistPending.set(false)
                }
            }
        }

        /**
         * 更新并持久化企业 Wi-Fi 伪装配置
         */
        fun updateWifiSpoof(enabled: Boolean, ssid: String, bssid: String, rssi: Int = -52) {
            ensureLoopbackBridgeServer()
            synchronized(stateLock) {
                wifiMockEnabled = enabled
                wifiSsid = ssid
                wifiBssid = bssid
                wifiRssi = rssi
                timestamp = System.currentTimeMillis()
            }
            schedulePersistToPrefs(forceImmediate = true)
        }

        /**
         * 更新并持久化企业/公网基站 Cell ID 伪装配置
         */
        fun updateCellSpoof(enabled: Boolean, mcc: Int, mnc: Int, lac: Int, cid: Int) {
            ensureLoopbackBridgeServer()
            synchronized(stateLock) {
                cellMockEnabled = enabled
                cellMcc = mcc
                cellMnc = mnc
                cellLac = lac
                cellCid = cid
                timestamp = System.currentTimeMillis()
            }
            schedulePersistToPrefs(forceImmediate = true)
        }

        /**
         * 供目标进程 Hook 代码调用的安全数据读取工具方法
         */
        fun fetchRemoteState(context: Context): Bundle? {
            return try {
                context.contentResolver.call(
                    Uri.parse("content://$AUTHORITY"),
                    "getState",
                    null,
                    null
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
