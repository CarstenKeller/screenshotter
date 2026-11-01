# Screenshotter (Android)

Overlay-Screenshots eines frei wählbaren Bildschirmbereichs – während die App darunter bedienbar bleibt.
Alle Aufnahmen einer Sitzung werden anschließend zu einem PDF zusammengefügt.

## Funktionsweise

- **Auswahlrahmen** als Overlay (`TYPE_APPLICATION_OVERLAY`). Im Aufnahmemodus ist das Fenster
  nicht berührbar (`FLAG_NOT_TOUCHABLE`, Deckkraft ≤ `maximumObscuringOpacityForTouch`), Touches
  gehen also an die App darunter. Der Rahmen wird außerhalb des Aufnahmebereichs gezeichnet.
- **Steuerleiste** (verschiebbar): 📷 Aufnehmen · Zähler · ↶ letzte löschen · ▭ Bereich ändern / ✓ übernehmen · PDF · ✕ Beenden.
- **Aufnahme** per `MediaProjection` → `VirtualDisplay` → `ImageReader`; Overlays werden für ~300 ms
  ausgeblendet, dann wird das aktuelle Bild auf den Rahmen zugeschnitten und als PNG im App-Speicher abgelegt.
- **PDF** mit `android.graphics.pdf.PdfDocument`:
  - *fortlaufend* (Standard): Aufnahmen untereinander auf A4-Seiten, lange Bilder werden auf Folgeseiten umbrochen;
    optional werden Überlappungen zwischen aufeinanderfolgenden Aufnahmen (beim Scrollen) per Zeilenvergleich entfernt (Heuristik, experimentell);
  - oder *eine Seite pro Aufnahme*.
- Ablage: ab Android 10 als Kopie in `Download/Screenshotter`, außerdem Öffnen/Teilen aus der App.
  Ungesicherte Aufnahmen bleiben nach dem Beenden erhalten und können in der App noch zu einem PDF gemacht oder verworfen werden.

## Voraussetzungen / Grenzen

- Android 8.0 (API 26) oder neuer.
- Berechtigung „Über anderen Apps einblenden“; bei jedem Start muss die Bildschirmaufnahme bestätigt werden (Systemvorgabe).
- Ab Android 14 wird die Aufnahme des gesamten Bildschirms angefordert (nicht nur einer App).
- Als geschützt markierte Inhalte (`FLAG_SECURE`, z. B. Banking-Apps, DRM) erscheinen schwarz.
- Die Überlappungserkennung funktioniert nur bei pixelgleichem Inhalt (normales Scrollen). Mitlaufende
  Kopfzeilen, Animationen oder Einblendungen im Bereich können sie verhindern – dann werden die Bilder einfach aneinandergehängt.

## Bauen

```
./gradlew assembleDebug
```

Die APK liegt danach unter `app/build/outputs/apk/debug/`. Jeder Push baut sie außerdem per GitHub Actions
(Artefakt `screenshotter-debug-apk`).
