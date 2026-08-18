import pg8000.native

CONN = dict(host="100.92.86.64", port=15432, user="dbuser",
            password="OpenGauss@2026", database="postgres")

def q(sql, **kw):
    with pg8000.native.Connection(**CONN) as c:
        rows = c.run(sql, **kw)
        names = [col['name'] for col in c.columns]
        return rows, names

def show(sql, head=10, **kw):
    rows, names = q(sql, **kw)
    print("  cols:", names)
    for r in rows[:head]:
        print("   ", r)

print("-- distinct trade_date in limit_up_pool --")
show("SELECT trade_date, COUNT(*) FROM limit_up_pool GROUP BY trade_date ORDER BY trade_date")
print("\n-- distinct trade_date in board_daily --")
show("SELECT trade_date, COUNT(*) FROM board_daily GROUP BY trade_date ORDER BY trade_date")
print("\n-- distinct trade_date in stock_daily --")
show("SELECT trade_date, COUNT(*) FROM stock_daily GROUP BY trade_date ORDER BY trade_date")
print("\n-- limit_up_pool.board_code sample (truncation check) --")
show("SELECT DISTINCT board_code FROM limit_up_pool ORDER BY board_code LIMIT 40")
print("\n-- stock_board_rel.ts_code format sample --")
show("SELECT ts_code, board_code, board_name, board_type, is_leader FROM stock_board_rel WHERE board_type=3 LIMIT 5")
print("\n-- limit_up_pool latest rows --")
show("SELECT ts_code, stock_name, board_code, board_pos, limit_style FROM limit_up_pool WHERE trade_date=(SELECT MAX(trade_date) FROM limit_up_pool) LIMIT 10")
print("\n-- concept count + sample --")
show("SELECT * FROM concept LIMIT 5")
print("\n-- board_daily main_net/board_type latest, top by limit_up_count --")
show("SELECT board_code, board_name, board_type, pct_chg, limit_up_count, main_net FROM board_daily WHERE trade_date=(SELECT MAX(trade_date) FROM board_daily) ORDER BY limit_up_count DESC NULLS LAST LIMIT 12")
print("\n-- main_fund_flow obj_type distribution --")
show("SELECT obj_type, COUNT(*), COUNT(DISTINCT trade_date) FROM main_fund_flow GROUP BY obj_type")
print("\n-- main_fund_flow board sample latest --")
show("SELECT board_code, main_net, super_big, big_net FROM main_fund_flow WHERE obj_type='board' AND trade_date=(SELECT MAX(trade_date) FROM main_fund_flow) ORDER BY main_net DESC NULLS LAST LIMIT 8")
