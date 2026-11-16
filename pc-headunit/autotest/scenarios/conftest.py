"""pytest для сценариев на стенде (нужен телефон по USB). Запуск из pc-headunit:

    python -m pytest autotest/scenarios -v                  # прогон
    python -m pytest autotest/scenarios -v --update-refs    # переснять эталоны
    python -m pytest autotest/scenarios -v --no-prepare     # телефон уже подготовлен stand.ps1 -PrepareOnly

Один стенд на весь прогон; перед каждым сценарием - кнопка «Домой». Тест, в котором создан эталон, помечается
skipped («эталон создан»), а не passed: новый эталон надо глазами посмотреть в autotest/refs.
"""
from __future__ import annotations

import sys
import time
from pathlib import Path

import pytest

PC_HU = Path(__file__).resolve().parents[2]
if str(PC_HU) not in sys.path:
    sys.path.insert(0, str(PC_HU))

from autotest.stand import Stand   # noqa: E402
from autotest.strings import LANGS   # noqa: E402


def pytest_addoption(parser):
    g = parser.getgroup("stand")
    g.addoption("--update-refs", action="store_true", help="переснять эталоны")
    g.addoption("--no-prepare", action="store_true", help="не звать stand.ps1 -PrepareOnly")
    g.addoption("--hu", default="honda", choices=["honda", "mazda"])
    g.addoption("--size", default="", help="WxH экрана ГУ (по умолчанию как в stand.ps1)")
    g.addoption("--stand-out", default="", help="каталог отчёта (по умолчанию out/autotest-<время>)")
    g.addoption("--animations", action="store_true", help="не выключать анимации Android на время прогона")
    g.addoption("--lang", default="ru", help=f"языки через запятую ({','.join(LANGS)}) или all")


def pytest_generate_tests(metafunc):
    """Каждый сценарий - на каждом языке из --lang; стенд один на язык (смена языка = перезапуск приложения)."""
    if "stand" in metafunc.fixturenames:
        opt = metafunc.config.option.lang
        langs = list(LANGS) if opt == "all" else [x.strip() for x in opt.split(",") if x.strip()]
        bad = [x for x in langs if x not in LANGS]
        if bad:
            raise pytest.UsageError(f"--lang: нет языков {bad}, есть {', '.join(LANGS)}")
        metafunc.parametrize("lang", langs, scope="session")


_RUN = time.strftime("%Y%m%d-%H%M%S")


@pytest.fixture(scope="session")
def stand_session(request, lang):
    import carlife_view as V
    o = request.config.option
    size = V.parse_size(o.size) if o.size else ((1728, 648) if o.hu == "mazda" else (1280, 720))
    base = Path(o.stand_out) if o.stand_out else PC_HU / "out" / ("autotest-" + _RUN)
    out = base / lang
    s = Stand(out, size, o.hu, prepare=not o.no_prepare, update_refs=o.update_refs,
              animations=o.animations, lang=lang)
    s.start()
    yield s
    s.stop()
    print(f"\nотчёт стенда: {out}")


@pytest.fixture
def stand(stand_session, request):
    s = stand_session
    s.act(f"=== {request.node.nodeid}")
    s.home()
    request.node._stand_created_before = len(s.created)
    yield s


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
    outcome = yield
    rep = outcome.get_result()
    if rep.when != "call" or not rep.passed:
        return
    s = item.funcargs.get("stand") if hasattr(item, "funcargs") else None
    if s is None:
        return
    new = s.created[getattr(item, "_stand_created_before", 0):]
    if new:
        rep.outcome = "skipped"
        rep.longrepr = (str(item.fspath), item.location[1] or 0, "эталон создан: " + ", ".join(Path(p).name for p in new))
