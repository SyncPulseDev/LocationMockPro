package com.antigravity.geospoofer.core

import com.antigravity.geospoofer.provider.LocationStateProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 路线移动与物理轨迹仿真器
 * 支持：多段路径插值、平滑转向航向角、转弯自动减速拟真、人类步态生理波动、
 * 虚拟计步器步频联动、无级动态调速、实时暂停/恢复、往返折返巡航、目标里程/时间自动守护与平滑刹车。
 */
class RouteSimulator(
    private val engine: MockLocationEngine,
    private val scope: CoroutineScope
) {
    private var simulationJob: Job? = null
    @Volatile private var isPaused = false

    // 常用速度预设（米/秒）
    enum class SpeedMode(val speedMps: Float, val label: String) {
        WALK(1.4f, "步行 (5 km/h)"),
        JOG(3.2f, "慢跑 (11.5 km/h)"),
        BIKE(5.5f, "骑行 (20 km/h)"),
        DRIVE(16.6f, "驾车 (60 km/h)")
    }

    // 巡航模式
    enum class RouteMode(val label: String) {
        ONE_WAY("🏁 单程终点驻留"),
        SHUTTLE("🔄 往返折返巡航"),
        RING_LOOP("🔁 闭环循环巡航")
    }

    data class SimulationState(
        val isSimulating: Boolean = false,
        val isPaused: Boolean = false,
        val currentLat: Double = 0.0,
        val currentLon: Double = 0.0,
        val currentBearing: Float = 0f,
        val currentSpeedMps: Float = 0f,
        val distanceCoveredMeters: Double = 0.0,
        val totalDistanceMeters: Double = 0.0,
        val currentSegmentIndex: Int = 0,
        val totalSegments: Int = 0,
        val progressFraction: Float = 0f,
        // 运动生理学与计步器联动指标
        val totalSteps: Long = 0L,
        val cadenceSpm: Int = 0,
        val caloriesKcal: Int = 0,
        // 拟真等红绿灯/随机驻留机制
        val isTemporarilyStopped: Boolean = false,
        val stopCountdownSeconds: Int = 0,
        val stopReason: String = "",
        // 地形海拔高度与累计爬升指标
        val currentElevationMeters: Double = 35.0,
        val elevationGainMeters: Double = 0.0,
        // 巡航模式与目标自动守护
        val routeMode: RouteMode = RouteMode.ONE_WAY,
        val targetDistanceMeters: Double? = null,
        val isGoalReached: Boolean = false,
        val goalSummary: String = "",
        val isShuttleReverse: Boolean = false
    )

    private val _simulationState = MutableStateFlow(SimulationState())
    val simulationState: StateFlow<SimulationState> = _simulationState.asStateFlow()

    var currentSpeedMode: SpeedMode = SpeedMode.WALK
    @Volatile var customSpeedMps: Float = SpeedMode.WALK.speedMps
    @Volatile var enablePhysiologicalGait: Boolean = true // 人类步频与转弯物理减速拟真
    @Volatile var enableRandomStops: Boolean = false // 拟真红绿灯与随机驻留
    @Volatile var enableFatigueCurve: Boolean = false // 真实体能消耗与配速衰减拟真
    @Volatile var enableTerrainElevation: Boolean = true // 真实地形海拔起伏拟真

    // 自动化巡航配置
    @Volatile var routeMode: RouteMode = RouteMode.ONE_WAY
    @Volatile var targetDistanceMeters: Double? = null // 目标里程 (米)，为 null 则不设上限
    @Volatile var targetDurationSeconds: Long? = null // 目标时间 (秒)

    /**
     * 动态调节运动速度（单位：米/秒）
     */
    fun setSpeed(speedMps: Float) {
        customSpeedMps = speedMps.coerceIn(0.2f, 40f) // 0.7 km/h ~ 144 km/h
        _simulationState.value = _simulationState.value.copy(currentSpeedMps = customSpeedMps)
    }

    /**
     * 暂停巡航模拟（坐标保持注入，心跳维持活跃，前进停止）
     */
    fun pauseSimulation() {
        if (!isSimulating || isPaused) return
        isPaused = true
        LocationStateProvider.updateSteps(_simulationState.value.totalSteps, 0)
        _simulationState.value = _simulationState.value.copy(
            isPaused = true,
            currentSpeedMps = 0f,
            cadenceSpm = 0
        )
    }

    /**
     * 恢复巡航模拟
     */
    fun resumeSimulation() {
        if (!isSimulating || !isPaused) return
        isPaused = false
        _simulationState.value = _simulationState.value.copy(isPaused = false)
    }

    /**
     * 开始沿指定路点列表模拟运动
     * @param waypoints WGS-84 格式路点集合
     * @param mode 巡航模式 (单程/折返/闭环)
     * @param targetMeters 目标里程 (米)
     * @param onProgress 每一步的回调
     */
    fun startRouteSimulation(
        waypoints: List<CoordinateUtils.LatLng>,
        mode: RouteMode = routeMode,
        targetMeters: Double? = targetDistanceMeters,
        onProgress: ((CoordinateUtils.LatLng, Float) -> Unit)? = null
    ) {
        if (waypoints.size < 2) return
        stopSimulation()
        LocationStateProvider.resetSimulationSession()
        isPaused = false
        this.routeMode = mode
        this.targetDistanceMeters = targetMeters

        simulationJob = scope.launch(Dispatchers.Default) {
            val tickIntervalMs = 350L // 350ms 拟真高频平滑注入（强力对抗高德/微信等网络定位拉扯）
            val tickIntervalSec = tickIntervalMs / 1000.0
            val strideLengthMeters = 0.72 // 人类平均单步步长约 0.72 米
            val startTimeMs = System.currentTimeMillis()

            var currentWaypoints = waypoints.toList()
            // 闭环模式自动防瞬移：若用户路标末点未连接首点，自动平滑封闭，杜绝循环交界处百米空间折跃
            if (mode == RouteMode.RING_LOOP && currentWaypoints.size >= 2) {
                val first = currentWaypoints.first()
                val last = currentWaypoints.last()
                val gap = CoordinateUtils.calculateDistance(last.latitude, last.longitude, first.latitude, first.longitude)
                if (gap > 10.0) {
                    currentWaypoints = currentWaypoints + first
                }
            }
            var isReverse = false
            var overallCumulativeDistance = 0.0
            var cumulativeSteps = 0.0
            var cumulativeElevationGain = 0.0
            var lastElevation = 35.0

            _simulationState.value = SimulationState(
                isSimulating = true,
                isPaused = false,
                currentLat = currentWaypoints.first().latitude,
                currentLon = currentWaypoints.first().longitude,
                totalDistanceMeters = targetMeters ?: calculateTotalDistance(currentWaypoints),
                totalSegments = currentWaypoints.size - 1,
                currentSpeedMps = customSpeedMps,
                routeMode = mode,
                targetDistanceMeters = targetMeters,
                isGoalReached = false,
                goalSummary = ""
            )

            var continueLoop = true
            while (continueLoop && isActive) {
                // 计算当前方向路段长度与方位角
                val segmentDistances = mutableListOf<Double>()
                val segmentBearings = mutableListOf<Float>()
                for (i in 0 until currentWaypoints.size - 1) {
                    segmentDistances.add(
                        CoordinateUtils.calculateDistance(
                            currentWaypoints[i].latitude, currentWaypoints[i].longitude,
                            currentWaypoints[i + 1].latitude, currentWaypoints[i + 1].longitude
                        )
                    )
                    segmentBearings.add(
                        CoordinateUtils.calculateBearing(
                            currentWaypoints[i].latitude, currentWaypoints[i].longitude,
                            currentWaypoints[i + 1].latitude, currentWaypoints[i + 1].longitude
                        )
                    )
                }

                var distanceSinceLastStop = 0.0
                var nextStopDistance = 600.0 + (Math.random() * 500.0)
                val stopReasons = listOf("等候红绿灯", "避让路口车辆", "系鞋带与饮水补给", "驻留调整呼吸")
                var segmentRemainder = 0.0

                for (i in 0 until currentWaypoints.size - 1) {
                    if (!isActive) break

                    val p1 = currentWaypoints[i]
                    val p2 = currentWaypoints[i + 1]
                    val segDist = segmentDistances[i]
                    val bearing = segmentBearings[i]

                    val nextBearing = if (i < currentWaypoints.size - 2) segmentBearings[i + 1] else bearing
                    var angleDiff = abs(bearing - nextBearing)
                    if (angleDiff > 180f) angleDiff = 360f - angleDiff

                    // 动力学步长守恒：继承前一路段末尾 tick 溢出的位移量，杜绝小折线段（如操场弯道）累积掉速
                    var traveledInSegment = segmentRemainder.coerceAtMost(segDist * 0.9)
                    segmentRemainder = 0.0

                    while (traveledInSegment < segDist && isActive) {
                        // 1. 若处于暂停状态，保持原地高频防跳心跳
                        if (isPaused) {
                            val currentPoint = CoordinateUtils.movePoint(
                                p1.latitude, p1.longitude,
                                traveledInSegment.coerceAtMost(segDist), bearing
                            )
                            engine.updateLocation(currentPoint.latitude, currentPoint.longitude, speed = 0f, bearing = bearing)
                            delay(250L) // 维持 250ms 高频脉冲，避免高德跳回真实位置
                            continue
                        }

                        // 2. 拟真红绿灯/随机驻留机制（维持 250ms 高频防跳心跳）
                        if (enableRandomStops && distanceSinceLastStop >= nextStopDistance) {
                            val stopDuration = 8 + (Math.random() * 10).toInt()
                            val reason = stopReasons.random()
                            val totalTicks = stopDuration * 4
                            for (tick in totalTicks downTo 1) {
                                if (!isActive || isPaused) break
                                val curPt = CoordinateUtils.movePoint(p1.latitude, p1.longitude, traveledInSegment.coerceAtMost(segDist), bearing)
                                engine.updateLocation(curPt.latitude, curPt.longitude, speed = 0f, bearing = bearing)
                                val remainingSec = (tick + 3) / 4
                                _simulationState.value = _simulationState.value.copy(
                                    isTemporarilyStopped = true,
                                    stopCountdownSeconds = remainingSec,
                                    stopReason = reason,
                                    currentSpeedMps = 0f,
                                    cadenceSpm = 0
                                )
                                delay(250L) // 高频 250ms 注入，杜绝驻留期间高德网络定位反扑跳点
                            }
                            distanceSinceLastStop = 0.0
                            nextStopDistance = 650.0 + (Math.random() * 600.0)
                            _simulationState.value = _simulationState.value.copy(
                                isTemporarilyStopped = false,
                                stopCountdownSeconds = 0,
                                stopReason = ""
                            )
                        }

                        // 3. 拟真速度与生理步态波动
                        var speedToUse = customSpeedMps
                        if (enablePhysiologicalGait) {
                            val cyclePhase = (System.currentTimeMillis() % 1600) / 1600.0 * 2.0 * PI
                            speedToUse *= 1.0f + (sin(cyclePhase) * 0.06f).toFloat()

                            // 转弯前 12 米减速拟真
                            val remainingInSeg = segDist - traveledInSegment
                            if (angleDiff > 35f && remainingInSeg < 12.0) {
                                val turnSlowdown = (1.0f - (angleDiff / 180f * 0.45f * (1.0 - remainingInSeg / 12.0))).toFloat()
                                speedToUse *= turnSlowdown.coerceIn(0.55f, 1.0f)
                            }
                        }

                        // 4. 真实体能消耗与配速衰减
                        if (enableFatigueCurve && targetMeters != null && targetMeters > 0) {
                            val prog = (overallCumulativeDistance / targetMeters).coerceIn(0.0, 1.0)
                            val fatigueFactor = 1.02f - (sin(prog * PI) * 0.12f).toFloat()
                            speedToUse *= fatigueFactor
                        }

                        val distanceThisTick = speedToUse * tickIntervalSec
                        traveledInSegment += distanceThisTick
                        distanceSinceLastStop += distanceThisTick
                        overallCumulativeDistance += distanceThisTick

                        val currentTargetDist = traveledInSegment.coerceAtMost(segDist)
                        val nextPoint = CoordinateUtils.movePoint(p1.latitude, p1.longitude, currentTargetDist, bearing)

                        // 5. 运动生理学动态步长与步频动力学拟真（杜绝传统固定步长引发 300+ SPM 异常被风控拦截）
                        val (dynamicStride, naturalCadence) = calculateHumanGait(speedToUse)
                        if (dynamicStride > 0.1 && speedToUse > 0.35f) {
                            cumulativeSteps += (distanceThisTick / dynamicStride)
                        }
                        val calculatedSteps = cumulativeSteps.toLong()
                        val calories = (overallCumulativeDistance * 0.062).toInt()
                        // 跨进程同步计步器至 LocationStateProvider（供 LSPosed 模块直接注入目标 App 传感器）
                        LocationStateProvider.updateSteps(calculatedSteps, naturalCadence)

                        // 6. 海拔与爬升计算（自然多频段平缓地理微地形模型，告别 350m 机械过山车正弦波）
                        var currentElevation = 35.0
                        if (enableTerrainElevation) {
                            // 宏观平缓坡道 (波长 1800m, 振幅 2.2m) + 中观自然起伏 (波长 650m, 振幅 0.7m) + 微地表纹理 (波长 160m, 振幅 0.2m)
                            val macroSlope = sin(overallCumulativeDistance / 1800.0 * 2.0 * PI) * 2.2
                            val mesoSlope = sin(overallCumulativeDistance / 650.0 * 2.0 * PI) * 0.7
                            val microTexture = sin(overallCumulativeDistance / 160.0 * 2.0 * PI) * 0.2
                            val elevationDelta = macroSlope + mesoSlope + microTexture
                            currentElevation = (35.0 + elevationDelta).coerceAtLeast(4.0)
                            val diff = currentElevation - lastElevation
                            if (diff > 0.02) cumulativeElevationGain += diff
                            lastElevation = currentElevation
                            engine.currentAltitude = currentElevation
                        }

                        val totalRefDist = targetMeters ?: calculateTotalDistance(waypoints)
                        val progressFraction = if (totalRefDist > 0) (overallCumulativeDistance / totalRefDist).toFloat().coerceIn(0f, 1f) else 0f

                        // 7. 目标守护检测：达成目标里程或目标时长
                        val reachedDistance = targetMeters != null && overallCumulativeDistance >= targetMeters
                        val reachedTime = targetDurationSeconds != null && ((System.currentTimeMillis() - startTimeMs) / 1000) >= targetDurationSeconds!!

                        if (reachedDistance || reachedTime) {
                            // 优雅渐进刹车 (5 秒缓冲衰减)
                            for (step in 4 downTo 1) {
                                val brakeSpeed = (speedToUse * (step / 5.0f)).coerceAtLeast(0.3f)
                                engine.updateLocation(nextPoint.latitude, nextPoint.longitude, speed = brakeSpeed, bearing = bearing)
                                delay(600L)
                            }

                            val summaryMsg = if (reachedDistance) {
                                "🏆 已成功达成目标里程 ${"%.2f".format(targetMeters!! / 1000.0)} km！"
                            } else {
                                "⏰ 已成功达到目标时长 ${targetDurationSeconds!! / 60} 分钟！"
                            }

                            _simulationState.value = _simulationState.value.copy(
                                isSimulating = true,
                                isPaused = true,
                                isGoalReached = true,
                                goalSummary = summaryMsg,
                                currentSpeedMps = 0f,
                                cadenceSpm = 0,
                                distanceCoveredMeters = overallCumulativeDistance,
                                progressFraction = 1.0f
                            )

                            // 保持在终点原地驻留注入
                            while (isActive) {
                                engine.updateLocation(nextPoint.latitude, nextPoint.longitude, speed = 0f, bearing = bearing)
                                delay(tickIntervalMs)
                            }
                            return@launch
                        }

                        // 正常更新位置与遥测状态
                        engine.updateLocation(
                            nextPoint.latitude,
                            nextPoint.longitude,
                            speed = speedToUse,
                            bearing = bearing
                        )

                        _simulationState.value = _simulationState.value.copy(
                            currentLat = nextPoint.latitude,
                            currentLon = nextPoint.longitude,
                            currentBearing = bearing,
                            currentSpeedMps = speedToUse,
                            distanceCoveredMeters = overallCumulativeDistance,
                            totalDistanceMeters = totalRefDist,
                            currentSegmentIndex = i + 1,
                            totalSegments = currentWaypoints.size - 1,
                            progressFraction = progressFraction,
                            totalSteps = calculatedSteps,
                            cadenceSpm = naturalCadence,
                            caloriesKcal = calories,
                            currentElevationMeters = currentElevation,
                            elevationGainMeters = cumulativeElevationGain,
                            isShuttleReverse = isReverse
                        )

                        onProgress?.invoke(nextPoint, bearing)
                        delay(tickIntervalMs)
                    }

                    if (traveledInSegment >= segDist) {
                        segmentRemainder = (traveledInSegment - segDist).coerceAtLeast(0.0)
                    }
                }

                // 一趟路程结束，根据模式决定后续行为
                when (mode) {
                    RouteMode.ONE_WAY -> {
                        continueLoop = false
                    }
                    RouteMode.SHUTTLE -> {
                        // 往返折返：翻转路点序列无缝折返
                        isReverse = !isReverse
                        currentWaypoints = currentWaypoints.reversed()
                        continueLoop = true
                    }
                    RouteMode.RING_LOOP -> {
                        // 闭环循环：从头再跑
                        continueLoop = true
                    }
                }
            }

            // 单程终点结束且未达设限时的自然终点常驻
            _simulationState.value = _simulationState.value.copy(
                isSimulating = true,
                isPaused = true,
                isGoalReached = true,
                goalSummary = "🏁 已顺利抵达路线终点，保持定位驻留守护",
                currentSpeedMps = 0f,
                cadenceSpm = 0,
                progressFraction = 1.0f
            )

            val finalPt = currentWaypoints.last()
            while (isActive) {
                engine.updateLocation(finalPt.latitude, finalPt.longitude, speed = 0f, bearing = 0f)
                delay(tickIntervalMs)
            }
        }
    }

    private fun calculateTotalDistance(points: List<CoordinateUtils.LatLng>): Double {
        var dist = 0.0
        for (i in 0 until points.size - 1) {
            dist += CoordinateUtils.calculateDistance(
                points[i].latitude, points[i].longitude,
                points[i + 1].latitude, points[i + 1].longitude
            )
        }
        return dist
    }

    /**
     * 停止模拟
     */
    fun stopSimulation() {
        simulationJob?.cancel()
        simulationJob = null
        LocationStateProvider.resetSimulationSession()
        isPaused = false
        _simulationState.value = _simulationState.value.copy(
            isSimulating = false,
            isPaused = false,
            isGoalReached = false,
            goalSummary = ""
        )
    }

    val isSimulating: Boolean
        get() = simulationJob?.isActive == true

    companion object {
        /**
         * 运动生理学步幅与步频动态动力学模型 (Human Locomotion Biomechanics Model)
         * 依据人体运动学自适应调节规律：
         * 随着跑速提升，步长非线性自然扩展，步频始终严格锁定在人类真实生理学边界内（110 ~ 198 SPM，极限截断 205 SPM）。
         */
        fun calculateHumanGait(speedMps: Float): Pair<Double, Int> {
            if (speedMps < 0.35f) {
                return Pair(0.65, 0) // 原地静止驻留
            }
            if (speedMps > 8.0f) {
                // > 28.8 km/h 判定为骑行或车载区间，步频置 0 防范非人特征
                return Pair(1.20, 0)
            }

            // 动态步长非线性拟合（米）
            val dynamicStride = when {
                speedMps <= 1.8f -> (0.55 + speedMps * 0.12).coerceIn(0.60, 0.78)
                speedMps <= 3.5f -> (0.78 + (speedMps - 1.8) * 0.22).coerceIn(0.78, 1.18)
                speedMps <= 5.5f -> (1.18 + (speedMps - 3.5) * 0.16).coerceIn(1.18, 1.50)
                else -> (1.50 + (speedMps - 5.5) * 0.10).coerceIn(1.50, 1.75)
            }

            val rawCadence = ((speedMps / dynamicStride) * 60.0).roundToInt()
            val naturalCadence = rawCadence.coerceIn(80, 205)

            return Pair(dynamicStride, naturalCadence)
        }
    }
}
