import sqlite3
import sys

p = sys.argv[1]
con = sqlite3.connect(p)
cur = con.cursor()

cur.execute("SELECT name FROM sqlite_master WHERE type='table'")
tables = [r[0] for r in cur.fetchall()]
print("tables:", tables)

for t in tables:
    try:
        cur.execute(f"SELECT count(*) FROM {t}")
        print(f"  {t}: {cur.fetchone()[0]} rows")
    except Exception as e:
        print(f"  {t}: err {e}")

if "tiles" in tables:
    print("\nsample rows (key, provider, tile_len, expires):")
    cur.execute("SELECT key, provider, length(tile), expires FROM tiles ORDER BY rowid DESC LIMIT 10")
    for r in cur.fetchall():
        # key 是 MapTileIndex：z 在高位
        k = r[0]
        z = k & 0xFF
        print(f"  key={k} z={z} provider={r[1]} len={r[2]} expires={r[3]}")
