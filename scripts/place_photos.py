#!/usr/bin/env python3
"""
Finds real, freely licensed photos (Wikimedia Commons) for every place in places.json and
writes place_photos.json: {placeId: [{url, author, license, page}, ...]}.

Sources, best first:
  1. images in the place's English Wikipedia articles (wikiTitles)
  2. the Wikidata item with this name near the place (P18 image)
  3. Thai / English Wikipedia articles found by name whose coordinates are near the place
  4. Commons photos taken at the place (geosearch), preferring file names that match the name

Only JPEG photos with CC / public-domain / GFDL licences are kept; maps, logos, flags,
panoramas and 360 images are skipped.

usage: place_photos.py places.json out.json [old.json]
"""
import json, math, re, sys, time, html
import urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor

UA = "ThaiTripPlanner/1.0 (https://github.com/attasakj-blip/thai-trip-planner)"
MAX = 4
SKIP = re.compile(r"(?i)flag|seal|logo|map|locator|icon|symbol|emblem|coat.of.arms|signature|360|panorama|pano\b|diagram|plan\b|chart|banner|svg|\.png|\.gif|\.tif")


def get(url, params, tries=4):
    q = url + "?" + urllib.parse.urlencode(params)
    for i in range(tries):
        try:
            req = urllib.request.Request(q, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:  # rate limits, timeouts
            time.sleep(2 + 3 * i)
    return {}


def km(a, b, c, d):
    r = 6371.0
    p1, p2 = math.radians(a), math.radians(c)
    dp, dl = p2 - p1, math.radians(d - b)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def strip(s):
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]*>", "", s or ""))).strip()


def free(lic):
    l = (lic or "").lower()
    return l.startswith("cc") or "public domain" in l or l.startswith("pd") or l.startswith("gfdl")


def infos(titles):
    """imageinfo for Commons file titles -> list of photo dicts in the same order (bad ones dropped)."""
    out = {}
    titles = [t for t in titles if t and not SKIP.search(t)]
    for i in range(0, len(titles), 40):
        chunk = titles[i:i + 40]
        d = get("https://commons.wikimedia.org/w/api.php", {
            "action": "query", "format": "json", "formatversion": "2", "titles": "|".join(chunk),
            "prop": "imageinfo", "iiprop": "url|extmetadata|mime|size", "iiurlwidth": "1000",
            "iiextmetadatafilter": "Artist|LicenseShortName",
        })
        for p in d.get("query", {}).get("pages", []):
            ii = (p.get("imageinfo") or [None])[0]
            if not ii or ii.get("mime") != "image/jpeg":
                continue
            if ii.get("width", 0) < 640 or ii.get("height", 0) < 400:
                continue
            # Very wide images are usually panoramas.
            if ii.get("width", 1) / max(1, ii.get("height", 1)) > 2.2:
                continue
            meta = ii.get("extmetadata") or {}
            lic = (meta.get("LicenseShortName") or {}).get("value")
            if not free(lic):
                continue
            out[p["title"]] = {
                "url": ii.get("thumburl") or ii.get("url"),
                "author": strip((meta.get("Artist") or {}).get("value")) or "Wikimedia Commons",
                "license": lic,
                "page": ii.get("descriptionurl") or "https://commons.wikimedia.org",
            }
    return [out[t] for t in titles if t in out]


def article_images(lang, titles):
    """File titles used in Wikipedia articles, lead image first."""
    files = []
    for t in titles:
        d = get(f"https://{lang}.wikipedia.org/w/api.php", {
            "action": "query", "format": "json", "formatversion": "2", "titles": t, "redirects": "1",
            "prop": "pageimages|images", "piprop": "name", "imlimit": "50",
        })
        for p in d.get("query", {}).get("pages", []):
            if p.get("pageimage"):
                files.append("File:" + p["pageimage"])
            files += [i["title"].replace("ไฟล์:", "File:") for i in p.get("images", [])]
    seen = set()
    return [f for f in files if not (f in seen or seen.add(f))]


def search_articles(lang, name, lat, lon, limit_km):
    """Wikipedia articles matching the name whose coordinates are within limit_km."""
    if not name:
        return []
    d = get(f"https://{lang}.wikipedia.org/w/api.php", {
        "action": "query", "format": "json", "formatversion": "2", "generator": "search",
        "gsrsearch": name, "gsrlimit": "5", "prop": "coordinates|pageimages", "piprop": "name",
    })
    out = []
    for p in sorted(d.get("query", {}).get("pages", []), key=lambda p: p.get("index", 99)):
        c = (p.get("coordinates") or [None])[0]
        if not c or km(lat, lon, c["lat"], c["lon"]) > limit_km:
            continue
        out.append(p["title"])
    return out[:2]


