# God — дорожная карта автономного Unity Intelligence Analyzer

**Дата актуализации:** 22 ноября 2026 года
**Назначение:** построить автономный инструмент исследования Unity-игр, который формирует доказательное техническое досье и карту переноса.

## 1. Главная цель

God должен самостоятельно исследовать выбранную папку Windows Unity-игры и ответить на вопросы: что находится внутри, как устроена сборка, как связаны файлы, код, Unity-объекты и ресурсы, какие API и системные возможности используются, что потребуется реализовать в целевой среде и какие интерфейсы между подсистемами необходимо соединить.

**Ключевой результат — Porting Dossier:** связанный комплект документов и dependency/wiring graph, а не просто inventory файлов и не API для импорта в другой проект.

God — самостоятельный Android-first исследовательский инструмент. История ручного исследования файлов в проекте Ramen (в том числе при работе с system-nnn) объясняет мотивацию. RamenUnity — отдельный проект; обязательной связи, общего runtime или импортёра отчётов между God и RamenUnity не требуется.

## 2. Принципы разработки

1. **Один выбор папки.** Обычный рабочий процесс начинается с выбора корня игры. God сам обнаруживает файлы и вложенные поддерживаемые контейнеры.
2. **Связное исследование.** Каждый анализатор передаёт находки в общий индекс и граф; отдельные списки без разрешённых связей — промежуточный результат.
3. **Evidence-first.** Каждый вывод имеет первичный источник, locator, метод, confidence и parser status.
4. **Честное покрытие.** Лимиты, недоступные файлы, повреждения, неизвестные форматы и unresolved links нельзя скрывать.
5. **Version-aware.** Форматы Unity, Mono и IL2CPP анализируются с учётом версий и реальных compatibility matrices.
6. **Безопасность.** God работает read-only, не запускает game binaries и не исполняет игровые скрипты.
7. **Масштабируемость.** Потоковое/позиционное чтение, bounded decompression, cancellation и ограниченное потребление памяти.
8. **Результат для разработчика.** Отчёты описывают не только найденную зависимость, но и то, какая реализация, интерфейс и тест понадобятся при переносе.
9. **Нет обязательной межпроектной интеграции.** JSON/manifest нужны для структурированного хранения, валидации и воспроизводимости, а не потому, что RamenUnity обязан их импортировать.

## 3. Целевая структура анализа

### Этап A — Inventory и file access

**Уже есть в MVP:** SAF recursive traversal с лимитами 100 000 entries/depth 40; JSON files[] содержит записи, собранные обходом; есть агрегированные ScanCoverage, базовый progress callback, single-job coordinator, job-scoped cancellation и inventory-only partial report при отмене. В рабочей ветке добавлен начальный typed `ScanIssue` слой: code/stage/severity/path/message публикуются в JSON `issues[]`, а `warnings[]` оставлен для совместимости. Markdown tree отдельно ограничен 20 000 строк.

**Цель расширения (не повторная реализация):**
- Добавить stable file IDs и per-file terminal statuses: discovered, queued, analyzed, partial, skipped, inaccessible, unsupported, failed.
- Сохранить текущий JSON inventory и связать его записи с analyzer runs/findings.
- Поддержка large-file streaming и positional/random-access reads через SAF abstraction.
- Потоковое хеширование по опции для сравнения файлов и доказательства идентичности.
- Grouping split files, sidecars и связанных ресурсов.
- Профили Quick / Standard / Deep с явными лимитами; Deep должен стремиться к полному обходу всех доступных поддерживаемых объектов.
- Любой cutoff отражается в coverage и не трактуется как отсутствие содержимого.

### Этап B — Container intelligence

- UnityFS block info, directory entries, compressed blocks.
- LZ4/LZMA и другие форматы только при наличии проверенной поддержки.
- Чтение отдельных bundle entries без распаковки всего архива в RAM.
- AssetBundle → SerializedFile → object/resource hierarchy.
- ZIP и другие распространённые контейнеры, вложенная структура с защитой от archive bombs.
- Поиск sidecar/external resources и разрешение путей.
- Отдельные состояния encrypted, unsupported, corrupted, truncated, inaccessible.
- Сохранение факта вложенности и источника каждого извлечённого entry.

