#!/usr/bin/env python3
"""Checks every app/src/main/assets/i18n/<lang>.json against i18n/source.json:
all English keys present, same {n} placeholders, no empty values."""
import json, pathlib, re, sys
root = pathlib.Path(__file__).resolve().parent.parent
src = json.loads((root / "i18n" / "source.json").read_text(encoding="utf-8"))
ph = re.compile(r"\{\d+\}")
bad = 0
files = sorted((root / "app/src/main/assets/i18n").glob("*.json"))
for f in files:
    t = json.loads(f.read_text(encoding="utf-8"))
    missing = [k for k in src if k not in t]
    extra = [k for k in t if k not in src]
    wrong = [k for k in src if k in t and sorted(ph.findall(k)) != sorted(ph.findall(t[k]))]
    empty = [k for k in t if not str(t[k]).strip()]
    if missing or wrong or empty:
        bad += 1
    print(f"{f.stem:6} {len(t):4} ok | missing {len(missing)} extra {len(extra)} placeholder {len(wrong)} empty {len(empty)}")
    for k in (missing + wrong)[:5]:
        print("    ", repr(k))
print(f"{len(files)} languages, {len(src)} texts")
sys.exit(1 if bad else 0)
