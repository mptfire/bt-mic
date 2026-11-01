package app.btmic.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import app.btmic.R
import app.btmic.engine.RecordEngine
import app.btmic.service.MicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * On-screen control surface. Recording runs in MicService (FGS microphone);
 * the activity requests permissions, marks "opened since boot" (the Android
 * 14+ mic-FGS gate) and mirrors the engine's truthful state (recipe 8/9).
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(Job() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        MicService.channel(this)
        getSharedPreferences("btmic", MODE_PRIVATE)
            .edit().putBoolean("opened", true).apply()

        val status = findViewById<TextView>(R.id.status)
        val btn = findViewById<Button>(R.id.toggle)

        requestPermissions()

        scope.launch {
            EngineBridge.ui.collect { u ->
                when (u) {
                    is RecordEngine.Ui.RecordingBt ->
                        status.text = getString(R.string.rec_bt)
                    is RecordEngine.Ui.RecordingPhone ->
                        status.text = getString(R.string.rec_phone)
                    is RecordEngine.Ui.Routing, is RecordEngine.Ui.WaitingForBt ->
                        status.text = getString(R.string.waiting_bt)
                    is RecordEngine.Ui.Saved ->
                        status.text = getString(R.string.saved)
                    is RecordEngine.Ui.Failed ->
                        status.text = getString(R.string.failed, u.why)
                    null -> status.text = getString(R.string.idle)
                }
                btn.setText(
                    if (EngineBridge.recording()) R.string.stop else R.string.record)
            }
        }

        btn.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(); return@setOnClickListener
            }
            if (EngineBridge.recording()) MicService.stop(this) else MicService.start(this)
        }
    }

    private fun requestPermissions() {
        val need = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) {
            Toast.makeText(this, R.string.why_perms, Toast.LENGTH_LONG).show()
            requestPermissions(need.toTypedArray(), 1)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
