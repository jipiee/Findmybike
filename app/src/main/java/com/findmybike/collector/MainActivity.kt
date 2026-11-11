package com.findmybike.collector

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var placementGroup: RadioGroup
    private lateinit var etRider: EditText
    private lateinit var etBike: EditText
    private lateinit var etNotes: EditText
    private lateinit var btnTrip: Button
    private lateinit var eventButtons: List<Button>
    private lateinit var tvStatus: TextView
    private lateinit var btnShare: Button

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1000) }
    }

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) startTrip()
            else toast("Precise location permission is needed for GPS")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        TripRecorder.init(this)

        placementGroup = findViewById(R.id.placementGroup)
        etRider = findViewById(R.id.etRider)
        etBike = findViewById(R.id.etBike)
        etNotes = findViewById(R.id.etNotes)
        btnTrip = findViewById(R.id.btnTrip)
        tvStatus = findViewById(R.id.tvStatus)
        btnShare = findViewById(R.id.btnShare)

        PLACEMENTS.forEachIndexed { i, (_, label) ->
            placementGroup.addView(RadioButton(this).apply { id = PLACEMENT_ID_BASE + i; text = label })
        }
        placementGroup.check(PLACEMENT_ID_BASE)   // default: front trouser pocket

        // label written to events.csv  <-  button
        val events = listOf(
            "ride_start" to R.id.btnRideStart,
            "short_stop" to R.id.btnShortStop,
            "push_bike" to R.id.btnPush,
            "undo_last" to R.id.btnUndo,
            "parked" to R.id.btnParked,
        )
        eventButtons = events.map { (label, id) ->
            findViewById<Button>(id).apply {
                setOnClickListener { v ->
                    TripRecorder.event(label)
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    toast(if (label == "parked") "PARKED saved with current GPS position" else "Marked: $label")
                    refresh()
                }
            }
        }

        btnTrip.setOnClickListener {
            if (TripRecorder.state == TripRecorder.State.IDLE) {
                val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                if (android.os.Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) startTrip()
                else askPermissions.launch(needed.toTypedArray())
            } else {
                AlertDialog.Builder(this)
                    .setTitle("End this trip?")
                    .setMessage("Did you mark PARKED when you locked the bike?")
                    .setPositiveButton("End trip") { _, _ -> endTrip() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        btnShare.setOnClickListener { shareLastTrip() }
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    private fun startTrip() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            toast("Turn on Location (GPS) in quick settings first"); return
        }
        val placement = PLACEMENTS[placementGroup.checkedRadioButtonId - PLACEMENT_ID_BASE].first
        ContextCompat.startForegroundService(this, Intent(this, TripService::class.java))
        TripRecorder.start(mapOf(
            "phone_placement" to placement,
            "rider" to etRider.text.toString().trim(),
            "bike" to etBike.text.toString().trim(),
        ))
        toast("Trip started — wait for a GPS fix before riding")
        refresh()
    }

    private fun endTrip() {
        TripRecorder.stop(mapOf("notes" to etNotes.text.toString().trim())) { zip ->
            stopService(Intent(this, TripService::class.java))
            toast(if (zip != null) "Saved ${zip.name}" else "Saved")
            etNotes.setText("")
            refresh()
        }
        refresh()
    }

    private fun shareLastTrip() {
        val zip = TripRecorder.lastZip
            ?: TripRecorder.tripsRoot().listFiles { f -> f.name.endsWith(".zip") }?.maxByOrNull { it.lastModified() }
        if (zip == null) { toast("No finished trip yet"); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share ${zip.name}"))
    }

    private fun refresh() {
        val rec = TripRecorder.state == TripRecorder.State.RECORDING
        btnTrip.text = if (rec) "End trip" else "Start trip"
        eventButtons.forEach { it.isEnabled = rec }
        listOf<View>(etRider, etBike).forEach { it.isEnabled = !rec }
        for (i in 0 until placementGroup.childCount) placementGroup.getChildAt(i).isEnabled = !rec

        val sb = StringBuilder()
        val missing = TripRecorder.missingSensors()
        if (missing.isNotEmpty()) sb.append("Missing sensors: $missing\n\n")
        if (rec) {
            val s = (SystemClock.elapsedRealtime() - TripRecorder.startElapsedMs) / 1000
            sb.append(String.format(Locale.US, "RECORDING  %d:%02d\n", s / 60, s % 60))
            sb.append("Last event: ${TripRecorder.lastEvent}\n\n")
            val fix = TripRecorder.lastFix
            if (fix == null) sb.append("GPS: waiting for fix…\n")
            else {
                val age = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000_000
                sb.append(String.format(Locale.US, "GPS: ±%.0f m  %.1f km/h  (%ds ago)\n", fix.accuracy, fix.speed * 3.6, age))
            }
            sb.append("Satellites: ${TripRecorder.satsUsed} used / ${TripRecorder.satsInView} in view")
            sb.append(String.format(Locale.US, "  (%.0f dB-Hz)\n", TripRecorder.meanCn0Used))
            sb.append(String.format(Locale.US, "Pressure: %.2f hPa   Battery: %d%%\n\n", TripRecorder.lastPressure, TripRecorder.lastBattery))
            sb.append("Rows written:\n")
            TripRecorder.counts.toSortedMap().forEach { (f, n) -> sb.append(String.format(Locale.US, "  %-14s %d\n", f, n.get())) }
        } else {
            sb.append("Idle.\n")
            TripRecorder.lastZip?.let { sb.append("Last trip: ${it.name}\n") }
            val n = TripRecorder.tripsRoot().listFiles { f -> f.isDirectory }?.size ?: 0
            sb.append("Trips on this phone: $n\n")
            sb.append("Folder: ${TripRecorder.tripsRoot().absolutePath}\n")
        }
        tvStatus.text = sb
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val PLACEMENT_ID_BASE = 2000
        /** value saved to meta.json  ->  label shown on screen */
        private val PLACEMENTS = listOf(
            "front_trouser_pocket" to "Front trouser pocket",
            "back_trouser_pocket" to "Back trouser pocket",
            "jacket_pocket" to "Jacket pocket",
            "backpack" to "Backpack / bag",
            "handlebar_mount" to "Handlebar mount",
        )
    }
}
