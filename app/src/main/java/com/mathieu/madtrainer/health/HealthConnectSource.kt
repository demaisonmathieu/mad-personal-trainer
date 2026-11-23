package com.mathieu.madtrainer.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.mathieu.madtrainer.data.BodyKind
import com.mathieu.madtrainer.data.BodyPoint
import com.mathieu.madtrainer.data.RestingHr
import com.mathieu.madtrainer.data.Vo2MaxPoint
import java.time.Instant
import kotlin.reflect.KClass

/** Disponibilité de Health Connect sur le téléphone. */
enum class HealthAvailability { AVAILABLE, NEEDS_UPDATE, NOT_INSTALLED }

/** Données lues : pesées brutes (balance), FC de repos et VO2max (montre). */
data class HealthData(val points: List<BodyPoint>, val restingHr: List<RestingHr>, val vo2max: List<Vo2MaxPoint> = emptyList())

/** Lecture des mesures corporelles écrites dans Health Connect par les autres applis (Starfit, Zepp…). */
class HealthConnectSource(private val context: Context) {

    companion object {
        const val PROVIDER = "com.google.android.apps.healthdata"

        private val types: List<KClass<out Record>> = listOf(
            WeightRecord::class, BodyFatRecord::class, LeanBodyMassRecord::class, BoneMassRecord::class,
            BodyWaterMassRecord::class, BasalMetabolicRateRecord::class, HeightRecord::class, RestingHeartRateRecord::class,
            Vo2MaxRecord::class,
        )

        /** Permissions de lecture demandées (toutes facultatives : on lit ce qui est autorisé). */
        val permissions: Set<String> = types.map { HealthPermission.getReadPermission(it) }.toSet()
    }

    fun availability(): HealthAvailability = when (HealthConnectClient.getSdkStatus(context, PROVIDER)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.NEEDS_UPDATE
        else -> HealthAvailability.NOT_INSTALLED
    }

    /** Fiche Play Store de Health Connect (installation ou mise à jour). */
    fun storeIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
        setPackage("com.android.vending")
        data = Uri.parse("market://details?id=$PROVIDER&url=healthconnect%3A%2F%2Fonboarding")
        putExtra("overlay", true)
        putExtra("callerId", context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    /** Lit les [days] derniers jours, uniquement pour les types autorisés. */
    suspend fun read(days: Long = 365): HealthData {
        val granted = grantedPermissions()
        val since = Instant.now().minusSeconds(days * 86_400)
        fun ok(t: KClass<out Record>) = HealthPermission.getReadPermission(t) in granted

        val points = mutableListOf<BodyPoint>()
        if (ok(WeightRecord::class)) readAll(WeightRecord::class, since).forEach {
            points += BodyPoint(it.time.toEpochMilli(), BodyKind.WEIGHT, it.weight.inKilograms, it.metadata.dataOrigin.packageName)
        }
        if (ok(BodyFatRecord::class)) readAll(BodyFatRecord::class, since).forEach {
            points += BodyPoint(it.time.toEpochMilli(), BodyKind.BODY_FAT_PCT, it.percentage.value, it.metadata.dataOrigin.packageName)
        }
        if (ok(LeanBodyMassRecord::class)) readAll(LeanBodyMassRecord::class, since).forEach {
            points += BodyPoint(it.time.toEpochMilli(), BodyKind.LEAN_MASS, it.mass.inKilograms, it.metadata.dataOrigin.packageName)
        }
        if (ok(BoneMassRecord::class)) readAll(BoneMassRecord::class, since).forEach {
            points += BodyPoint(it.time.toEpochMilli(), BodyKind.BONE_MASS, it.mass.inKilograms, it.metadata.dataOrigin.packageName)
        }
        if (ok(BodyWaterMassRecord::class)) readAll(BodyWaterMassRecord::class, since).forEach {
            points += BodyPoint(it.time.toEpochMilli(), BodyKind.WATER_MASS, it.mass.inKilograms, it.metadata.dataOrigin.packageName)
        }
        if (ok(BasalMetabolicRateRecord::class)) readAll(BasalMetabolicRateRecord::class, since).forEach {
            points += BodyPoint(it.time.toEpochMilli(), BodyKind.BMR, it.basalMetabolicRate.inKilocaloriesPerDay, it.metadata.dataOrigin.packageName)
        }
        if (ok(HeightRecord::class)) readAll(HeightRecord::class, since).forEach {
            points += BodyPoint(it.time.toEpochMilli(), BodyKind.HEIGHT, it.height.inMeters, it.metadata.dataOrigin.packageName)
        }
        val resting = if (ok(RestingHeartRateRecord::class)) {
            readAll(RestingHeartRateRecord::class, since).map {
                RestingHr(it.time.toEpochMilli(), it.beatsPerMinute.toInt(), it.metadata.dataOrigin.packageName)
            }
        } else emptyList()
        val vo2 = if (ok(Vo2MaxRecord::class)) {
            readAll(Vo2MaxRecord::class, since).map {
                Vo2MaxPoint(it.time.toEpochMilli(), it.vo2MillilitersPerMinuteKilogram, it.metadata.dataOrigin.packageName)
            }
        } else emptyList()
        return HealthData(points, resting, vo2)
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, since: Instant): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val res = client.readRecords(
                ReadRecordsRequest(type, TimeRangeFilter.after(since), pageSize = 1000, pageToken = token),
            )
            out += res.records
            token = res.pageToken
        } while (token != null && out.size < 20_000)
        return out
    }
}

/** Nom lisible de l'appli qui a écrit la mesure. */
fun sourceLabel(pkg: String): String = when {
    pkg.isBlank() -> "?"
    pkg.contains("starfit", true) -> "Starfit"
    pkg.contains("zepp", true) || pkg.contains("huami", true) -> "Zepp"
    pkg.contains("fitdays", true) -> "Fitdays"
    pkg.contains("strava", true) -> "Strava"
    pkg == "com.google.android.apps.fitness" -> "Google Fit"
    pkg == HealthConnectSource.PROVIDER || pkg == "com.google.android.healthconnect.controller" -> "Health Connect"
    else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
}
