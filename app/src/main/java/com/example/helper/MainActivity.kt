package com.example.helper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.helper.service.SolverService

class MainActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val DRAW_OVERLAY_REQUEST_CODE = 1001
    private val SCREEN_CAPTURE_REQUEST_CODE = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val startButton = Button(this).apply {
            text = "Match-3 Solver 헬퍼 시작"
            textSize = 20f
        }
        setContentView(startButton)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        startButton.setOnClickListener {
            checkPermissionsAndStart()
        }

        // 혹시 서비스가 동작하면서 최초 인텐트가 전달되었을 경우를 대비한 처리
        handleServiceSignal(intent)
    }

    private fun checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, DRAW_OVERLAY_REQUEST_CODE)
        } else {
            startActivityForResult(
                mediaProjectionManager.createScreenCaptureIntent(),
                SCREEN_CAPTURE_REQUEST_CODE
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == DRAW_OVERLAY_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startActivityForResult(
                    mediaProjectionManager.createScreenCaptureIntent(),
                    SCREEN_CAPTURE_REQUEST_CODE
                )
            } else {
                Toast.makeText(this, "오버레이 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }

        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, SolverService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            // 🎯 [수정 핵심] 여기서 바로 moveTaskToBack(true)를 호출하면 Android 14 정책 위반 크래시가 납니다.
            // 서비스가 확실하게 구동을 완료한 뒤 호출하는 신호를 받아 안전하게 백그라운드로 보낼 것입니다.
        }
    }

    // 🎯 [추가 핵심] SolverService가 구동 완료 후 본 액티비티를 싱글탑으로 깨울 때 신호를 수신하는 곳입니다.
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleServiceSignal(intent)
    }

    private fun handleServiceSignal(intent: Intent?) {
        if (intent?.getBooleanExtra("ACTION_FINISH", false) == true) {
            // 서비스가 완전하게 포그라운드로 승격 및 미디어 프로젝션 등록을 끝낸 시점이므로 안전하게 백그라운드로 밀어냅니다.
            moveTaskToBack(true)
        }
    }
}
