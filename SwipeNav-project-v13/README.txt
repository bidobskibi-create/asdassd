./build.sh -> out/SwipeNav-flash.zip. Flash in TWRP over the installed ROM (no need to reflash the ROM). Reboot after.
(GitHub: push this folder and download the "SwipeNav-flash" artifact from the Actions run.)

v4 - editable gestures + force close
  * Settings > Gestures: tap Swipe left / Swipe right / Swipe up / Swipe up and hold (or far)
    and choose: Nothing, Back, Home, Recent apps, Force close app, Control panel.
    Defaults are unchanged (Back, Back, Home, Recent apps), so existing installs behave as before.
  * "Force close app" force-stops the app you are in (never the launcher, system UI or lock screen).
    It needs FORCE_STOP_PACKAGES, which is granted because the app is installed in /system/priv-app.
    If that is ever refused it falls back to Home + killBackgroundProcesses.
  * The pill turns red when a gesture is set to Force close app.
  * New files: Actions.java. Changed: GestureView, SwipeNavService, SettingsActivity, AndroidManifest
    (added GET_TASKS, FORCE_STOP_PACKAGES, KILL_BACKGROUND_PROCESSES).
