# BearJ3rk's Nerd Scanner V0.9

An Android app that scans Magic: The Gathering cards, looks them up on Scryfall, displays current printing prices, and opens the matching Scryfall page. It also supports manual fuzzy search by card name.

## V0.9 features

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
- Configurable 1–5 second pause after each successful camera scan
- More compact camera and result layout with resilient matched-card images
- Artwork-based printing guesses using cached Scryfall image fingerprints
- Automatic 300-entry artwork cache with a Settings option to clear it
- Identified Scryfall artwork requests with alternate-image fallbacks and visible errors
- Persistent 100 MB HTTP image cache shared by card display and artwork matching
- History for the last 50 successful scans with add-to-list actions
- Long-press printing and foil/non-foil editing in lists and history
- Cleaner USD-only pricing and more compact scan-result spacing

OCR provides a likely card-name match. Always verify the displayed set and collector number, especially for cards with many printings.

## Build

Push to `main` or run **Build Android APK** from GitHub Actions. Download the APK from the workflow run's **Artifacts** section.

Locally, with JDK 17 and the Android SDK installed:

```sh
gradle assembleRelease
```

The signed release APK is published on the GitHub Releases page by GitHub Actions.

Card data and images are provided by Scryfall. Magic: The Gathering is a trademark of Wizards of the Coast. This project is not affiliated with or endorsed by Scryfall or Wizards of the Coast.
