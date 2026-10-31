# 0007. VIDEO_HEARTBEAT на PAUSE: пустое сообщение раз в 100 мс

- Статус: принято
- Дата: 2026-10-14 (примерно, PR #2, #18), доработки до 2026-10-30 (PR #123)
- Источники: PR #2, #18, #37, #67, #68, #69, #72, #96, #123, кейсы h93, h222, h224, h425, h625, h211, `CarLifeSession.kt:1259-1303`, `:1463`

## Контекст

ГУ закрывает USB через 3,6–6 с после `VIDEO_ENCODER_PAUSE`: VW 20082120/22/34 (h93, h222, h224), CNS2 Delphi (h425),
Skoda 21290002 (h625), VW BT 1420 по воздуху, Desay G6S по воздуху (h211). Baidu во время паузы шлёт `VIDEO_HEARTBEAT`.

## Решение

На PAUSE сразу шлётся `VIDEO_HEARTBEAT` (0x20002, длина 0) и затем раз в 100 мс (`PAUSE_HEARTBEAT_MS`,
`CarLifeSession.kt:1463`) — как `Recorder.sendEmptyPacketBackground` у Baidu. Вне паузы после `IDLE_HEARTBEAT_MS = 1 с` без
кадров шлётся тот же heartbeat (Delphi, Skoda). Desay G6S по USB — прежний пустой `VIDEO_DATA` раз в 2 с, без heartbeat.
VW BT 1420 по воздуху дополнительно получает START с IDR энкодера, не дожидаясь IDR от AA. Включается для каждого ГУ
отдельно (0006), см. `pauseHeartbeatHu` (`CarLifeSession.kt:1270`).

## Альтернативы

- Пустой `VIDEO_DATA` вместо heartbeat — у BT 1420 ГУ рвала сессию через 0,2–0,4 с после START.
- Глобальный keepalive — ломал Honda (0006).
- Самообучаемый heartbeat по поведению ГУ — отложен после 3.0.0 (форк mhzsajan, см. 0006).

## Последствия

- Новый ГУ с обрывом через 3–6 с после PAUSE добавляется в `pauseHeartbeatHu` по каналу, а не общим включением.
- Интервал 100 мс взят у Baidu (0010).
