import pg8000.native

CONN = dict(host="100.92.86.64", port=15432, user="dbuser",
            password="OpenGauss@2026", database="postgres")

def q(sql):
    with pg8000.native.Connection(**CONN) as c:
        return c.run(sql)

# 1) all user tables (skip crawler management tables for brevity but show all)
rows = q("""
    SELECT tablename
    FROM pg_tables
    WHERE schemaname='public'
    ORDER BY tablename
""")
tables = [r[0] for r in rows]
print("=== TABLES (%d) ===" % len(tables))
print(", ".join(tables))

# 2) for tables relevant to calc layer, print columns
focus = ["limit_up_pool","limit_pool","strong_pool","zhaban_pool","board_daily",
         "stock_board_rel","board_basic","stock_daily","main_fund_flow","concept",
         "sentiment_daily","mainline_daily","leader_pool_daily","theme_factor_daily",
         "four_dimension_daily","trend_candidate_daily","index_daily","trade_calendar"]
cols = q("""
    SELECT table_name, column_name, data_type, character_maximum_length
    FROM information_schema.columns
    WHERE table_schema='public'
    ORDER BY table_name, ordinal_position
""")
by_t = {}
for t, col, dt, ln in cols:
    by_t.setdefault(t, []).append((col, dt, ln))

for t in focus:
    if t not in by_t:
        print("\n### %s : (NOT FOUND)" % t)
        continue
    print("\n### %s ###" % t)
    for col, dt, ln in by_t[t]:
        print("  %-22s %s" % (col, dt))
