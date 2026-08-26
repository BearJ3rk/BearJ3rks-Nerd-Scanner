# BearJ3rk's Nerd Scanner V0.5

An Android app that scans Magic: The Gathering cards, looks them up on Scryfall, displays current printing prices, and opens the matching Scryfall page. It also supports manual fuzzy search by card name.

## V0.5 features

- Camera scanning with on-device ML Kit OCR
- Manual card-name search
- Scryfall card image, printing, collector number, USD/foil/EUR prices, and link
- Conservative request throttling and an identifiable API user agent
- Last-result caching
- Printing picker with Scryfall set symbols, set names, and collector numbers
- Experimental camera set-symbol matching plus manual printing selection
- Gear-menu update checker using official GitHub releases
- Multiple persistent lists with foil/non-foil prices, quantity controls, and totals
- Stable signed release builds for Android updates
- Adaptive Android launcher icon based on the supplied scanner artwork

OCR provides a likely card-name match. Always verify the displayed set and collector number, especially for cards with many printings.

## Build

Push to `main` or run **Build Android APK** from GitHub Actions. Download the APK from the workflow run's **Artifacts** section.

Locally, with JDK 17 and the Android SDK installed:

```sh
gradle assembleRelease
```

The signed release APK is published on the GitHub Releases page by GitHub Actions.

Card data and images are provided by Scryfall. Magic: The Gathering is a trademark of Wizards of the Coast. This project is not affiliated with or endorsed by Scryfall or Wizards of the Coast.
