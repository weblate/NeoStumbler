package xyz.malkki.neostumbler.geosubmit

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.GsonBuilder
import timber.log.Timber
import xyz.malkki.neostumbler.StumblerApplication
import xyz.malkki.neostumbler.gson.InstantTypeAdapter
import java.net.SocketTimeoutException
import java.time.Instant
import kotlin.time.DurationUnit
import kotlin.time.measureTime

class ReportSendWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        const val PERIODIC_WORK_NAME = "report_upload_periodic"
        const val ONE_TIME_WORK_NAME = "report_upload_one_time"

        const val INPUT_SEND_ALL = "send_all"

        private const val MIN_REPORTS_TO_SEND = 100
    }

    override suspend fun doWork(): Result {
        val application = applicationContext as StumblerApplication

        val geosubmit = MLSGeosubmit(application.httpClient, GsonBuilder().registerTypeAdapter(Instant::class.java, InstantTypeAdapter()).create())

        val db = application.reportDb

        val notUploadedReports = db.reportDao().getAllReportsNotUploaded()

        if (notUploadedReports.isEmpty()) {
            Timber.i("No Geosubmit reports to send")
            return Result.success()
        }

        val sendAll = inputData.getBoolean(INPUT_SEND_ALL, true)

        //Send third of the reports at once (or at least 100)
        val numReportsToSend = (notUploadedReports.size / 3).coerceAtLeast(MIN_REPORTS_TO_SEND)
        val reportsToSend = if (sendAll || numReportsToSend >= notUploadedReports.size) {
            notUploadedReports
        } else {
            //Randomly select third of the unsent reports
            notUploadedReports.shuffled().take(numReportsToSend)
        }

        val geosubmitReports = reportsToSend
            .map { report ->
                Report(
                    report.report.timestamp,
                    Report.Position.fromDbEntity(report.position),
                    report.wifiAccessPoints.map(Report.WifiAccessPoint::fromDbEntity).takeIf { it.isNotEmpty() },
                    report.cellTowers.map(Report.CellTower::fromDbEntity).takeIf { it.isNotEmpty() },
                    report.bluetoothBeacons.map(Report.BluetoothBeacon::fromDbEntity).takeIf { it.isNotEmpty() }
                )
            }

        return try {
            val duration = measureTime {
                geosubmit.sendReports(geosubmitReports)
            }

            val now = Instant.now()
            db.reportDao().update(*reportsToSend.map { it.report.copy(uploaded = true, uploadTimestamp = now) }.toTypedArray())

            Timber.i("Successfully sent ${geosubmitReports.size} reports to MLS in ${duration.toString(DurationUnit.SECONDS, 2)}")

            Result.success()
        } catch (ex: Exception) {
            Timber.w(ex, "Failed to send Geosubmit reports")

            if (shouldRetry(ex)) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private fun shouldRetry(exception: Exception): Boolean {
        if (exception is SocketTimeoutException) {
            //Retry timeouts because most likely we are just temporarily disconnected
            return true
        }

        if (exception is MLSGeosubmit.MLSException && exception.httpStatusCode in 500..599) {
            //Retry server-side errors (HTTP status 5xx)
            return true
        }

        return false
    }
}