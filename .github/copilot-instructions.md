# OpenIPC Buildroot External Tree — Copilot Instructions

## File Header Standard

Every new file created in this project **must** include these lines in its
header comment (adapted to the file's comment syntax):

1. `Copyright (c) OpenIPC  https://openipc.org  MIT License`

### C / H files

```c
/*
 *
 * Copyright (c) OpenIPC  https://openipc.org  MIT License
 *
 * <filename> — <brief description>
 *
 */
```

### Makefiles, Kconfig, shell scripts, conf files

```makefile
#
# Copyright (c) OpenIPC  https://openipc.org  MIT License
#
# <filename> — <brief description>
#
```

### Buildroot .mk files

```makefile
################################################################################
#
# Copyright (c) OpenIPC  https://openipc.org  MIT License
#
# <package> — <brief description>
#
################################################################################
```

## Platform Constraints

- **Language standard: C99 / POSIX.1-2008** (uClibc / musl / glibc).
  Compile with `-std=c99 -D_POSIX_C_SOURCE=200809L` (or `-std=gnu99` when
  GNU extensions are needed). Do not use C11/C17-only features.
- No `nanosleep` — use `usleep` with `_XOPEN_SOURCE=600` or `_GNU_SOURCE`.
- Target C library: uClibc, musl, or glibc.
- Optimise for size (`-Os`, `-ffunction-sections`, `-Wl,--gc-sections`).

## Logging Requirements

- All C programs **must** log to syslog via `openlog(3)` / `syslog(3)`.
- Use `LOG_DAEMON` facility and appropriate levels (`LOG_INFO`, `LOG_ERR`, etc.).
- Duplicate important messages to stdout/stderr for interactive use.

## Git & Review Policy

- Copilot **must never** run `git commit`, `git push`, `gh pr create`,
  or any command that modifies Git history or creates pull requests.
- All commits and PRs are made **only by a human, manually**.
- After completing changes, Copilot should notify the user that the
  changes are ready and suggest the appropriate Git commands to review,
  commit, and push.
- After every completed task, Copilot **must** suggest a ready-to-paste
  `git commit` command with a commit message in **English**, following
  the Conventional Commits style (e.g. `fix:`, `feat:`, `refactor:`,
  `ci:`, `docs:`). Format example:
  ```
  git add -A && git commit -m "fix: brief summary" -m "- detail one
  - detail two"
  ```
