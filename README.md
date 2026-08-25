# BearJ3rk's Nerd Scanner V0.2.1

An Android app that scans Magic: The Gathering cards, looks them up on Scryfall, displays current printing prices, and opens the matching Scryfall page. It also supports manual fuzzy search by card name.

## V0.2.1 features

- Camera scanning with on-device ML Kit OCR
- Manual card-name search
- Scryfall card image, printing, collector number, USD/foil/EUR prices, and link
- Conservative request throttling and an identifiable API user agent
- Last-result caching
- Printing picker with Scryfall set symbols, set names, and collector numbers
- Captured scan preview and improved system-navigation spacing
- Gear-menu update checker using official GitHub releases

OCR provides a likely card-name match. Always verify the displayed set and collector number, especially for cards with many printings.

## Build

Push to `main` or run **Build Android APK** from GitHub Actions. Download the APK from the workflow run's **Artifacts** section.

Locally, with JDK 17 and the Android SDK installed:

```sh
gradle assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Card data and images are provided by Scryfall. Magic: The Gathering is a trademark of Wizards of the Coast. This project is not affiliated with or endorsed by Scryfall or Wizards of the Coast.
