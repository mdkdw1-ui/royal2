import os
from PIL import Image, ImageFile

# 깨지거나 불안정한 이미지 포맷도 강제로 읽도록 설정
ImageFile.LOAD_TRUNCATED_IMAGES = True

def generate_icons():
    source_file = 'icon01.png'
    
    if not os.path.exists(source_file):
        print(f"❌ 오류: {source_file} 파일이 존재하지 않습니다.")
        return

    try:
        # 포맷을 PNG로 한정하지 않고 모든 이미지 포맷으로 강제 오픈 시도
        with Image.open(source_file) as img:
            # 안전하게 RGB나 RGBA 모드로 변경하여 처리
            if img.mode not in ('RGB', 'RGBA'):
                img = img.convert('RGBA')
                
            # 안드로이드 해상도별 크기 정의
            sizes = {
                'mipmap-mdpi': 48,
                'mipmap-hdpi': 72,
                'mipmap-xhdpi': 96,
                'mipmap-xxhdpi': 144,
                'mipmap-xxxhdpi': 192
            }
            
            base_path = 'app/src/main/res'
            
            for folder, size in sizes.items():
                target_dir = os.path.join(base_path, folder)
                os.makedirs(target_dir, exist_ok=True)
                
                # 이미지 크기 조절 후 저장
                resized_img = img.resize((size, size), Image.Resampling.LANCZOS)
                resized_img.save(os.path.join(target_dir, 'ic_launcher.png'), 'PNG')
                print(f"▶ 생성 완료: {target_dir}/ic_launcher.png ({size}x{size})")
                
            print("\n🎉 모든 앱 아이콘이 성공적으로 생성되었습니다!")
            
    except Exception as e:
        print(f"❌ 오류 발생: 코드를 수정해도 이미지 내부가 손상되어 읽을 수 없습니다. ({e})")

if __name__ == "__main__":
    generate_icons()
