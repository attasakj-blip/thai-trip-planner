import json, time, urllib.request, urllib.parse, math, re
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
hubs = json.load(open("data/hubs.json"))
strip = lambda s: re.sub(r"\s*[\(（].*?[\)）]", "", s).strip()
out = {}
for h in hubs:
    qs = [strip(h["name"]["th"]), strip(h["name"]["en"]) + ", " + h["province"]["en"]]
    if h["type"] == "AIRPORT": qs = [h["id"].upper() + " airport", strip(h["name"]["en"])]
    cands = []
    for q in qs:
        d = get("https://nominatim.openstreetmap.org/search?" + urllib.parse.urlencode({"format": "json", "limit": 5, "countrycodes": "th", "q": q, "accept-language": "th"}))
        time.sleep(1.1)
        for x in d or []:
            cands.append({"q": q, "lat": float(x["lat"]), "lon": float(x["lon"]), "cls": x.get("class"), "type": x.get("type"), "name": x.get("display_name", "")[:120], "km": round(km(h["lat"], h["lon"], float(x["lat"]), float(x["lon"])), 2)})
    out[h["id"]] = cands
    print(h["id"], min([c["km"] for c in cands], default=None), flush=True)
json.dump(out, open("data/result3.json", "w"), ensure_ascii=False, indent=1)
