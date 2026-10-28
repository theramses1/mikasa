package com.moiehfih.app.health

import android.annotation.TargetApi
import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.AggregateRecordsRequest
import android.health.connect.AggregateRecordsGroupedByPeriodResponse
import android.health.connect.AggregateRecordsResponse
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.HealthPermissions
import android.health.connect.LocalTimeRangeFilter
import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.ReadRecordsResponse
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.datatypes.ActiveCaloriesBurnedRecord
import android.health.connect.datatypes.AggregationType
import android.health.connect.datatypes.BloodPressureRecord
import android.health.connect.datatypes.DistanceRecord
import android.health.connect.datatypes.ExerciseSessionRecord
import android.health.connect.datatypes.ExerciseSessionType
import android.health.connect.datatypes.HeartRateRecord
import android.health.connect.datatypes.OxygenSaturationRecord
import android.health.connect.datatypes.Record
import android.health.connect.datatypes.RestingHeartRateRecord
import android.health.connect.datatypes.SleepSessionRecord
import android.health.connect.datatypes.StepsRecord
import android.health.connect.datatypes.WeightRecord
import android.os.Build
import android.os.OutcomeReceiver
import com.moiehfih.app.data.AppData
import com.moiehfih.app.data.CardioSession
import com.moiehfih.app.data.CardioType
import com.moiehfih.app.data.HealthDay
import com.moiehfih.app.logic.Calc
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Importa actividad y signos vitales de Health Connect, donde los dejan Google Fit, Fitbit,
 * Samsung Health, Garmin, Wear OS y otras apps de relojes y pulseras.
 *
 * Usa la API de Health Connect integrada en Android 14+ (sin bibliotecas externas).
 */
object HealthSync {

    val isSupported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** Permisos de lectura que pide la app (todos opcionales). */
    val PERMISSIONS = arrayOf(
        "android.permission.health.READ_STEPS",
        "android.permission.health.READ_ACTIVE_CALORIES_BURNED",
        "android.permission.health.READ_DISTANCE",
        "android.permission.health.READ_EXERCISE",
        "android.permission.health.READ_HEART_RATE",
        "android.permission.health.READ_RESTING_HEART_RATE",
        "android.permission.health.READ_SLEEP",
        "android.permission.health.READ_WEIGHT",
        "android.permission.health.READ_OXYGEN_SATURATION",
        "android.permission.health.READ_BLOOD_PRESSURE",
    )

    fun grantedPermissions(ctx: Context): Set<String> =
        PERMISSIONS.filter { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }.toSet()

    /** Lo leído de Health Connect, listo para guardarse en el hilo principal con [apply]. */
    class Fetched(val days: List<HealthDay>, val sessions: List<CardioSession>, val errors: List<String>)

    class Summary(val days: Int, val sessions: Int, val weights: Int, val errors: List<String>)

    /** Lee los datos entre [from] y [to] (inclusive). Llamar fuera del hilo principal. */
    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun fetch(ctx: Context, from: LocalDate, to: LocalDate, weightKg: Double): Fetched {
        val manager = ctx.getSystemService(HealthConnectManager::class.java)
            ?: return Fetched(emptyList(), emptyList(), listOf("Health Connect no está disponible en este teléfono."))
        return Reader(manager, grantedPermissions(ctx), from, to, weightKg).read()
    }

