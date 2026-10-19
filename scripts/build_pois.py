#!/usr/bin/env python3
"""Builds the nearby-places files the app shows for each tourist place: restaurants, cafés,
hospitals, police stations and fire stations, with phone numbers and photos where known.

Runs in GitHub Actions (weekly). Reads the whole-Thailand OpenStreetMap extract from Geofabrik
(free, ODbL) with pyosmium, so it does not depend on busy public Overpass servers. Adds photos
and phone numbers from Wikidata / Wikimedia Commons, and phones from official open datasets
listed in scripts/phone_sources.json when present.

Output: site/data/v1/pois/<place id>.json
"""
import json, math, pathlib, re, sys, time, urllib.parse, urllib.request, hashlib, collections

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
OUT = ROOT / "site" / "data" / "v1" / "pois"
PBF = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "thailand-latest.osm.pbf"
# Optional Overture Maps extract (scripts/overture_extract.py): many more restaurants and phone numbers.
OVERTURE = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else None
UA = "ThaiTripPlanner-data/0.2 (https://github.com/attasakj-blip/thai-trip-planner)"

# ------------------------------------------------------------------ health rules (same as logic/PoiRules.kt)
NOT_CARE = re.compile(r"(?i)นวด|massage|spa\b|สปา|ความงาม|beauty|aesthetic|cosmetic|skin|ผิวพรรณ|เสริมสวย|dental|dentist|ทันต|ฟัน|"
                      r"vet\b|veterinar|สัตว์|animal|\bpet\b|แผนไทย|thai traditional|herb|สมุนไพร|hair|lasik|แว่น|optic|nail|tattoo")
HOSPITAL_NAME = re.compile(r"(?i)โรงพยาบาล|hospital|รพ\.|medical cent|ศูนย์การแพทย์|ศูนย์แพทย์")
HEALTH_CENTRE_NAME = re.compile(r"(?i)ส่งเสริมสุขภาพ|รพ\.?สต|health promoting|health cent|สถานีอนามัย|ศูนย์บริการสาธารณสุข")
CLINIC_NAME = re.compile(r"(?i)คลินิก|คลีนิก|clinic|เวชกรรม|polyclinic|medical")
PUBLIC_NAME = re.compile(r"(?i)โรงพยาบาล(ศูนย์|ทั่วไป|ชุมชน|มหาราช|สมเด็จ|จังหวัด|ส่งเสริม)|ค่ายทหาร|ตำรวจ|มหาวิทยาลัย|university|provincial|government|crown prince|somdet|ส่งเสริมสุขภาพ|สาธารณสุข")
BAD_SPECIALITY = re.compile(r"(?i)dentist|cosmetic|dermatology|massage|alternative|traditional|veterinary|psychotherapy")
POLICE_STATION = re.compile(r"(?i)สถานีตำรวจ|สภ\.|สน\.|police station|tourist police|ตำรวจท่องเที่ยว")


def health_kind(t, is_area):
    name = " ".join(t.get(k, "") for k in ("name", "name:th", "name:en"))
    if not name.strip() or NOT_CARE.search(name) or BAD_SPECIALITY.search(t.get("healthcare:speciality", "")):
        return None
    amenity, hc = t.get("amenity"), t.get("healthcare")
    known = "wikidata" in t or (t.get("beds", "").isdigit() and int(t["beds"]) > 0)
    if amenity == "hospital" or hc == "hospital":
        return "HOSPITAL" if (is_area or known or HOSPITAL_NAME.search(name)) else None
    if HEALTH_CENTRE_NAME.search(name):
        return "HEALTH_CENTRE"
    if amenity in ("clinic", "doctors") or hc == "clinic":
        if HOSPITAL_NAME.search(name) and (is_area or known):
            return "HOSPITAL"
        if (is_area or known) and CLINIC_NAME.search(name):
            return "CLINIC"
    return None


def owner(t):
    ot = t.get("operator:type")
    if ot in ("government", "public", "military", "university"): return "PUBLIC"
    if ot in ("private", "business", "commercial"): return "PRIVATE"
    name = " ".join(t.get(k, "") for k in ("name", "name:th", "operator"))
    return "PUBLIC" if PUBLIC_NAME.search(name) else None


def kind_of(t):
    a = t.get("amenity")
    if a in ("restaurant", "food_court"): return "restaurant"
    if a == "cafe": return "cafe"
    if a == "police": return "police"
    if a == "fire_station": return "fire"
    if a in ("hospital", "clinic", "doctors") or t.get("healthcare") in ("hospital", "clinic"): return "hospital"
    return None


