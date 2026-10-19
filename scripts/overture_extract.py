"""Pulls Thai restaurants, cafés, hospitals, police and fire stations from Overture Maps places (free, CDLA-Permissive-2.0)
into a JSON-lines file for build_pois.py. Usage: python3 scripts/overture_extract.py out.jsonl"""
import json, re, subprocess, sys
import duckdb

out = subprocess.run(["aws", "s3", "ls", "--no-sign-request", "s3://overturemaps-us-west-2/release/"], capture_output=True, text=True).stdout
rel = sorted(l.split()[-1].strip("/") for l in out.splitlines() if "PRE" in l)[-1]
print("Overture release", rel)
con = duckdb.connect()
con.execute("INSTALL httpfs; LOAD httpfs; SET s3_region='us-west-2';")
src = f"read_parquet('s3://overturemaps-us-west-2/release/{rel}/theme=places/type=place/*', hive_partitioning=1)"
CAFE = ("coffee_shop", "cafe", "tea_room", "bubble_tea_shop", "dessert_shop", "bakery", "ice_cream_shop", "cupcake_shop", "donut_shop", "smoothie_juice_bar")
SKIP_FOOD = ("bar", "pub", "lounge", "cocktail_bar", "beer_bar", "internet_cafe", "candy_store", "wine_bar", "hookah_bar", "night_club")
# Records that are offices, shops or buildings rather than somewhere to eat or get help.
NOT_A_PLACE = re.compile(r"(?i)บริษัท|จำกัด|หจก|ห้างหุ้นส่วน|ศูนย์กระจายสินค้า|^อาคาร|ซัพพลาย|supply|co\.,? ?ltd|limited|เครื่องดับเพลิง|ถังดับเพลิง|อุปกรณ์")
# Departments and wards listed as if they were hospitals.
HOSPITAL_PART = re.compile(r"(?i)\bicu\b|i\.c\.u|แผนก|หอผู้ป่วย|ward|ตึก|อาคาร|คลินิก|clinic|department|ห้องฉุกเฉิน|ห้อง|ชั้น|floor|ศูนย์")
rows = con.execute(f"""
  SELECT id, names.primary, names.common['en'], names.common['th'], taxonomy.primary, taxonomy.hierarchy[1], confidence,
         phones[1], websites[1], socials[1], addresses[1].freeform, bbox.ymin, bbox.xmin, operating_status
  FROM {src}
  WHERE bbox.xmin BETWEEN 97.3 AND 105.7 AND bbox.ymin BETWEEN 5.6 AND 20.5
    AND coalesce(addresses[1].country, 'TH') = 'TH' AND coalesce(operating_status, 'open') = 'open'
    AND (taxonomy.hierarchy[1] = 'food_and_drink' OR taxonomy.primary IN ('hospital', 'emergency_department', 'police_station', 'fire_station')
         OR names.primary LIKE 'สถานีตำรวจ%' OR names.primary LIKE 'สภ.%' OR names.primary LIKE '%ดับเพลิง%' OR names.primary LIKE 'โรงพยาบาล%')
""").fetchall()
n = 0
with open(sys.argv[1], "w", encoding="utf-8") as f:
    for (oid, name, en, th, tp, top, conf, phone, web, social, addr, lat, lon, st) in rows:
        if not name: continue
        tp = tp or ""
        if NOT_A_PLACE.search(name): continue
        public = tp in ("", "government_office", "community_and_government", "social_or_community_service", "military_site")
        if tp == "police_station" or (name.startswith(("สถานีตำรวจ", "สภ.")) and public): kind = "police"
        elif tp == "fire_station" or (re.match(r"(สถานี|หน่วย|กอง|ศูนย์)?.{0,12}ดับเพลิง", name) and public): kind = "fire"
        elif tp == "hospital" or (name.startswith("โรงพยาบาล") and (public or tp == "health_care")):
            if HOSPITAL_PART.search(name): continue
            kind = "hospital"
        elif top == "food_and_drink" and tp not in SKIP_FOOD: kind = "cafe" if tp in CAFE else "restaurant"
        else: continue
        floor = {"restaurant": 0.65, "cafe": 0.65, "hospital": 0.6}.get(kind, 0.4)
        if (conf or 0) < floor: continue
        f.write(json.dumps({"id": oid, "kind": kind, "name": name, "en": en, "th": th, "cat": tp, "conf": round(conf, 2), "phone": phone,
                            "website": web, "social": social, "address": addr, "lat": lat, "lon": lon}, ensure_ascii=False) + "\n")
        n += 1
print("Overture rows kept", n)
