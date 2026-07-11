package com.example.helper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
            // 🎯 [핵심 수정] Handler().post를 통해 메인 루프 한 사이클 뒤로 실행을 미룹니다.
            // 이렇게 하면 액티비티가 완전히 OS 상에서 Foreground 상태로 복귀한 뒤 서비스가 시작되어 튕김이 완전히 방지됩니다.
            Handler(Looper.getMainLooper()).post {
                try {
                    val serviceIntent = Intent(this, SolverService::class.java).apply {
                        putExtra("resultCode", resultCode)
                        putExtra("data", data)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "서비스 시작 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleServiceSignal(intent)
    }

    private fun handleServiceSignal(intent: Intent?) {
        if (intent?.getBooleanExtra("ACTION_FINISH", false) == true) {
            moveTaskToBack(true)
        }
    }
}