# ------------------------------------------------------------------ read OSM
def read_osm(path):
    import osmium
    items = []

    class H(osmium.SimpleHandler):
        def keep(self, tags, lat, lon, is_area, oid):
            t = {k.k: k.v for k in tags}
            k = kind_of(t)
            if not k or not (t.get("name") or t.get("name:th") or t.get("name:en")):
                return
            items.append((k, lat, lon, is_area, t, oid))

        def node(self, n):
            if n.tags and ("amenity" in n.tags or "healthcare" in n.tags) and n.location.valid():
                self.keep(n.tags, n.location.lat, n.location.lon, False, f"n{n.id}")

        def area(self, a):
            if not ("amenity" in a.tags or "healthcare" in a.tags):
                return
            pts = [(nd.lat, nd.lon) for ring in a.outer_rings() for nd in ring if nd.location.valid()]
            if pts:
                self.keep(a.tags, sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts), True,
                          ("w" if a.from_way() else "r") + str(a.orig_id()))

    H().apply_file(str(path), locations=True)
    return items


def read_overture(path):
    """Overture places as the same (kind, lat, lon, is_area, tags, id) tuples as OSM, with tags made up from its fields."""
    items = []
    if not path or not path.exists():
        return items
    amen = {"restaurant": "restaurant", "cafe": "cafe", "hospital": "hospital", "police": "police", "fire": "fire_station"}
    for line in path.open(encoding="utf-8"):
        o = json.loads(line)
        t = {"name": o["name"], "amenity": amen[o["kind"]], "_src": "overture", "_conf": o["conf"]}
        if o.get("th"): t["name:th"] = o["th"]
        if o.get("en"): t["name:en"] = o["en"]
        if o.get("phone"): t["phone"] = o["phone"]
        if o.get("website") or o.get("social"): t["website"] = o.get("website") or o.get("social")
        if o.get("address"): t["_address"] = o["address"]
        cat = (o.get("cat") or "").replace("_restaurant", "").replace("_", " ")
        if cat and cat not in ("restaurant", "cafe", "coffee shop", "food and drink", "hospital", "police station", "fire station"): t["cuisine"] = cat
        items.append((o["kind"], o["lat"], o["lon"], False, t, "o" + o["id"][:12]))
    return items


def same_place(a, b):
    """Two records of one place: close together with matching names."""
    if km(a["lat"], a["lon"], b["lat"], b["lon"]) > 0.15: return False
    na, nb = norm(a["name"]["th"] or ""), norm(b["name"]["th"] or "")
    ea, eb = norm(a["name"]["en"] or ""), norm(b["name"]["en"] or "")
    def close(x, y): return len(x) >= 3 and len(y) >= 3 and (x == y or x in y or y in x)
    return close(na, nb) or close(ea, eb) or close(na, eb) or close(ea, nb)


# ------------------------------------------------------------------ helpers
def km(a, b, c, d):
    r = math.radians
    x = math.sin(r(c - a) / 2) ** 2 + math.cos(r(a)) * math.cos(r(c)) * math.sin(r(d - b) / 2) ** 2
    return 12742 * math.asin(math.sqrt(min(1.0, x)))


def get_json(url, tries=4):
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=60) as r:
                return json.load(r)
        except Exception:
            time.sleep(2 + 3 * i)
    return None


def thumb(file, width=480):
    name = file.removeprefix("File:").strip().replace(" ", "_")
    md5 = hashlib.md5(name.encode()).hexdigest()
    enc = urllib.parse.quote(name)
    suffix = ".png" if name.lower().endswith(".svg") else ""
    return f"https://upload.wikimedia.org/wikipedia/commons/thumb/{md5[0]}/{md5[:2]}/{enc}/{width}px-{enc}{suffix}"


def phone_of(t):
    p = t.get("phone") or t.get("contact:phone") or t.get("contact:mobile") or t.get("emergency:phone")
    return fmt_phone(p.split(";")[0].strip()) if p else None


def fmt_phone(p):
    """+66 2 123 4567 / +6621234567 -> 02 123 4567; mobiles 08x xxx xxxx. Others unchanged."""
    d = re.sub(r"\D", "", p)
    if d.startswith("66"): d = "0" + d[2:]
    if not d.startswith("0") or len(d) not in (9, 10): return p
    if len(d) == 10: return f"{d[:3]} {d[3:6]} {d[6:]}"
    return f"{d[:2]} {d[2:5]} {d[5:]}" if d[1] == "2" else f"{d[:3]} {d[3:6]} {d[6:]}"


