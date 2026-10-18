import json, urllib.request, urllib.parse, time
Q = """[out:json][timeout:180];
rel["type"="route"]["route"~"^(subway|light_rail|monorail|train)$"](13.40,100.20,14.20,100.95);
out body;
>;
out body qt;"""
for url in ["https://overpass-api.de/api/interpreter","https://overpass.private.coffee/api/interpreter","https://maps.mail.ru/osm/tools/overpass/api/interpreter"]:
    try:
        req = urllib.request.Request(url, data=urllib.parse.urlencode({"data": Q}).encode(), headers={"User-Agent": "ThaiTripPlanner/0.1"})
        d = json.load(urllib.request.urlopen(req, timeout=240)); break
    except Exception as e:
        print(url, e); time.sleep(5)
nodes = {e["id"]: e for e in d["elements"] if e["type"] == "node"}
rels = [e for e in d["elements"] if e["type"] == "relation"]
out = []
for r in rels:
    t = r.get("tags", {})
    stops = []
    for m in r["members"]:
        if m["type"] == "node" and m["role"].startswith("stop") and m["ref"] in nodes:
            n = nodes[m["ref"]]; nt = n.get("tags", {})
            stops.append({"id": n["id"], "lat": n["lat"], "lon": n["lon"], "name": nt.get("name", ""), "th": nt.get("name:th", nt.get("name", "")), "en": nt.get("name:en", ""), "ref": nt.get("ref", "")})
    out.append({"id": r["id"], "route": t.get("route"), "ref": t.get("ref", ""), "name": t.get("name", ""), "name_en": t.get("name:en", ""), "name_th": t.get("name:th", ""),
                "network": t.get("network", ""), "operator": t.get("operator", ""), "colour": t.get("colour", ""), "from": t.get("from", ""), "to": t.get("to", ""), "stops": stops})
json.dump(out, open("data/bkk_rail_raw.json", "w"), ensure_ascii=False, indent=1)
print(len(out))
