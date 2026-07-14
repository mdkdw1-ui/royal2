import os
from PIL import Image

def generate_android_icons(source_path="icon01.png", res_base_path="app/src/main/res"):
    if not os.path.exists(source_path):
        print(f"❌ 오류: 프로젝트 루트 디렉토리에 '{source_path}' 파일이 없습니다!")
        print("GitHub 루트에 있는 icon01.png 파일을 로컬 프로젝트의 최상위 루트 디렉토리로 다운로드해 주세요.")
        return

    # 안드로이드 표준 해상도별 mipmap 폴더 및 타겟 해상도 지정
    sizes = {
        "mipmap-mdpi": (48, 48),
        "mipmap-hdpi": (72, 72),
        "mipmap-xhdpi": (96, 96),
        "mipmap-xxhdpi": (144, 144),
        "mipmap-xxxhdpi": (192, 192)
    }

    try:
        # 원본 이미지 불러오기
        img = Image.open(source_path)
        print(f"원본 파일 로드 완료: {source_path} ({img.size[0]}x{img.size[1]})")
        
        # 투명도(Alpha 채널) 보존을 위해 RGBA로 강제 변환
        if img.mode != 'RGBA':
            img = img.convert('RGBA')

        for folder, size in sizes.items():
            # 폴더 자동 생성 (예: app/src/main/res/mipmap-xxhdpi)
            folder_path = os.path.join(res_base_path, folder)
            os.makedirs(folder_path, exist_ok=True)
            
            # 고품질 Lanczos 보간법을 사용하여 이미지 리사이즈
            resized_img = img.resize(size, Image.Resampling.LANCZOS)
            target_path = os.path.join(folder_path, "ic_launcher.png")
            resized_img.save(target_path, "PNG")
            print(f"▶ 생성 완료: {target_path} ({size[0]}x{size[1]})")
            
        print("\n🎉 모든 해상도의 앱 아이콘이 성공적으로 생성 및 대체되었습니다!")
    except Exception as e:
        print(f"❌ 오류 발생: {e}")

if __name__ == "__main__":
    generate_android_icons()
