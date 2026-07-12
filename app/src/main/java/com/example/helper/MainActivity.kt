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

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, SolverService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }

            // 안드로이드 14 보안 대응: 액티비티가 활성화된 포그라운드 상태에서 서비스를 즉시 켭니다.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            Toast.makeText(this, "화면 공유 분석 제어판이 활성화되었습니다.\n게임 화면으로 이동해 주세요!", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "화면 공유 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "그리기 권한 승인됨! 다시 시작 버튼을 눌러주세요.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "오버레이 표시를 위해 권한 동의가 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val startButton = Button(this).apply {
            text = "매칭 헬퍼 시작하기"
            textSize = 18f
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    overlayLauncher.launch(intent)
                } else {
                    val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    captureLauncher.launch(mpManager.createScreenCaptureIntent())
                }
            }
        }
        setContentView(startButton)
    }
}
