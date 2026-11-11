#!/usr/bin/env python3
"""Диагноз по логу Headswap: вероятные причины проблемы и что делать.

Запуск:  python -X utf8 tools/logdiag.py <zip|папка|файл.log> [--json] [--all]

Поверх tools/logdigest.py: берёт его сводку, считает по сырому логу маркеры из
tools/logdiag_rules.json и прогоняет правила. Правила — данные (JSON), движок только
сравнивает факты, поэтому их можно дополнять без кода и перенести в приложение.
Только stdlib.
"""
import json
import os
import re
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import logdigest as ld  # noqa: E402

RULES_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "logdiag_rules.json")
CHANNEL_RE = re.compile(r"STATISTIC_INFO channel=(\d+)")
EIO_RE = re.compile(r"EIO \(I/O error\)|ENODEV")
PAUSE_RE = re.compile(r"VIDEO_ENCODER_PAUSE")
SHOW = 3          # сколько причин показывать
MIN_SCORE = 30    # ниже — не показываем


def vtuple(v):
    m = re.match(r"(\d+)\.(\d+)(?:\.(\d+))?", v or "")
    return tuple(int(x or 0) for x in m.groups()) if m else None


def load_rules(path=RULES_PATH):
    with open(path, encoding="utf-8") as f:
        r = json.load(f)
    r["_markers"] = {k: re.compile(v) for k, v in r["markers"].items()}
    return r


# ---------------------------------------------------------------- факты

def scan_text(logs, logcats, markers):
    """Маркеры: число событий (совпадения в пределах 2 с — одно событие: одна ошибка
    пишется и в лог, и в WL), первая и последняя строка. Плюс пауза → EIO и channel."""
    found = {k: {"n": 0, "first": None, "last": None, "_t": None, "ts": []} for k in markers}
    channel = None
    pause_eio = 0
    for name, text in logs + logcats:
        clock = ld.Clock(ld.stamp_of(name))  # дата из имени: время разных файлов не должно совпадать
        last_pause = None
        for raw in text.splitlines():
            m = ld.TS_RE.match(raw)
            t = clock.at(int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4))) if m else None
            for k, rx in markers.items():
                if rx.search(raw):
                    f = found[k]
                    if t is None or f["_t"] is None or abs((t - f["_t"]).total_seconds()) > 2:
                        f["n"] += 1
                        if t is not None:
                            f["ts"].append(t)
                    f["_t"] = t
                    line = raw.strip()[:200]
                    if f["first"] is None:
                        f["first"] = line
                    f["last"] = line
            if channel is None:
                mc = CHANNEL_RE.search(raw)
                if mc:
                    channel = mc.group(1)
            if t is None:
                continue
            body = m.group(5)
            if PAUSE_RE.search(body):
                last_pause = t
            elif last_pause and EIO_RE.search(body):
                dt = (t - last_pause).total_seconds()
                if 3 <= dt <= 10:
                    pause_eio += 1
                last_pause = None
    return found, channel, pause_eio


def near(a, b, sec):
    """Сколько событий из a случилось в пределах sec секунд от какого-нибудь события из b."""
    return sum(1 for x in a if any(abs((x - y).total_seconds()) <= sec for y in b))


def facts_of(path, rules):
    device, logs, logcats = ld.load_sources(path)
    dg = ld.Digest()
    for name, text in logs:
        dg.feed_log(name, text)
    for name, text in logcats:
        dg.feed_logcat(name, text)
    r = ld.build(dg, device, logcats)
    found, channel, pause_eio = scan_text(logs, logcats, rules["_markers"])

    env = r.get("env") or {}
    ss = r.get("sessions") if isinstance(r.get("sessions"), list) else []
    ep = r.get("episodes") if isinstance(r.get("episodes"), dict) else {}
    counts = ep.get("counts") or {}
    frames = [s["frames"] or 0 for s in ss]
    # видео от телефона (AA), а не кадры заглушки: «rx=.. config=.. data=N»
    aa_video = [int(m.group(1)) for s in ss if (m := re.search(r"data=(\d+)", s.get("aa") or ""))]
    eio_s = [s for s in ss if re.search(r"EIO|ENODEV", s.get("reason") or "")]
    durs = [s["dur_s"] for s in eio_s if s.get("dur_s") is not None]
    try:
        sdk = int(env.get("sdk") or 0) or None
    except ValueError:
        sdk = None
    f = {
        "lines": r.get("lines") or 0,
        "version": env.get("headswap"),
        "device": env.get("device"),
        "android": env.get("android"),
        "sdk": sdk,
        "hu": " | ".join(re.sub(r" x\d+$", "", h) for h in env.get("hu") or []),
        "channel": channel or "",
        "status": env.get("status") or "",
        "requested": " | ".join(sorted({s["requested"] for s in ss if s.get("requested")})),
        "n_sessions": len(ss),
        "frames_max": max(frames, default=0),
        "frames_total": sum(frames),
        "two_frame_sessions": sum(1 for x in frames if x == 2),
        "aa_video_max": max(aa_video, default=None),
        "eio_sessions": len(eio_s),
        "eio_short": sum(1 for d in durs if d <= 15),
        "eio_median_s": statistics.median(durs) if durs else None,
        "no_gms": ep.get("no_gms_flags") or 0,
        "pause_eio": pause_eio,
        # abort в ту же секунду, что пропал Wi-Fi, — телефон потерял сеть; без этого — VPN/стек
        "abort_iface": near(found.get("abort", {}).get("ts", []), found.get("iface_down", {}).get("ts", []), 3),
    }
    f["abort_no_iface"] = found.get("abort", {}).get("n", 0) - f["abort_iface"]
    for k, v in counts.items():
        f["ep_" + k] = v
    for k, v in found.items():
        f["m." + k] = v["n"]
    return f, found, r