def norm(s):
    s = re.sub(r"(?i)โรงพยาบาล|รพ\.|hospital|สถานีตำรวจภูธร|สถานีตำรวจนครบาล|สถานีตำรวจ|สภ\.|สน\.|police station|สถานีดับเพลิง(และกู้ภัย)?|fire station", "", s)
    return re.sub(r"[\s\-().,]", "", s).lower()


# ------------------------------------------------------------------ official phone lists (optional)
def load_phone_lists():
    """scripts/phone_sources.json: [{"kind": "hospital", "url": "...csv", "name": "col", "phone": "col", "lat": "col", "lon": "col", "province": "col"}]"""
    cfg = ROOT / "scripts" / "phone_sources.json"
    out = collections.defaultdict(list)
    if not cfg.exists():
        return out
    import csv, io
    for src in json.loads(cfg.read_text(encoding="utf-8")):
        try:
            with urllib.request.urlopen(urllib.request.Request(src["url"], headers={"User-Agent": UA}), timeout=120) as r:
                text = r.read().decode(src.get("encoding", "utf-8-sig"), errors="replace")
            rows = json.loads(text) if src.get("format") == "json" else list(csv.DictReader(io.StringIO(text)))
            if src.get("format") == "json" and src.get("path"):
                for k in src["path"].split("."): rows = rows[k]
            for row in rows:
                name, phone = (row.get(src["name"]) or "").strip(), (row.get(src["phone"]) or "").strip()
                if not name or not phone: continue
                lat = lon = None
                try:
                    lat, lon = float(row.get(src.get("lat", ""), "")), float(row.get(src.get("lon", ""), ""))
                except Exception:
                    pass
                out[src["kind"]].append({"key": norm(name), "name": name, "phone": phone, "lat": lat, "lon": lon,
                                         "province": (row.get(src.get("province", ""), "") or "").strip()})
            print("phone list", src["kind"], src["url"], len(out[src["kind"]]))
        except Exception as e:
            print("phone list failed", src.get("url"), e)
    return out


def match_phone(lists, kind, name, lat, lon, province):
    key = norm(name)
    if len(key) < 3: return None
    best = None
    for r in lists.get(kind, []):
        if r["key"] != key and not (len(key) >= 5 and (key in r["key"] or r["key"] in key)):
            continue
        if r["lat"] is not None and r["lon"] is not None:
            d = km(lat, lon, r["lat"], r["lon"])
            if d > 3: continue
            score = d
        else:
            if province and r["province"] and province not in r["province"] and r["province"] not in province: continue
            score = 5 if r["key"] == key else 8
        if best is None or score < best[0]: best = (score, r)
    return best[1]["phone"] if best else None


