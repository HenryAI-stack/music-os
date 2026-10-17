# 🎤 SingAlong

Android-App, die erkennt, welcher Song gerade auf dem Handy läuft (Spotify, YouTube Music, Apple Music, Deezer, …), und den passenden Liedtext karaoke-artig synchron anzeigt.

- **Song-Erkennung:** über Androids MediaSession-API, also ohne Mikrofon, ohne API-Key und ohne Kosten
- **Lyrics:** [LRCLIB](https://lrclib.net) (kostenlos, zeitgestempelte LRC-Texte)
- **Anzeige:** aktuelle Zeile hervorgehoben und automatisch mitscrollend, Feinjustierung ±0,5 s
- **Stack:** Kotlin, Jetpack Compose, Material 3, minSdk 26 (Android 8)

## APK bauen

### Variante 1: GitHub Actions (ohne lokale Installation)
1. Neues Repo anlegen, z. B. `HenryAI-stack/singalong`, und den Inhalt dieses Ordners hochladen (inkl. `.github/` und `app/debug.keystore`).
2. Bei jedem Push auf `main` baut der Workflow automatisch. Alternativ unter **Actions → Build APK → Run workflow** manuell starten.
3. Nach ca. 3–5 Minuten liegt die APK unter dem Lauf bei **Artifacts → SingAlong-apk** (als ZIP).

### Variante 2: Android Studio
Ordner öffnen, Gradle-Sync abwarten, ▶ Run.

## Installieren & einrichten
1. APK aufs Handy laden und installieren (Installation aus unbekannten Quellen erlauben).
2. App öffnen → **Zugriff erlauben** → SingAlong bei „Benachrichtigungszugriff“ aktivieren.
3. **Schalter ausgegraut?** (Android 13+, bei selbst installierten APKs): App-Infos → ⋮ → **Eingeschränkte Einstellungen zulassen**, dann Schritt 2 wiederholen.
4. Musik starten, und der Text läuft mit.

Updates lassen sich einfach drüberinstallieren, weil alle Builds mit demselben Debug-Schlüssel (`app/debug.keystore`) signiert sind.

## Aufbau
```
app/src/main/java/at/cloudplay/singalong/
├── MainActivity.kt              Einstieg, Bildschirm bleibt an
├── MainViewModel.kt             Song-Wechsel → Lyrics laden, Cache, Offset
├── media/
│   ├── NowPlaying.kt            Song + hochgerechnete Position
│   ├── NowPlayingRepository.kt  Beobachtet aktive Media-Sessions
│   └── NowPlayingListener.kt    Nötig für die Berechtigung
├── lyrics/
│   ├── LrcLibClient.kt          LRCLIB: exakte Abfrage → Suche → Freitext
│   └── LrcParser.kt             LRC parsen, aktive Zeile finden
└── ui/                          Compose-Oberfläche
```

## Grenzen
- Erkennt nur Musik, die **auf diesem Handy** läuft (Mikrofon-Erkennung folgt evtl. später).
- Manche Apps (z. B. YouTube im Browser) liefern unsaubere Titel; die App bereinigt typische Zusätze wie „(Official Video)“ oder „- Remastered“.
- Nicht jeder Song hat synchronisierte Lyrics auf LRCLIB. Dann wird der normale Text angezeigt.
- Nur für private Nutzung gedacht: Für eine Veröffentlichung im Play Store bräuchte man Lyrics-Lizenzen.
