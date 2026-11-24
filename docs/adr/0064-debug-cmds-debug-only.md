# 0064. Отладочные команды MainActivity только в debug

- Статус: принято
- Дата: 2026-11-24
- Источники: `MainActivity.handleIntent`, `MainActivity.RELEASE_CMDS`

## Контекст

Аудит 24.11.2026: `MainActivity` экспортирован (LAUNCHER), а extra `cmd` исполнялся в release без проверки. Любое приложение
на переднем плане могло запустить мост в Wi-Fi B (6 портов на 0.0.0.0), слать UDP на любой адрес, отправить маршрут в хост Car App.

## Решение

В release проходит только allowlist `RELEASE_CMDS` (`phone_card_perm`, `wallpaper_pick` — их шлёт сам лаунчер), остальное при
`!BuildConfig.DEBUG` пишется в лог и игнорируется. Smoke = `initWith(debug)`, стенд и скрипты не задеты.
Отвергнуто: отдельная debug-activity: больше кода, а стенд и скрипты шлют команды на `MainActivity`.
