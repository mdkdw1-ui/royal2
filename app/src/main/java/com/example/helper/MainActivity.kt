package com.example.helper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.helper.service.SolverService

class MainActivity : AppCompatActivity() {

    // 1. 미디어 프로젝션 권한 요청 창을 띄우고 결과를 받는 콜백 설정
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // 사용자가 화면 공유를 허용했을 때 서비스 시작
            val serviceIntent = Intent(this, SolverService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data) // 권한 데이터 통째로 전달
            }

            // 안드로이드 8.0 이상에 맞는 포그라운드 서비스 시작 명령어 사용
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            Toast.makeText(this, "화면 공유 분석을 시작합니다.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "화면 공유 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 간단한 시작 버튼 생성 (레이아웃 파일이 없다면 런타임에 주입)
        val startButton = Button(this).apply {
            text = "매칭 헬퍼 시작하기"
            setOnClickListener {
                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                // 화면 공유 권한 요청 창 띄우기
                captureLauncher.launch(mpManager.createScreenCaptureIntent())
            }
        }
        setContentView(startButton)

        // 3. 서비스 측에서 "액티비티 종료(백그라운드 이동)" 신호를 보냈을 때 처리
        if (intent?.getBooleanExtra("ACTION_FINISH", false) == true) {
            moveTaskToBack(true) // 앱을 종료하지 않고 홈 화면(백그라운드)으로 안전하게 내림
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("ACTION_FINISH", false) == true) {
            moveTaskToBack(true) // 백그라운드로 안전하게 내림
        }
    }
}
