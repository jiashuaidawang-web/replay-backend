#!/usr/bin/env python3
"""验证重建后的 CK 表能接受 OG 真实数据（含 NULL），不再触发空校验。
重点测之前因 NOT NULL 拦截的列：stock_board_rel.effective_date/board_type、board_basic.board_type/create_date。
"""
import pg8000.native
import urllib.request, base64

OG = dict(host='100.92.86.64', port=15432, user='dbuser', password='OpenGauss@2026', database='postgres')
CK_URL = "http://124.223.220.245:8123/"
CK_AUTH = "Basic " + base64.b64encode(b"default:pamirs@123").decode()

def og_cols(table):
    c = pg8000.native.Connection(**OG)
    c.run(f"SELECT * FROM {table} LIMIT 0")
    names = [col['name'] for col in c.columns]
    c.close()
    return names

def og_rows(table, where, limit):
    c = pg8000.native.Connection(**OG)
    rows = c.run(f"SELECT * FROM {table} WHERE {where} LIMIT {limit}")
    c.close()
    return rows

def ck_cols(table):
    req = urllib.request.Request(CK_URL, data=f"DESCRIBE crawler.{table} FORMAT TabSeparated".encode(),
                                 headers={"Authorization": CK_AUTH})
    with urllib.request.urlopen(req, timeout=15) as r:
        return [line.split('\t')[0] for line in r.read().decode().splitlines() if line]

def to_tsv(rows):
    out = []
    for row in rows:
        cells = []
        for v in row:
            if v is None:
                cells.append('\\N')
            elif isinstance(v, bool):
                cells.append('1' if v else '0')
            else:
                s = str(v)
                # TabSeparated: tab/newline/backslash must be escaped
                s = s.replace('\\', '\\\\').replace('\t', '\\t').replace('\n', '\\n')
                cells.append(s)
        out.append('\t'.join(cells))
    return '\n'.join(out)

def ck_insert_tsv(table, cols, tsv):
    sql = f"INSERT INTO crawler.{table} ({', '.join(cols)}) FORMAT TabSeparated"
    body = sql.encode() + b'\n' + tsv.encode()
    req = urllib.request.Request(CK_URL, data=body, headers={"Authorization": CK_AUTH})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return None, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.read().decode(), ''

def ck_count(table):
    req = urllib.request.Request(CK_URL, data=f"SELECT count() FROM crawler.{table}".encode(),
                                 headers={"Authorization": CK_AUTH})
    with urllib.request.urlopen(req, timeout=15) as r:
        return r.read().decode().strip()

tests = [
    # (table, where_to_find_NULL_rows, label)
    ('stock_board_rel', "effective_date IS NULL OR board_type IS NULL", 'stock_board_rel 含NULL'),
    ('board_basic',     "board_type IS NULL OR create_date IS NULL",     'board_basic 含NULL'),
]

for table, where, label in tests:
    print(f"\n=== {label} ({table}) ===")
    cols = ck_cols(table)
    print(f"CK 列数: {len(cols)}")
    rows = og_rows(table, where, 5)
    print(f"OG 取到含NULL行数: {len(rows)}")
    if not rows:
        # 退化：取任意行也测一下写入通道
        rows = og_rows(table, "1=1", 3)
        print(f"  无NULL行，退化取任意行: {len(rows)}")
    if not rows:
        print("  跳过（OG无数据）")
        continue
    tsv = to_tsv(rows)
    err, ok = ck_insert_tsv(table, cols, tsv)
    if err:
        print(f"  INSERT 失败: {err[:300]}")
    else:
        print(f"  INSERT 成功，写入 {len(rows)} 行")
    print(f"  CK {table} 当前行数: {ck_count(table)}")

# 再做一次纯通道测试：main_fund_flow 三维度 NULL（obj_type=board 时 ts_code 为空）
print("\n=== main_fund_flow 三维度NULL (obj_type='board') ===")
table = 'main_fund_flow'
cols = ck_cols(table)
rows = og_rows(table, "obj_type='board'", 5)
print(f"OG 取到行数: {len(rows)}")
if rows:
    # 检查是否真有 NULL
    has_null = any(v is None for row in rows for v in row)
    print(f"  含NULL: {has_null}")
    tsv = to_tsv(rows)
    err, ok = ck_insert_tsv(table, cols, tsv)
    if err:
        print(f"  INSERT 失败: {err[:300]}")
    else:
        print(f"  INSERT 成功，写入 {len(rows)} 行")
    print(f"  CK {table} 当前行数: {ck_count(table)}")
