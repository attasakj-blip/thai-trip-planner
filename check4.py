# Checks new places' coordinates: Wikipedia coordinates for their article, then Nominatim search by name within the province.
import json, time, urllib.request, urllib.parse, math, re, sys
UA = {"User-Agent": "ThaiTripPlanner-coordcheck/0.1 (https://github.com/attasakj-blip/thai-trip-planner)"}
def get(url):
    for i in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                return json.load(r)
        except Exception:
            time.sleep(3)
    return None
def km(a, b, c, d):
    r = math.radians
    x = math.sin(r(c - a) / 2) ** 2 + math.cos(r(a)) * math.cos(r(c)) * math.sin(r(d - b) / 2) ** 2
    return 12742 * math.asin(math.sqrt(x))
src = sys.argv[1] if len(sys.argv) > 1 else "data/places_new.json"
places = json.load(open(src))
strip = lambda s: re.sub(r"\s*[\(（].*?[\)）]", "", s).strip()
titles = sorted({p["wikiTitles"][0] for p in places if p.get("wikiTitles")})
wiki = {}
for i in range(0, len(titles), 40):
    q = urllib.parse.urlencode({"action": "query", "format": "json", "prop": "coordinates", "redirects": 1, "titles": "|".join(titles[i:i+40]), "colimit": "max"})
    d = get("https://en.wikipedia.org/w/api.php?" + q) or {}
    norm = {n["from"]: n["to"] for n in d.get("query", {}).get("normalized", [])}
    red = {n["from"]: n["to"] for n in d.get("query", {}).get("redirects", [])}
    pages = {pg["title"]: pg for pg in d.get("query", {}).get("pages", {}).values()}
    for t in titles[i:i+40]:
        x = norm.get(t, t); x = red.get(x, x)
        pg = pages.get(x)
        if pg is None or "missing" in pg: wiki[t] = None; continue
        c = (pg.get("coordinates") or [None])[0]
        wiki[t] = [c["lat"], c["lon"]] if c else "nocoords"
    time.sleep(1)
out = {}
for p in places:
    r = {"lat": p["lat"], "lon": p["lon"], "province": p["province"]["en"]}
    if p.get("wikiTitles"): r["wiki"] = wiki.get(p["wikiTitles"][0], None)
    cands = []
    for q in (strip(p["name"]["th"]) + " " + p["province"]["th"], strip(p["name"]["en"]) + ", " + p["province"]["en"], strip(p["name"]["th"])):
        d = get("https://nominatim.openstreetmap.org/search?" + urllib.parse.urlencode({"format": "json", "limit": 4, "countrycodes": "th", "q": q, "addressdetails": 1}))
        time.sleep(1.1)
        for x in d or []:
            a = x.get("address", {})
            cands.append({"q": q, "lat": float(x["lat"]), "lon": float(x["lon"]), "cls": x.get("class"), "type": x.get("type"),
                          "state": a.get("state") or a.get("province") or a.get("city"), "name": x.get("display_name", "")[:120],
                          "km": round(km(p["lat"], p["lon"], float(x["lat"]), float(x["lon"])), 2)})
        if cands: break
    r["cands"] = cands
    out[p["id"]] = r
    print(p["id"], r.get("wiki"), min([c["km"] for c in cands], default=None), flush=True)
json.dump(out, open("data/result4.json" if len(sys.argv) < 3 else sys.argv[2], "w"), ensure_ascii=False, indent=1)
