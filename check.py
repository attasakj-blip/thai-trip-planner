import json, time, urllib.request, urllib.parse, math
UA = {"User-Agent": "ThaiTripPlanner-coordcheck/0.1 (https://github.com/attasakj-blip/thai-trip-planner)"}
def get(url):
    for i in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                return json.load(r)
        except Exception as e:
            time.sleep(3)
    return None
def km(a, b, c, d):
    r = math.radians
    x = math.sin(r(c - a) / 2) ** 2 + math.cos(r(a)) * math.cos(r(c)) * math.sin(r(d - b) / 2) ** 2
    return 12742 * math.asin(math.sqrt(x))
places = json.load(open("data/places.json"))
out = {}
# Wikipedia coordinates in batches
titles = {}
for p in places:
    for t in p["wikiTitles"][:1]:
        titles.setdefault(t, []).append(p["id"])
tl = list(titles)
wiki = {}
for i in range(0, len(tl), 40):
    q = urllib.parse.urlencode({"action": "query", "format": "json", "prop": "coordinates|pageprops", "redirects": 1, "titles": "|".join(tl[i:i+40]), "colimit": "max"})
    d = get("https://en.wikipedia.org/w/api.php?" + q) or {}
    norm = {n["from"]: n["to"] for n in d.get("query", {}).get("normalized", [])}
    red = {n["from"]: n["to"] for n in d.get("query", {}).get("redirects", [])}
    pages = {pg["title"]: pg for pg in d.get("query", {}).get("pages", {}).values()}
    for t in tl[i:i+40]:
        x = red.get(norm.get(t, t), norm.get(t, t))
        pg = pages.get(x)
        if pg is None or "missing" in pg: wiki[t] = {"missing": True}; continue
        c = (pg.get("coordinates") or [None])[0]
        wiki[t] = {"lat": c["lat"], "lon": c["lon"]} if c else {"nocoords": True, "wikidata": pg.get("pageprops", {}).get("wikibase_item")}
    time.sleep(1)
for p in places:
    r = {}
    if p["wikiTitles"]:
        w = wiki.get(p["wikiTitles"][0], {})
        r["wiki"] = w
        if "lat" in w: r["wikiKm"] = round(km(p["lat"], p["lon"], w["lat"], w["lon"]), 2)
    # reverse geocode: which province is the point in?
    d = get("https://nominatim.openstreetmap.org/reverse?" + urllib.parse.urlencode({"format": "json", "lat": p["lat"], "lon": p["lon"], "zoom": 10, "accept-language": "en"}))
    time.sleep(1.1)
    if d: r["revState"] = d.get("address", {}).get("state") or d.get("address", {}).get("province") or d.get("address", {}).get("city")
    # forward search by Thai name, then English name
    for key, qtxt in (("th", p["name"]["th"] + " " + p["province"]["th"]), ("en", p["name"]["en"] + ", " + p["province"]["en"])):
        d = get("https://nominatim.openstreetmap.org/search?" + urllib.parse.urlencode({"format": "json", "limit": 3, "countrycodes": "th", "q": qtxt}))
        time.sleep(1.1)
        if d:
            r["search_" + key] = [{"lat": float(x["lat"]), "lon": float(x["lon"]), "name": x.get("display_name", "")[:120], "type": x.get("type"), "km": round(km(p["lat"], p["lon"], float(x["lat"]), float(x["lon"])), 2)} for x in d]
            if r["search_" + key]: break
    out[p["id"]] = r
    print(p["id"], r.get("wikiKm"), r.get("revState"), [s["km"] for s in r.get("search_th", r.get("search_en", []))], flush=True)
json.dump(out, open("data/result.json", "w"), ensure_ascii=False, indent=1)
