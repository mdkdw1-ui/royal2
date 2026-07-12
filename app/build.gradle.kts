# AndroidX 및 최적화 설정
android.useAndroidX=true
# OpenCV 호환성 변환 과정을 건너뛰어 Heap Space 에러를 근본적으로 차단합니다.
android.enableJetifier=false

# Gradle 데몬 메모리 최적화 (대용량 네이티브 컴파일 대응)
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=512m -XX:+HeapDumpOnOutOfMemoryError

# 가상 머신(CI 환경) 및 빌드 속도 향상 추가 옵션
org.gradle.caching=true
org.gradle.parallel=true
