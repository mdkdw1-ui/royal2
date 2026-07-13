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

// 🎯 하위 폴더에 있는 SolverService를 올바르게 불러옵니다.
import com.example.helper.service.SolverService

// 🎯 깃허브 컴파일러 레이아웃 인식용 R 클래스 임포트
import com.example.helper.R

class MainActivity : AppCompatActivity() {

    private val REQUEST_MEDIA_PROJECTION = 1001
    private val REQUEST_OVERLAY_PERMISSION = 1002 
    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val btnStart = findViewById<Button>(R.id.btnStartService)
        btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "다른 앱 위에 표시 권한을 허용해주세요.", Toast.LENGTH_LONG).show()
                
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            } else {
                startScreenCaptureRequest()
            }
        }
    }

    private fun startScreenCaptureRequest() {
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "권한이 허용되었습니다! 다시 버튼을 누르면 시작합니다.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "권한이 거부되어 서비스를 시작할 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        } 
        else if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                
                val serviceIntent = Intent(this, SolverService::class.java).apply {
                    putExtra("RESULT_CODE", resultCode)
                    putExtra("RESULT_DATA", data)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                Toast.makeText(this, "분석기가 활성화되었습니다. 게임을 켜주세요.", Toast.LENGTH_SHORT).show()
                moveTaskToBack(true)

            } else {
                Toast.makeText(this, "화면 공유 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
