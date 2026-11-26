# AutoClock: record & replay taps on a schedule

## Get the APK (no Android Studio needed)
1. Create a new GitHub repo and upload this folder (keep the `.github` folder).
2. Open the repo's **Actions** tab, run **Build APK** (or just push to `main`).
3. When it finishes, download the **AutoClock-apk** artifact, unzip it and install `app-debug.apk`
   on your phone (allow "install unknown apps").

Or: open this folder in Android Studio and choose Build > Build APK(s).

## First-time setup on the phone
1. Open AutoClock and tap **1. Enable accessibility service** > AutoClock > turn on.
2. Tap **2. Allow notifications & exact alarms**, then **3. Disable battery optimisation**.
   On Xiaomi/Oppo/Vivo/Samsung also allow Autostart and "no restrictions" for battery.
3. Keep location ON.

## Record
1. **+ New automation**, enter a name (e.g. Clock In), pick the time. Default days are Mon to Fri.
2. You are sent to the home screen and a floating bar appears (drag it by the red dot).
3. Open Zoho CRM and do the clock-in normally: tap today's date, tap Clock In, tap Yes.
4. At any screen, tap **Stop rule** and pick the text that should stop the run
   (e.g. the "out of 100m range" message). A "100m range" rule is added by default.
5. A tap on a popup (like the location dialog) is marked optional automatically.
   Use **Optional** to toggle the last step yourself.
6. Tap **Done**. Repeat for Clock Out at 6:30 PM.

## Replay
At the scheduled time you get a notification. Unlock the phone and tap **OK**.
The app checks location, opens Zoho, replays your taps, then watches 6 s for a stop rule.
You get a final notification: done, stopped (with the reason) or failed (with the step).

## Note on recording
While the floating bar is showing, a transparent layer records each tap and swipe and passes it on to the
app underneath, so the app behaves normally. Taps are saved by the button text/ID under your finger
(so they survive small layout changes), and by screen position if no text exists.
