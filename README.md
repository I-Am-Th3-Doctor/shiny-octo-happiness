# Searchlight

A personal, search-first Android launcher modeled on Searcho. The home screen is your
wallpaper and ONE frosted home card: swipeable tiles (with page dots) on top, the search row
(magnifier, greeting, gear) fixed at the bottom. No dock, no app grid, ever.

Kotlin, plain Android Views, three small AndroidX libraries. No ads, no analytics, no tracking.
The only network traffic is the weather: Open-Meteo forecast + place search, with your location
rounded to ~1 km. Everything you search stays on the phone.

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
3. Gear → **Permissions**: turn on what you want searched or shown. Each switch either shows
   Android's prompt or opens the right Android settings page (switch it on there, press Back).
   - **Contacts, Messages, Music, Photos & videos, Location**: normal prompts.
   - **All files**: *Allow access to manage all files*. The first index runs in the background.
   - **Media controls** (Music card), **Do Not Disturb**, **Modify system settings** (brightness).
   - If Android says *Restricted setting* (common for sideloaded apps with Media controls or SMS):
     *Settings → Apps → Searchlight → ⋮ → Allow restricted settings*, then try again.
4. Gear → **Widget Center**: turn cards on/off, reorder them, choose the idle card, pick
   **°F / °C** (default °F), and set the weather location: device location, or a ZIP/city.
5. Back to your old launcher at any time: *Settings → Apps → Default apps → Home app*.

## Using it

| Do this | Get this |
|---|---|
| Tap the card (outside a tile), type | Tiles fold away; results appear above the card, best one nearest it |
| Enter | Opens the top (highlighted) result |
| `42 x 18` | `= 756` right in the bar and as the top result (tap to copy) |
| Type a name / number | Contacts, with call and message buttons |
| Type a song, artist or album | Songs (tap to play) |
| Type words from a text | Matching SMS, newest first |
| Type part of a file name | Any file in storage: PDFs, zips, docs, photos, music, APKs, folders |
| Nothing local matches | The top result is a web search (engine selectable in settings) |
| Long-press an app result | Add to / remove from favorites, App info, Uninstall |
| Long-press a file result | Open with…, Share, Open containing folder, Copy path |
| **Long-press the magnifier** | 3×2 grid of your six favorites |
| Swipe the tiles | Next tile (dots show where you are) |
| Turn tiles off in Widget Center | The card shrinks; all off = just the search row |
| Tap the weather card | Refresh (or set a location if none) |
| Long-press the weather card | Switch °F/°C instantly, refresh, weather settings |
| Start music anywhere | Music Player card jumps to the front (toggle in Widget Center) |
| Gear | Settings: Widget Center, permissions, greeting text, web engine, favorites |
| Back / Home | Clears the search and drops the keyboard |

## Phases

- **Phase 1 ✓:** HOME launcher, wallpaper + card pager + search pill (no dock); search across
  apps, contacts, all files, calculator, web fallback; six-favorites popup; permissions hub.
- **Phase 2 ✓ (this):** Widget Center + built-in cards: Weather (Open-Meteo direct; stored in °C,
  converted for display, so the persisted °F/°C setting always converts; default °F; device
  location or ZIP/city; refresh on unlock + every 30 min), Calendar & Date, Battery & Signals,
  Music Player (jumps to front when audio starts), Quick Actions (torch, volume, DND,
  brightness); idle-card picker. Search adds songs, photos & videos, and messages.
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
  search/BackgroundSearch.kt   Songs, photos, messages, files on one cancellable thread
  messages/, media/            SMS and MediaStore sources, thumbnails, media-session listener
  weather/Weather.kt           Pure Open-Meteo client logic, WMO codes, °F/°C (JVM-tested)
  weather/WeatherRepository    Location, fetch, refresh schedule, cache
  cards/                       Weather, Calendar & Date, Battery & Signals, Music, Quick Actions
  WidgetCenterActivity.kt      Cards on/off + order, idle card, weather units + location
  ui/                          Results adapter, favorites popup, intents, permission shortcuts
```
