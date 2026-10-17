# 🎤 Music-os

Android-App, die erkennt, welcher Song gerade auf dem Handy läuft (Spotify, YouTube Music, Apple Music, Deezer, …), und den passenden Liedtext karaoke-artig synchron anzeigt.

- **Song-Erkennung (Handy):** über Androids MediaSession-API, ohne API-Key und ohne Kosten
- **Song-Erkennung (Mikrofon):** eigener Fingerabdruck-Algorithmus + inoffizielle Shazam-Schnittstelle (wie SongRec), kostenlos; danach läuft der Text synchron zur Musik aus der Umgebung
- **Lyrics:** [LRCLIB](https://lrclib.net) (kostenlos, zeitgestempelte LRC-Texte)
- **Verlauf:** alle erkannten Songs lokal am Handy, einzeln per 🗑 oder komplett löschbar
- **Updates:** die App findet neue Versionen selbst und installiert sie mit einem Tipp
- **Anzeige:** aktuelle Zeile hervorgehoben und automatisch mitscrollend, Feinjustierung ±0,5 s
- **Sprachen:** Englisch und Deutsch, umschaltbar über das ⚙️ Zahnrad oben rechts (Standard: Systemsprache)
- **Stack:** Kotlin, Jetpack Compose, Material 3, minSdk 26 (Android 8)

## APK herunterladen

Jeder Push auf `main` baut die App automatisch und veröffentlicht sie als GitHub-Release (`v0.1.<Build-Nummer>`).

**Neueste Version, immer derselbe Link (gut als Lesezeichen am Handy):**
https://github.com/HenryAI-stack/music-os/releases/latest/download/Music-os.apk

**Updates:** Nach der ersten Installation musst du nichts mehr herunterladen. Music-os prüft beim Start, ob es eine neue Version gibt, und zeigt dann „Aktualisieren“. Beim allerersten In-App-Update fragt Android einmal nach der Erlaubnis „Apps installieren“ und lässt dich bestätigen. Ab Android 12 laufen die folgenden Updates meist ohne weitere Bestätigung.

Alle Versionen: [Releases](https://github.com/HenryAI-stack/music-os/releases). Ein Build lässt sich auch manuell starten: **Actions → Build & Release APK → Run workflow**.

### Lokal bauen (Android Studio)
Ordner öffnen, Gradle-Sync abwarten, ▶ Run.

## Installieren & einrichten
1. APK aufs Handy laden und installieren (Installation aus unbekannten Quellen erlauben).
2. App öffnen → **Zugriff erlauben** → Music-os bei „Benachrichtigungszugriff“ aktivieren.
3. **Schalter ausgegraut?** (Android 13+, bei selbst installierten APKs): App-Infos → ⋮ → **Eingeschränkte Einstellungen zulassen**, dann Schritt 2 wiederholen.
4. Musik starten, und der Text läuft mit.

Updates lassen sich einfach drüberinstallieren, weil alle Builds mit demselben Debug-Schlüssel (`app/debug.keystore`) signiert sind.

## Aufbau
```
app/src/main/java/at/cloudplay/musicos/
├── MainActivity.kt              Einstieg, Bildschirm bleibt an
├── MainViewModel.kt             Song-Wechsel → Lyrics laden, Cache, Offset
├── media/
│   ├── NowPlaying.kt            Song + hochgerechnete Position
│   ├── NowPlayingRepository.kt  Beobachtet aktive Media-Sessions
│   └── NowPlayingListener.kt    Nötig für die Berechtigung
├── recognition/
│   ├── MicRecognizer.kt         Mikrofon-Aufnahme, Erkennung nach 4/8/12 s
│   ├── ShazamSignature.kt       Fingerabdruck (FFT, Peaks, Binärformat)
│   ├── ShazamClient.kt          Anfrage an Shazam, Antwort inkl. Song-Position
│   └── Resampler.kt             44,1/48 kHz → 16 kHz
├── history/HistoryRepository.kt Verlauf (history.json im App-Speicher)
├── update/                      In-App-Updates über GitHub-Releases
├── lyrics/
│   ├── LrcLibClient.kt          LRCLIB: exakte Abfrage → Suche → Freitext
│   └── LrcParser.kt             LRC parsen, aktive Zeile finden
└── ui/                          Compose-Oberfläche
```

## Grenzen
- Die Mikrofon-Erkennung nutzt eine **inoffizielle** Shazam-Schnittstelle. Sie ist kostenlos, kann aber jederzeit ohne Vorwarnung aufhören zu funktionieren. Übertragen wird nur ein Fingerabdruck, nie die Aufnahme.
- Bei Musik aus der Umgebung kann der Text leicht verschoben sein – dafür gibt es „Text früher/später“ und „Neu erkennen“.
- Manche Apps (z. B. YouTube im Browser) liefern unsaubere Titel; die App bereinigt typische Zusätze wie „(Official Video)“ oder „- Remastered“.
- Nicht jeder Song hat synchronisierte Lyrics auf LRCLIB. Dann wird der normale Text angezeigt.
- Nur für private Nutzung gedacht: Für eine Veröffentlichung im Play Store bräuchte man Lyrics-Lizenzen.
