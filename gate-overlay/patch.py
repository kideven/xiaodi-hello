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
     '        applicationId = "com.even.gateclient"\n        versionName = "0.7.69-gate5"'),
])

# Gate borrowed-path: expose a static protect() hook so the embedded
# VLESS underlay client can keep its socket out of the VPN tunnel.
edit("main/src/main/java/de/blinkt/openvpn/core/OpenVPNService.java", [
    ("public class OpenVPNService extends VpnService implements StateListener, Callback, ByteCountListener, IOpenVPNServiceInternal {",
     """public class OpenVPNService extends VpnService implements StateListener, Callback, ByteCountListener, IOpenVPNServiceInternal {

    // Gate: hook for the embedded underlay (borrowed path) client.
    public static volatile OpenVPNService gateInstance;

    public static boolean gateProtect(java.net.Socket socket) {
        OpenVPNService svc = gateInstance;
        if (svc == null)
            return false;
        try {
            return svc.protect(socket);
        } catch (Exception e) {
            return false;
        }
    }"""),
    ("    public void onCreate() {\n        super.onCreate();",
     "    public void onCreate() {\n        super.onCreate();\n        gateInstance = this;"),
])

# Gate zh-CN: two preferences in general_settings.xml have hardcoded
# English titles (not string resources); translate them in place.
edit("main/src/ui/res/xml/general_settings.xml", [
    ('android:title="OpenVPN 3 Core"', 'android:title="OpenVPN 3 内核"'),
    ('android:summaryOff="Use OpenVPN 2.x"', 'android:summaryOff="使用 OpenVPN 2.x"'),
    ('android:summaryOn="Use the C++ OpenVPN library (experimental)"',
     'android:summaryOn="使用 C++ 版 OpenVPN 库（实验性）"'),
    ('android:title="Use internal file browser"', 'android:title="使用内置文件浏览器"'),
    ('android:summary="Always use the very basic file browser instead of the Android file browser. Use this option if you have problems selecting files."',
     'android:summary="始终使用最基础的内置文件浏览器，而不是 Android 系统文件浏览器。选择文件有问题时开启此项。"'),
])

edit("main/src/main/res/values/strings.xml", [
    ('<string name="app">OpenVPN for Android</string>',
     '<string name="app">Gate 列表</string>'),
])

print("patch.py: all patches applied")
