#!/usr/bin/env python3
import re
import sys

VERSION_FILE = "version.txt"
GRADLE_FILE = "app/build.gradle.kts"

# 1. 현재 버전 읽기
try:
    with open(VERSION_FILE, "r") as f:
        current = f.read().strip()
except FileNotFoundError:
    current = "1.001"

# 2. 파싱 (major.minor)
m = re.match(r'^(\d+)\.(\d{3})$', current)
if not m:
    print(f"❌ 잘못된 버전 형식: {current} (예: 1.001)")
    sys.exit(1)

major = int(m.group(1))
minor = int(m.group(2))

# 3. 증가 (minor 999 초과 시 major++)
minor += 1
if minor > 999:
    major += 1
    minor = 1

new_version = f"{major}.{minor:03d}"
version_code = major * 1000 + minor

# 4. version.txt 저장
with open(VERSION_FILE, "w") as f:
    f.write(new_version)

# 5. build.gradle.kts 업데이트
with open(GRADLE_FILE, "r", encoding="utf-8") as f:
    content = f.read()

content = re.sub(r'versionCode\s*=\s*\d+', f'versionCode = {version_code}', content)
content = re.sub(r'versionName\s*=\s*"[^"]*"', f'versionName = "{new_version}"', content)

with open(GRADLE_FILE, "w", encoding="utf-8") as f:
    f.write(content)

print(f"✅ 버전 업데이트: {current} → {new_version} (versionCode: {version_code})")
