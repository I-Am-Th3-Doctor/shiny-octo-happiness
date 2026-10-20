# Searchlight

A personal, search-first Android launcher. The whole home screen is one search bar:
press Home, type two letters, hit Enter.

Kotlin, plain Android Views, no Compose/Flutter, two AndroidX dependencies. No ads, no analytics,
no network access in Phase 1 (the APK does not even request the INTERNET permission).

## Get the APK (no Android Studio needed)

Every push builds a debug APK on GitHub Actions (`.github/workflows/build.yml`):

- **Easiest, from your phone:** open the repo's **Releases** page →
  **Searchlight debug build #N** (tag `debug-latest`) → tap `searchlight-N.apk`.
- Or: **Actions** tab → latest *Build debug APK* run → *Artifacts* → `searchlight-debug-N`
  (a zip containing the APK).

The APK is signed with a fixed debug key checked in at `keystore/debug.keystore`, so every new
build installs straight over the previous one and keeps your favorites and default-launcher
setting. It's a throwaway debug key, fine for sideloading; never reuse it for anything published.

## Install & set as default launcher

1. Download the APK on the phone and tap it. The first time, Android asks you to allow your browser
   (or Files app) to *install unknown apps* — allow it, go back, tap **Install**.
   Play Protect may warn about an unknown developer: **More details → Install anyway**.
2. Press **Home**. Android asks which home app to use → pick **Searchlight** → **Always**.
   If it doesn't ask: *Settings → Apps → Default apps → Home app → Searchlight*,
   or open Searchlight from your old launcher, type `sea`, open **Searchlight settings** →
   **Set Searchlight as default home app**.
3. To go back to your old launcher: same *Default apps → Home app* screen.

## Using it

| Do this | Get this |
|---|---|
| Press Home | Fresh search, keyboard up |
| Type `gm` + Enter | Opens the top (highlighted) result |
| Long-press a result | Add to / remove from favorites, App info, Uninstall |
| Tap a favorite under the bar | Opens it |
| Long-press a favorite | Remove, move left/right, App info |
| **Long-press the magnifier** | 3×2 grid of your six favorites |
| Long-press empty space | Launcher settings, change default home app |
| Type `sea` | *Searchlight settings* (reorder/remove favorites, keyboard toggle) |
| Back | Clears the search |

Ranking: exact → prefix → punctuation-insensitive prefix (`wifi` → *Wi-Fi Analyzer*) → later word
(`maps` → *Google Maps*) → initials/camelCase (`yt` → *YouTube*) → substring → subsequence → one
typo (`setings`). Inside a tier, apps you launch more often win. Unit-tested in
`app/src/test/.../RankingTest.kt`.

## Roadmap

- **Phase 1 (this):** HOME launcher, search bar, fuzzy app search + launch, favorites row,
  long-press-magnifier favorites grid, settings. Foldable-aware layout.
- **Phase 2:** contacts + files in search, web fallback, calculator (`42 x 18`), glance
  (time/date/battery), weather via Open-Meteo with a persisted °F/°C setting (default °F),
  device location with ZIP/city fallback, refresh on unlock + every 30 min.
- **Phase 3:** AppWidgetHost widgets (long-press to add/resize/remove), quick actions
  (torch, Wi-Fi, Bluetooth, DND, brightness), gestures (swipe down = notifications,
  double-tap = lock via accessibility service), icon packs.

## Project layout

```
app/src/main/java/io/github/iamth3doctor/searchlight/
  SearchlightApp.kt        Application: owns Prefs + AppRepository
  MainActivity.kt          Home screen
  SettingsActivity.kt      Favorites, keyboard toggle, set-as-default
  apps/AppEntry.kt         One launchable activity (per user profile)
  apps/AppRepository.kt    LauncherApps list, disk cache for instant cold start, icon cache, launch
  data/Prefs.kt            Favorites (max 6, ordered), launch counts, settings
  search/FuzzyMatcher.kt   Pure-Kotlin scorer (JVM unit-tested)
  search/Ranking.kt        Score + usage boost + tie-breaks
  search/SearchEngine.kt   Entry point the UI calls (Phase 2 adds more sources here)
  ui/ResultsAdapter.kt     Result list
  ui/FavoritesPopup.kt     Long-press-magnifier grid
  ui/MaxWidthLinearLayout.kt  Keeps a centered column on the unfolded screen
```
