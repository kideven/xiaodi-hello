#!/usr/bin/env python3
"""Patch a pristine ics-openvpn checkout (cwd = app/) into the Gate client:
- register GateListActivity as the LAUNCHER activity (main manifest)
- strip LAUNCHER categories from upstream MainActivity (ui manifest)
- applicationId -> com.even.gateclient, versionName suffix, app label
Idempotent-ish: fails loudly if anchors are missing.
"""
import sys

def edit(path, pairs):
    with open(path, encoding="utf-8") as f:
        s = f.read()
    for old, new in pairs:
        if old not in s:
            print(f"ANCHOR MISSING in {path}: {old[:80]!r}")
            sys.exit(1)
        s = s.replace(old, new, 1)
    with open(path, "w", encoding="utf-8") as f:
        f.write(s)
    print("patched", path)

GATE_ACTIVITY = """
        <activity
            android:name="com.even.gate.GateListActivity"
            android:exported="true"
            android:label="Gate 列表"
            android:theme="@android:style/Theme.Material.Light">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>"""

edit("main/src/main/AndroidManifest.xml", [
    ("    </application>", GATE_ACTIVITY),
])

edit("main/src/ui/AndroidManifest.xml", [
    ('                <category android:name="android.intent.category.LAUNCHER" />\n', ""),
    ('                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />\n', ""),
])

edit("main/build.gradle.kts", [
    ('        versionName = "0.7.69"',
     '        applicationId = "com.even.gateclient"\n        versionName = "0.7.69-gate1"'),
])

edit("main/src/main/res/values/strings.xml", [
    ('<string name="app">OpenVPN for Android</string>',
     '<string name="app">Gate 列表</string>'),
])

print("patch.py: all patches applied")
