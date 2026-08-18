import pg8000.native

SQL = open('replay-backend/src/main/resources/sql/replay-calc-task-og.sql').read()
try:
    c = pg8000.native.Connection(host='100.92.86.64', port=15432, user='dbuser',
                                 password='OpenGauss@2026', database='postgres', timeout=20)
    for stmt in [s.strip() for s in SQL.split(';') if s.strip()]:
        c.run(stmt)
    print('DDL executed OK')
    rows = c.run("SELECT column_name, data_type, is_nullable FROM information_schema.columns "
                 "WHERE table_name='replay_calc_task' ORDER BY ordinal_position")
    for r in rows:
        print('  ', r[0], r[1], 'null=' + r[2])
    cnt = c.run("SELECT count(*) FROM replay_calc_task")[0][0]
    print('rows=', cnt)
    c.close()
except Exception as e:
    print('OG ERROR:', e)
