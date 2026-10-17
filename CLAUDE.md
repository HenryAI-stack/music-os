# CLAUDE.md – Music-os

Android-App (Kotlin, Jetpack Compose), die den gerade laufenden Song auf dem Handy erkennt und die Lyrics karaoke-synchron anzeigt. Privates Hobbyprojekt von Max; Antworten und UI-Texte auf **Deutsch**.

## Grundprinzipien
- **Keine laufenden Kosten:** keine kostenpflichtigen APIs, keine API-Keys. Neue Dienste nur, wenn sie kostenlos und ohne Key nutzbar sind.
- **Keine zusätzlichen Libraries ohne Grund:** HTTP läuft über `HttpURLConnection` + `org.json` (beides im Android-SDK). Retrofit/OkHttp/Ktor nur, wenn es echten Nutzen bringt.
- **Nur private Nutzung:** Lyrics kommen von LRCLIB; eine Play-Store-Veröffentlichung wäre lizenzpflichtig.

## Architektur
| Datei | Aufgabe |
|---|---|
| `media/NowPlayingRepository.kt` | Beobachtet aktive Media-Sessions über `MediaSessionManager` (braucht Benachrichtigungszugriff), wählt die spielende Session, liefert `StateFlow<NowPlaying?>` |
| `media/NowPlaying.kt` | Song-Metadaten + `currentPositionMs()`: Position wird aus `PlaybackState` (position, lastPositionUpdateTime, speed) hochgerechnet |
| `media/NowPlayingListener.kt` | Leerer `NotificationListenerService` – nur für die Berechtigung |
| `lyrics/LrcLibClient.kt` | lrclib.net: erst `/api/get` (exakt), dann `/api/search` mit bereinigtem Titel, dann Freitext `q`; bevorzugt `syncedLyrics`, wählt bei Suche nach Dauer-Nähe |
| `lyrics/LrcParser.kt` | LRC-Parsing (mehrere Zeitstempel pro Zeile, Word-Tags `<mm:ss.xx>` werden entfernt), `indexAt()` per Binärsuche |
| `MainViewModel.kt` | Lädt Lyrics bei Song-Wechsel (`trackKey` = artist\|title), Cache, Sync-Offset (±0,5 s, positiv = Text früher) |
| `ui/MusicOsApp.kt` | Compose-UI: Berechtigungs-Screen, Header mit Fortschritt, Karaoke-Liste (aktive Zeile oben im Drittel), Sync-Leiste |

Paket: `at.cloudplay.musicos` · minSdk 26 · targetSdk/compileSdk 35 · Theme immer dunkel.

## Build
- Lokal: `./gradlew assembleDebug` (JDK 17+, Android SDK nötig)
- CI: `.github/workflows/build.yml` baut bei jedem Push auf `main` und legt ein GitHub-Release `v0.1.<run_number>` mit `Music-os.apk` an. Fester Download-Link: `releases/latest/download/Music-os.apk`
- Versionierung: `versionCode`/`versionName` kommen aus `GITHUB_RUN_NUMBER` (lokal 1). Nicht manuell hochzählen.
- Commit-Nachrichten werden zu Release-Notes → aussagekräftig und auf Deutsch formulieren.
- Signierung: fester Debug-Key `app/debug.keystore` (Passwort `android`) – absichtlich im Repo, damit Updates drüberinstalliert werden können. Nicht entfernen.
- Versionen: AGP 8.10.1, Kotlin 2.2.0, Compose BOM 2025.05.01, Gradle 8.14.3

## Bekannte Stolpersteine
- Android 13+: Bei sideloaded APKs ist der Benachrichtigungszugriff gesperrt, bis man in den App-Infos „Eingeschränkte Einstellungen zulassen“ aktiviert. Der Berechtigungs-Screen erklärt das.
- Manche Player liefern `lastPositionUpdateTime = 0` oder unsaubere Titel (YouTube). Beides wird abgefangen bzw. in `cleanTitle()` bereinigt.
- LRCLIB-Requests immer mit `User-Agent`-Header.

## Ideen / nächste Schritte
- Mikrofon-Modus für Musik aus der Umgebung (kostenlose Optionen begrenzt, siehe README)
- Wort-genaues Highlighting, wo LRCLIB Word-Timestamps liefert
- Lyrics-Cache auf Disk für Offline-Nutzung
