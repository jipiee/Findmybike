package com.findmybike.collector

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Records one bike trip: IMU, magnetometer, barometer, GPS, GNSS quality, battery, and labelled events.
 *
 * Threading: every callback (sensors, GPS, GNSS) and every file write runs on ONE background
 * HandlerThread ("TripThread"), so writers are single-threaded and the UI never blocks on disk I/O.
 *
 * Time base: all files use t_us = elapsedRealtimeNanos / 1000 (microseconds since boot). Sensor
 * events, Location.elapsedRealtimeNanos and SystemClock.elapsedRealtimeNanos share this clock,
 * so all streams line up exactly. meta.json stores the matching wall-clock time for conversion.
 */
object TripRecorder : SensorEventListener, LocationListener {

    enum class State { IDLE, RECORDING }

    /** sensor type, file name, CSV header, requested sampling period (µs) */
    private class Spec(val type: Int, val file: String, val header: String, val periodUs: Int)

    private val specs = listOf(
        Spec(Sensor.TYPE_ACCELEROMETER, "accel.csv", "t_us,ax,ay,az", 10_000),                 // 100 Hz, m/s² incl. gravity
        Spec(Sensor.TYPE_GYROSCOPE, "gyro.csv", "t_us,gx,gy,gz", 10_000),                        // 100 Hz, rad/s
        Spec(Sensor.TYPE_MAGNETIC_FIELD, "mag.csv", "t_us,mx,my,mz", 20_000),                    // 50 Hz, µT (calibrated)
        Spec(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, "mag_uncal.csv", "t_us,mx,my,mz,bx,by,bz", 20_000), // raw + hard-iron bias
        Spec(Sensor.TYPE_PRESSURE, "baro.csv", "t_us,hpa", 100_000),                            // ~10 Hz, hPa
    )

    @Volatile var state = State.IDLE
        private set
    @Volatile var tripDir: File? = null
        private set
    @Volatile var lastZip: File? = null
        private set
    @Volatile var lastEvent = "—"
        private set
    @Volatile var lastFix: Location? = null
        private set
    @Volatile var satsInView = 0
        private set
    @Volatile var satsUsed = 0
        private set
    @Volatile var meanCn0Used = 0f
        private set
    @Volatile var lastPressure = 0f
        private set
    @Volatile var lastBattery = -1
        private set
    @Volatile var startElapsedMs = 0L
        private set

    /** Rows written per file, for the live status display. */
    val counts = ConcurrentHashMap<String, AtomicLong>()

    private lateinit var app: Context
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var handler: Handler
    private var initialized = false

