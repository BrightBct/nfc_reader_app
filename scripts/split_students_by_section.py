#!/usr/bin/env python3
import argparse
import csv
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Split a student roster CSV into one private CSV per section.",
    )
    parser.add_argument("source", type=Path, help="Source roster CSV")
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("data/sections"),
        help="Output directory (default: data/sections)",
    )
    args = parser.parse_args()

    with args.source.open("r", encoding="utf-8-sig", newline="") as source_file:
        reader = csv.DictReader(source_file)
        headers = reader.fieldnames or []
        section_header = next(
            (header for header in headers if header.strip().lower() == "section"),
            None,
        )
        if section_header is None:
            raise SystemExit("The CSV does not contain a Section column.")
        rows = list(reader)

    args.output_dir.mkdir(parents=True, exist_ok=True)
    sections = sorted(
        {row.get(section_header, "").strip() for row in rows if row.get(section_header, "").strip()},
    )
    for section in sections:
        output_path = args.output_dir / f"students-section-{section}.csv"
        section_rows = [
            row for row in rows if row.get(section_header, "").strip() == section
        ]
        with output_path.open("w", encoding="utf-8-sig", newline="") as output_file:
            writer = csv.DictWriter(output_file, fieldnames=headers)
            writer.writeheader()
            writer.writerows(section_rows)
        print(f"{section}: {len(section_rows)} students -> {output_path}")


if __name__ == "__main__":
    main()
