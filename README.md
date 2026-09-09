# BearJ3rk's Nerd Scanner V0.14

An Android app that scans Magic: The Gathering cards, looks them up on Scryfall, displays current printing prices, and opens the matching Scryfall page. It also supports manual fuzzy search by card name.

## V0.14 features

- Camera scanning with on-device ML Kit OCR
- Manual card-name search
- Scryfall card image, printing, collector number, USD/foil/EUR prices, and link
- Conservative request throttling and an identifiable API user agent
- Last-result caching
- Printing picker with Scryfall set symbols, set names, and collector numbers
- Experimental camera set-symbol matching plus manual printing selection
- Gear-menu updater that downloads GitHub release APKs inside the app
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
- Background artwork guessing without technical match-status messages
- Shorter Search tab label with consistently aligned navigation buttons
- Removes the scanner status row and unnecessary temporary Toast overlays
- Brief confirmation when a history card is added to the active list
- Artwork-first scanning against the learned image cache, with OCR fallback
- Edit Lists menu for creating and deleting lists
- Tighter spacing between matched artwork, card details, and USD prices
- Sticky elevated result footer with Add to List, Open Scryfall, and Change Set

OCR provides a likely card-name match. Always verify the displayed set and collector number, especially for cards with many printings.

## Build

Push to `main` or run **Build Android APK** from GitHub Actions. Download the APK from the workflow run's **Artifacts** section.

Locally, with JDK 17 and the Android SDK installed:

```sh
gradle assembleRelease
```

The signed release APK is published on the GitHub Releases page by GitHub Actions.

Card data and images are provided by Scryfall. Magic: The Gathering is a trademark of Wizards of the Coast. This project is not affiliated with or endorsed by Scryfall or Wizards of the Coast.
