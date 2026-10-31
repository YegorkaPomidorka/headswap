# 0013. Legacy ADB-режим — отдельный APK с пакетом com.baidu.carlife

- Статус: принято (в `main` не влито, живёт в ветке `claude/carlife-adb-release`)
- Дата: 2026-10-19 (решение про отдельный APK), 2026-10-30 (ветки не удалять)
- Источники: кейс h60, ветки `claude/carlife-adb-release`, `claude/carlife-adb-diag` (`origin`), `link/CarTransport.kt`

## Контекст

Старые ГУ (h60, Subaru) запускают CarLife сами: `am start com.baidu.carlife/.CarlifeActivity`, пушат `bdim.jar` и ищут
именно пакет `com.baidu.carlife`. Обычный Headswap (`com.yegorkapomidorka.headswap`) для такого ГУ невидим.

## Решение

Отдельная сборка с `applicationId = com.baidu.carlife`, `versionName = 8.6.7`, `versionCode = 259` (как у настоящего CarLife:
ГУ может смотреть версию пакета). Собирается `gradlew assembleDebug -PcarlifePackage` (в ветке: `android/app/build.gradle.kts:15`).
Связь с ГУ по ADB — через `MultiSocketTransport` с `TransportCaps.TCP_ADB` (ветка, `CarTransport.kt:23`; в `main` такой
константы нет, там только `USB_AOA`, `TCP_DEBUG`, `TCP6`). Проверка обновлений выключена (0012).
Егор 30.10: ветки `claude/carlife-adb-release` и `claude/carlife-adb-diag` не удалять, поддержка продолжается.

## Альтернативы

- Переключатель режима в обычном Headswap — не работает: ГУ ищет пакет с конкретным именем.
- Заглушка-APK с флажком в основном приложении (19.10) — отвергнута, чтобы не смешивать legacy и прод.

## Последствия

- Две линии приложения: правки общего кода (`aa/`, `CarLifeSession`) придётся подтягивать в ветку вручную.
- Ветка живёт отдельно и в `main` не вливается — по той же причине: legacy и прод не смешиваем.
