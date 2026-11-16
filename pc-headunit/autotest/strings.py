"""Строки приложения по ключам из android/app/src/main/res/values*/strings*.xml - сценарии пишутся ключами
(`s.R.l_sec_look`), и один сценарий проходит на всех языках.

Разбор как у aapt: экранирование \\' \\" \\n \\t \\uXXXX, пробелы схлопываются (кроме "строки в кавычках"), теги вроде
<xliff:g>/<b> дают свой текст. Ключа нет в языке - английский, как сделает Android.
"""
from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from functools import lru_cache
from pathlib import Path

RES = Path(__file__).resolve().parents[2] / "android" / "app" / "src" / "main" / "res"

# тег языка приложения (AppLocale.TAGS) -> каталог ресурсов
LANG_DIRS = {"en": "values", "ru": "values-ru", "ar": "values-ar", "fr": "values-fr", "zh-CN": "values-zh-rCN"}
LANGS = tuple(LANG_DIRS)


def unescape(raw: str) -> str:
    out: list[str] = []
    quoted = False
    i, n = 0, len(raw)
    while i < n:
        ch = raw[i]
        if ch == "\\" and i + 1 < n:
            nx = raw[i + 1]
            if nx == "u" and re.fullmatch(r"[0-9a-fA-F]{4}", raw[i + 2:i + 6]):
                out.append(chr(int(raw[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append({"n": "\n", "t": "\t"}.get(nx, nx))
            i += 2
            continue
        if ch == '"':
            quoted = not quoted
            i += 1
            continue
        if ch.isspace() and not quoted:
            if not out or out[-1] != " ":
                out.append(" ")
            i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out).strip()


def _text(el: ET.Element) -> str:
    return "".join(el.itertext())


@lru_cache(maxsize=None)
def table(lang: str, res: Path = RES) -> dict[str, str]:
    """Ключ -> текст для языка (только строки этого каталога, без английского запаса)."""
    d = res / LANG_DIRS[lang]
    out: dict[str, str] = {}
    for f in sorted(d.glob("strings*.xml")):
        root = ET.parse(f).getroot()
        for el in root:
            name = el.get("name")
            if not name:
                continue
            if el.tag == "string":
                out[name] = unescape(_text(el))
            elif el.tag == "plurals":
                for it in el.findall("item"):
                    if it.get("quantity") == "other":
                        out[name] = unescape(_text(it))
            elif el.tag == "string-array":
                for k, it in enumerate(el.findall("item")):
                    out[f"{name}[{k}]"] = unescape(_text(it))
    return out


def untranslatable(res: Path = RES) -> set[str]:
    """Ключи с translatable="false" (бренды, форматы): их английский текст на любом языке - норма."""
    keys: set[str] = set()
    for f in sorted((res / "values").glob("strings*.xml")):
        for el in ET.parse(f).getroot():
            if el.get("translatable") == "false" and el.get("name"):
                keys.add(el.get("name"))
    return keys


_FMT = re.compile(r"%(?:(\d+)\$)?([-#+ 0,(]*\d*(?:\.\d+)?)([sdfx%])")


def format_android(text: str, *args) -> str:
    """String.format для %s, %d, %1$s, %.1f, %%."""
    seq = iter(range(len(args)))

    def sub(m: re.Match) -> str:
        pos, flags, conv = m.groups()
        if conv == "%":
            return "%"
        i = int(pos) - 1 if pos else next(seq)
        v = args[i]
        return ("%" + flags + conv) % v if conv in "dfx" else str(v)

    return _FMT.sub(sub, text)


class Strings:
    """Строки одного языка: `R.l_sec_look`, `R("l_battery", 80)`, `R.get("key")`."""

    def __init__(self, lang: str, res: Path = RES):
        if lang not in LANG_DIRS:
            raise ValueError(f"язык {lang!r}: есть {', '.join(LANGS)}")
        self.lang, self.res = lang, res
        self._own = table(lang, res)
        self._en = table("en", res)

    def get(self, key: str, *args) -> str:
        text = self._own.get(key)
        if text is None:
            text = self._en.get(key)
        if text is None:
            raise KeyError(f"нет строки {key!r} в {RES / 'values'}")
        return format_android(text, *args) if args else text

    def translated(self, key: str) -> bool:
        return key in self._own

    def __call__(self, key: str, *args) -> str:
        return self.get(key, *args)

    def __getattr__(self, key: str) -> str:
        if key.startswith("_"):
            raise AttributeError(key)
        try:
            return self.get(key)
        except KeyError as e:
            raise AttributeError(str(e)) from None
