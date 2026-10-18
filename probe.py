import urllib.request, urllib.parse, time, json
UA = "ThaiTripPlanner/0.1 (Android; https://github.com/attasakj-blip/thai-trip-planner)"
hosts = ["https://overpass-api.de/api/interpreter","https://overpass.private.coffee/api/interpreter","https://maps.mail.ru/osm/tools/overpass/api/interpreter","https://overpass.kumi.systems/api/interpreter","https://overpass.osm.jp/api/interpreter"]
qs = {"restaurant": '[out:json][timeout:50];(nwr["amenity"~"^(restaurant|food_court)$"](around:4000,13.8826,100.4950););out center tags 400;',
      "hospital": '[out:json][timeout:50];(nwr["amenity"~"^(hospital|clinic|doctors)$"](around:30000,13.8826,100.4950);nwr["healthcare"~"^(hospital|clinic)$"](around:30000,13.8826,100.4950););out center tags 400;'}
out = []
for h in hosts:
    for k, q in qs.items():
        t = time.time()
        try:
            req = urllib.request.Request(h, data=urllib.parse.urlencode({"data": q}).encode(), headers={"User-Agent": UA})
            r = urllib.request.urlopen(req, timeout=90); body = r.read().decode()
            n = len(json.loads(body).get("elements", [])) if body.lstrip().startswith("{") else -1
            res = f"{r.status} n={n} rem={json.loads(body).get('remark','')[:80] if n>=0 else body[:80]}"
        except Exception as e:
            res = f"ERR {e}"
        line = f"{h} {k} {time.time()-t:.1f}s {res}"; print(line); out.append(line)
open("data/probe.txt", "w").write("\n".join(out))
