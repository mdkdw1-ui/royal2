import re

FILE = "/data/data/com.termux/files/home/royal2/app/src/main/java/com/example/helper/service/SolverService.kt"
with open(FILE, "r", encoding="utf-8") as f:
    content = f.read()

# 함수 시작 찾기
start_idx = content.find("    private fun saveGimmickBitmap(bitmap: Bitmap) {")
if start_idx == -1:
    print("NOT_FOUND")
    exit(1)

# 매칭 중괄호
brace = 0
i = content.find("{", start_idx)
end_idx = -1
while i < len(content):
    if content[i] == '{':
        brace += 1
    elif content[i] == '}':
        brace -= 1
        if brace == 0:
            end_idx = i + 1
            break
    i += 1

if end_idx == -1:
    print("NO_END")
    exit(1)

NEW = '''    // v42: saveGimmickBitmap UI 스레드 안전
    private fun saveGimmickBitmap(bitmap: Bitmap) {
        backgroundHandler?.post {
            try {
                val dir = getExternalFilesDir("gimmicks")
                if (dir != null && !dir.exists()) dir.mkdirs()
                val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
                val file = File(dir, "gimmick_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out ->
                    resizedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                val newMat = Mat()
                Utils.bitmapToMat(resizedBitmap, newMat)
                Imgproc.cvtColor(newMat, newMat, Imgproc.COLOR_RGBA2GRAY)
                synchronized(dynamicTemplates) {
                    dynamicTemplates.add(newMat)
                    dynamicTemplateFiles.add(file)
                    templateSizes.add(Pair(newMat.width(), newMat.height()))
                }
                resizedBitmap.recycle()
                mainHandler.post {
                    Toast.makeText(applicationContext, "OK gimmick (${dynamicTemplates.size})", Toast.LENGTH_SHORT).show()
                    refreshControlUI()
                }
            } catch (e: Exception) {
                AppLogger.e("gimmick save fail", e)
            } finally {
                mainHandler.post {
                    isImageGrabberMode = false
                    isGrabberProcessing = false
                    overlayView?.let {
                        try {
                            val params = it.layoutParams as WindowManager.LayoutParams
                            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            windowManager.updateViewLayout(it, params)
                        } catch (ex: Exception) {}
                    }
                    refreshControlUI()
                }
            }
        }
    }'''

content = content[:start_idx] + NEW + content[end_idx:]

with open(FILE, "w", encoding="utf-8") as f:
    f.write(content)

print("PATCH_OK")
