import pg8000.native
CONN = dict(host="100.92.86.64", port=15432, user="dbuser", password="OpenGauss@2026", database="postgres")
def q(sql, **kw):
    with pg8000.native.Connection(**CONN) as c:
        rows = c.run(sql, **kw)
        names = [col['name'] for col in c.columns]
        return rows, names
def show(sql, head=12, **kw):
    rows, names = q(sql, **kw)
    print("  cols:", names)
    for r in rows[:head]: print("   ", r)

D="2026-08-05"
print("-- trade_calendar populated? --")
show("SELECT COUNT(*), MIN(trade_date), MAX(trade_date), SUM(is_trading) FROM trade_calendar")
print("\n-- board_daily limit_up_count fill rate @%s --" % D)
show("SELECT COUNT(*) total, COUNT(limit_up_count) filled, COUNT(main_net) net_filled, COUNT(pct_chg) pct_filled FROM board_daily WHERE trade_date=:d", d=D)
print("\n-- concept boards (type=3) in board_basic vs members in stock_board_rel --")
show("""SELECT b.board_code, b.board_name,
              (SELECT COUNT(*) FROM stock_board_rel r WHERE r.board_code=b.board_code) AS members
       FROM board_basic b WHERE b.board_type=3
       ORDER BY members DESC LIMIT 8""")
print("\n-- concept board count --")
show("SELECT board_type, COUNT(*) FROM board_basic GROUP BY board_type")
print("\n-- limit_up stocks @%s joined to type=3 boards (sample of board attribution) --" % D)
show("""SELECT u.ts_code, u.board_pos,
              (SELECT STRING_AGG(r.board_code,',') FROM stock_board_rel r
                WHERE r.ts_code=split_part(u.ts_code,'.',1) AND r.board_type=3) AS type3_boards
       FROM limit_up_pool u WHERE u.trade_date=:d LIMIT 8""", d=D)
print("\n-- per concept board limit_up count @%s (joined), top 12 by followers --" % D)
show("""SELECT r.board_code, bb.board_name, COUNT(*) AS lim_up_cnt
       FROM limit_up_pool u
       JOIN stock_board_rel r ON r.ts_code=split_part(u.ts_code,'.',1) AND r.board_type=3
       LEFT JOIN board_basic bb ON bb.board_code=r.board_code
       WHERE u.trade_date=:d
       GROUP BY r.board_code, bb.board_name
       ORDER BY lim_up_cnt DESC LIMIT 12""", d=D)
print("\n-- yest_limit_ret check: 08-04 limit_up stocks pct on 08-05 --")
show("""SELECT AVG(d.pct_chg) avg_ret, COUNT(*) n
       FROM stock_daily d
       WHERE d.trade_date='2026-08-05'
         AND d.ts_code IN (SELECT ts_code FROM limit_up_pool WHERE trade_date='2026-08-04')""")
