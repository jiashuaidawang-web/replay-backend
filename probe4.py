import pg8000.native
CONN = dict(host="100.92.86.64", port=15432, user="dbuser", password="OpenGauss@2026", database="postgres")
def q(sql, **kw):
    with pg8000.native.Connection(**CONN) as c:
        rows = c.run(sql, **kw)
        names=[col['name'] for col in c.columns]
        return rows, names

# For each populated table, check NULL counts on columns CK declares NOT NULL (no Nullable)
checks = {
 "stock_daily": ["trade_date","ts_code"],  # update_date absent entirely
 "board_daily": ["update_date","create_date","board_code"],
 "limit_up_pool": ["update_date","trade_date","ts_code"],
 "limit_down_pool": ["update_date"],
 "zhaban_pool": ["update_date"],
 "strong_pool": ["update_date"],
 "cixin_pool": ["update_date"],
 "main_fund_flow": ["ts_code","board_code","index_code","update_date"],
 "stock_board_rel": ["update_date","effective_date","board_type"],
 "board_basic": ["update_date","create_date"],
}
print("=== openGauss NULL evidence (cols CK declares NOT NULL) ===")
for t, cols in checks.items():
    sel = ", ".join("COUNT(%s) FILTER (WHERE %s IS NULL) AS null_%s" % (c,c,c) for c in cols)
    total = "COUNT(*) AS total"
    rows, names = q("SELECT %s, %s FROM %s" % (total, sel, t))
    r = rows[0]
    print("%-16s total=%-8s" % (t, r[0]), {names[i]: r[i] for i in range(1,len(names))})

# does stock_daily have update_date/create_date at all?
rows, names = q("SELECT column_name FROM information_schema.columns WHERE table_name='stock_daily' AND column_name IN ('update_date','create_date')")
print("\nstock_daily has update_date/create_date:", [x[0] for x in rows] or "NONE")
rows, names = q("SELECT column_name FROM information_schema.columns WHERE table_name='index_daily' AND column_name IN ('update_date','create_date')")
print("index_daily has update_date/create_date:", [x[0] for x in rows] or "NONE")
rows, names = q("SELECT column_name FROM information_schema.columns WHERE table_name='stock_weekly' AND column_name IN ('update_date','create_date')")
print("stock_weekly has update_date/create_date:", [x[0] for x in rows] or "NONE")