    /** Guarda lo importado. Llamar en el hilo principal. */
    fun apply(data: AppData, fetched: Fetched): Summary {
        var weights = 0
        data.batch {
            for (h in fetched.days) {
                data.saveHealthDay(h)
                // El peso del reloj/báscula solo se usa si ese día no hay uno registrado a mano.
                val kg = h.weightKg
                if (kg != null && !data.weights.containsKey(h.date)) {
                    data.setWeight(h.date, Calc.roundTo(kg, 0.1))
                    weights++
                }
            }
            fetched.sessions.forEach { data.upsertImportedCardio(it) }
        }
        data.setHealthState(true, System.currentTimeMillis())
        return Summary(fetched.days.size, fetched.sessions.size, weights, fetched.errors)
    }

    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private class Reader(
        private val m: HealthConnectManager,
        private val granted: Set<String>,
        private val from: LocalDate,
        private val to: LocalDate,
        private val weightKg: Double,
    ) {
        private val executor = Executors.newSingleThreadExecutor()
        private val zone = ZoneId.systemDefault()
        private val errors = ArrayList<String>()

        fun read(): Fetched {
            try {
                val steps = perDay(HealthPermissions.READ_STEPS, StepsRecord.STEPS_COUNT_TOTAL)
                val active = perDay(HealthPermissions.READ_ACTIVE_CALORIES_BURNED, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                val distance = perDay(HealthPermissions.READ_DISTANCE, DistanceRecord.DISTANCE_TOTAL)
                val hrAvg = perDay(HealthPermissions.READ_HEART_RATE, HeartRateRecord.BPM_AVG)
                val hrMin = perDay(HealthPermissions.READ_HEART_RATE, HeartRateRecord.BPM_MIN)
                val hrMax = perDay(HealthPermissions.READ_HEART_RATE, HeartRateRecord.BPM_MAX)
                val resting = perDay(HealthPermissions.READ_RESTING_HEART_RATE, RestingHeartRateRecord.BPM_AVG)
                val weight = perDay(HealthPermissions.READ_WEIGHT, WeightRecord.WEIGHT_AVG)
                val sleep = sleepByWakeDay()
                val spo2 = averageByDay(HealthPermissions.READ_OXYGEN_SATURATION, OxygenSaturationRecord::class.java) {
                    it.time to it.percentage.value
                }
                val bpSys = averageByDay(HealthPermissions.READ_BLOOD_PRESSURE, BloodPressureRecord::class.java) {
                    it.time to it.systolic.inMillimetersOfMercury
                }
                val bpDia = averageByDay(HealthPermissions.READ_BLOOD_PRESSURE, BloodPressureRecord::class.java) {
                    it.time to it.diastolic.inMillimetersOfMercury
                }

                val days = ArrayList<HealthDay>()
                var d = from
                while (!d.isAfter(to)) {
                    val h = HealthDay(
                        d, steps[d], active[d]?.inCalories?.div(1000), distance[d]?.inMeters,
                        hrAvg[d], hrMin[d], hrMax[d], resting[d], sleep[d], spo2[d], bpSys[d], bpDia[d],
                        weight[d]?.inGrams?.div(1000),
                    )
                    if (h != HealthDay(d)) days += h
                    d = d.plusDays(1)
                }
                return Fetched(days, exerciseSessions(), errors)
            } finally {
                executor.shutdown()
            }
        }

        private fun <R> await(call: (OutcomeReceiver<R, HealthConnectException>) -> Unit): R {
            val latch = CountDownLatch(1)
            var result: R? = null
            var error: Exception? = null
            call(object : OutcomeReceiver<R, HealthConnectException> {
                override fun onResult(r: R) {
                    result = r
                    latch.countDown()
                }

                override fun onError(e: HealthConnectException) {
                    error = e
                    latch.countDown()
                }
            })
            if (!latch.await(60, TimeUnit.SECONDS)) throw IllegalStateException("Health Connect no respondió a tiempo")
            error?.let { throw it }
            @Suppress("UNCHECKED_CAST")
            return result as R
        }

        private fun <T> guarded(permission: String, what: String, empty: T, block: () -> T): T {
            if (permission !in granted) return empty
            return try {
                block()
            } catch (e: Exception) {
                errors += "$what: ${e.message ?: e.javaClass.simpleName}"
                empty
            }
        }

        private fun <T> perDay(permission: String, type: AggregationType<T>): Map<LocalDate, T> =
            guarded(permission, permission.substringAfterLast('.'), emptyMap()) {
                val filter = LocalTimeRangeFilter.Builder()
                    .setStartTime(from.atStartOfDay())
                    .setEndTime(to.plusDays(1).atStartOfDay())
                    .build()
                val request = AggregateRecordsRequest.Builder<T>(filter).addAggregationType(type).build()
                val groups = await<List<AggregateRecordsGroupedByPeriodResponse<T>>> {
                    m.aggregateGroupByPeriod(request, Period.ofDays(1), executor, it)
                }
                groups.mapNotNull { g -> g.get(type)?.let { g.startTime.toLocalDate() to it } }.toMap()
            }

        private fun <T> total(type: AggregationType<T>, start: Instant, end: Instant): T? {
            val filter = TimeInstantRangeFilter.Builder().setStartTime(start).setEndTime(end).build()
            val request = AggregateRecordsRequest.Builder<T>(filter).addAggregationType(type).build()
            return await<AggregateRecordsResponse<T>> { m.aggregate(request, executor, it) }.get(type)
        }

        private fun <T : Record> records(type: Class<T>, start: Instant, end: Instant): List<T> {
            val out = ArrayList<T>()
            val request = ReadRecordsRequestUsingFilters.Builder(type)
                .setTimeRangeFilter(TimeInstantRangeFilter.Builder().setStartTime(start).setEndTime(end).build())
                .setPageSize(5000)
                .build()
            out += await<ReadRecordsResponse<T>> { m.readRecords(request, executor, it) }.records
            return out
        }

        private val rangeStart get() = from.atStartOfDay(zone).toInstant()
        private val rangeEnd get() = to.plusDays(1).atStartOfDay(zone).toInstant()

        private fun <T : Record> averageByDay(permission: String, type: Class<T>, value: (T) -> Pair<Instant, Double>): Map<LocalDate, Double> =
            guarded(permission, type.simpleName, emptyMap()) {
                records(type, rangeStart, rangeEnd).map(value)
                    .groupBy({ it.first.atZone(zone).toLocalDate() }, { it.second })
                    .mapValues { (_, v) -> v.average() }
            }

        /** Minutos dormidos, asignados al día en que terminó el sueño (sin contar tiempo despierto). */
        private fun sleepByWakeDay(): Map<LocalDate, Long> =
            guarded(HealthPermissions.READ_SLEEP, "Sueño", emptyMap()) {
                val awake = setOf(
                    SleepSessionRecord.StageType.STAGE_TYPE_AWAKE,
                    SleepSessionRecord.StageType.STAGE_TYPE_AWAKE_IN_BED,
                    SleepSessionRecord.StageType.STAGE_TYPE_AWAKE_OUT_OF_BED,
                )
                records(SleepSessionRecord::class.java, rangeStart.minus(Duration.ofHours(18)), rangeEnd)
                    .groupBy { it.endTime.atZone(zone).toLocalDate() }
                    .filterKeys { !it.isBefore(from) && !it.isAfter(to) }
                    .mapValues { (_, sessions) ->
                        sessions.sumOf { s ->
                            val awakeMin = s.stages.filter { it.type in awake }.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
                            Duration.between(s.startTime, s.endTime).toMinutes() - awakeMin
                        }
                    }
            }

        private fun exerciseSessions(): List<CardioSession> =
            guarded(HealthPermissions.READ_EXERCISE, "Ejercicio", emptyList()) {
                records(ExerciseSessionRecord::class.java, rangeStart, rangeEnd).mapNotNull { s ->
                    val type = cardioType(s.exerciseType) ?: return@mapNotNull null
                    val minutes = Duration.between(s.startTime, s.endTime).toMinutes().toInt()
                    if (minutes < 1) return@mapNotNull null
                    val kcal = guarded(HealthPermissions.READ_ACTIVE_CALORIES_BURNED, "Calorías de sesión", null) {
                        total(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, s.startTime, s.endTime)?.inCalories?.div(1000)
                    }?.takeIf { it > 0 } ?: Calc.cardioKcal(type.met, weightKg, minutes)
                    val km = guarded(HealthPermissions.READ_DISTANCE, "Distancia de sesión", null) {
                        total(DistanceRecord.DISTANCE_TOTAL, s.startTime, s.endTime)?.inMeters?.div(1000)
                    }?.takeIf { it > 0 }
                    val hr = guarded(HealthPermissions.READ_HEART_RATE, "Pulso de sesión", null) {
                        total(HeartRateRecord.BPM_AVG, s.startTime, s.endTime)
                    }
                    val title = s.title?.toString()?.takeIf { it.isNotBlank() }
                    CardioSession(
                        0, s.startTime.atZone(zone).toLocalDate(), type, minutes, km?.let { Calc.roundTo(it, 0.01) }, kcal, hr?.toInt(),
                        listOfNotNull(title, "Importado de Health Connect").joinToString(" · "), "hc:" + s.metadata.id,
                    )
                }
            }
    }

    /** Tipo de cardio para una sesión de Health Connect; null para fuerza (se registra en Entreno). */
    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun cardioType(t: Int): CardioType? = when (t) {
        ExerciseSessionType.EXERCISE_SESSION_TYPE_STRENGTH_TRAINING,
        ExerciseSessionType.EXERCISE_SESSION_TYPE_WEIGHTLIFTING,
        ExerciseSessionType.EXERCISE_SESSION_TYPE_CALISTHENICS,
        ExerciseSessionType.EXERCISE_SESSION_TYPE_STRETCHING,
        ExerciseSessionType.EXERCISE_SESSION_TYPE_GUIDED_BREATHING,
        ExerciseSessionType.EXERCISE_SESSION_TYPE_YOGA,
        ExerciseSessionType.EXERCISE_SESSION_TYPE_PILATES,
        -> null
        ExerciseSessionType.EXERCISE_SESSION_TYPE_WALKING -> CardioType.CAMINAR
        ExerciseSessionType.EXERCISE_SESSION_TYPE_RUNNING,
        ExerciseSessionType.EXERCISE_SESSION_TYPE_RUNNING_TREADMILL,
        -> CardioType.CORRER
        ExerciseSessionType.EXERCISE_SESSION_TYPE_BIKING -> CardioType.BICICLETA
        ExerciseSessionType.EXERCISE_SESSION_TYPE_BIKING_STATIONARY -> CardioType.BICI_FIJA
        ExerciseSessionType.EXERCISE_SESSION_TYPE_SWIMMING_POOL,
        ExerciseSessionType.EXERCISE_SESSION_TYPE_SWIMMING_OPEN_WATER,
        -> CardioType.NADAR
        ExerciseSessionType.EXERCISE_SESSION_TYPE_ELLIPTICAL -> CardioType.ELIPTICA
        ExerciseSessionType.EXERCISE_SESSION_TYPE_ROWING,
        ExerciseSessionType.EXERCISE_SESSION_TYPE_ROWING_MACHINE,
        -> CardioType.REMO
        ExerciseSessionType.EXERCISE_SESSION_TYPE_STAIR_CLIMBING,
        ExerciseSessionType.EXERCISE_SESSION_TYPE_STAIR_CLIMBING_MACHINE,
        -> CardioType.ESCALADORA
        ExerciseSessionType.EXERCISE_SESSION_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
        ExerciseSessionType.EXERCISE_SESSION_TYPE_BOOT_CAMP,
        -> CardioType.HIIT
        ExerciseSessionType.EXERCISE_SESSION_TYPE_HIKING -> CardioType.SENDERISMO
        ExerciseSessionType.EXERCISE_SESSION_TYPE_DANCING -> CardioType.BAILE
        ExerciseSessionType.EXERCISE_SESSION_TYPE_BADMINTON,
        ExerciseSessionType.EXERCISE_SESSION_TYPE_BASKETBALL,
        ExerciseSessionType.EXERCISE_SESSION_TYPE_SOCCER,
        ExerciseSessionType.EXERCISE_SESSION_TYPE_TENNIS,
        ExerciseSessionType.EXERCISE_SESSION_TYPE_VOLLEYBALL,
        ExerciseSessionType.EXERCISE_SESSION_TYPE_BOXING,
        ExerciseSessionType.EXERCISE_SESSION_TYPE_MARTIAL_ARTS,
        ExerciseSessionType.EXERCISE_SESSION_TYPE_SQUASH,
        ExerciseSessionType.EXERCISE_SESSION_TYPE_TABLE_TENNIS,
        ExerciseSessionType.EXERCISE_SESSION_TYPE_RACQUETBALL,
        -> CardioType.DEPORTE
        else -> CardioType.OTRO
    }
}