### Этап C — Unity build profile и native analysis

- Многоканальное определение Unity version, target platform, build layout и scripting backend.
- PE headers, sections, data directories, RVA resolver, imports, exports, delay imports и forwarded exports.
- Граф native binaries → imported libraries → functions.
- Сопоставление импортов с реально найденными библиотеками и указание отсутствующих файлов.
- Динамическая загрузка и LoadLibrary/GetProcAddress clues с уровнем достоверности.
- Отдельные признаки Windows-only API, x86/x64 limitations и platform plugin dependencies.
- Полная evidence chain для версии Unity, архитектуры, build target и backend.

### Этап D — Managed / Mono code intelligence

- Полная поддерживаемая metadata inventory: assemblies, namespaces, nested types, inheritance, interfaces, fields, methods, attributes и references.
- P/Invoke maps с library, entry point, calling convention и flags.
- IL method body parser с локальными сигнатурами, exception handlers и token references.
- IL instruction decoding и static call-site graph.
- Cross-assembly symbol resolution.
- Отдельные статусы для AssemblyRef, call site, statically reachable estimate и runtime-unverified.
- Reflection, delegates, dynamic loading и unknown IL patterns как явные coverage gaps.
- Проверка выбранных parser libraries, Android packaging и лицензий до добавления.

### Этап E — IL2CPP intelligence

- Versioned parser framework для global-metadata.dat.
- Извлечение assemblies, types, fields, methods, generic data и attributes по поддерживаемым layouts.
- Mapping metadata tokens к доступным native structures.
- PE/native dependency inventory для GameAssembly.
- Архитектурные ограничения и native boundary report.
- Отдельная матрица версий и тестовые fixtures; metadata parsing не путать с выполнением IL2CPP кода.

### Этап F — Unity serialized data и scene graph

- Матрица Unity version × SerializedFile version × TypeTree encoding.
- Полноценные bounded/streaming object-table reads без скрытого ограничения только первыми samples.
- Полные TypeTree и shared TypeTree subtree payload для поддерживаемых версий.
- Local и external PPtr resolution; GUID/path и bundle references.
- Декодеры GameObject, Transform, Component, MonoBehaviour, MonoScript, ScriptableObject и других форматов.
- Scene/prefab/object graph с component order, enabled/active flags, parent-child relations и unresolved edges.
- Статусы raw/metadata-only/partial/decoded/unsupported per object.
- Golden fixtures для разных поколений Unity.

### Этап G — Asset/resource intelligence

- Texture2D: размеры, formats, mipmaps, streaming references.
- Mesh: vertex layout, indices, submeshes, bounds.
- AudioClip: format, channels, frequency, payload references.
- Material/Shader: properties, shader links, variants и texture dependencies.
- AnimationClip, Animator, fonts, TextAsset, ScriptableObject.
- Resource, StreamingAssets и sidecar mapping.
- AssetBundle dependency graph и shared-resource mapping.
- По умолчанию metadata-first; payload export только по необходимости и в пределах безопасных лимитов.

### Этап H — Cross-reference и wiring graph

Это сквозной этап: он не должен откладываться до завершения всех декодеров. Новые анализаторы сразу публикуют структурированные nodes, edges и evidence.

Типы узлов:
- файлы, контейнеры и entries;
- native binaries, managed assemblies, types, methods и fields;
- Unity scenes, serialized objects, GameObjects, components и scripts;
- assets, resources, shaders и configurations;
- Unity APIs, platform APIs и runtime subsystems;
- blockers, implementation tasks и validation tests.

Типы связей:
- contains / extracted-from;
- imports / exports;
- references-assembly / references-type;
- inherits / implements;
- calls-static / calls-possibly-dynamic;
- invokes-pinvoke / loads-library;
- attaches-script / component-of;
- references-object / external-pointer;
- loads-asset / uses-resource;
- uses-unity-api / requires-platform-api;
- depends-on-subsystem / must-connect-to;
- unresolved-reference / unsupported-format.

