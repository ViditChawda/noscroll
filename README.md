# NoScroll

Android app that blocks Instagram Reels and YouTube Shorts. The rest of each app keeps working (feed, DMs, search, normal videos).

## ⬇️ Download
**[Download the latest NoScroll.apk](https://github.com/ViditChawda/noscroll/releases/latest/download/NoScroll.apk)**: always the newest build. Older builds are on the [Releases page](https://github.com/ViditChawda/noscroll/releases).

## Features
- Block Reels / Shorts (auto-exit the player) and cover their tab buttons
- Or allow them with **daily limits**: time limit and reels limit (Reels + Shorts together)
- Live counter pill at the top of the screen: this session's count, today's total, limit progress ring
- Weekly stats with per-app bar charts and week-over-week comparison

## How it works
An `AccessibilityService` (`BlockerService.kt`) watches only `com.instagram.android` and `com.google.android.youtube`. When it finds a view ID from the Reels/Shorts player on screen, it presses **Back** and shows a toast.

## Run it
1. Open this folder in Android Studio (Ladybug or newer) and let Gradle sync.
2. Plug in your phone (USB debugging on) and press ▶ Run.
3. In the app, tap **Turn on blocker** → Accessibility → NoScroll → On.
   - Android 13+ sideloaded apps: if the toggle is greyed out, go to App info → ⋮ → **Allow restricted settings**, then try again.
4. Open YouTube → Shorts, or Instagram → Reels. It should bounce you right out.

## When it breaks (after an IG/YT update)
1. In `BlockerService.kt`, set `DEBUG_DUMP = true` and run the app.
2. Open Reels/Shorts and watch Logcat with filter `NoScroll`.
3. Find the ID that only shows up in the player (e.g. `...:id/clips_viewer_view_pager`) and add it to `IG_REELS_IDS` / `YT_SHORTS_IDS`.

## Play Store notes
- You need the in-app disclosure dialog (already included) and the Accessibility declaration form in Play Console.
- Add a privacy policy that says no screen data is collected.
- Expect a stricter review than usual.
