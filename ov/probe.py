import duckdb, subprocess
out = subprocess.run(["aws", "s3", "ls", "--no-sign-request", "s3://overturemaps-us-west-2/release/"], capture_output=True, text=True).stdout
rel = sorted(l.split()[-1].strip("/") for l in out.splitlines() if "PRE" in l)[-1]
con = duckdb.connect()
con.execute("INSTALL httpfs; LOAD httpfs; SET s3_region='us-west-2';")
src = f"read_parquet('s3://overturemaps-us-west-2/release/{rel}/theme=places/type=place/*', hive_partitioning=1)"
con.execute(f"""CREATE TABLE th AS SELECT names.primary AS name, taxonomy.primary AS tp, taxonomy.hierarchy AS h, basic_category AS bc, confidence, phones, addresses[1].country AS cc, operating_status st
  FROM {src} WHERE bbox.xmin BETWEEN 97.3 AND 105.7 AND bbox.ymin BETWEEN 5.6 AND 20.5""")
print(con.execute("SELECT cc, count(*) FROM th GROUP BY cc ORDER BY 2 DESC LIMIT 6").fetchall())
print(con.execute("SELECT st, count(*) FROM th GROUP BY st").fetchall())
for r in con.execute("SELECT tp, bc, count(*), count(phones) FROM th WHERE tp ILIKE '%police%' OR tp ILIKE '%fire%' OR tp ILIKE '%hospital%' OR tp ILIKE '%emergency%' OR tp ILIKE '%rescue%' OR tp ILIKE '%clinic%' OR bc ILIKE '%police%' GROUP BY 1,2 ORDER BY 3 DESC LIMIT 40").fetchall(): print(r)
for r in con.execute("SELECT tp, bc, count(*) FROM th WHERE h[1]='food_and_drink' GROUP BY 1,2 ORDER BY 3 DESC LIMIT 50").fetchall(): print(r)
print(con.execute("SELECT name, tp, bc, phones FROM th WHERE name LIKE 'สถานีตำรวจ%' LIMIT 8").fetchall())
print(con.execute("SELECT tp, bc, count(*), count(phones) FROM th WHERE name LIKE 'สถานีตำรวจ%' OR name LIKE 'สภ.%' GROUP BY 1,2 ORDER BY 3 DESC LIMIT 8").fetchall())
print(con.execute("SELECT tp, bc, count(*), count(phones) FROM th WHERE name LIKE '%ดับเพลิง%' GROUP BY 1,2 ORDER BY 3 DESC LIMIT 8").fetchall())
print(con.execute("SELECT tp, bc, count(*), count(phones) FROM th WHERE name LIKE 'โรงพยาบาล%' GROUP BY 1,2 ORDER BY 3 DESC LIMIT 8").fetchall())