У каждой связи — evidence, locator, confidence и unresolved reason. Неоднозначные совпадения имён не превращаются в подтверждённые связи.

### Этап I — Platform requirements и porting analysis

- Сводка API Unity и системных API, реально найденных в игре.
- Graphics/render pipeline, shader model и graphics API requirements.
- Input systems, keyboard/mouse/controller/touch and vibration.
- Audio, filesystem, threading/synchronization, networking and SDK integrations.
- Native plugin portability и missing DLL assessment.
- Required implementation / optional integration / potential issue / confirmed blocker / unknown.
- Матрица: требование → evidence → нужный интерфейс → зависимые подсистемы → задача реализации → тест.
- Порядок реализации, учитывающий граф зависимостей и bootstrap/load order.
- Никаких гарантий запуска только по статическим данным.

### Этап J — Porting Dossier и отчётная система

Целевой комплект:

1. Game Overview.
2. Build Profile.
3. Complete File Inventory.
4. Container & Archive Report.
5. Native Dependency Report.
6. Managed Code Report.
7. Unity Data & Scene Report.
8. Assets & Resources Report.
9. Dependency & Wiring Map.
10. Platform Requirements.
11. Porting Implementation Plan.
12. Blockers & Unknowns.

Отчёт должен быть навигационным: каждый тезис ведёт к evidence и связанным узлам графа. Для больших сборок использовать split Markdown/JSONL и индекс, а не один огромный текстовый файл.

Machine-readable JSON, JSON Schema, format version и manifest применяются для воспроизводимости, проверки целостности и последующей автоматизации. Это внутреннее качество исследовательских материалов, не межпроектный импортный контракт.

## 4. Приоритетный порядок реализации

| Приоритет | Работа | Результат |
|---|---|---|
| P0 | Исправность текущих анализаторов, честные coverage и тестовые fixtures | Достоверная база |
| P1 | FileAccess abstraction, random access, streaming и распространение cancellation в операции чтения | Надёжный полный обход; inventory-only partial report уже реализован, но отмена внутри provider reads и analyzer-level partial results остаются открытыми |
| P1 | UnityFS/ZIP/container readers | Доступ к вложенному содержимому |
| P1 | Per-file IDs/statuses и streaming report pipeline поверх существующего JSON files[] inventory | Ничего не теряется между анализаторами |
| P2 | PE/native dependency graph | Понимание нативных границ |
| P2 | CLR/Mono metadata, IL, P/Invoke и call-site graph | Понимание managed-логики |
| P2 | SerializedFile/TypeTree/object graph | Понимание сцен и компонентов |
| P2 | Asset decoders and resource graph | Понимание контента |
| P2 | IL2CPP metadata and native mapping | Второй backend |
| P3 | Cross-reference/wiring graph and platform assessment | Сквозная картина зависимостей |
| P3 | Porting Dossier and actionable implementation plan | Главный продукт God |
| P3 | UI navigation, deep profiles, validation matrix | Практическая эксплуатация |

P1/P2 обозначают приоритет исследования и разработки, а не обязательный порядок для каждого файла. Общий graph/evidence format должен развиваться вместе с парсерами.

## 5. Acceptance criteria

### Текущий уровень проверок