# ---------------------------------------------------------------- правила

def check(cond, f):
    name, op, val = cond
    x = f.get(name)
    if op == "~":
        return bool(x) and re.search(val, str(x)) is not None
    if op == "<v":
        a, b = vtuple(x), vtuple(val)
        return a is not None and a < b
    if op == ">=v":
        a, b = vtuple(x), vtuple(val)
        return a is not None and a >= b
    if x is None:
        if op in ("==", "!="):  # «нет данных» не равно нулю
            return (op == "!=") != (val is None)
        x = 0
    if op == "==":
        return x == val
    if op == "!=":
        return x != val
    if op == ">=":
        return x >= val
    if op == "<=":
        return x <= val
    if op == ">":
        return x > val
    if op == "<":
        return x < val
    raise ValueError(f"неизвестный оператор {op}")


def diagnose(f, found, rules):
    hits = []
    for rule in rules["rules"]:
        if not all(check(c, f) for c in rule.get("all", [])):
            continue
        if rule.get("any") and not any(check(c, f) for c in rule["any"]):
            continue
        if any(check(c, f) for c in rule.get("none", [])):
            continue
        score = rule["score"]
        for cond, add in rule.get("boost", []):
            if check(cond, f):
                score += add
        advice = rule["advice"]
        steps = [s["text"] for s in rule.get("steps", [])]
        fixed = rule.get("fixed_in")
        if fixed:
            v = vtuple(f.get("version"))
            if v is not None and v >= vtuple(fixed):
                continue  # уже исправлено в версии юзера — эта причина не та
            advice = f"Обновите Headswap до {fixed} или новее (последняя {rules['latest']}): там это исправлено. " + advice
            steps.insert(0, f"Обновите Headswap до {fixed} или новее")
        evidence = []
        for k in rule.get("evidence", []):
            e = found.get(k)
            if e and e["n"]:
                evidence.append(f"{e['first']}" + (f"  (×{e['n']})" if e["n"] > 1 else ""))
        hits.append({"id": rule["id"], "title": rule["title"], "phase": rule["phase"],
                     "category": rule["category"], "score": min(score, 100),
                     "what": rule.get("what"), "steps": steps,
                     "advice": advice, "ask": rule.get("ask"), "evidence": evidence,
                     "suppresses": rule.get("suppresses", [])})
    suppressed = {s for h in hits for s in h["suppresses"]}
    hits = [h for h in hits if h["id"] not in suppressed]
    hits.sort(key=lambda h: -h["score"])
    return hits


# ---------------------------------------------------------------- вывод

def summary_line(f):
    parts = []
    if f["version"]:
        parts.append(f"Headswap {f['version']}")
    if f["device"]:
        parts.append(f"{f['device']}" + (f", Android {f['android']}" if f["android"] else ""))
    if f["hu"]:
        parts.append(f"ГУ: {f['hu']}" + (f" (channel {f['channel']})" if f["channel"] else ""))
    elif f["channel"]:
        parts.append(f"ГУ channel {f['channel']}")
    return "; ".join(parts) or "окружение не определено (нет device.txt)"


def state_line(f):
    if f["n_sessions"] == 0:
        return "сессий с магнитолой не было"
    s = f"сессий {f['n_sessions']}, максимум кадров {f['frames_max']}"
    if f["eio_sessions"]:
        s += f", оборвано по USB {f['eio_sessions']}"
    return s


def render(f, hits, show_all=False):
    L = [summary_line(f), "Итог: " + state_line(f), ""]
    notes = [h for h in hits if h["category"] == "note"]
    top = [h for h in hits if h["score"] >= MIN_SCORE and h["category"] != "note"]
    if not show_all:
        top = top[:SHOW]
    if not top:
        for h in notes:
            L.append(f"Заметка: {h['advice']}")
        if not any(h["id"] == "works" for h in notes):
            L.append("Причина не распознана. Пришлите лог разработчику с описанием: что делали и что видно на магнитоле.")
        return "\n".join(L)
    for i, h in enumerate(top, 1):
        L.append(f"{i}. {h['title']}  [{h['score']}%]")
        for e in h["evidence"][:2]:
            L.append(f"   в логе: {e}")
        L.append(f"   что случилось: {h['what']}")
        L.append("   как исправить:")
        for n, s in enumerate(h["steps"], 1):
            L.append(f"     {n}. {s}")
        if h["ask"]:
            L.append(f"   уточнить: {h['ask']}")
        L.append("")
    for h in notes:
        L.append(f"Заметка: {h['advice']}")
    L.append("Проценты — уверенность правила, а не вероятность. Если совет не помог — пришлите лог разработчику.")
    return "\n".join(L)


def run(path, rules=None):
    rules = rules or load_rules()
    f, found, _ = facts_of(path, rules)
    return f, diagnose(f, found, rules)


def main(argv):
    # консоль Windows (cp1251) падает на «×» из device.txt
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    args = [a for a in argv[1:] if not a.startswith("--")]
    if len(args) != 1:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    f, hits = run(args[0])
    if "--json" in argv:
        for h in hits:
            h.pop("suppresses", None)
        print(json.dumps({"facts": f, "causes": hits}, ensure_ascii=False, indent=1))
    else:
        print(render(f, hits, "--all" in argv))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
