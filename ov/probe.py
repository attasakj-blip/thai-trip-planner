import duckdb, subprocess, json, sys
out = subprocess.run(["aws", "s3", "ls", "--no-sign-request", "s3://overturemaps-us-west-2/release/"], capture_output=True, text=True).stdout
rels = sorted(l.split()[-1].strip("/") for l in out.splitlines() if "PRE" in l)
print("releases", rels[-4:])
rel = rels[-1]
con = duckdb.connect()
con.execute("INSTALL httpfs; LOAD httpfs; INSTALL spatial; LOAD spatial; SET s3_region='us-west-2';")
src = f"read_parquet('s3://overturemaps-us-west-2/release/{rel}/theme=places/type=place/*', hive_partitioning=1)"
cols = con.execute(f"DESCRIBE SELECT * FROM {src} LIMIT 0").fetchall()
for c in cols: print("COL", c[0], str(c[1])[:300])
names = [c[0] for c in cols]
if "categories" in names: cat = "categories.primary"
elif "basic_category" in names: cat = "basic_category"
elif "taxonomy" in names: cat = "taxonomy.primary"
else: cat = "NULL"
extra = ", ".join(n for n in ("taxonomy", "basic_category", "categories", "operating_status") if n in names)
con.execute(f"""CREATE TABLE th AS SELECT id, names.primary AS name, names.common AS common, {cat} AS cat, {extra},
  confidence, phones, websites, socials, addresses, brand, sources, bbox.xmin AS lon, bbox.ymin AS lat
  FROM {src} WHERE bbox.xmin BETWEEN 97.3 AND 105.7 AND bbox.ymin BETWEEN 5.6 AND 20.5""")
print(con.execute("SELECT * FROM th WHERE confidence > 0.9 LIMIT 2").fetchall())
print(con.execute("SELECT count(*) FROM th").fetchall())
print(con.execute("DESCRIBE th").fetchall())
for row in con.execute("SELECT cat, count(*) c FROM th GROUP BY cat ORDER BY c DESC LIMIT 60").fetchall(): print(row)
for cat in ("restaurant", "thai_restaurant", "cafe", "coffee_shop", "hospital", "police_department", "fire_department"):
    print(cat, con.execute(f"SELECT count(*), count(phones), avg(confidence) FROM th WHERE cat='{cat}'").fetchall())
for (la, lo, nm) in ((13.7500, 100.4913, "grand palace"), (14.4380, 101.3720, "khao yai"), (18.7883, 98.9853, "chiang mai"), (16.4419, 101.1300, "phu hin rong kla"), (7.8800, 98.3923, "phuket")):
    print("==", nm)
    for r in con.execute(f"""SELECT name, cat, round(confidence,2), phones, round(111*sqrt(pow(lat-{la},2)+pow((lon-{lo})*cos(radians({la})),2)),2) km FROM th
        WHERE cat IN ('restaurant','thai_restaurant','cafe','coffee_shop','seafood_restaurant','noodles_restaurant') AND abs(lat-{la})<0.05 AND abs(lon-{lo})<0.05 ORDER BY km LIMIT 12""").fetchall(): print("  ", r)
    print("  count within ~5km", con.execute(f"SELECT count(*) FROM th WHERE (cat LIKE '%restaurant%' OR cat IN ('cafe','coffee_shop')) AND abs(lat-{la})<0.045 AND abs(lon-{lo})<0.045 AND confidence>0.5").fetchall())
print(con.execute("SELECT name, cat, phones, websites, socials, sources FROM th WHERE cat='hospital' AND confidence>0.8 LIMIT 3").fetchall())
con.execute("COPY (SELECT * FROM th) TO 'th_places.parquet' (FORMAT parquet)")
