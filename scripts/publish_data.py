#!/usr/bin/env python3
"""Builds the GitHub Pages site the app downloads its place and hub library from.

The source of truth is app/src/main/assets/places.json and hubs.json (the same files are
built into the app as its offline fallback). This script checks them, then writes
site/data/v1/{places,hubs,meta}.json. meta.json's "version" is a hash of the content, so the
app only downloads again when something actually changed.
"""
import datetime
import hashlib
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
OUT = ROOT / "site" / "data" / "v1"


def check_places(places):
    errors = []
    ids = set()
    for p in places:
        pid = p.get("id")
        if not pid or pid in ids:
            errors.append(f"missing or duplicate id: {pid}")
        ids.add(pid)
        for key in ("name", "province", "description"):
            if not p.get(key, {}).get("th") or not p.get(key, {}).get("en"):
                errors.append(f"{pid}: {key} needs both th and en")
        if not (5 <= p.get("lat", 0) <= 21 and 97 <= p.get("lon", 0) <= 106):
            errors.append(f"{pid}: lat/lon outside Thailand")
        for h in p.get("highlights", []):
            if any(m < 1 or m > 12 for m in h.get("months", [])):
                errors.append(f"{pid}: highlight month out of range")
    return errors


def check_hubs(hubs):
    errors = []
    for h in hubs:
        if h.get("type") not in ("AIRPORT", "BUS", "TRAIN", "PIER"):
            errors.append(f"{h.get('id')}: unknown type {h.get('type')}")
    return errors


def main():
    places_text = (ASSETS / "places.json").read_text(encoding="utf-8")
    hubs_text = (ASSETS / "hubs.json").read_text(encoding="utf-8")
    flights_text = (ASSETS / "flights.json").read_text(encoding="utf-8")
    routes_text = (ASSETS / "routes.json").read_text(encoding="utf-8")
    routes = json.loads(routes_text).get("routes", [])
    places, hubs, flights = json.loads(places_text), json.loads(hubs_text), json.loads(flights_text)
    errors = check_places(places) + check_hubs(hubs)
    airlines = {a["code"] for a in flights.get("airlines", [])}
    airports = {h["id"] for h in hubs if h.get("type") == "AIRPORT"}
    for r in flights.get("routes", []):
        for end in (r.get("a"), r.get("b")):
            if end not in airports:
                errors.append(f"flight route {r.get('a')}-{r.get('b')}: unknown airport {end}")
        for code in r.get("airlines", []):
            if code not in airlines:
                errors.append(f"flight route {r.get('a')}-{r.get('b')}: unknown airline {code}")
    hub_ids = {h["id"] for h in hubs}
    for r in routes:
        for hid in r.get("stops", []):
            if hid not in hub_ids:
                errors.append(f"route {r.get('id')}: unknown hub {hid}")
        if r.get("mode") not in ("FLIGHT", "TRAIN", "BUS", "VAN", "BOAT"):
            errors.append(f"route {r.get('id')}: unknown mode {r.get('mode')}")
        for t in r.get("trips", []):
            if len(t.get("times", [])) != len(r.get("stops", [])):
                errors.append(f"route {r.get('id')}: a trip has {len(t.get('times', []))} times for {len(r.get('stops', []))} stops")
    for p in places:
        for hid in p.get("hubIds", []):
            if hid not in hub_ids:
                errors.append(f"{p['id']}: unknown hub {hid}")
    if errors:
        print("\n".join(errors))
        sys.exit(1)

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "places.json").write_text(places_text, encoding="utf-8")
    (OUT / "hubs.json").write_text(hubs_text, encoding="utf-8")
    (OUT / "flights.json").write_text(flights_text, encoding="utf-8")
    (OUT / "routes.json").write_text(routes_text, encoding="utf-8")
    version = hashlib.sha256((places_text + hubs_text + flights_text + routes_text).encode("utf-8")).hexdigest()[:16]
    meta = {
        "version": version,
        "updatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "places": len(places),
        "hubs": len(hubs),
        "flightRoutes": len(flights.get("routes", [])),
        "routes": len(routes),
    }
    (OUT / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    (ROOT / "site" / "index.html").write_text(
        "<!doctype html><meta charset='utf-8'><title>Thai Trip data</title>"
        f"<p>Data for the Thai Trip Planner app. Version {version}, {len(places)} places, {len(hubs)} hubs.</p>",
        encoding="utf-8",
    )
    print(json.dumps(meta, ensure_ascii=False))


if __name__ == "__main__":
    main()
