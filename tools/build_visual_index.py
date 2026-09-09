#!/usr/bin/env python3
"""Build a compact visual fingerprint index from Scryfall bulk card data."""

import argparse
import concurrent.futures
import gzip
import io
import json
import os
import time
import urllib.request

from PIL import Image

USER_AGENT = "BearJ3rksNerdScanner-VisualIndex/1.0"


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json,image/*;q=0.9,*/*;q=0.8"})
    with urllib.request.urlopen(request, timeout=45) as response:
        return response.read()


def fingerprint(data: bytes) -> tuple[int, int, int, int]:
    image = Image.open(io.BytesIO(data)).convert("L").resize((16, 16), Image.Resampling.LANCZOS)
    levels = list(image.getdata())
    mean = sum(levels) // len(levels)
    hashes = []
    for block in range(4):
        value = 0
        for bit, level in enumerate(levels[block * 64 : block * 64 + 64]):
            if level >= mean:
                value |= 1 << bit
        hashes.append(value)
    return tuple(hashes)


def existing_index(path: str) -> dict[str, tuple[int, int, int, int]]:
    if not path or not os.path.exists(path):
        return {}
    with gzip.open(path, "rt", encoding="utf-8") as source:
        return {parts[0]: tuple(int(value, 16) for value in parts[1:]) for line in source if len(parts := line.rstrip().split("\t")) == 5}


def card_art(card: dict) -> str | None:
    uri = card.get("image_uris", {}).get("art_crop")
    if uri:
        return uri
    for face in card.get("card_faces", []):
        uri = face.get("image_uris", {}).get("art_crop")
        if uri:
            return uri
    return None


def download_one(item: tuple[str, str]):
    card_id, url = item
    for attempt in range(3):
        try:
            return card_id, fingerprint(fetch(url))
        except Exception:
            if attempt == 2:
                return card_id, None
            time.sleep(1 + attempt)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="visual-index.tsv.gz")
    parser.add_argument("--existing")
    parser.add_argument("--workers", type=int, default=12)
    parser.add_argument("--limit", type=int)
    args = parser.parse_args()

    bulk = json.loads(fetch("https://api.scryfall.com/bulk-data"))
    artwork_data = next(item for item in bulk["data"] if item["type"] == "unique_artwork")
    download_uri = artwork_data.get("jsonl_download_uri") or artwork_data.get("download_uri")
    raw_cards = fetch(download_uri)
    if raw_cards[:2] == b"\x1f\x8b":
        raw_cards = gzip.decompress(raw_cards)
    decoded = raw_cards.decode("utf-8")
    cards = [json.loads(line) for line in decoded.splitlines() if line.strip()] if download_uri.endswith((".jsonl", ".jsonl.gz")) else json.loads(decoded)
    prior = existing_index(args.existing)
    # Identical art cannot distinguish reprints visually, so keep one representative
    # card ID per artwork. The app can still offer Change Set after recognition.
    art_to_id = {}
    for card in cards:
        art = card_art(card)
        if art and card["id"] not in prior:
            art_to_id.setdefault(art, card["id"])
    targets = [(card_id, art) for art, card_id in art_to_id.items()]
    if args.limit:
        targets = targets[: args.limit]

    results = dict(prior)
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        for index, (card_id, value) in enumerate(executor.map(download_one, targets), 1):
            if value is not None:
                results[card_id] = value
            if index % 1000 == 0:
                print(f"Processed {index}/{len(targets)} new card images", flush=True)

    with gzip.open(args.output, "wt", encoding="utf-8", compresslevel=9) as output:
        for card_id in sorted(results):
            output.write(card_id + "\t" + "\t".join(f"{value:016x}" for value in results[card_id]) + "\n")
    print(f"Wrote {len(results)} fingerprints to {args.output}")


if __name__ == "__main__":
    main()
