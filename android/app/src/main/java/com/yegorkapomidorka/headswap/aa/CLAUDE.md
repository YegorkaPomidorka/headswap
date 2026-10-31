# aa/ — сторона Android Auto и ядро моста

Карта всего приложения — `docs/ARCHITECTURE.md`, решения — `docs/adr/`.

- `AaBridge.kt` (~2800 строк) читать по функции, не целиком: `onFrame`/`aaFrame` (видео), `onAudio*`, `onCarlifeTouch`,
  `onHuInfo`/`onHuStatistic` (определение ГУ), `attach()` (сброс состояния).
- Новый флаг ГУ `hu*`: константа в companion, `private set`, **сброс в `attach()`**, установка в `onHuInfo`/`onHuStatistic`,
  тест в `AaBridgeTest`. Включать только на своём ГУ (ADR 0006).
- `huVw20082120` по смыслу значит «heartbeat на PAUSE», не только VW 20082120.
- Сервер 5277 одноразовый: закрытие только через `AapSession.shutdown` (ByeBye → EOF), живую AA не рвать (ADR 0003).
- `VideoMode` и `CarPreset` хранятся в prefs по имени enum — не переименовывать значения.
- Проброс или перекодирование решается в четырёх местах (список в ARCHITECTURE.md, раздел «Видео»). Меняешь одно — проверь остальные.
- Тесты пакета — `android/app/src/test/java/.../aa/`, эталоны в `src/test/resources/aa/` (golden.txt генерирует `pc-headunit`).