def wikidata_image(name, lang, lat, lon, limit_km):
    if not name:
        return []
    d = get("https://www.wikidata.org/w/api.php", {
        "action": "wbsearchentities", "format": "json", "search": name, "language": lang, "limit": "5",
    })
    ids = [e["id"] for e in d.get("search", [])]
    if not ids:
        return []
    e = get("https://www.wikidata.org/w/api.php", {
        "action": "wbgetentities", "format": "json", "ids": "|".join(ids), "props": "claims",
    }).get("entities", {})
    files = []
    for i in ids:
        cl = e.get(i, {}).get("claims", {})
        try:
            c = cl["P625"][0]["mainsnak"]["datavalue"]["value"]
        except Exception:
            continue
        if km(lat, lon, c["latitude"], c["longitude"]) > limit_km:
            continue
        for s in cl.get("P18", []):
            v = s.get("mainsnak", {}).get("datavalue", {}).get("value")
            if v:
                files.append("File:" + v)
    return files


def words(name_en):
    stop = {"the", "of", "and", "national", "park", "temple", "wat", "beach", "island", "market", "museum", "mount", "ko", "koh", "khao", "ban"}
    return [w for w in re.findall(r"[a-z]{3,}", (name_en or "").lower()) if w not in stop]


def geo_files(lat, lon, radius, name_th, name_en):
    d = get("https://commons.wikimedia.org/w/api.php", {
        "action": "query", "format": "json", "formatversion": "2", "list": "geosearch",
        "gscoord": f"{lat}|{lon}", "gsradius": str(radius), "gsnamespace": "6", "gslimit": "100",
    })
    hits = d.get("query", {}).get("geosearch", [])
    ws = words(name_en)
    core = re.sub(r"^(วัด|อุทยานแห่งชาติ|เกาะ|หาด|ตลาด|พิพิธภัณฑ์|ดอย|น้ำตก|ถ้ำ|เขา)", "", name_th or "")[:6]
    def match(t):
        tl = t.lower()
        return any(w in tl for w in ws) or (core and core in t)
    named = [h["title"] for h in hits if match(h["title"])]
    near = [h["title"] for h in hits if h["dist"] <= min(radius, 400) and h["title"] not in named]
    return named, near


def photos_for(p):
    lat, lon = p["lat"], p["lon"]
    th, en = p["name"]["th"], p["name"]["en"]
    big = any(k in en.lower() for k in ("national park", "island", "ko ", "koh ", "mountain", "doi ", "lake", "dam", "forest"))
    limit = 25 if big else 5
    got, seen = [], set()

    def add(files):
        for ph in infos([f for f in files if f not in seen]):
            if len(got) >= MAX:
                return
            if ph["url"] in seen:
                continue
            seen.add(ph["url"])
            got.append(ph)
        seen.update(files)

    if p.get("wikiTitles"):
        add(article_images("en", p["wikiTitles"]))
    if len(got) < MAX:
        add(wikidata_image(th, "th", lat, lon, limit) or wikidata_image(en, "en", lat, lon, limit))
    if len(got) < MAX:
        add(article_images("th", search_articles("th", th, lat, lon, limit)))
    if len(got) < MAX:
        add(article_images("en", search_articles("en", en, lat, lon, limit)))
    if len(got) < 2:
        named, near = geo_files(lat, lon, 3000 if big else 800, th, en)
        add(named)
        if not got:
            add(near)
    return got


def main():
    places = json.load(open(sys.argv[1], encoding="utf-8"))
    old = {}
    if len(sys.argv) > 3:
        try:
            old = json.load(open(sys.argv[3], encoding="utf-8"))
        except Exception:
            pass
    out = {}

    def work(p):
        try:
            return p["id"], photos_for(p)
        except Exception as e:
            print("error", p["id"], e, file=sys.stderr)
            return p["id"], old.get(p["id"], [])

    with ThreadPoolExecutor(6) as ex:
        for n, (pid, ph) in enumerate(ex.map(work, places), 1):
            out[pid] = ph or old.get(pid, [])
            if n % 100 == 0:
                print(n, "places", sum(1 for v in out.values() if v), "with photos", flush=True)
    missing = [p["id"] for p in places if not out.get(p["id"])]
    print("places", len(places), "with photos", len(places) - len(missing), "missing", len(missing))
    print("missing:", " ".join(missing))
    json.dump(out, open(sys.argv[2], "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    main()
