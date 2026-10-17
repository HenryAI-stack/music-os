# CLAUDE.md – Music-os

Android-App (Kotlin, Jetpack Compose), die den gerade laufenden Song auf dem Handy erkennt und die Lyrics karaoke-synchron anzeigt. Privates Hobbyprojekt von Max; Antworten auf **Deutsch**. Die App ist **zweisprachig (Englisch/Deutsch)**.

## Grundprinzipien
- **Keine laufenden Kosten:** keine kostenpflichtigen APIs, keine API-Keys. Neue Dienste nur, wenn sie kostenlos und ohne Key nutzbar sind.
- **Keine zusätzlichen Libraries ohne Grund:** HTTP läuft über `HttpURLConnection` + `org.json` (beides im Android-SDK). Retrofit/OkHttp/Ktor nur, wenn es echten Nutzen bringt.
- **Nur private Nutzung:** Lyrics kommen von LRCLIB; eine Play-Store-Veröffentlichung wäre lizenzpflichtig.

## Sprachen
- Alle UI-Texte stehen in `res/values/strings.xml` (Englisch, Standard) und `res/values-de/strings.xml` (Deutsch). Neue Texte **immer in beiden Dateien** anlegen – keine festen Strings im Kotlin-Code.
- ViewModel/Logik liefern nur typisierte Meldungen (`MicMessage`, `UpdateMessage`, `RecognitionError`); übersetzt wird in der UI.
- Sprachwahl: `LanguageSettings` (SharedPreferences, angewendet in `MainActivity.attachBaseContext`, Wechsel per `recreate()`); Einstellungen über das Zahnrad oben rechts. `res/xml/locales_config.xml` aktiviert zusätzlich die System-Spracheinstellung pro App (Android 13+).

## Architektur
| Datei | Aufgabe |
|---|---|
| `media/NowPlayingRepository.kt` | Beobachtet aktive Media-Sessions über `MediaSessionManager` (braucht Benachrichtigungszugriff), wählt die spielende Session, liefert `StateFlow<NowPlaying?>` |
| `media/NowPlaying.kt` | Song-Metadaten + `currentPositionMs()`: Position wird aus `PlaybackState` (position, lastPositionUpdateTime, speed) hochgerechnet |
| `media/NowPlayingListener.kt` | Leerer `NotificationListenerService` – nur für die Berechtigung |
| `lyrics/LrcLibClient.kt` | lrclib.net: erst `/api/get` (exakt), dann `/api/search` mit bereinigtem Titel, dann Freitext `q`; bevorzugt `syncedLyrics`, wählt bei Suche nach Dauer-Nähe |
| `lyrics/LrcParser.kt` | LRC-Parsing (mehrere Zeitstempel pro Zeile, Word-Tags `<mm:ss.xx>` werden entfernt), `indexAt()` per Binärsuche |
| `recognition/ShazamSignature.kt` | Fingerabdruck-Generator, 1:1-Port von shazamio `algorithm.py`/`signature.py` (16 kHz, max. 12 s). Reines Kotlin → JVM-testbar |
| `recognition/ShazamClient.kt` | POST an `amp.shazam.com/discovery/v5/.../tag/{uuid}/{uuid}` (inoffiziell, kostenlos). `matches[0].offset` = Songposition beim Aufnahmestart → Karaoke-Sync |
| `recognition/MicRecognizer.kt` | AudioRecord (16 kHz, Fallback 48/44,1 kHz + `Resampler`), Erkennungsversuche nach 4/8/12 s parallel zur Aufnahme |
| `history/HistoryRepository.kt` | Verlauf als `history.json` in `filesDir` (max. 300, gleicher Song innerhalb 15 min wird nicht doppelt gespeichert); Löschen einzeln/alle; Tippen → `YouTubeLauncher` (YouTube-App per ACTION_SEARCH, Fallback Browser-Suche) |
| `update/UpdateChecker.kt` | Fragt `api.github.com/repos/HenryAI-stack/music-os/releases/latest`; Tag `v0.1.N` → versionCode N; Asset `*.apk` |
| `update/AppUpdater.kt` + `UpdateReceiver.kt` | Lädt APK direkt in eine `PackageInstaller`-Session; ab Android 12 `USER_ACTION_NOT_REQUIRED` (greift, sobald die App „Installer of record“ ist), sonst Bestätigungsdialog. Braucht `REQUEST_INSTALL_PACKAGES` |
| `MainViewModel.kt` | Mikro-Song (`packageName = MIC_SOURCE`) hat Vorrang vor Media-Session; lädt Lyrics bei Song-Wechsel (`trackKey` = artist\|title), Cache, Sync-Offset (±0,5 s, positiv = Text früher); Songdauer für Mikro-Songs kommt von LRCLIB |
| `ui/MusicOsApp.kt` | Compose-UI: Berechtigungs-Screen, Header mit Fortschritt, Karaoke-Liste (aktive Zeile oben im Drittel), Sync-Leiste |

Paket: `at.cloudplay.musicos` · minSdk 26 · targetSdk/compileSdk 35 · Theme immer dunkel.

## Build
- Lokal: `./gradlew assembleDebug` (JDK 17+, Android SDK nötig)
- CI: `.github/workflows/build.yml` baut bei jedem Push auf `main` und legt ein GitHub-Release `v0.1.<run_number>` mit `Music-os.apk` an. Fester Download-Link: `releases/latest/download/Music-os.apk`
- Versionierung: `versionCode`/`versionName` kommen aus `GITHUB_RUN_NUMBER` (lokal 1). Nicht manuell hochzählen.
- Commit-Nachrichten werden zu Release-Notes → aussagekräftig und auf Deutsch formulieren.
- Signierung: fester Debug-Key `app/debug.keystore` (Passwort `android`) – absichtlich im Repo, damit Updates drüberinstalliert werden können. Nicht entfernen.
- Versionen: AGP 8.10.1, Kotlin 2.2.0, Compose BOM 2025.05.01, Gradle 8.14.3

## Tests
- `./gradlew testDebugUnitTest` – u. a. `ShazamSignatureTest` vergleicht mit Referenzdaten aus shazamio (`src/test/resources/shazam/`, Generator `gen_golden.py`). Bei Änderungen am Algorithmus muss dieser Test grün bleiben.
- Live-Test gegen Shazam läuft nur mit `SHAZAM_LIVE_TEST=1` (in CI, blockiert Releases nicht).

## Bekannte Stolpersteine
- Release-Tags müssen dem Schema `v0.1.<run_number>` folgen, sonst findet der In-App-Updater keine Updates.
- Google Play Protect kann selbst gebaute APKs blockieren (sensible Berechtigungen). Workaround für Max: Play Protect kurz deaktivieren. Ab 2027 droht weltweit Pflicht-Entwickler-Registrierung für Sideloading.
- Android 13+: Bei sideloaded APKs ist der Benachrichtigungszugriff gesperrt, bis man in den App-Infos „Eingeschränkte Einstellungen zulassen“ aktiviert. Der Berechtigungs-Screen erklärt das.
- Manche Player liefern `lastPositionUpdateTime = 0` oder unsaubere Titel (YouTube). Beides wird abgefangen bzw. in `cleanTitle()` bereinigt.
- LRCLIB-Requests immer mit `User-Agent`-Header.

## Ideen / nächste Schritte
- Mikro-Modus: automatisch neu erkennen, wenn der Song zu Ende ist
- Wort-genaues Highlighting, wo LRCLIB Word-Timestamps liefert
- Lyrics-Cache auf Disk für Offline-Nutzung
