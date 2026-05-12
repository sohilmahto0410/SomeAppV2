package com.sohil.icaibatchmonitor

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerRegion: Spinner
    private lateinit var spinnerPou: Spinner
    private lateinit var spinnerCourse: Spinner
    private lateinit var spinnerInterval: Spinner
    private lateinit var btnAdd: MaterialButton
    private lateinit var btnToggleMonitor: MaterialButton
    private lateinit var btnRefreshNow: MaterialButton
    private lateinit var btnRetryRegions: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvConfigHeader: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView

    private lateinit var prefs: PreferencesManager
    private lateinit var adapter: MonitoredConfigAdapter
    private val scraper = ICAIScraper()

    private var regionOptions: List<ICAIScraper.DropdownOption> = emptyList()
    private var pouOptions: List<ICAIScraper.DropdownOption> = emptyList()
    private var courseOptions: List<ICAIScraper.DropdownOption> = emptyList()
    private var formFields: ICAIScraper.FormFields? = null

    private val intervalOptions = listOf(
        "Every 15 minutes" to 15,
        "Every 30 minutes" to 30,
        "Every 1 hour"     to 60,
        "Every 2 hours"    to 120,
        "Every 6 hours"    to 360
    )

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // ─── Lifecycle ────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = PreferencesManager(this)
        NotificationHelper.createNotificationChannel(this)
        requestNotifPermission()
        bindViews()

        // IMPORTANT: Set adapters on ALL spinners IMMEDIATELY so they are
        // tappable from the very first frame — before any network call.
        initSpinnerPlaceholders()
        setupIntervalSpinner()
        setupRecyclerView()
        setupAddButton()
        setupMonitorButton()
        setupRefreshButton()

        // Retry button shown when region load fails
        btnRetryRegions.setOnClickListener { loadRegions() }

        loadRegions()
        refreshConfigList()
        updateMonitorButtonState()
    }

    override fun onResume() {
        super.onResume()
        refreshConfigList()
        updateMonitorButtonState()
    }

    // ─── View binding ─────────────────────────────────────────────────────────────

    private fun bindViews() {
        spinnerRegion    = findViewById(R.id.spinnerRegion)
        spinnerPou       = findViewById(R.id.spinnerPou)
        spinnerCourse    = findViewById(R.id.spinnerCourse)
        spinnerInterval  = findViewById(R.id.spinnerInterval)
        btnAdd           = findViewById(R.id.btnAdd)
        btnToggleMonitor = findViewById(R.id.btnToggleMonitor)
        btnRefreshNow    = findViewById(R.id.btnRefreshNow)
        btnRetryRegions  = findViewById(R.id.btnRetryRegions)
        tvStatus         = findViewById(R.id.tvStatus)
        tvConfigHeader   = findViewById(R.id.tvConfigHeader)
        progressBar      = findViewById(R.id.progressBar)
        recyclerView     = findViewById(R.id.recyclerView)
    }

    // ─── Spinner initialisation ───────────────────────────────────────────────────

    /**
     * Give every spinner an adapter immediately so they render as tappable,
     * not as gray blank boxes. Data is replaced once network calls complete.
     */
    private fun initSpinnerPlaceholders() {
        setSpinnerData(spinnerRegion, listOf("Loading regions…"))
        setSpinnerData(spinnerPou,    listOf("Select a region first"))
        setSpinnerData(spinnerCourse, listOf("Select a POU first"))
    }

    private fun setSpinnerData(spinner: Spinner, items: List<String>) {
        val a = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = a
    }

    private fun setupIntervalSpinner() {
        val labels = intervalOptions.map { it.first }
        val a = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerInterval.adapter = a
        spinnerInterval.setSelection(1) // default: 30 min
    }

    // ─── Network: load regions ────────────────────────────────────────────────────

    private fun loadRegions() {
        btnRetryRegions.visibility = View.GONE
        setLoading(true, "Connecting to ICAI website…")

        lifecycleScope.launch {
            try {
                val (fields, regions) = withContext(Dispatchers.IO) {
                    scraper.getRegions()
                }
                formFields = fields
                regionOptions = regions

                if (regions.isEmpty()) {
                    setStatus("⚠️ No regions returned. Tap Retry.")
                    btnRetryRegions.visibility = View.VISIBLE
                    setLoading(false)
                    return@launch
                }

                // Populate region spinner with real data
                val labels = listOf("── Select Region ──") + regions.map { it.label }
                setSpinnerData(spinnerRegion, labels)

                // Attach listener AFTER setting adapter to avoid premature triggers
                spinnerRegion.post {
                    spinnerRegion.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                            if (pos == 0) {
                                pouOptions = emptyList()
                                courseOptions = emptyList()
                                setSpinnerData(spinnerPou,    listOf("Select a region first"))
                                setSpinnerData(spinnerCourse, listOf("Select a POU first"))
                                return
                            }
                            loadPOUs(regions[pos - 1])
                        }
                        override fun onNothingSelected(p: AdapterView<*>) {}
                    }
                }

                setStatus("✅ Select a region")
                setLoading(false)

            } catch (e: Exception) {
                setStatus("❌ Failed: ${e.message}")
                btnRetryRegions.visibility = View.VISIBLE
                setSpinnerData(spinnerRegion, listOf("Failed — tap Retry"))
                setLoading(false)
            }
        }
    }

    // ─── Network: load POUs ───────────────────────────────────────────────────────

    private fun loadPOUs(region: ICAIScraper.DropdownOption) {
        val fields = formFields ?: return
        setSpinnerData(spinnerPou,    listOf("Loading POUs…"))
        setSpinnerData(spinnerCourse, listOf("Select a POU first"))
        pouOptions = emptyList()
        courseOptions = emptyList()
        setLoading(true, "Loading POUs for ${region.label}…")

        lifecycleScope.launch {
            try {
                val (newFields, pous) = withContext(Dispatchers.IO) {
                    scraper.getPOUs(fields, region.value)
                }
                formFields = newFields
                pouOptions = pous

                if (pous.isEmpty()) {
                    setSpinnerData(spinnerPou, listOf("No POUs found"))
                    setStatus("No POUs found for ${region.label}")
                    setLoading(false)
                    return@launch
                }

                val labels = listOf("── Select POU ──") + pous.map { it.label }
                setSpinnerData(spinnerPou, labels)

                spinnerPou.post {
                    spinnerPou.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                            if (pos == 0) {
                                courseOptions = emptyList()
                                setSpinnerData(spinnerCourse, listOf("Select a POU first"))
                                return
                            }
                            loadCourses(region, pous[pos - 1])
                        }
                        override fun onNothingSelected(p: AdapterView<*>) {}
                    }
                }

                setStatus("✅ Select a POU")
                setLoading(false)

            } catch (e: Exception) {
                setSpinnerData(spinnerPou, listOf("Error loading POUs"))
                setStatus("❌ POU load failed: ${e.message}")
                setLoading(false)
            }
        }
    }

    // ─── Network: load courses ────────────────────────────────────────────────────

    private fun loadCourses(
        region: ICAIScraper.DropdownOption,
        pou: ICAIScraper.DropdownOption
    ) {
        val fields = formFields ?: return
        setLoading(true, "Loading courses…")

        lifecycleScope.launch {
            try {
                val (newFields, courses) = withContext(Dispatchers.IO) {
                    scraper.getCourses(fields, region.value, pou.value)
                }
                formFields = newFields
                courseOptions = courses

                // Use live courses if available, otherwise show static list
                val courseList = if (courses.isNotEmpty()) courses else staticCourses()
                courseOptions = courseList

                val labels = listOf("── Select Course ──") + courseList.map { it.label }
                setSpinnerData(spinnerCourse, labels)

                setStatus("✅ Ready — select a course then tap Add")
                setLoading(false)

            } catch (e: Exception) {
                // Fall back to static course list on error
                val courseList = staticCourses()
                courseOptions = courseList
                val labels = listOf("── Select Course ──") + courseList.map { it.label }
                setSpinnerData(spinnerCourse, labels)
                setStatus("⚠️ Using default courses")
                setLoading(false)
            }
        }
    }

    /** Fallback courses — these rarely change on ICAI */
    private fun staticCourses() = listOf(
        ICAIScraper.DropdownOption("OC",   "ICITSS - Orientation Course"),
        ICAIScraper.DropdownOption("IT",   "ICITSS - Information Technology"),
        ICAIScraper.DropdownOption("AIT",  "AICITSS - Advanced Information Technology"),
        ICAIScraper.DropdownOption("MCS",  "Advanced (ICITSS) MCS Course"),
        ICAIScraper.DropdownOption("MCSW", "Advanced (ICITSS) MCS Course - Weekend")
    )

    // ─── Add config ───────────────────────────────────────────────────────────────

    private fun setupAddButton() {
        btnAdd.setOnClickListener {
            val regionPos  = spinnerRegion.selectedItemPosition
            val pouPos     = spinnerPou.selectedItemPosition
            val coursePos  = spinnerCourse.selectedItemPosition
            val intervalPos = spinnerInterval.selectedItemPosition

            if (regionPos == 0 || regionOptions.isEmpty()) {
                showSnack("Please select a region"); return@setOnClickListener
            }
            if (pouPos == 0 || pouOptions.isEmpty()) {
                showSnack("Please select a POU"); return@setOnClickListener
            }
            if (coursePos == 0 || courseOptions.isEmpty()) {
                showSnack("Please select a course"); return@setOnClickListener
            }

            val region   = regionOptions[regionPos - 1]
            val pou      = pouOptions[pouPos - 1]
            val course   = courseOptions[coursePos - 1]
            val interval = intervalOptions[intervalPos].second

            val config = MonitorConfig(
                regionLabel  = region.label,  regionValue  = region.value,
                pouLabel     = pou.label,     pouValue     = pou.value,
                courseLabel  = course.label,  courseValue  = course.value,
                intervalMinutes = interval
            )

            if (prefs.addConfig(config)) {
                showSnack("✅ Added: ${config.courseLabel} @ ${pou.label}")
                refreshConfigList()
            } else {
                showSnack("⚠️ Already monitoring this combination")
            }
        }
    }

    // ─── Monitor toggle ───────────────────────────────────────────────────────────

    private fun setupMonitorButton() {
        btnToggleMonitor.setOnClickListener {
            val configs = prefs.getConfigs()
            if (configs.isEmpty()) {
                showSnack("Add at least one batch first"); return@setOnClickListener
            }
            if (prefs.isMonitoringActive()) {
                BatchMonitorWorker.cancel(this)
                prefs.setMonitoringActive(false)
                showSnack("🛑 Monitoring stopped")
            } else {
                val minInterval = configs.minOf { it.intervalMinutes }.toLong()
                BatchMonitorWorker.schedule(this, minInterval)
                prefs.setMonitoringActive(true)
                showSnack("▶️ Monitoring started")
            }
            updateMonitorButtonState()
        }
    }

    private fun setupRefreshButton() {
        btnRefreshNow.setOnClickListener {
            if (prefs.getConfigs().isEmpty()) {
                showSnack("Add at least one config first"); return@setOnClickListener
            }
            BatchMonitorWorker.runNow(this)
            showSnack("🔄 Manual check triggered")
        }
    }

    private fun setupRecyclerView() {
        adapter = MonitoredConfigAdapter { config ->
            AlertDialog.Builder(this)
                .setTitle("Remove Monitor")
                .setMessage("Stop monitoring ${config.displayName()}?")
                .setPositiveButton("Remove") { _, _ ->
                    prefs.removeConfig(config.id)
                    refreshConfigList()
                    showSnack("Removed")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private fun updateMonitorButtonState() {
        if (prefs.isMonitoringActive()) {
            btnToggleMonitor.text = "⏹ Stop Monitoring"
            btnToggleMonitor.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.holo_red_light))
        } else {
            btnToggleMonitor.text = "▶ Start Monitoring"
            btnToggleMonitor.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.colorSuccess))
        }
    }

    private fun refreshConfigList() {
        val configs = prefs.getConfigs()
        adapter.submitList(configs)
        tvConfigHeader.text = "Monitored Batches (${configs.size})"
    }

    private fun setLoading(loading: Boolean, message: String = "") {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnAdd.isEnabled = !loading
        if (message.isNotBlank()) tvStatus.text = message
    }

    private fun setStatus(msg: String) { tvStatus.text = msg }

    private fun showSnack(msg: String) =
        Snackbar.make(recyclerView, msg, Snackbar.LENGTH_LONG).show()

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
