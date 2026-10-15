# CLAUDE.md

## Как изменения попадают в main

Репозиторий github.com/YegorkaPomidorka/headswap, владелец Егор. Порядок всегда один:

1. **Worktree.** Правки только в отдельном worktree со своей веткой `claude/...`. В основном checkout (`main`) параллельно работают другие сессии, туда не коммитить.
2. **Ветка.** Коммиты в ветку worktree. `git add` только явных путей, без `-a`. В конце сообщения строка `Co-Authored-By`.
3. **Пуш ветки.** Через личный аккаунт gh, не через GCM (он уводит в рабочую организацию):
   `git -c credential.helper= -c "credential.helper=!gh auth git-credential" push -u origin <ветка>`
   Если main ушёл вперёд, сначала подтянуть его в ветку (в Claude Desktop — `sync_with_base_branch`), прогнать тесты.
4. **PR** в `main`: `gh pr create --repo YegorkaPomidorka/headswap --base main --head <ветка>`.
5. **Мердж.** Когда Егор говорит «залей» / «влей», сливать сразу, ревью не ждать — это его разрешение:
   `gh pr merge <номер> --repo YegorkaPomidorka/headswap --merge`
6. **После мерджа** main на GitHub обновлён. Локальный `main` в основном checkout подтянет та сессия, которая там работает; из worktree его не трогать.

Версия (`versionCode`/`versionName` в `android/app/build.gradle.kts`) поднимается отдельным коммитом «Version X.Y.Z (versionCode N)» только при выпуске релиза. Тестовые сборки (`-cx5test1` и т.п.) собираются с временной правкой версии, которая в коммит не попадает.

Релиз, кроме сайта, выкладывается в GitHub Releases: оттуда приложение обновляется само (`update/UpdateLogic.kt`). Тег `vX.Y.Z`, файл `headswap-X.Y.Z.apk` (имя как на сайте), описание релиза приложение показывает перед установкой:
`gh release create vX.Y.Z headswap-X.Y.Z.apk --repo YegorkaPomidorka/headswap --title "Headswap X.Y.Z" --notes-file <что нового>`
Тестовые сборки на GitHub выкладывать только с `--prerelease`: последним релизом GitHub их не считает, приложение их не предложит.

## Карточки юзеров hNN

«Забери h86», «разбери h12» и т.п. — это id карточки в трекере проблем юзеров Headswap Chat (`C:\Users\pomidorka\Documents\lily\tracking\headswap-issues.json`, поле `id`). Карточку, её логи и вложения запросить у сессии Telegram-дежурного (скилл `telegram-duty`): найти её через `ListAgents` и спросить через `SendMessage`. Файлы на диске по «h86» не искать.
