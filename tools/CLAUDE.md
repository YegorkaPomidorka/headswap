# tools/ — разбор логов

- Разбор лога пользователя: сначала `python -X utf8 tools/logdiag.py <zip|log>` (причина + совет), потом `logdigest.py` (сводка).
- `logdiag_rules.json` общий с приложением: gradle `copyLogdiagRules` кладёт его в assets, `diag/LogDiag.kt` читает те же правила.
  Новая причина = правило в JSON + кейс в `logdiag_cases/` из реального лога. Python и Kotlin должны давать одинаковый результат
  (`tools/test_logdiag.py`, `LogDiagTest.kt`).
- Маркеры — регулярки по сырым строкам лога. Формат строк `AppLog`/`WLog` — контракт (ADR 0016).
- Логи пользователей в репо не класть: репо публичный.
