import zipfile
import os

apk_path = "uptodown-com.yousician.ukulele.apk"
with zipfile.ZipFile(apk_path, 'r') as zip_ref:
    namelist = zip_ref.namelist()
    
    print("Files under lib/:")
    lib_files = [n for n in namelist if n.startswith("lib/")]
    for f in lib_files[:40]:
        print(f"  {f}")
    if len(lib_files) > 40:
        print(f"  ... and {len(lib_files) - 40} more lib files")
        
    print("\nFiles under assets/:")
    assets_files = [n for n in namelist if n.startswith("assets/")]
    for f in assets_files[:40]:
        print(f"  {f}")
    if len(assets_files) > 40:
        print(f"  ... and {len(assets_files) - 40} more asset files")
