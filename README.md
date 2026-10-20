# Searchlight

A personal, search-first Android launcher modeled on Searcho. The home screen is your
wallpaper, a swipeable glance-card area, and one search pill. No dock, no app grid, ever.

Kotlin, plain Android Views, three small AndroidX libraries. No ads, no analytics, no tracking:
Phase 1 doesn't even request the INTERNET permission, so nothing can leave the phone.

## Get the APK (no Android Studio needed)

Every push builds a debug APK on GitHub Actions (`.github/workflows/build.yml`):

- **From your phone:** open
  <https://github.com/I-Am-Th3-Doctor/shiny-octo-happiness/releases/tag/debug-latest>
  and tap `searchlight-N.apk`.
- Or: **Actions** tab → latest *Build debug APK* run → *Artifacts* (a zip with the APK).

Builds are signed with the fixed debug key in `keystore/debug.keystore`, so each new APK installs
over the previous one and keeps your settings. It's a throwaway key for sideloading only.

## Install & set as default launcher

1. Download the APK on the phone and open it. Allow your browser to *install unknown apps* when
   asked, then **Install** (Play Protect: **More details → Install anyway**).
2. Press **Home** → pick **Searchlight** → **Always**. If you aren't asked: open Searchlight, tap
   the gear → **Set Searchlight as default home app** (or *Settings → Apps → Default apps → Home app*).
3. Gear → **Permissions**: turn on **Contacts** and **All files** (Android opens its own screen
   for All files access: switch *Allow access to manage all files* on, then go back).
   The first file index runs in the background; Settings shows its progress.
4. Back to your old launcher at any time: *Settings → Apps → Default apps → Home app*.

## Using it

| Do this | Get this |
|---|---|
| Tap the pill, type | Cards give way to results; the best one sits right above the pill |
| Enter | Opens the top (highlighted) result |
| `42 x 18` | `= 756` right in the bar and as the top result (tap to copy) |
| Type a name / number | Contacts, with call and message buttons |
| Type part of a file name | Any file in storage: PDFs, zips, docs, photos, music, APKs, folders |
| Nothing local matches | The top result is a web search (engine selectable in settings) |
| Long-press an app result | Add to / remove from favorites, App info, Uninstall |
| Long-press a file result | Open with…, Share, Open containing folder, Copy path |
| **Long-press the magnifier** | 3×2 grid of your six favorites |
| Swipe the card area | Next card (dots show where you are) |
| Gear | Settings: permissions, greeting text, web engine, favorites |
| Back / Home | Clears the search and drops the keyboard |

## Phases

- **Phase 1 (this):** HOME launcher, wallpaper + card pager + search pill (no dock); search across
  apps, contacts, all files, calculator, web fallback; six-favorites popup; permissions hub.
  The two Phase 1 cards (clock/date, tips) are placeholders for the card area.
- **Phase 2:** Widget Center + built-in cards: Weather (Open-Meteo direct, °F default with a
  °C/°F setting that converts and persists), Calendar & Date, Battery & Signals, Music Player
  (jumps to front when audio starts), Quick Actions; idle-card picker. Messages/music search.
- **Phase 3:** real Android widgets via AppWidgetHost: long-press → add, resize, remove.
- **Phase 4:** frosted glass tinted from the wallpaper, six search-bar styles, typography,
  gestures (accessibility: double-tap to lock), icon packs.

## Project layout

```
app/src/main/java/io/github/iamth3doctor/searchlight/
  SearchlightApp.kt            Owns Prefs + the three data sources
  MainActivity.kt              Home screen: cards, pill, results, menus
  SettingsActivity.kt          Default home, permissions hub, greeting, web engine, favorites
  apps/                        LauncherApps list (disk-cached for instant cold start), icons, launch
  contacts/ContactRepository   In-memory contacts (name, best number, photo), auto-reload
  files/FileIndex.kt           Pure-Kotlin storage walker, on-disk index, file matcher (JVM-tested)
  files/FileSearch.kt          Background indexing, MediaStore change watch, cancellable search
  search/FuzzyMatcher, Ranking Pure-Kotlin fuzzy scoring (JVM-tested)
  search/Calculator.kt         Pure-Kotlin expression parser (JVM-tested)
  search/SearchEngine.kt       Instant part of search: calc + apps + contacts
  ui/                          Results adapter, cards pager, favorites popup, intents
```
