package dev.rambo.airposture

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat

/**
 * Field harness for Fitbit Air BLE protocol discovery and posture-haptic testing.
 * All diagnostics are recorded locally and are exported only on explicit share.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var haptics: HapticSink
    private lateinit var ble: AirBleManager
    private lateinit var diagnostics: DiagnosticsLogger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnostics = DiagnosticsLogger(this)
        val file = diagnostics.startNewSession()
        haptics = PhoneHapticSink(this)
        ble = AirBleManager(this, diagnostics, ::appendLog)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        status = TextView(this).apply {
            text = "Air Posture v${BuildConfig.VERSION_NAME}\n" +
                "診断ログ: ${file.name}\n" +
                "スマホバイブ: ready\n\n" +
                "推奨: Airを背中に装着 → BLE解析 → 下のマーカーを動作直前に押してください。\n"
            textSize = 14f
            setTextIsSelectable(true)
        }

        content.addView(button("姿勢警告バイブをテスト") {
            diagnostics.event("haptic_test")
            haptics.postureWarning()
            appendLog("haptic: double pulse")
        })
        content.addView(button("Fitbit AirをBLEスキャン・解析") {
            if (hasBluetoothPermissions()) ble.scanAndConnect() else requestBluetoothPermissions()
        })
        content.addView(button("● マーカー: 直立/良い姿勢") { marker("neutral") })
        content.addView(button("● マーカー: 前傾/悪い姿勢") { marker("forward") })
        content.addView(button("● マーカー: 歩行・日常動作") { marker("walk") })
        content.addView(button("● マーカー: Fitbit側アラームを今作動") { marker("fitbit_alarm") })
        content.addView(button("新しいログセッションを開始（BLE停止）") {
            val newFile = ble.startNewSession()
            appendLog("new session: ${newFile.name}; BLE stopped")
        })
        content.addView(button("ログZIPを共有 / ChatGPTへ送る") {
            runCatching {
                val zip = diagnostics.createShareZip()
                appendLog("export: ${zip.name} (${zip.length()} bytes)")
                startActivity(IntentChooser.create(diagnostics.shareIntent(zip), "診断ログを共有"))
            }.onFailure {
                appendLog("export failed: ${it.javaClass.simpleName}: ${it.message}")
                Toast.makeText(this, "ログ書き出しに失敗しました", Toast.LENGTH_LONG).show()
            }
        })
        content.addView(button("BLE接続/スキャンを停止") {
            ble.stop()
            diagnostics.event("ble_stop_requested")
            appendLog("BLE stopped")
        })
        content.addView(status)
        setContentView(ScrollView(this).apply { addView(content) })

        if (!hasBluetoothPermissions()) requestBluetoothPermissions()
    }

    override fun onDestroy() {
        ble.stop()
        diagnostics.event("activity_destroy")
        super.onDestroy()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun marker(name: String) {
        diagnostics.event("marker_$name")
        appendLog("MARKER: $name @ ${System.currentTimeMillis()}")
        Toast.makeText(this, "マーカー記録: $name", Toast.LENGTH_SHORT).show()
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            status.append("$message\n")
            // Keep the on-screen TextView bounded; the full record stays in JSONL.
            if (status.text.length > 45_000) {
                status.text = status.text.takeLast(30_000)
            }
        }
    }

    private fun hasBluetoothPermissions() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun requestBluetoothPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            100,
        )
    }
}

/** Avoid importing androidx.activity just for Intent.createChooser; keeps Activity minimal. */
private object IntentChooser {
    fun create(intent: android.content.Intent, title: String): android.content.Intent =
        android.content.Intent.createChooser(intent, title)
}
