# Screenshots of the main screens

Renders real composables at the phone sizes the app targets (Robolectric, dark theme, Indonesian labels, which are the
longest). By default it only draws the screens. With reference images committed it can also fail when a screen
changes (see "Regression check" below). One scenario does fail on its own: `diagnosticsEmptyLog` throws if a tool screen with an empty log box can no longer be
laid out (the crash that force-closed Diagnostics, Build Matrix, SSH and ROM Studio).

## Run it with one click
GitHub > **Actions** > **Screenshots** > **Run workflow**. When it finishes, open the run and download the
**screenshots** artifact (a zip of PNGs named `<screen>-<size>.png`).

## Run it locally
```bash
gradle :app:testDebugUnitTest -PforgeScreenshots --tests "com.nextcraft.forge.ui.Shots*"
# images: app/build/screenshots/
# offline: add -PforgeRoboDir=<dir with android-all-instrumented-*.jar>
```

## Screens covered
Tools (with bottom bar), project header, Projects, Chat, Settings, approval sheet, chat change review, Diagnostics
with an empty log box, and the code viewer diff.

## Regression check (reference images)
`Screenshots` has a **mode** when you press Run workflow:

| mode | what it does |
|---|---|
| render | only draws the screens (default) |
| record | also writes reference images; download the **baselines** artifact and commit the PNGs to `app/src/screenshotTest/baselines/` |
| compare | fails if a screen differs from its reference by more than 0.5% of its pixels (per-channel tolerance 8/255); the **screenshot-diffs** artifact shows the changed pixels in red |

Only two sizes are kept as references (`6.3in-412x923` and `land-932x430`, set by `forge.screenshot.baselineLabels`) to
keep the repository small. Take references from the workflow itself, never from a phone, so fonts and rendering match.
When a screen changes on purpose: run **record**, commit the new baselines in the same PR as the UI change.

Limits: this is a manual workflow, not a required check, because rendering can still differ between runner images; if
compare starts failing without a UI change, re-record. Locally: add `-PforgeScreenshotMode=record` or `=compare` to the
command above.

## Inside the app: capture the screens for the agent
In a chat, tap **+** (attach) > **App screens (automatic)** > **Start**. The app opens ten screens one by one (about
10 seconds): the open project's Chat, Files, Terminal and Preview, then Projects, AI settings, Tools, Diagnostics,
Code viewer and Settings. Each is photographed with `PixelCopy` (so the terminal and web views are included), scaled to
at most 720 px wide, and saved in the project as `attachments/app-screens-<date-time>/NN-name.png`. The pictures are then
attached to the message being written; they only leave the phone when you press send. The screen you were on is
restored afterwards. Dialogs and menus are separate windows and are not part of the pictures.

Private data: the AI settings and Diagnostics screens show the signed-in account and the device model. Close or skip
anything you do not want the agent to see (remove the chips before sending).
