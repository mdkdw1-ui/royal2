import re, os
BASE = os.path.expanduser("~/royal2")
SVC = f"{BASE}/app/src/main/java/com/example/helper/service/SolverService.kt"

with open(SVC, "r", encoding="utf-8") as f:
    s = f.read()

pattern = r'var rows = when \{\s*rowsRaw < 9\.5f -> 9.*?else -> Math\.round\(rowsRaw\)\.toInt\(\)\s*\}'

# replacement는 lambda로 넘겨 escape 해석 회피
def repl(m):
    return (
        'val rows = when {\n'
        '                rowsRaw < 9.7f -> 10\n'
        '                rowsRaw < 11.5f -> 11\n'
        '                else -> {\n'
        '                    AppLogger.d("⚠️ 비정상 rowsRaw=${"%.2f".format(rowsRaw)} → 검출 실패")\n'
        '                    return null\n'
        '                }\n'
        '            }'
    )

new_s, n = re.subn(pattern, repl, s, count=1, flags=re.DOTALL)
if n == 0:
    print("FAIL: rows pattern not matched")
    exit(1)
s = new_s
print(f"OK: P1 rows applied ({n} match)")

with open(SVC, "w", encoding="utf-8") as f:
    f.write(s)
print("SAVED")
