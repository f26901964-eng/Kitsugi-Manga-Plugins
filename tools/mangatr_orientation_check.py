#!/usr/bin/env python3
"""Compare corrected Manga-TR pages with their 180-degree counterparts using OCR."""
from __future__ import annotations

import argparse
import csv
import io
import json
import math
import re
import subprocess
from pathlib import Path

from PIL import Image, ImageDraw

MODULE = Path("keiyoushi-extensions-tr/src/tr/mangatr")
DEFAULT_INPUT = MODULE / "build/mangatr-live"
DEFAULT_OUTPUT = MODULE / "build/mangatr-orientation"


def ocr(image: Image.Image, work: Path, stem: str) -> dict[str, object]:
    path = work / f"{stem}.png"
    image.save(path, format="PNG")
    result = subprocess.run(
        ["tesseract", str(path), "stdout", "-l", "tur+eng", "--psm", "11", "tsv"],
        check=True,
        capture_output=True,
        text=True,
        timeout=90,
    )
    rows = csv.DictReader(io.StringIO(result.stdout), delimiter="\t")
    words: list[str] = []
    score = 0.0
    confidences: list[float] = []
    for row in rows:
        text = (row.get("text") or "").strip()
        if len(text) < 2 or not re.search(r"[\w]", text, re.UNICODE):
            continue
        try:
            confidence = float(row.get("conf") or -1)
        except ValueError:
            continue
        if confidence < 0:
            continue
        words.append(text)
        confidences.append(confidence)
        score += confidence * min(len(text), 20)
    return {
        "score": round(score, 2),
        "words": len(words),
        "mean_confidence": round(sum(confidences) / len(confidences), 2) if confidences else 0,
        "excerpt": " ".join(words)[:240],
    }


def contact_sheet(results: list[dict[str, object]], output: Path) -> None:
    tile_width = 360
    label_height = 54
    rows = []
    for result in results:
        corrected = Image.open(Path(str(result["corrected_path"]))).convert("RGB")
        rotated = corrected.transpose(Image.Transpose.ROTATE_180)
        scale = min(tile_width / corrected.width, 500 / corrected.height)
        size = (max(1, int(corrected.width * scale)), max(1, int(corrected.height * scale)))
        tile_height = size[1] + label_height
        row = Image.new("RGB", (tile_width * 2 + 30, tile_height + 10), "white")
        draw = ImageDraw.Draw(row)
        label = (
            f"{result['reader']} page {result['page']} | "
            f"OCR {result['corrected']['score']} vs {result['rotated']['score']}"
        )
        draw.text((8, 6), label, fill="black")
        draw.text((8, 23), "CORRECTED", fill="black")
        draw.text((tile_width + 38, 23), "ROTATED 180", fill="black")
        row.paste(corrected.resize(size), (0, label_height))
        row.paste(rotated.resize(size), (tile_width + 30, label_height))
        rows.append(row)

    sheet = Image.new("RGB", (tile_width * 2 + 30, sum(row.height for row in rows)), "#dddddd")
    y = 0
    for row in rows:
        sheet.paste(row, (0, y))
        y += row.height
    sheet.save(output, quality=88, optimize=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--expected", type=int, default=12)
    parser.add_argument("--minimum-text-samples", type=int, default=3)
    args = parser.parse_args()
    paths = sorted(args.input.glob("reader-*/page-*.png"))
    if len(paths) != args.expected:
        raise SystemExit(f"Expected {args.expected} corrected chapter-one samples, found {len(paths)}")
    args.output.mkdir(parents=True, exist_ok=True)

    results: list[dict[str, object]] = []
    for path in paths:
        reader = path.parent.name.removeprefix("reader-")
        page = int(path.stem.removeprefix("page-"))
        with Image.open(path) as source:
            corrected = source.convert("RGB")
            corrected.load()
        rotated = corrected.transpose(Image.Transpose.ROTATE_180)
        stem = f"reader-{reader}-page-{page}"
        corrected_ocr = ocr(corrected, args.output, f"{stem}-corrected")
        rotated_ocr = ocr(rotated, args.output, f"{stem}-rotated")
        result = {
            "reader": reader,
            "page": page,
            "corrected_path": str(path),
            "corrected": corrected_ocr,
            "rotated": rotated_ocr,
        }
        results.append(result)
        print(
            f"reader {reader} page {page}: corrected score={corrected_ocr['score']} "
            f"words={corrected_ocr['words']}; rotated score={rotated_ocr['score']} "
            f"words={rotated_ocr['words']}"
        )
        if corrected_ocr["excerpt"]:
            print(f"  corrected OCR: {corrected_ocr['excerpt']}")

    eligible = [
        result for result in results
        if max(result["corrected"]["score"], result["rotated"]["score"]) >= 400
        and max(result["corrected"]["words"], result["rotated"]["words"]) >= 4
    ]
    corrected_wins = sum(result["corrected"]["score"] > result["rotated"]["score"] * 1.1 for result in eligible)
    rotated_wins = sum(result["rotated"]["score"] > result["corrected"]["score"] * 1.1 for result in eligible)
    corrected_total = sum(result["corrected"]["score"] for result in eligible)
    rotated_total = sum(result["rotated"]["score"] for result in eligible)
    required_wins = max(args.minimum_text_samples, math.ceil(len(eligible) * 0.7))
    summary = {
        "samples": len(results),
        "text_rich_samples": len(eligible),
        "corrected_wins": corrected_wins,
        "rotated_wins": rotated_wins,
        "required_corrected_wins": required_wins,
        "corrected_total_score": round(corrected_total, 2),
        "rotated_total_score": round(rotated_total, 2),
        "passed": (
            len(eligible) >= args.minimum_text_samples
            and corrected_wins >= required_wins
            and corrected_wins > rotated_wins
            and corrected_total >= rotated_total * 1.25
        ),
        "results": results,
    }
    report = args.output / "orientation-report.json"
    report.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    contact_sheet(results, args.output / "orientation-comparison.jpg")
    print(json.dumps({key: value for key, value in summary.items() if key != "results"}, ensure_ascii=False))
    if not summary["passed"]:
        raise SystemExit("Corrected pages did not pass the OCR orientation comparison")


if __name__ == "__main__":
    main()
