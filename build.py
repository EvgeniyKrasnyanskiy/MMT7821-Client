import os
import sys
import subprocess
import zipfile
import shutil

# Resolve Android SDK directory dynamically (from env or standard user path)
SDK_DIR = (
    os.environ.get("ANDROID_HOME")
    or os.environ.get("ANDROID_SDK_ROOT")
    or os.path.expanduser("~/AppData/Local/Android/Sdk")
)
BUILD_TOOLS = os.environ.get("BUILD_TOOLS") or os.path.join(SDK_DIR, "build-tools", "34.0.0")
PLATFORM_JAR = os.environ.get("PLATFORM_JAR") or os.path.join(SDK_DIR, "platforms", "android-34", "android.jar")

# Resolve JDK
JAVA_HOME = os.environ.get("JAVA_HOME")
if not JAVA_HOME:
    for default_path in [
        r"H:\Program Files\Microsoft\jdk-17.0.19.10-hotspot",
        r"C:\Program Files\Java\jdk-17",
        r"C:\Program Files\Microsoft\jdk-17",
    ]:
        if os.path.exists(default_path):
            JAVA_HOME = default_path
            break

JAVAC = os.path.join(JAVA_HOME, "bin", "javac.exe") if (JAVA_HOME and os.path.exists(os.path.join(JAVA_HOME, "bin", "javac.exe"))) else "javac"
DEBUG_KEYSTORE = os.environ.get("DEBUG_KEYSTORE") or os.path.expanduser("~/.android/debug.keystore")

AAPT2 = os.path.join(BUILD_TOOLS, "aapt2.exe")
D8 = os.path.join(BUILD_TOOLS, "d8.bat")
APKSIGNER = os.path.join(BUILD_TOOLS, "apksigner.bat")

APP_DIR = os.path.abspath("app")
BUILD_DIR = os.path.join(APP_DIR, "build")
GEN_DIR = os.path.join(BUILD_DIR, "gen")
CLASSES_DIR = os.path.join(BUILD_DIR, "classes")
DEX_DIR = os.path.join(BUILD_DIR, "dex")
OUTPUT_APK = os.path.abspath("MMT7821-Client.apk")

def run(cmd, desc):
    print(f"[*] {desc}...")
    res = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if res.returncode != 0:
        print(f"[-] ERROR during {desc}:")
        print(res.stdout)
        print(res.stderr)
        sys.exit(res.returncode)
    return res.stdout

def build():
    os.makedirs(GEN_DIR, exist_ok=True)
    os.makedirs(CLASSES_DIR, exist_ok=True)
    os.makedirs(DEX_DIR, exist_ok=True)

    # 1. Compile resources
    run(f'"{AAPT2}" compile --dir "{APP_DIR}/res" -o "{BUILD_DIR}/compiled_res.zip"', "Compiling resources")

    # 2. Link resources and generate R.java
    run(f'"{AAPT2}" link -I "{PLATFORM_JAR}" --manifest "{APP_DIR}/AndroidManifest.xml" --java "{GEN_DIR}" -o "{BUILD_DIR}/unaligned.apk" "{BUILD_DIR}/compiled_res.zip" --auto-add-overlay', "Linking resources")

    # 3. Compile Java sources
    java_files = [os.path.join(GEN_DIR, "com", "openmedtronic", "guardian", "R.java")]
    src_dir = os.path.join(APP_DIR, "src")
    for root, dirs, files in os.walk(src_dir):
        for f in files:
            if f.endswith(".java"):
                java_files.append(os.path.join(root, f))
    files_str = " ".join(f'"{f}"' for f in java_files)
    run(f'"{JAVAC}" -encoding UTF-8 -cp "{PLATFORM_JAR}" -d "{CLASSES_DIR}" {files_str}', "Compiling Java")

    # 4. Convert classes to dex with d8
    jar_path = os.path.join(BUILD_DIR, "classes.jar")
    with zipfile.ZipFile(jar_path, "w") as zf:
        for root, dirs, files in os.walk(CLASSES_DIR):
            for f in files:
                if f.endswith(".class"):
                    full_p = os.path.join(root, f)
                    rel_p = os.path.relpath(full_p, CLASSES_DIR)
                    zf.write(full_p, rel_p)
    run(f'"{D8}" --output "{DEX_DIR}" "{jar_path}"', "DEXing bytecode")

    # 5. Package dex into apk
    print("[*] Packaging classes.dex into APK...")
    unaligned_apk = os.path.join(BUILD_DIR, "unaligned.apk")
    unsigned_apk = os.path.join(BUILD_DIR, "app_unsigned.apk")
    shutil.copy2(unaligned_apk, unsigned_apk)

    with zipfile.ZipFile(unsigned_apk, "a") as z:
        dex_path = os.path.join(DEX_DIR, "classes.dex")
        z.write(dex_path, "classes.dex")

    # 6. Sign APK with apksigner
    run(f'"{APKSIGNER}" sign --ks "{DEBUG_KEYSTORE}" --ks-pass pass:android --ks-key-alias androiddebugkey --out "{OUTPUT_APK}" "{unsigned_apk}"', "Signing APK")
    print(f"[+] Successfully built and signed {OUTPUT_APK}")

if __name__ == "__main__":
    build()
