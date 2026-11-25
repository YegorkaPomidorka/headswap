"""Прогнать команды на стенде без pytest - для разведки экранов и съёмки эталонов.

    python -m autotest dump "text Настройки" dump back "swipe 1000 400 300 400" dump

Команды (каждая - один аргумент, слова через пробел):
    dump                  кадр в out/steps + текст экрана (OCR) в консоль
    shot NAME             кадр в out/steps/NN-NAME.png
    tap X Y               тап в пикселях видео
    long X Y              долгое нажатие
    text ФРАЗА            тап по надписи (OCR)
    swipe X1 Y1 X2 Y2     свайп
    back | home | voice   кнопки ГУ
    key 0xNN              hard key
    wait SEC              пауза
    check NAME            сравнить с эталоном (нет эталона - создать)
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from .stand import PC_HU, Stand, StandError


def main(argv=None) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    p = argparse.ArgumentParser(prog="python -m autotest", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("commands", nargs="*")
    p.add_argument("--hu", default="honda", choices=["honda", "mazda"])
    p.add_argument("--size", default="")
    p.add_argument("--mode", default="desktop", choices=["desktop", "aa", "keep"])
    p.add_argument("--no-prepare", action="store_true", help="телефон уже подготовлен (stand.ps1 -PrepareOnly)")
    p.add_argument("--update-refs", action="store_true")
    p.add_argument("--out", default="")
    p.add_argument("--echo", action="store_true", help="лог эмулятора в консоль")
    a = p.parse_args(argv)
    import carlife_view as V
    size = V.parse_size(a.size) if a.size else ((1728, 648) if a.hu == "mazda" else (1280, 720))
    out = Path(a.out) if a.out else PC_HU / "out" / ("autotest-" + time.strftime("%Y%m%d-%H%M%S"))
    try:
        with Stand(out, size, a.hu, a.mode, prepare=not a.no_prepare, update_refs=a.update_refs, echo=a.echo) as s:
            print(f"video {s.video_size[0]}x{s.video_size[1]}, out: {out}")
            for cmd in a.commands:
                verb, *rest = cmd.split(" ", 1)
                arg = rest[0] if rest else ""
                nums = [int(v, 0) for v in arg.split()] if verb in ("tap", "long", "swipe", "key") else []
                print(f"> {cmd}")
                if verb == "dump":
                    f = s.shot("dump")
                    for line in s.text(f):
                        print("   ", line)
                elif verb == "shot":
                    s.shot(arg or "shot")
                elif verb == "tap":
                    s.tap(*nums)
                elif verb == "long":
                    s.long_press(*nums)
                elif verb == "text":
                    s.tap_text(arg)
                elif verb == "swipe":
                    s.swipe(*nums)
                elif verb in ("back", "home", "voice"):
                    getattr(s, verb)()
                elif verb == "key":
                    s.key(nums[0])
                elif verb == "wait":
                    time.sleep(float(arg))
                elif verb == "check":
                    s.check(arg)
                else:
                    raise StandError(f"неизвестная команда {verb!r}")
            if s.created:
                print("эталоны созданы:", *s.created, sep="\n    ")
    except StandError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
