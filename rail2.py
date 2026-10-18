import json, urllib.request, urllib.parse, time
Q = """[out:json][timeout:120];
(node["railway"~"^(station|halt)$"](13.40,100.20,14.20,100.95);
 node["public_transport"="station"](13.40,100.20,14.20,100.95););
out body;"""
for url in 2*["https://overpass-api.de/api/interpreter","https://overpass.private.coffee/api/interpreter","https://maps.mail.ru/osm/tools/overpass/api/interpreter"]:
    try:
        req = urllib.request.Request(url, data=urllib.parse.urlencode({"data": Q}).encode(), headers={"User-Agent": "ThaiTripPlanner/0.1"})
        d = json.load(urllib.request.urlopen(req, timeout=180)); break
    except Exception as e:
        print(f"::warning::{url} {e}"); time.sleep(5)
else:
    raise SystemExit(1)
out = [{"id": e["id"], "lat": e["lat"], "lon": e["lon"], "tags": e.get("tags", {})} for e in d["elements"]]
json.dump(out, open("data/bkk_stations_raw.json", "w"), ensure_ascii=False, indent=0)
print(len(out))