# ------------------------------------------------------------------ main
def main():
    places = json.loads((ASSETS / "places.json").read_text(encoding="utf-8"))
    t0 = time.time()
    items = read_osm(PBF)
    print(f"OSM: {len(items)} named places in {time.time() - t0:.0f}s", collections.Counter(i[0] for i in items))
    ov = read_overture(OVERTURE)
    print(f"Overture: {len(ov)}", collections.Counter(i[0] for i in ov))
    items += ov
    grid = collections.defaultdict(list)
    for it in items:
        grid[(int(it[1] * 10), int(it[2] * 10))].append(it)

    def around(lat, lon, radius):
        r = int(radius / 11) + 1
        gx, gy = int(lat * 10), int(lon * 10)
        for x in range(gx - r, gx + r + 1):
            for y in range(gy - r, gy + r + 1):
                for it in grid.get((x, y), ()):
                    d = km(lat, lon, it[1], it[2])
                    if d <= radius: yield d, it

    lists = load_phone_lists()
    RADIUS = {"restaurant": 3, "cafe": 3, "hospital": 30, "police": 25, "fire": 25}
    per_place, wikidata_ids = {}, set()
    for p in places:
        res = {}
        for kind, radius in RADIUS.items():
          # Food: widen the circle in the countryside until there is a fair choice.
          for radius in ((radius, 8, 15, 25) if kind in ("restaurant", "cafe") else (radius,)):
            cands = []
            for d, (k, lat, lon, is_area, t, oid) in around(p["lat"], p["lon"], radius):
                if k != kind: continue
                e = {"id": oid, "lat": round(lat, 6), "lon": round(lon, 6), "km": round(d, 2),
                     "name": {"th": t.get("name:th") or t.get("name") or t.get("name:en"), "en": t.get("name:en") or t.get("name") or t.get("name:th")}}
                if kind == "hospital":
                    hk = health_kind(t, is_area)
                    if not hk: continue
                    e["health"] = hk
                    if owner(t): e["owner"] = owner(t)
                    if t.get("beds", "").isdigit(): e["beds"] = int(t["beds"])
                    if t.get("emergency") == "yes": e["emergency"] = True
                ph = phone_of(t)
                if ph: e["phone"] = ph
                for src, dst in (("website", "website"), ("contact:website", "website"), ("opening_hours", "hours")):
                    if t.get(src) and dst not in e: e[dst] = t[src]
                if t.get("cuisine"): e["cuisine"] = t["cuisine"].replace(";", ", ").replace("_", " ")
                addr = " ".join(dict.fromkeys(x for x in (t.get("addr:housenumber"), t.get("addr:street"), t.get("addr:subdistrict"),
                                                         t.get("addr:district"), t.get("addr:city"), t.get("addr:province")) if x))
                if addr: e["address"] = addr
                elif t.get("_address"): e["address"] = t["_address"]
                e["src"] = t.get("_src", "osm")
                img = t.get("image", "") if t.get("image", "").startswith("File:") else t.get("wikimedia_commons", "")
                if img.startswith("File:"): e["photo"] = thumb(img); e["photoPage"] = "https://commons.wikimedia.org/wiki/" + urllib.parse.quote(img.replace(" ", "_"))
                if re.fullmatch(r"Q\d+", t.get("wikidata", "")): e["wikidata"] = t["wikidata"]
                rich = sum(1 for x in ("phone", "hours", "cuisine", "website", "wikidata") if x in e) + 2 * t.get("_conf", 0.7)
                cands.append((e, rich))
            # Merge the same place found in both sources (OSM first: it has hours and photos).
            if kind in ("restaurant", "cafe"):
                cands = sorted(cands, key=lambda c: c[0]["km"])[:400]  # plenty to pick 40 from, keeps the city centre fast
            cands.sort(key=lambda c: (c[0]["src"] != "osm", c[0]["km"]))
            merged = []
            grid2 = collections.defaultdict(list)
            for e, rich in cands:
                cell = (round(e["lat"] * 100), round(e["lon"] * 100))
                dup = next((m for x in range(cell[0] - 1, cell[0] + 2) for y in range(cell[1] - 1, cell[1] + 2) for m in grid2[(x, y)] if same_place(m[0], e)), None)
                if dup:
                    for f in ("phone", "website", "address", "cuisine"):
                        if f not in dup[0] and f in e: dup[0][f] = e[f]
                    continue
                m = [e, rich]; merged.append(m); grid2[cell].append(m)
            cands = [(m[0], m[1]) for m in merged]
            if kind not in ("restaurant", "cafe") or len(cands) >= 10: break
          if kind in ("restaurant", "cafe"):
              # Nearest first, but places with more information (phone, hours...) move up a little.
              cands.sort(key=lambda c: c[0]["km"] - 0.25 * c[1])
              keep = [c[0] for c in cands[: 40 if kind == "restaurant" else 25]]
          elif kind == "hospital":
              order = {"HOSPITAL": 0, "HEALTH_CENTRE": 1, "CLINIC": 2}
              # Map records first: Overture often repeats a big hospital's position for its departments.
              hs = sorted((c[0] for c in cands if c[0]["health"] == "HOSPITAL"), key=lambda e: e["km"] + (0 if e["src"] == "osm" else 1.5))
              spots, hs2 = set(), []
              for e in hs:
                  spot = (round(e["lat"], 4), round(e["lon"], 4))
                  if e["src"] != "osm" and spot in spots: continue
                  spots.add(spot); hs2.append(e)
              hs = hs2[:8]
              hc = sorted((c[0] for c in cands if c[0]["health"] == "HEALTH_CENTRE" and c[0]["km"] <= 10), key=lambda e: e["km"])[:3]
              cl = sorted((c[0] for c in cands if c[0]["health"] == "CLINIC" and c[0]["km"] <= 5), key=lambda e: e["km"])[:3]
              keep = sorted(hs + hc + cl, key=lambda e: (order[e["health"]], e["km"]))
          elif kind == "police":
              cands.sort(key=lambda c: (0 if POLICE_STATION.search(c[0]["name"]["th"] + " " + c[0]["name"]["en"]) else 1, c[0]["km"]))
              keep = [c[0] for c in cands[:6]]
          else:
              cands.sort(key=lambda c: c[0]["km"])
              keep = [c[0] for c in cands[:5]]
          # De-duplicate the same place mapped twice (a dot and a building).
          seen, uniq = set(), []
          for e in keep:
              key = (e["name"]["th"], round(e["lat"], 2), round(e["lon"], 2))
              if key in seen: continue
              seen.add(key); uniq.append(e)
          for e in uniq:
              if "wikidata" in e: wikidata_ids.add(e["wikidata"])
              if "phone" not in e and kind in ("hospital", "police", "fire"):
                  ph = match_phone(lists, kind, e["name"]["th"], e["lat"], e["lon"], p["province"]["th"])
                  if ph: e["phone"] = ph; e["phoneSource"] = "official"
          res[kind] = uniq
        per_place[p["id"]] = res
    print(f"places done in {time.time() - t0:.0f}s; wikidata ids {len(wikidata_ids)}")

    # Wikidata: photo / logo and phone number.
    wd = {}
    ids = sorted(wikidata_ids)
    for i in range(0, len(ids), 50):
        d = get_json("https://www.wikidata.org/w/api.php?" + urllib.parse.urlencode(
            {"action": "wbgetentities", "format": "json", "props": "claims", "ids": "|".join(ids[i:i + 50])}))
        for qid, ent in ((d or {}).get("entities") or {}).items():
            c = ent.get("claims") or {}
            def first(prop):
                try: return c[prop][0]["mainsnak"]["datavalue"]["value"]
                except Exception: return None
            wd[qid] = {"image": first("P18"), "logo": first("P154"), "phone": first("P1329")}
        time.sleep(0.5)

    # Commons photos taken right at each emergency place (a sign or the building), one search per place.
    word = {"hospital": r"hospital|โรงพยาบาล|รพ\.", "police": r"police|ตำรวจ|สภ\.|สน\.", "fire": r"fire|ดับเพลิง"}
    for pid, res in per_place.items():
        p = next(x for x in places if x["id"] == pid)
        for kind in ("hospital", "police", "fire", "restaurant", "cafe"):
            for e in res.get(kind, []):
                w = wd.get(e.get("wikidata"))
                if w:
                    if "photo" not in e and (w["image"] or w["logo"]):
                        f = w["image"] or w["logo"]
                        e["photo"] = thumb(f); e["photoPage"] = "https://commons.wikimedia.org/wiki/File:" + urllib.parse.quote(f.replace(" ", "_"))
                    if "phone" not in e and w["phone"]: e["phone"] = w["phone"]; e["phoneSource"] = "wikidata"
        need = [e for k in ("hospital", "police", "fire") for e in res.get(k, []) if "photo" not in e and e["km"] <= 10]
        if not need: continue
        d = get_json("https://commons.wikimedia.org/w/api.php?" + urllib.parse.urlencode(
            {"action": "query", "format": "json", "formatversion": "2", "list": "geosearch", "gsnamespace": "6",
             "gscoord": f"{p['lat']}|{p['lon']}", "gsradius": "10000", "gslimit": "500"}))
        files = [g for g in ((d or {}).get("query") or {}).get("geosearch", []) if re.search(r"(?i)\.(jpe?g|png)$", g["title"])]
        for kind in ("hospital", "police", "fire"):
            for e in res.get(kind, []):
                if "photo" in e: continue
                rx = re.compile("(?i)" + word[kind])
                tokens = [x for x in re.split(r"\s+", e["name"]["en"] or "") if len(x) > 3]
                best = None
                for g in files:
                    dd = km(e["lat"], e["lon"], g["lat"], g["lon"])
                    if dd > 0.2: continue
                    title = g["title"]
                    if not (rx.search(title) or any(tok.lower() in title.lower() for tok in tokens)): continue
                    if best is None or dd < best[0]: best = (dd, title)
                if best:
                    e["photo"] = thumb(best[1]); e["photoPage"] = "https://commons.wikimedia.org/wiki/" + urllib.parse.quote(best[1].replace(" ", "_"))
        time.sleep(0.3)

    OUT.mkdir(parents=True, exist_ok=True)
    stats = collections.Counter()
    for pid, res in per_place.items():
        for k, v in res.items():
            stats[k] += len(v)
            stats[k + "_phone"] += sum(1 for e in v if "phone" in e)
            stats[k + "_photo"] += sum(1 for e in v if "photo" in e)
            for e in v: e.pop("wikidata", None)
        (OUT / f"{pid}.json").write_text(json.dumps({"place": pid, "updated": time.strftime("%Y-%m-%d"), **res}, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    few = sorted(pid for pid, res in per_place.items() if len(res.get("restaurant", [])) < 5)
    stats["places_few_restaurants"] = len(few)
    print("places with under 5 restaurants:", few[:40])
    (OUT / "index.json").write_text(json.dumps({"updated": time.strftime("%Y-%m-%d"), "places": len(per_place), "stats": stats}, ensure_ascii=False), encoding="utf-8")
    print(dict(stats))


if __name__ == "__main__":
    main()
