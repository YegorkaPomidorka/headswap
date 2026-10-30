# CLAUDE.md

## Ticket workflow

### Ticket isolation
- Each session owns exactly ONE ticket (hNNN). At session start, state the ticket ID, the reporter's name/handle and their head unit. Re-confirm it before any message, build or log analysis.
- Never message, build for, or act on another ticket's user (e.g. h319/@olya1305 vs h295) unless explicitly told to.
- If the user says stop or do nothing, stop all actions immediately.

### Research before building
- Default for a new ticket: collect ALL logs, photos and videos, then analyze and report a diagnosis.
- Do NOT build test APKs (hNNNtestN) or start implementing until the user explicitly says to build.
- Before adding a new video mode or fix, check existing branches, commits and open PRs for overlap (`git log --all --oneline | grep -i <keyword>`, `gh pr list`).

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
