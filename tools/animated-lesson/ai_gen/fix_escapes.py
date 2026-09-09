# -*- coding: utf-8 -*-
"""修复上一步补丁在 shell heredoc 中被吞掉的反斜杠：
Python 把 \\f \\a \\t 解释成了控制字符（换页/响铃/制表），这里按字符串级还原为 LaTeX 命令。
只处理被污染的控制字符组合，幂等可重跑。"""
import json
import glob

FIX = {
    "\x0crac": "\\frac",
    "\x07ngle": "\\angle",
    "\x09riangle": "\\triangle",
    "\x0c": "\\f",       # 兜底：残留控制字符原样转义回可见反斜杠序列
    "\x07": "\\a",
    "\x09": "\\t",
}


def walk(obj):
    n = 0
    if isinstance(obj, dict):
        for k, v in obj.items():
            if isinstance(v, str):
                for bad, good in FIX.items():
                    if bad in v and not v.startswith("k_"):
                        obj[k] = v = v.replace(bad, good)
                        n += 1
            else:
                n += walk(v)
    elif isinstance(obj, list):
        for it in obj:
            n += walk(it)
    return n


total = 0
for path in glob.glob("lessons/*.json"):
    sb = json.load(open(path, encoding="utf-8"))
    cnt = walk(sb)
    if cnt:
        json.dump(sb, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(path, "fixed", cnt)
    total += cnt
print("total", total)
