package com.example.helper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.helper.service.SolverService

class MainActivity : AppCompatActivity() {

    // 1. 화면 공유 권한 요청 런처
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, SolverService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            Toast.makeText(this, "화면 공유 분석을 시작합니다.", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true) // 즉시 홈 화면으로 내려감
        } else {
            Toast.makeText(this, "화면 공유 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    // 2. 다른 앱 위에 그리기 권한 요청 런처
    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "그리기 권한 승인됨! 다시 버튼을 눌러주세요.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "오버레이 표시를 위해 권한 동의가 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val startButton = Button(this).apply {
            text = "매칭 헬퍼 시작하기"
            setOnClickListener {
                // 🎯 [핵심] 다른 앱 위에 그리기 권한이 있는지 먼저 체크합니다.
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    overlayLauncher.launch(intent)
                } else {
                    // 권한이 이미 있다면 화면 공유 시스템 팝업을 띄웁니다.
                    val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    captureLauncher.launch(mpManager.createScreenCaptureIntent())
                }
            }
        }
        setContentView(startButton)
    }
}
