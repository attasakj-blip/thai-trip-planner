import json, urllib.request, urllib.parse, time
Q = """[out:json][timeout:180];
rel["type"="route"]["route"~"^(subway|light_rail|monorail|train)$"](13.40,100.20,14.20,100.95);
out body;
>;
out body qt;"""
for url in 2*["https://overpass-api.de/api/interpreter","https://overpass.private.coffee/api/interpreter","https://maps.mail.ru/osm/tools/overpass/api/interpreter"]:
    try:
        req = urllib.request.Request(url, data=urllib.parse.urlencode({"data": Q}).encode(), headers={"User-Agent": "ThaiTripPlanner/0.1"})
        d = json.load(urllib.request.urlopen(req, timeout=240)); break
    except Exception as e:
        print(f"::warning::{url} {e}"); time.sleep(5)
else:
    print("::error::all mirrors failed"); raise SystemExit(1)
nodes = {e["id"]: e for e in d["elements"] if e["type"] == "node"}
ways = {e["id"]: e for e in d["elements"] if e["type"] == "way"}
rels = [e for e in d["elements"] if e["type"] == "relation"]
out = []
for r in rels:
    t = r.get("tags", {})
    stops = []
    def add(lat, lon, nt, oid):
        nm = nt.get("name", "")
        if stops and stops[-1]["name"] == nm and nm: return
        stops.append({"id": oid, "lat": lat, "lon": lon, "name": nm, "th": nt.get("name:th", nm), "en": nt.get("name:en", ""), "ref": nt.get("ref", "")})
    st = [m for m in r["members"] if m["role"].startswith("stop") and m["type"] == "node" and m["ref"] in nodes]
    pf = [m for m in r["members"] if m["role"].startswith("platform")]
    use = st if len(st) >= len(pf) else pf
    for m in use:
        if m["type"] == "node" and m["ref"] in nodes:
            n = nodes[m["ref"]]; add(n["lat"], n["lon"], n.get("tags", {}), n["id"])
        elif m["type"] == "way" and m["ref"] in ways:
            w = ways[m["ref"]]; pts = [nodes[i] for i in w["nodes"] if i in nodes]
            if pts: add(sum(p["lat"] for p in pts)/len(pts), sum(p["lon"] for p in pts)/len(pts), w.get("tags", {}), w["id"])
    r["_use"] = len(use)
    out.append({"id": r["id"], "route": t.get("route"), "ref": t.get("ref", ""), "name": t.get("name", ""), "name_en": t.get("name:en", ""), "name_th": t.get("name:th", ""),
                "network": t.get("network", ""), "operator": t.get("operator", ""), "colour": t.get("colour", ""), "from": t.get("from", ""), "to": t.get("to", ""), "stops": stops})
json.dump(out, open("data/bkk_rail_raw.json", "w"), ensure_ascii=False, indent=1)
print(len(out))
