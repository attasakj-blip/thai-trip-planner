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
P = {p["id"]: p for p in json.load(open("data/places.json"))}
todo = json.load(open("data/todo.json"))
strip = lambda s: re.sub(r"\s*[\(（].*?[\)）]", "", s).strip()
out = {}
for pid in todo:
    p = P[pid]
    th, en = strip(p["name"]["th"]), strip(p["name"]["en"])
    qs = [th + " " + p["province"]["th"], th, th.split(" ")[0], en + ", " + p["province"]["en"], en.split(",")[0] + " Thailand"]
    cands = []
    seen = set()
    for q in dict.fromkeys(qs):
        d = get("https://nominatim.openstreetmap.org/search?" + urllib.parse.urlencode({"format": "json", "limit": 5, "countrycodes": "th", "q": q, "accept-language": "th"}))
        time.sleep(1.1)
        for x in d or []:
            key = x.get("osm_id")
            if key in seen: continue
            seen.add(key)
            dn = x.get("display_name", "")
            cands.append({"q": q, "lat": float(x["lat"]), "lon": float(x["lon"]), "type": x.get("type"), "cls": x.get("class"), "name": dn[:140],
                          "inProv": p["province"]["th"] in dn, "km": round(km(p["lat"], p["lon"], float(x["lat"]), float(x["lon"])), 2)})
    out[pid] = cands
    print(pid, len(cands), flush=True)
json.dump(out, open("data/result2.json", "w"), ensure_ascii=False, indent=1)