- На проверенной main-базе `052c023` — 16 JVM test files / 91 @Test methods; run [#333](https://github.com/HelterFall/God/actions/runs/44800837048) прошёл JVM tests, `:app:assembleDebug` и upload APK artifact. Source code main соответствует `513f1ac`.
- Feature branch `feat/typed-scan-issues` содержит 17 JVM test files / 92 `@Test` methods: один отдельный `ScanIssueTest` плюс assertions в существующих scanner/partial-report tests. PR [#28 / run #335](https://github.com/HelterFall/God/actions/runs/44982658313) подтвердил эту feature branch: JVM tests, debug APK build и artifact upload прошли.
- `PeParserTest` проверяет PE32/PE32+ named/ordinal imports, ARM64 machine recognition и negative RVA cases. Parser tests в основном используют synthetic inputs.
- Fake-tree integration coverage `GameScanner.scan()` включает PE imports, synthetic CLR metadata, Unity SerializedFile v22, ambiguous и successful external MonoScript resolution, Unity candidate open failure и directory listing failure с partial inventory/coverage и typed `ScanIssue`. Широкая Unity version/TypeTree/scene-PPtr matrix, truncated streams и прочие provider failure cases, реальные compiler-produced/Unity fixtures, Android SAF instrumentation, fuzz/property и coverage metrics пока отсутствуют.

Полная матрица и список QA gaps: [аудит тестирования и CI](TEST_AUDIT_2026-11-19.md).

### Надёжность анализа

- Для каждого файла есть итоговый статус или точное объяснение, почему статус неизвестен.
- Все ограничения, пропуски и ошибки отражаются в coverage.
- Отмена на контролируемых границах создаёт inventory-only partial report; незавершённые parser результаты не объявляются завершёнными.
- Большие файлы читаются потоково/позиционно с контролем памяти.
- Вложенные архивы имеют защиту от чрезмерной глубины и распакованного объёма.
- Повреждённые или зашифрованные данные не обозначаются как успешно разобранные.

### Полнота исследования

- Каждое семейство форматов имеет опубликованную матрицу поддерживаемых версий.
- Mono/IL2CPP, native/managed, Unity data, containers и assets тестируются на реальных fixtures.
- Взаимные ссылки разрешаются сквозным способом либо получают явный unresolved reason.
- Выводы имеют source locator и confidence.
- Отсутствие находки не выдаётся за доказательство отсутствия, если сканирование было ограничено.

### Полезность для переноса

- Для целевого профиля игры определены используемые подсистемы и API.
- Native/managed/platform boundaries показаны в карте зависимостей.
- Porting plan содержит интерфейсы, связи, порядок реализации и validation steps.
- Отдельно указаны confirmed blockers, optional integrations и unknowns.
- Разработчик может перейти из задачи переноса к конкретному evidence без повторного ручного исследования всей папки.

### Техническая зрелость

- CI стабильно зелёный.
- Golden/property/fuzz tests покрывают parser boundaries.
- Есть реальные, legально распространяемые fixtures и end-to-end scanner tests на небольших сборках.
- Есть тесты на повреждённые, большие и вложенные файлы.
- Android SAF проверен instrumentation tests на permission loss, providers без seek и Activity recreation.
- CI запускается на pull_request и сохраняет JUnit/coverage reports.
- Gradle Wrapper закрепляет build environment; GitHub Actions используют поддерживаемые runtimes.
- Поддерживаемые версии и известные ограничения опубликованы.
- Report schema/manifest проверяются автоматически.

## 6. Что не является целью God

- Запускать игру или исполнять EXE/DLL/IL.
- Быть Windows emulator или Unity runtime.
- Рендерить сцены.
- Реализовывать Android graphics/input/audio backend.
- Гарантировать успешный порт любой игры.
- Обязательный импорт отчёта в Ramen или RamenUnity.
- Подменять человеческую/динамическую проверку там, где статического анализа недостаточно.

## 7. Исполнение плана

Подробная последовательность работ, зависимости между этапами, ближайшие маленькие задачи и Definition of Done вынесены в отдельный документ:

[**План дальнейших действий God**](IMPLEMENTATION_PLAN.md)

Эта дорожная карта определяет конечные функциональные направления. План дальнейших действий определяет практический порядок изменений в коде и критерии перехода между этапами. При расхождении приоритет для ближайшего рабочего шага задаёт IMPLEMENTATION_PLAN.md, а полный перечень целевых функций сохраняется здесь.

Не добавлять изолированные декодеры ради количества. Каждый новый анализатор должен публиковать структурированные findings/evidence и расширять общую модель связей.
