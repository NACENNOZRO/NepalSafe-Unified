#!/usr/bin/env python3
"""Build the modified native application using the installed Android SDK and JDK 17+."""
import os
from pathlib import Path
import shutil
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
project = root / 'android'
sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk:
    candidates = [Path.home()/'Library/Android/sdk', Path.home()/'Android/Sdk']
    if os.environ.get('LOCALAPPDATA'):
        candidates.append(Path(os.environ['LOCALAPPDATA'])/'Android/Sdk')
    sdk = next((str(p) for p in candidates if (p/'platforms/android-35/android.jar').is_file()), None)
if not sdk or not (Path(sdk)/'platforms/android-35/android.jar').is_file():
    sys.exit('Install Android SDK Platform 35 using Android Studio and set ANDROID_HOME to its SDK directory.')
if not shutil.which('java') and not os.environ.get('JAVA_HOME'):
    sys.exit('Install JDK 17+ or set JAVA_HOME to the Android Studio Java runtime.')
env = os.environ.copy()
env['ANDROID_HOME'] = str(Path(sdk).resolve())
env['ANDROID_SDK_ROOT'] = env['ANDROID_HOME']
command = ['cmd', '/c', 'gradlew.bat'] if os.name == 'nt' else ['bash', './gradlew']
subprocess.run(command + ['--no-daemon', 'testDebugUnitTest', 'lintDebug', 'assembleDebug'], cwd=project, env=env, check=True)
apk = project / 'app/build/outputs/apk/debug/app-debug.apk'
output = root / 'build-output'
output.mkdir(exist_ok=True)
shutil.copy2(apk, output/'NepalSafe-Integration-debug.apk')
print('Built:', output/'NepalSafe-Integration-debug.apk')
print('Install on a test phone and run the device checklist. A successful build is not an end-to-end certification.')