    private val writers = HashMap<String, BufferedWriter>()   // only touched on TripThread
    private var meta = JSONObject()
    private var batteryTicks = 0

    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        handler = Handler(HandlerThread("TripThread").apply { start() }.looper)
        initialized = true
    }

    fun missingSensors(): List<String> =
        specs.filter { sensorManager.getDefaultSensor(it.type) == null }.map { it.file }

    fun tripsRoot(): File = File(app.getExternalFilesDir(null), "trips").apply { mkdirs() }

    private fun nowUs() = SystemClock.elapsedRealtimeNanos() / 1000

    // ---------------------------------------------------------------- controls (UI thread)

    /** Caller must already hold ACCESS_FINE_LOCATION. */
    fun start(setup: Map<String, String>) {
        if (state != State.IDLE) return
        state = State.RECORDING
        startElapsedMs = SystemClock.elapsedRealtime()
        lastEvent = "trip_start"
        lastFix = null
        counts.clear()
        handler.post { openTrip(setup) }
    }

    /** Record a labelled moment (e.g. "parked"). Safe to call from the UI thread. */
    fun event(label: String) {
        if (state != State.RECORDING) return
        val t = nowUs()                      // timestamp taken at the tap, not when the write happens
        lastEvent = label
        handler.post { writeEvent(t, label) }
    }

    /** Ends the trip; [extra] (e.g. notes) is added to meta.json. [onDone] gets the zip on the main thread. */
    fun stop(extra: Map<String, String>, onDone: (File?) -> Unit) {
        if (state != State.RECORDING) return
        state = State.IDLE
        val t = nowUs()
        handler.post {
            extra.forEach { (k, v) -> meta.put(k, v) }
            writeEvent(t, "trip_end")
            unregisterAll()
            handler.removeCallbacks(housekeeping)
            writers.values.forEach { it.close() }
            writers.clear()
            val dir = tripDir
            if (dir != null) {
                meta.put("end_wall_ms", System.currentTimeMillis())
                meta.put("end_elapsed_us", t)
                meta.put("rows", JSONObject(counts.mapValues { it.value.get() }))
                File(dir, "meta.json").writeText(meta.toString(2))
                lastZip = zip(dir)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onDone(lastZip) }
        }
    }

    // ---------------------------------------------------------------- TripThread

    @SuppressLint("MissingPermission")
    private fun openTrip(setup: Map<String, String>) {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val dir = File(tripsRoot(), "trip_$stamp").apply { mkdirs() }
        tripDir = dir

        specs.forEach { open(dir, it.file, it.header) }
        open(dir, "gps.csv", "t_us,utc_ms,lat,lon,acc_m,alt_m,vacc_m,speed_mps,speed_acc_mps,bearing_deg,provider")
        open(dir, "gnss.csv", "t_us,sats_in_view,sats_used,mean_cn0_used_dbhz")
        open(dir, "events.csv", "t_us,label,lat,lon,acc_m")
        open(dir, "battery.csv", "t_us,percent,charging")

        meta = JSONObject().apply {
            put("app", "FindMyBike Collector 1.0")
            put("trip_id", dir.name)
            put("start_wall_ms", System.currentTimeMillis())
            put("start_elapsed_us", nowUs())
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            setup.forEach { (k, v) -> put(k, v) }
            put("sensors", JSONArray().apply {
                specs.forEach { s ->
                    sensorManager.getDefaultSensor(s.type)?.let {
                        put(JSONObject().apply {
                            put("file", s.file); put("name", it.name); put("vendor", it.vendor)
                            put("requested_period_us", s.periodUs); put("min_delay_us", it.minDelay)
                            put("resolution", it.resolution.toDouble()); put("max_range", it.maximumRange.toDouble())
                        })
                    }
                }
            })
        }
        File(dir, "meta.json").writeText(meta.toString(2))   // written now too, in case the trip is cut short

        specs.forEach { s ->
            sensorManager.getDefaultSensor(s.type)?.let {
                sensorManager.registerListener(this, it, s.periodUs, handler)
            }
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, handler.looper)
        locationManager.registerGnssStatusCallback(gnssCallback, handler)

        writeEvent(nowUs(), "trip_start")
        batteryTicks = 0
        handler.post(housekeeping)
    }

    private fun open(dir: File, name: String, header: String) {
        writers[name] = File(dir, name).bufferedWriter().apply { write(header); newLine() }
    }

    private fun row(file: String, line: String) {
        val w = writers[file] ?: return
        w.write(line); w.newLine()
        counts.getOrPut(file) { AtomicLong() }.incrementAndGet()
    }

    private fun writeEvent(tUs: Long, label: String) {
        val f = lastFix
        row("events.csv", if (f != null)
            String.format(Locale.US, "%d,%s,%.7f,%.7f,%.1f", tUs, label, f.latitude, f.longitude, f.accuracy)
        else "$tUs,$label,,,")
        writers["events.csv"]?.flush()       // events are precious: flush immediately
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (state != State.RECORDING) return
        val spec = specs.firstOrNull { it.type == e.sensor.type } ?: return
        val t = e.timestamp / 1000
        val v = e.values
        val line = when (e.sensor.type) {
            Sensor.TYPE_PRESSURE -> { lastPressure = v[0]; String.format(Locale.US, "%d,%.4f", t, v[0]) }
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED ->
                String.format(Locale.US, "%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f", t, v[0], v[1], v[2], v[3], v[4], v[5])
            else -> String.format(Locale.US, "%d,%.6f,%.6f,%.6f", t, v[0], v[1], v[2])
        }
        row(spec.file, line)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(loc: Location) {
        if (state != State.RECORDING) return
        lastFix = loc
        val vacc = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toString() else ""
        val sacc = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond.toString() else ""
        row("gps.csv", String.format(Locale.US, "%d,%d,%.7f,%.7f,%.1f,%.1f,%s,%.2f,%s,%.1f,%s",
            loc.elapsedRealtimeNanos / 1000, loc.time, loc.latitude, loc.longitude, loc.accuracy,
            loc.altitude, vacc, loc.speed, sacc, loc.bearing, loc.provider ?: ""))
    }

    // Older Android versions call these; no-ops keep the listener compatible.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    /** Satellite count and signal strength: drops sharply indoors / in bike sheds. */
    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            if (state != State.RECORDING) return
            var used = 0
            var cn0 = 0f
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) { used++; cn0 += status.getCn0DbHz(i) }
            }
            satsInView = status.satelliteCount
            satsUsed = used
            meanCn0Used = if (used > 0) cn0 / used else 0f
            row("gnss.csv", String.format(Locale.US, "%d,%d,%d,%.1f", nowUs(), satsInView, used, meanCn0Used))
        }
    }

    /** Every 5 s: flush files (so a killed app loses at most 5 s). Every 30 s: log battery. */
    private val housekeeping = object : Runnable {
        override fun run() {
            if (state != State.RECORDING) return
            if (batteryTicks % 6 == 0) {
                val b = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (b != null) {
                    val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    lastBattery = if (level >= 0) level * 100 / scale else -1
                    row("battery.csv", "${nowUs()},$lastBattery,${if (plugged) 1 else 0}")
                }
            }
            batteryTicks++
            writers.values.forEach { it.flush() }
            handler.postDelayed(this, 5_000)
        }
    }

    private fun unregisterAll() {
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
        locationManager.unregisterGnssStatusCallback(gnssCallback)
    }

    private fun zip(dir: File): File {
        val out = File(dir.parentFile, "${dir.name}.zip")
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                zos.putNextEntry(ZipEntry("${dir.name}/${f.name}"))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return out
    }
}
