# CleanStatusBar (libxposed API 102 module, scope: System UI)

Hides in the status bar:
- up/down data arrows (mobile + Wi-Fi)
- VoLTE / VoNR icon
- privacy green dot

Toggle each feature with the constants at the top of `MainHook.java`.
Needs LSPosed / Vector with libxposed API 102 support.
Build: push to GitHub, run the "Build APK" workflow, download the artifact.
Enable in LSPosed, scope "System UI", then reboot / restart System UI.
