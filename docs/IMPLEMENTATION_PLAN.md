# God — план дальнейших действий

**Дата:** 22 ноября 2026 года  
**Базовая ветка:** main  
**Последний source commit в `main`:** `653e370c74beba48f2f5b8423276db84b6cecc51` (код PR #33; после него PR #34 синхронизировал документацию).
**Последний HEAD `main`:** `7f8256b4b6b2945971283b29fd6b46ba8aa1a616`.
**Последний main CI:** [run #387](https://github.com/HelterFall/God/actions/runs/45000959608) завершился `success` на HEAD `7f8256b`.
**Рабочая ветка:** `work/qa02-unity-scene-graph-run`; **последний code/test commit:** `1da9a2916a96d97af18a2a02d7e30d27fdf6dfe2`.
**CI PR #35:** [run #45014386292](https://github.com/HelterFall/God/actions/runs/45014386292) завершился `success` на code/test commit `1da9a29`: 144 JVM tests, debug APK build и artifact upload.
**Тестовая база:** `main` — 19 JVM test files / 110 @Test methods; candidate — 20 files / 144 methods. Реальные game binary fixtures и Android instrumentation отсутствуют.
**Связанные документы:** [назначение и конечный результат](GOD_PURPOSE_AND_OUTPUT.md), [полная дорожная карта](FULL_ANALYZER_ROADMAP.md), [технический аудит](AUDIT_2026-11-18.md), [аудит тестирования и CI](TEST_AUDIT_2026-11-19.md).

## 1. С чего начинаем

God уже умеет выполнять ограниченную инвентаризацию папки, показывать базовый прогресс, принимать запрос на отмену, экспортировать Markdown/JSON schemaVersion 24, собирать ScanCoverage и анализировать PE/CLR metadata и часть Unity SerializedFile/scene data. `ReportExporter`, `ScanCoordinator` и partial report с per-file analyzer outcomes реализованы. PR #29 добавил `FileRecord`; PR #30 — PE/CLR AnalyzerRun; PR #31 — Unity header/SerializedFile AnalyzerRun; PR #33 добавил local MonoScript payload run и был слит в main. PR #35 candidate добавляет `unity_scene_graph`, per-file outcomes для external MonoScript/PPtr/MonoBehaviour resolvers и корректное обновление ранее skipped metadata run при дополнительном чтении внешнего target. EVIDENCE-02 подключает `UnityMonoBehaviourScriptResolver` и публикует `MonoBehaviour → MonoScript`; EVIDENCE-03 подключает `UnityExternalPointerResolver` для sampled внешних полей `gameObject`, `father`, `children`, `components`. Оба среза формируют общие `Finding`/`Evidence`/`GraphNode`/`GraphEdge`; target nodes создаются только для реальных object records и `FileRecord`, а unresolved/out-of-sample targets сохраняются как `targetHint`. `m_Script` не дублируется. Полные и partial/cancelled JSON reports schemaVersion 24 сохраняют четыре model arrays. Run #45014386292 подтвердил 144 JVM tests, debug APK и artifact upload; это два ограниченных resolver slices, не полный межанализаторный граф. Изменения PR #35 находятся в отдельной ветке и не слиты в main. Реальные game fixtures, полная compatibility matrix и Android SAF по-прежнему открыты.

Главная проблема следующего этапа — не недостаток отдельных парсеров. Сейчас приложению не хватает общего надёжного конвейера, который:

1. читает все доступные файлы, в том числе большие и вложенные;
2. сохраняет результаты каждого анализа независимо от UI;
3. не теряет уже найденные сведения при ошибке или отмене;
4. связывает результаты парсеров в общую модель;
5. позволяет из любого вывода перейти к исходному файлу и конкретному evidence.

Поэтому первым делом строим фундамент данных и чтения. Затем делаем сквозной вертикальный срез «выбрал папку → обнаружили архив → прочитали entries → проанализировали внутренний Unity-файл → сохранили результат». После этого расширяем глубину анализа кода, сцен и ресурсов.

## 2. Общие правила выполнения

- Работать небольшими проверяемыми изменениями. Каждый завершённый шаг — отдельный commit с тестами.
- Не переписывать весь GameScanner за один раз. Переносить ответственность поэтапно, сохраняя текущее поведение.
- Каждый анализатор должен возвращать структурированный результат, evidence и статус покрытия, а не самостоятельно собирать итоговый текст.
- Любые лимиты должны быть явными и отражаться в отчёте. Отсутствие находки в неполной выборке не означает, что её нет в игре.
- Не загружать большие файлы и архивы целиком в память, если формат допускает потоковое или позиционное чтение.
- Не запускать игровые EXE/DLL, не исполнять CIL и не менять оригиналы файлов.
- Не считать строку, AssemblyRef, импорт или статический call site доказательством runtime-использования. У каждого уровня — собственный тип evidence.
- Сначала проверить и переиспользовать существующие ScanCoverage, ScanEntry, ScanResult и parser-specific data classes. Не создавать дубликаты уже работающих моделей.
- JSON Schema и manifest нужны для целостности, воспроизводимости и будущей автоматизации отчётов. Уже существующий schemaVersion=24 считать текущей версией структуры JSON; не вводить второй номер версии с тем же смыслом.
- Не создавать обязательный импортёр или runtime/API-связь с Ramen или RamenUnity.
- Сначала довести Mono/SerializedFile путь до полезной глубины и получить реальные fixtures. IL2CPP развивать отдельной веткой, не смешивая его с готовностью Mono.

### 2.1. Что уже реализовано — не делать повторно

Этот список нужен, чтобы следующие этапы расширяли текущий код, а не заново создавали уже работающие функции. «Реализовано» здесь означает ограниченную текущую версию, а не полную целевую возможность.

### Android UI, инвентаризация и ScanCoverage

- [x] Выбор корневой папки через SAF и сохранение выбранного URI между пересозданиями Activity.
- [x] Рекурсивный обход через DocumentFile с лимитами 100 000 записей и глубиной 40.
- [x] Сбор относительного пути, имени, типа directory/file и размера; подсчёт общего числа записей и байтов.
- [x] JSON-поле files содержит все записи, собранные текущим обходом в пределах этих лимитов. Markdown-дерево отдельно ограничено 20 000 строк.
- [x] Базовый callback прогресса во время инвентаризации; UI обновляет путь/счётчик каждые 128 записей.
- [x] Кнопка запроса отмены и job-scoped cancellation через `ScanCoordinator`.
- [x] При отмене на этапах inventory/PE/Unity/resolver возвращается partial report с собранным inventory; `analyzerRuns[]` сохраняет завершённые и queued результаты, а `findings[]`, `evidence[]`, `graphNodes[]`, `graphEdges[]` сохраняют уже сформированные EVIDENCE-02 Unity script bindings и EVIDENCE-03 external PPtr relations.
- [x] Агрегированная ScanCoverage: inventory counters, PE/CLR/Unity analyzer candidate/attempt/parsed/partial/failed/limited/skipped counts и derived status.

**Ограничения:** прогресс пока относится преимущественно к обходу папок. `scanCoverage.analyzers` в partial report остаётся пустым, но `analyzerRuns[]` сохраняет per-file результаты этапов, уже достигнутых к моменту отмены. PE/CLR, Unity header/SerializedFile, local MonoScript payload, bounded Unity scene graph и (PR #35 candidate) external MonoScript/PPtr/MonoBehaviour resolver stages подключены к per-file AnalyzerRun. Отмена проверяется на границах parser/resolver этапов, а не внутри каждого блокирующего provider read. Android lifecycle/SAF instrumentation tests ещё отсутствуют.

### Уже существующие форматы и экспорт

- [x] Экспорт Markdown и JSON.
- [x] JSON включает schemaVersion=24, scanCoverage, files, `nativeImports` и bounded `nativeImportSymbols` для прочитанных PE thunk tables.
- [x] Markdown tree имеет отдельный предел 20 000 строк.
- [x] Текущие Markdown и JSON сначала формируются в памяти как строки; потокового ReportWriter пока нет.
- [x] Экспорт открывает пользовательский destination через SAF.
- [x] Запись выполняется через `ReportExporter`: null stream и ошибки открытия/записи/закрытия приводят к ошибке; успешный статус появляется только после завершения записи и закрытия потока.

**Оставшееся ограничение:** Markdown/JSON по-прежнему полностью формируются в памяти; потоковый `ReportWriter` ещё не реализован.

### Уже существующие анализаторы

- [x] Unity build inventory и эвристическая классификация Mono/IL2CPP.
- [x] Multi-source Unity version evidence с разрешением конфликтов без угадывания.
- [x] PE headers, машинная архитектура x86/x86-64/ARM64, bounded sections/RVA и static Import/Thunk Table: DLL names, named functions, hints и ordinal imports; OriginalFirstThunk с FirstThunk fallback.
- [x] CLR directory recognition и ограниченная CLR metadata: Assembly identity, Module, TypeDef, поля, методы, AssemblyRef и распространённые signatures.
- [x] Привязка полей/методов к TypeDef и ограниченное сопоставление MonoScript identity с managed TypeDef.
- [x] Unity SerializedFile headers и metadata v19–v26 в ограниченных окнах.
- [x] Sampled object records, ClassID counts, TypeTree summaries и выбранные полные TypeTrees.
- [x] Ограниченное чтение MonoScript payload, часть local/external PPtr resolution и MonoBehaviour script bindings.
- [x] Ограниченный decoder GameObject/Transform/MonoBehaviour и локальный GameObject–Transform–Component scene graph.
- [x] Распознавание UnityFS header.

**Не подразумевается как завершённое:** распаковка UnityFS/AssetBundle/ZIP, полная PE import/export analysis (delay/forwarded exports и широкий compatibility matrix), CLR P/Invoke map и IL bodies, IL2CPP metadata parsing, полный scene graph и asset payload decoding, общий cross-domain dependency graph и Porting Dossier generator.
## 2.2. Проблемы, выявленные аудитом тестирования и качества

Полная матрица тестов, проверенные CI steps и технические детали: [TEST_AUDIT_2026-11-19.md](TEST_AUDIT_2026-11-19.md).

### P1 — исправить до расширения функциональности

- [ ] **QA-01 — scanner integration coverage (расширена в PR #35).** Тридцать три теста `GameScannerIntegrationTest` запускают настоящий `GameScanner.scan()` на in-memory tree: inventory/progress, PE/CLR/Unity header/SerializedFile AnalyzerRuns, local MonoScript payload, `unity_scene_graph`, external MonoScript/PPtr resolvers, EVIDENCE-02 MonoBehaviour→MonoScript и EVIDENCE-03 cross-file PPtr → Finding/Evidence/GraphNode/GraphEdge. Проверяются resolved/metadata-only/out-of-sample targets, реальные AnalyzerRun/FileRecord links, exact locators, separate father/children relations, shared graph nodes, no m_Script duplication, provider failures и cancellation. Осталось проверить partial-stream/error matrix, реальные Unity fixtures, широкую version compatibility и Android SAF.
- [ ] **QA-02 — scan lifecycle/cancellation race.** Реализованы single-job `ScanCoordinator`, job-scoped cancellation, Activity-safe worker и partial report при отмене на проверяемых этапах. Семь тестов `PartialReportBuilderTest` проверяют маркеры partial/cancelled, inventory JSON, escaping, Markdown cap, AnalyzerRun outcomes и сохранение всех четырёх Finding/Evidence/Graph arrays при отмене. Осталось: Android lifecycle/SAF instrumentation, cancellation внутри длинных provider reads и сохранение job/report после Activity recreation.
- [x] **QA-03 — export false success.** Исправлено: `ReportExporter` отклоняет null stream и передаёт ошибки открытия, записи и закрытия; пять JVM-тестов проверяют эти сценарии, UTF-8 и закрытие потока.
- [ ] **QA-04 — Android SAF tests.** Нет instrumentation tests для реальных document providers, URI permissions, stream errors, Activity recreation и export destination.
- [ ] **QA-05 — real binary fixtures.** 144 @Test в ветке-кандидате по-прежнему используют преимущественно synthetic byte arrays/model objects и in-memory scanner tree; добавить реальные PE, CLR и Unity SerializedFile/TypeTree fixtures, включая Unity 6.
- [ ] **QA-06 — bounded PE imports реализованы; позитивная ARM64 и часть negative RVA checks добавлены.** `PeParserTest` проверяет PE32/PE32+, named imports с Hint, ordinal imports, OriginalFirstThunk/FirstThunk fallback, truncated thunk table, ARM64 machine recognition, Import Directory RVA вне секций и unreadable DLL-name RVA при продолжении чтения последующих descriptors. `GameScannerIntegrationTest` проверяет вывод DLL/function/ordinal symbols в JSON/Markdown. Осталось: широкая malformed descriptor/thunk matrix, ARM64 real samples, delay imports, exports/forwarded exports и реальные PE fixtures.
- [ ] **QA-07 — parser compatibility matrix.** Уточнить и расширить проверки CLR signatures/metadata, Unity header versions, SerializedFile v19–v26, TypeTree/shared subtree и scene graph limits. Synthetic tests оставить, но сверять их с реальными samples.
- [ ] **QA-08 — binary robustness.** Добавить property/fuzz tests для offsets, lengths, counts, overflow, malformed heaps, truncated streams и compression/container boundaries.

### P2 — надёжность разработки и CI

- [ ] **QA-09 — pull request validation and test artifacts.** Workflow теперь запускается также на pull_request; остаётся добавить сохранение JUnit XML и coverage reports, lint/static checks и отдельные Android instrumentation checks.
- [ ] **QA-10 — reproducible Gradle.** В репозитории нет Gradle Wrapper; добавить wrapper, закрепить Gradle distribution и использовать его локально/в CI.
- [ ] **QA-11 — deprecated Actions runtime.** Последние workflow logs предупреждают о Node.js 20 deprecation у используемых Actions major versions. Обновить actions до поддерживаемых runtime и проверить workflow повторно.
- [ ] **QA-12 — измеримость тестов.** Подключить JaCoCo/Kover или аналогичный отчёт. Сначала получить baseline, не вводить произвольный процентный gate до измерения.

`main` после PR #34: 19 JVM test files / 110 @Test methods; [run #387](https://github.com/HelterFall/God/actions/runs/45000959608) подтвердил JVM tests, `:app:assembleDebug` и APK artifact upload. PR #35 candidate на code/test commit `1da9a29` содержит 20 files / 144 methods; [run #45014386292](https://github.com/HelterFall/God/actions/runs/45014386292) прошёл все workflow steps.

## 3. Последовательность реализации

### Этап 0 — зафиксировать исходную точку

**Статус:** [x] выполнено для базового commit.

- [x] Проверить текущую ветку и зафиксировать исходный commit.
- [x] Проверить зелёный CI перед текущей правкой: run #256 (JVM tests и debug APK build успешны) на commit 1bad13c.
- [x] Зафиксировать ограничения текущего MVP в техническом аудите.
- [x] Утвердить целевое назначение God и формат Porting Dossier.

**Результат:** есть воспроизводимая исходная точка. Любое последующее улучшение сравнивается с ней, а не оценивается по субъективному количеству добавленного кода.

### Этап 1 — общая модель результатов и evidence

**Приоритет:** P0  
**Зависимости:** нет.

В коде есть частные модели результатов парсеров, `FileRecord` для inventory, `ScanResult` и агрегированная `ScanCoverage`. `GameScanner` использует `ScanEntry` как связку записи и read-only `ScanNode`; не вводить второй инвентарь. Общий слой доказательных результатов расширять постепенно.

**Текущий рабочий срез:** main содержит per-file AnalyzerRun для PE/CLR (PR #30), Unity header/SerializedFile (PR #31) и local `mono_script_payload` (PR #33). PR #35 candidate дополняет их bounded `unity_scene_graph` и outcomes для существующих external resolver stages, не переписывая resolver implementations: `unity_external_monoscript` и `unity_monobehaviour_script_bindings` привязаны к owner SerializedFile; `unity_external_monoscript_payload` — к target SerializedFile; `unity_external_pointers` — к source/owner file. При дополнительном bounded чтении внешнего target обновляется уже существующий `serialized_file_metadata` run, без duplicate analyzer/file IDs. EVIDENCE-01 добавил общие модели Finding/Evidence/Graph. EVIDENCE-02 публикует sampled `MonoBehaviour.m_Script → MonoScript`; EVIDENCE-03 публикует sampled cross-file PPtrs для `gameObject`, `father`, `children` и `components`, используя реальные object records и FileRecords для target nodes, а unresolved/out-of-sample targets оставляет как hints. `m_Script` не дублируется. Full/partial JSON schemaVersion 24 содержит `findings[]`, `evidence[]`, `graphNodes[]`, `graphEdges[]`; остальные parser families пока используют собственные summaries. Code/test commit `1da9a29` прошёл run #45014386292 (144 JVM tests, debug APK и artifact upload). Изменения PR #35 не слиты в main.

**Переиспользовать:**
- `ScanEntry` уже использует `FileRecord`: SHA-256 ID, `parentId`, источник идентичности и `inventoryStatus`; приоритет — дополнять эту модель, не создавать второй список файлов.
- AnalyzerCoverage, ScanCoverage и deriveAnalyzerStatus как уже работающую агрегированную модель покрытия.
- Существующие PeInfo, ClrAssemblySummary, UnityFileHeader, UnityMetadataSummary и scene graph models как parser-specific findings.
- ScanResult пока содержит только готовые Markdown/JSON строки; со временем заменить/расширить его структурированным ScanReport.

**Что ещё не существует как общий межанализаторный слой:**
- ContainerRecord для обычных и вложенных архивных entries.
- Базовый AnalyzerRun с scanId, inputFileId, analyzerId, status и reason уже реализован для PE/CLR, Unity header/SerializedFile и local MonoScript payload; расширенная модель с parser version и явными полями supported format range/limits ещё не завершена.
- Parser-neutral `Finding` и `Evidence` с точными source locators, scan-scoped IDs и ссылками на `AnalyzerRun`/`FileRecord` уже используются первым реальным Unity MonoBehaviour resolver path; findings/evidence и graph arrays публикуются в full/partial report JSON schemaVersion 24. Остальные parser outputs пока не подключены.
- `GraphNode` и `GraphEdge` уже формируются для ограниченной связи MonoBehaviour→MonoScript; общая агрегация всех Unity/scene/asset/CLR/PE endpoints и полный cross-file graph остаются будущей задачей.

**EVIDENCE-01 — общие модели и JSON round-trip (реализовано в candidate PR #35; используется в первом resolver path):**
- Добавлены parser-neutral JVM models `Finding`, `Evidence`, `SourceLocator`, `GraphNode`, `GraphEdge` с детерминированными scan-scoped IDs; identity keys включают scan, owner file/analyzer run и семантический ключ.
- `Finding` и `Evidence` связываются с `AnalyzerRun.id` и `FileRecord.id`; `Evidence` дополнительно хранит метод обнаружения, confidence и точный locator.
- `SourceLocator` различает byte offset/length, PE RVA, CLR metadata token, Unity `fileID`/PathID и IL offset; проверяет отрицательные/переполняющиеся byte ranges, unsigned-32-bit RVA/token, signed-32-bit Unity fileID и integer-only JSON. Нельзя трактовать RVA или PathID как смещение файла.
- `GraphNode`/ `GraphEdge` представляют сущности и отношения без Android/SAF зависимостей; endpoint validation и store остаются следующим шагом.
- У каждого типа есть стабильный JSON representation и обратное чтение; `AnalysisModelsTest` проверяет round-trip, null/optional fields, enums, byte/range validation, numeric overflow, ссылки и deterministic identity.
- EVIDENCE-01 сам по себе не подключал parser summaries; EVIDENCE-02 подключил один существующий Unity resolver end-to-end и additive full/partial report JSON arrays, сохранив schemaVersion 24. Остальные parser-specific summaries ещё не переведены в общие модели.

**Задачи:**
- [x] Спроектировать общий Kotlin data model без зависимости от Android UI (`AnalysisModels.kt` не импортирует Android/SAF APIs).
- [x] Эволюционировать inventory entries в `FileRecord` со стабильным ID и `parentId`; использовать хеш provider key при наличии, иначе относительный путь, parent identity и порядком дубликата.
- [x] Добавить `inventoryStatus` (`discovered`, `enumerated`, `partial`, `inaccessible`) и сохранять его в JSON, включая partial report при отмене.
- [x] Добавить `AnalyzerRun` с `inputFileId` и статусами `queued`, `analyzed`, `partial`, `skipped`, `unsupported`, `failed`; PE/CLR — PR #30, Unity header/SerializedFile — PR #31, local MonoScript payload — PR #33. Partial report сохраняет уже собранные и queued outcomes.
- [x] Подключить `mono_script_payload` run для локальных MonoScript targets в PR #33; success/partial/failed/skipped и FileRecord link проверяются scanner integration tests.
- [x] Подключить per-file runs к существующим external MonoScript/PPtr/MonoBehaviour resolvers: owner/target FileRecord linkage, analyzed/partial/skipped/failed outcomes, cancellation-safe queued records и без дублирования deterministic AnalyzerRun IDs; при дополнительном чтении target обновлять прежний skipped SerializedFile run (PR #35, code/test commit `8130363`).
- [x] Ввести `Finding`/`Evidence` с точными locators: FileRecord linkage plus byte offset/length, RVA, metadata token, Unity fileID/PathID и IL offset; typed confidence/status/method, reason и parser/format provenance.
- [x] Ввести typed `ScanIssue` для диагностик с постоянным кодом, стадией, уровнем серьёзности, необязательным путём и сообщением. JSON `issues[]` добавлен без изменения существующего `warnings[]`; `schemaVersion=24` сохранён.
- [x] Проверить структурированные diagnostics на scanner integration paths и при cancellation; коды/стадии/пути проверяются, а legacy `warnings[]` и Markdown сохраняют исходные сообщения.
- [x] Ввести `GraphNode`/`GraphEdge` здесь один раз; unresolved edges сохраняют `targetHint`, не создавая фиктивный target node. Endpoint existence validation и графовая агрегация ещё впереди.
- [x] Добавить JSON serialization/deserialization и round-trip tests для всех пяти моделей; enum IDs и optional/null fields фиксируются тестами.
**EVIDENCE-02 — первый сквозной срез (Unity MonoBehaviour → MonoScript; реализовано в candidate PR #35):**
- Переиспользован существующий `UnityMonoBehaviourScriptResolver`; parsing/resolver logic не переписывались.
- Каждый sampled binding создаёт `Finding`/`Evidence` с точным `AnalyzerRun.id` и owner `FileRecord.id`; Evidence сохраняет Unity PPtr `fileID`/`PathID`.
- Создаётся source GraphNode для MonoBehaviour; target GraphNode — только если target MonoScript object реально найден в metadata и у него есть `FileRecord`.
- Создаётся directed `attaches-script` edge. Null PPtr создаёт finding/evidence/source node, но не edge. Unresolved/ambiguous target хранится как edge с `targetHint`, без выдуманного target node.
- `resolved-monoscript-class` → `decoded/confirmed`; объект найден без payload → `metadata-only/strong`; null pointer → `skipped/confirmed`; отсутствующий/неоднозначный target → `unresolved` с причиной и hint.
- Полные и partial/cancelled JSON reports включают top-level `findings[]`, `evidence[]`, `graphNodes[]`, `graphEdges[]`; schemaVersion 24 и существующие parser-specific sections сохранены.
- Markdown отображает текущие evidence-backed script bindings; записи остаются ограниченными текущими object samples и resolver limits.
- Тесты проверяют resolved, metadata-only, unresolved и null PPtr, foreign keys AnalyzerRun/FileRecord, graph endpoints, отсутствие фиктивных target nodes и сохранение данных в partial report.
- Это не полный cross-file scene graph и не интеграция других parser families; следующие resolver families должны использовать те же модели.

**Задачи EVIDENCE-02:**
- [x] Преобразовать существующие MonoBehaviour script binding results в `Finding`/`Evidence`/`GraphNode`/`GraphEdge`.
- [x] Добавить model arrays в полный и partial JSON report без изменения schemaVersion 24.
- [x] Проверить реальные ссылки на AnalyzerRun/FileRecord и graph endpoints.
- [x] Добавить end-to-end tests на resolved, metadata-only, unresolved/ambiguous и null PPtr.
- [x] Обновить README, purpose, audit, test audit и roadmap по фактическому объёму.

- [x] Подключить `UnityMonoBehaviourScriptResolver` как первый end-to-end пример; остальные resolver/parser families подключать к тем же типам, не создавая parallel models.

**Критерий этапа 1 — выполнен на первом вертикальном срезе:** один `GameScanner.scan()` формирует Finding/Evidence/source+target GraphNodes/GraphEdge из реального MonoBehaviour resolver output, сохраняет `AnalyzerRun`/`FileRecord` links и точные Unity PPtr locators в schemaVersion 24 full/partial JSON report. Другие анализаторы ещё не подключены, поэтому межанализаторный граф остаётся частичным.
### Этап 2 — единый файловый слой поверх SAF

**Приоритет:** P0  
**Зависимости:** этап 1.

В `GameScanner` теперь используется read-only контракт `ScanNode`/`ScanFileAccess`: имя, тип записи, размер, список дочерних элементов и sequential `InputStream`. `SafScanNode`/`SafScanFileAccess` оборачивают Android `DocumentFile`/`ContentResolver`, поэтому `GameScanner` больше не зависит от Android SAF API. `readPrefix` всё ещё накапливает ограниченный префикс в `ByteArrayOutputStream`; range/positional reads и cancellation-aware read пока отсутствуют.

**Задачи:**
- [x] Ввести базовые `ScanNode`/`ScanFileAccess` и SAF-адаптер; fake in-memory implementation используется в scanner integration tests.
- [ ] Расширить контракт до `ReadableEntry` с relative path, metadata/capabilities и индивидуальным статусом доступа.
- [x] Поддержать sequential `InputStream` через `ScanFileAccess`.
- [ ] Добавить positional/range reads там, где их реально поддерживает SAF provider.
- [ ] Для provider без seek использовать честный sequential fallback; не обещать random access, которого нет.
- [ ] Расширить существующий shouldCancel callback до job-scoped cancellation token, передаваемого в операции чтения и декомпрессии.
- [ ] Проверять offset, длины, integer overflow и границы диапазонов до чтения.
- [ ] Не делать полную копию файла в RAM ради чтения небольшого участка.
- [x] Добавить fake in-memory implementation и использовать её в `GameScannerIntegrationTest`.
- [ ] Добавить file-backed test implementation и сценарии разных stream/provider capabilities.

**Критерий завершения:** PE и Unity header parser читают данные через общий интерфейс; тесты покрывают range read, EOF, truncated input, provider без seek, cancellation и oversized range.

### Этап 3 — inventory и управление scan job

**Приоритет:** P0  
**Зависимости:** этапы 1–2.

Рекурсивный обход, базовый progress callback, JSON inventory и кнопка отмены уже существуют. Задача — не переписать обход заново, а вынести его из GameScanner, добавить per-entry state и исправить жизненный цикл задания.

**Уже реализовано:**
- [x] SAF directory traversal через DocumentFile с текущими лимитами 100 000 записей и глубиной 40.
- [x] Сбор path/name/directory/size и aggregate totals.
- [x] JSON files[] содержит все собранные entries; Markdown tree отдельно обрезается до 20 000 строк.
- [x] UI получает базовый прогресс инвентаризации; обновление текста происходит каждые 128 записей.
- [x] Пользователь может запросить отмену через кнопку.

**Что нужно сделать:**
- [ ] Выделить InventoryScanner / ScanCoordinator из GameScanner, не переписывая существующий обход без необходимости.
- [x] Добавить стабильные file IDs, `parentId` и `inventoryStatus` в JSON files[]; provider keys хешируются, raw URI не экспортируется.
- [x] Добавить per-file AnalyzerRun для PE, CLR metadata, Unity header, SerializedFile metadata и local MonoScript payload; `inventoryStatus` по-прежнему относится только к обходу папок.
- [x] Подключить per-file AnalyzerRun к Unity header/SerializedFile metadata в PR #31.
- [x] Подключить отдельный `mono_script_payload` run для локальных targets в PR #33.
- [x] Подключить отдельные per-file runs к external MonoScript payload resolution, external-reference/PPtr и MonoBehaviour script resolvers в PR #35; проверить owner/target FileRecord IDs, deterministic no-duplicates и promotion previously skipped metadata.
- [ ] Сохранить aggregate ScanCoverage и расширить его связями с per-file/per-analyzer runs.
- [ ] Учитывать причины пропусков: permission denied, inaccessible, depth/entry limit, too large, unsupported и другие.
- [ ] Добавить analyzer jobs с per-file/per-analyzer status и избежать повторной обработки без необходимости.
- [x] Отключать кнопку повторного запуска, пока scan активен; `ScanCoordinator` запрещает второй job.
- [x] Заменить общий флаг на отдельный job-scoped cancellation token в `ScanCoordinator`.
- [x] При отмене сохранять уже собранную инвентаризацию и завершённые/queued AnalyzerRun outcomes; parser результаты не выдаются за готовые, если соответствующие этапы не достигнуты.
- [ ] Добавить прогресс по стадиям и анализаторам; текущий счётчик/путь отражает в основном обход папок.
- [ ] Сохранять результат/состояние scan при пересоздании Activity; сейчас сохраняется URI папки, но не отчёт и не состояние задания.

**Обязательные оставшиеся тесты:** Activity destruction/recreation и SAF provider cancellation; fake-tree integration tests уже проверяют inventory и точный partial-report snapshot. Расширить тесты на успешные parser/resolver paths, ошибки чтения и resource limits.

**Выполнено частично:** fake-tree tests проверяют inventory и точный snapshot при отмене. **Критерий полного завершения:** один активный job; Android lifecycle/provider tests подтверждают корректную остановку и отсутствие утечки Activity.

### Этап 4 — потоковое сохранение отчёта

**Приоритет:** P0  
**Зависимости:** этапы 1–3.

Экспорт Markdown и JSON уже работает. JSON schemaVersion=24 содержит files[], analyzer coverage, DLL-level nativeImports и bounded nativeImportSymbols. Но buildMarkdown/buildJson возвращают большие строки, а MainActivity хранит обе строки в памяти до экспорта. Отдельного ReportWriter, JSON Schema и manifest пока нет.

**Задачи:**
- [ ] Создать отдельный ReportWriter, не связанный с UI и GameScanner.
- [ ] Записывать крупные секции потоково, не собирая весь отчёт в одну большую строку.
- [ ] Разделить краткое резюме и подробные данные (inventory/findings/graph edges), сохранив текущие Markdown/JSON outputs как совместимый переходный формат.
- [ ] Формализовать правила bump/backward compatibility для schemaVersion=24 как версии структуры JSON. Не вводить второй report format version с тем же смыслом.
- [ ] Добавить JSON Schema и тест валидации текущего и будущего report format.
- [ ] Добавить manifest с версией God/analyzer, schemaVersion, временем, coverage и hashes частей отчёта.
- [x] Проверять null output stream и ошибки открытия/записи/закрытия через `ReportExporter`; helper выбрасывает IOException, а UI сообщает об успехе только после закрытия потока (пять JVM-тестов уже есть).
- [ ] Поддержать partial/cancelled report с явной маркировкой.

**Проверки экспорта, уже выполненные:** null output stream, IOException при открытии/записи/закрытии, UTF-8 и закрытие потока покрыты пятью тестами `ReportExporterTest`. Для будущего потокового `ReportWriter` отдельно потребуются тесты больших отчётов, частичной записи и совместимости JSON.

**Критерий завершения:** тест генерирует большой отчёт без построения всей структуры в памяти; JSON проходит schema validation; manifest корректен; null stream/ошибка экспорта не обозначаются как успех; partial report содержит completed/partial/failed analyzer states.

### Этап 5 — чтение архивов и контейнеров

**Приоритет:** P1  
**Зависимости:** этапы 1–4.

Это первая большая пользовательская функция, уменьшающая необходимость вручную распаковывать и отправлять отдельные файлы.

Порядок реализации:

1. ZIP reader: список entries, чтение отдельного entry, вложенные архивы.
2. Безопасная виртуальная файловая система контейнеров: анализатор получает entry через тот же FileAccess interface, что и обычный файл.
3. UnityFS block info и directory reader.
4. Поддерживаемые decompression modes, начиная с проверенных LZ4/LZMA вариантов.
5. AssetBundle → SerializedFile → objects/resources.
6. Split archives и sidecar/external mapping.

**Защитные ограничения обязательны:**
- максимальная глубина вложения;
- максимальное число entries;
- максимальный суммарный распакованный объём;
- максимальный размер одного entry;
- допустимый compression ratio;
- отмена при распаковке;
- запрет выхода путём из виртуального корня контейнера;
- статусы corrupted, truncated, unsupported compression и encrypted/opaque.

Не распаковывать всё содержимое на диск автоматически: сначала читать entries потоково и передавать их анализаторам.

**Критерий завершения:** тестовая игра с ZIP и UnityFS позволяет получить inventory вложенных entries и проанализировать поддерживаемый SerializedFile внутри контейнера; ограничения и неизвестные данные отражены в отчёте.

### Этап 6 — анализ native binaries и зависимостей

**Приоритет:** P1/P2  
**Зависимости:** этапы 1–5; GraphNode/GraphEdge и Evidence создаются на этапе 1.

**Уже реализовано:**
- [x] PE/DOS/PE signature recognition и machine architecture x86, x86-64, ARM64.
- [x] Ограниченное чтение section headers и RVA-to-file-offset mapping для текущего bounded buffer.
- [x] Определение CLR directory и базового CLR metadata locator.
- [x] Чтение DLL names и bounded named/ordinal symbols из static Import/Thunk Tables, включая OriginalFirstThunk/FirstThunk fallback.

**Ограничение текущего импорта:** `PeInfo.imports` содержит DLL names, а `PeInfo.importedSymbols` — bounded named function/ordinal records с Hint для named imports. PE parser получает prefix до 1 MiB, а managed PE может перечитываться до 4 MiB; exports, forwarded exports и delay imports пока не читаются.

**Что добавить:**
- [ ] Расширить чтение PE sections/data directories с positional reads, чтобы не зависеть от небольшого prefix.
- [x] Import/Thunk Table: function names, ordinal imports, hints, PE32/PE32+ thunk width и bounded truncation diagnostics.
- [ ] Расширить модель/диагностику для malformed descriptor/RVA cases и нестандартных layout variants.
- [x] Добавить positive PE32+ fixture с секцией и Import Directory; тест проверяет извлечение DLL names `KERNEL32.dll` и `USER32.dll`.
- [x] Проверить named function и ordinal thunk entries для PE32/PE32+.
- [ ] Расширить malformed descriptors/RVA matrix и добавить ARM64/real PE fixtures.
- [ ] Delay Import Table.
- [ ] Export Table и forwarded exports.
- [ ] Сопоставлять import DLL с найденными файлами, в том числе внутри поддерживаемых containers.
- [ ] Выявлять недостающие зависимости.
- [ ] Сохранять native dependency edges и function-level evidence.
- [ ] Исследовать dynamic loading clues отдельно от подтверждённых imports.
- [ ] Классифицировать архитектуру и Windows-only API как porting requirements, а не как автоматический verdict.

**Критерий завершения:** для native binary видна цепочка binary → imported function → DLL → найденный файл либо объяснение отсутствия; каждое ребро имеет evidence.

### Этап 7 — полный managed/Mono анализ

**Приоритет:** P2  
**Зависимости:** этапы 1–4 и общая модель графа.

**Уже реализовано:**
- [x] Ограниченная CLR metadata inventory: Assembly identity, Module, TypeDef, fields, methods и AssemblyRef.
- [x] Привязка fields/methods к declaring TypeDef через FieldList/MethodList.
- [x] Декодирование поддерживаемых CLR signatures: primitives, arrays, pointers, by-ref и generic variables.
- [x] Ограниченные method/field samples и статусы parsed/partial/failed.
- [x] Точное сопоставление доступных MonoScript class/namespace/assembly с managed TypeDef; неоднозначные совпадения не угадываются.

**Что добавить:**
- [ ] Расширить CLR metadata: nested types, inheritance, interfaces, attributes, generic constraints и остальные важные tables.
- [ ] Полная поддерживаемая P/Invoke map: library, entry point, convention, charset and flags. Текущие PE imports не заменяют CLR P/Invoke metadata.
- [ ] Читать method bodies: IL bytes, locals, exception handlers и token references.
- [ ] Декодировать IL instructions и строить static call-site graph.
- [ ] Разрешать tokens и symbols между assemblies.
- [ ] Собирать связи managed method → Unity API → native boundary.
- [ ] Отмечать reflection, dynamic loading, unresolved metadata и unknown IL opcodes.
- [ ] Различать assembly reference, static call site, estimated reachability и runtime-observed behavior.

**Критерий завершения:** на тестовых Mono-сборках God может показать не только названия классов, но и подтверждённые IL call sites, зависимости и P/Invoke boundaries с точными locator'ами.

### Этап 8 — Unity data, сцены и object graph

**Приоритет:** P2  
**Зависимости:** этапы 1–5.

**Уже реализовано:**
- [x] Распознавание SerializedFile headers, включая 64-bit layout v22+; metadata reader заявлен для v19–v26.
- [x] Сбор ограниченных object record samples, ClassID counts, MonoScript references и external-file paths.
- [x] TypeTree summaries и сохранение некоторых полных TypeTrees для GameObject/Transform и ограниченного числа MonoBehaviour types.
- [x] Чтение части MonoScript payload и сопоставление scriptTypeIndex/MonoScript с managed TypeDef в доступных пределах.
- [x] Базовый decoder GameObject, Transform и MonoBehaviour; локальный GameObject–Transform–Component graph.
- [x] Ограниченное разрешение local/external PPtr и MonoBehaviour script bindings.

**Что добавить:**
- [ ] Опубликовать и расширить матрицу Unity version × SerializedFile version × TypeTree layout на реальных fixtures.
- [ ] Добавить позиционное чтение metadata и object payload вместо bounded prefix dependency.
- [ ] Поддержать shared TypeTree subtrees payload для целевых версий; сейчас для v26 читается только число shared subtrees.
- [ ] Расширить decoder за пределы GameObject/Transform/MonoBehaviour.
- [ ] Добавить ScriptableObject и используемые игрой типы объектов.
- [ ] Улучшить PPtr resolution: local/external уже частично есть; расширить GUID/path и bundle-contained entries.
- [ ] Строить более полный scene/prefab graph с component order, состояниями объектов и связями через файлы/bundles.
- [ ] Сохранять raw/metadata-only/partial/unsupported object records, чтобы ничего не терять из-за неизвестного decoder.

**Критерий завершения:** для реальных fixture scenes God строит проверенный граф объектов и компонентов, а unresolved references и недекодированные объекты явно видны.

### Этап 9 — assets и конфигурация

**Приоритет:** P2/P3  
**Зависимости:** этапы 5 и 8.

**Задачи:**
- [ ] Texture2D, Mesh, AudioClip, Material, Shader.
- [ ] AnimationClip, Animator, Font, TextAsset, ScriptableObject.
- [ ] Resources, StreamingAssets и sidecar relationships.
- [ ] Input, audio, graphics/render pipeline, boot.config и PlayerSettings clues.
- [ ] Plugin manifests, SDKs, network/platform integrations.
- [ ] File access, threading, timer и synchronization clues.
- [ ] Связывать asset usage с объектами и кодом, где evidence доступен.

**Критерий завершения:** God может объяснить, какие основные ресурсы используются, где они лежат, какие объекты/материалы/скрипты на них ссылаются и какие backend features потребуются.

### Этап 10 — сквозной dependency/wiring graph

**Приоритет:** P2/P3  
**Зависимости:** модель этапа 1; наполнение идёт параллельно с этапами 5–9.

Не откладывать создание модели графа до завершения всех декодеров. Типы GraphNode/GraphEdge/Evidence создаются на этапе 1; этот этап не вводит их повторно, а связывает уже существующие parser outputs между собой. Локальный GameObject–Transform–Component scene graph в God уже есть, но общего графа File/Native/Managed/Unity/Asset dependencies ещё нет.

**Задачи:**
- [ ] Связать File/Container → Assembly/Native library → Type/Method → API → Unity Component/Scene → Asset.
- [ ] Согласовать IDs и типы cross-domain узлов/связей с моделью этапа 1; не создавать параллельные GraphNode/GraphEdge.
- [ ] Добавить unresolved/ambiguous edges и причины.
- [ ] Реализовать запросы: «кто использует этот API?», «какие сцены затрагивает эта библиотека?», «от чего зависит этот компонент?».
- [ ] Добавить экспорт графа в структурированный формат и ссылки из Markdown к evidence.
- [ ] Не создавать неподтверждённые связи по совпадению имени.

**Критерий завершения:** выбранная тестовая игра имеет навигационный граф, в котором основные выводы можно проследить до исходных файлов и точных мест.

### Этап 11 — platform assessment и Porting Dossier

**Приоритет:** P3  
**Зависимости:** достаточное покрытие этапов 5–10.

**Задачи:**
- [ ] Составить перечень используемых Unity API и системных API.
- [ ] Группировать требования по graphics, input, audio, filesystem, threading, networking и native plugins.
- [ ] Для каждого требования указывать evidence, confidence и затронутые компоненты.
- [ ] Разделить confirmed blocker, required implementation, optional integration, potential issue и unknown.
- [ ] Генерировать план подсистем с интерфейсами, входами/выходами, зависимостями и порядком подключения.
- [ ] Указывать, какие тесты подтвердят работоспособность каждой подсистемы.
- [ ] Отдельно отмечать то, что нельзя установить без динамического запуска или дополнительного исходного материала.

**Критерий завершения:** Porting Dossier позволяет начать реализацию целевой среды по приоритетам и интерфейсам, не перечитывая вручную исходную папку игры.

### Этап 12 — IL2CPP как отдельный профиль

**Приоритет:** P3 после устойчивого Mono-конвейера.  
**Зависимости:** file/container/report/evidence foundation.

- [ ] Versioned parser для global-metadata.dat.
- [ ] Metadata tables, types, methods, fields, generics.
- [ ] Native GameAssembly architecture/imports и mapping evidence.
- [ ] Форматная матрица по реальным IL2CPP fixtures.
- [ ] Отдельный отчёт об ограничениях native code analysis.

**Критерий завершения:** God способен исследовать объявленные версии IL2CPP metadata и native boundaries. Это не означает выполнение IL2CPP кода.

### Этап 13 — UX, реальные сборки и качество

**Приоритет:** непрерывно; финальная полировка после первых вертикальных срезов.

**Тестовая база:** `main` после PR #34 — 19 JVM test files / 110 @Test methods; candidate PR #35 code/test commit `1da9a29` — 20 files / 144 methods. `GameScannerIntegrationTest` содержит 33 метода, `PartialReportBuilderTest` — 8, `AnalysisModelsTest` — 18. Run [#45014386292](https://github.com/HelterFall/God/actions/runs/45014386292) прошёл JVM tests, `:app:assembleDebug` и upload APK artifact; EVIDENCE-02/03 покрывают resolved, metadata-only и unresolved external PPtr, разные поля и сохранение model arrays в partial report.

- [x] Добавить начальные scanner integration tests для inventory/progress, PE candidate/failure accounting, `ScanCoverage`, JSON/Markdown и partial inventory при cancellation.
- [x] Scanner integration: успешный bounded CLR metadata path через `GameScanner.scan()` до JSON/Markdown и `ScanCoverage` (PR #15, synthetic managed PE fixture).
- [x] Scanner integration: успешный synthetic Unity SerializedFile v22 path через `GameScanner.scan()` до JSON/Markdown, Unity version evidence и analyzer coverage (PR #17).
- [x] Scanner integration: external MonoScript basename ambiguity проходит через `GameScanner.scan()` и отражается как `external-file-name-ambiguous` без выбранного target path (PR #19).
- [x] Scanner integration: уникальный external MonoScript target читается через SerializedFile object table и payload parser; binding class/namespace/assembly/execution order попадает в JSON/Markdown (PR #21, synthetic fixture).
- [x] Scanner integration: Unity provider `openInputStream` failure отражается как failed header analyzer и partial/skipped metadata analyzer; inventory и предупреждение сохраняются в report (PR #23).
- [x] Scanner integration: ошибка `listFiles()` у подкаталога сохраняет уже найденный каталог и последующие доступные sibling entries, отмечает inventory partial и записывает inaccessibleDirectoryCount/warning (PR #25).
- [x] Structured `ScanIssue` output: inventory directory-listing failure, Unity provider failure и cancellation публикуют стабильные code/stage/severity/path в JSON `issues[]`; старые `warnings[]` и Markdown сохраняют прежний текст.
- [ ] Расширить scanner integration coverage на truncated/partial streams, прочие provider errors, реальные fixtures и остальные resolver paths.
- [x] JVM tests для scan locking, job-scoped cancellation и pure partial-report builder; Android Activity/SAF instrumentation остаются.
- [x] Тесты `ReportExporter` для null stream и ошибок открытия/записи/закрытия; не дублировать их при реализации потокового `ReportWriter`.
- [ ] Добавить настоящие legal/redistributable PE/CLR/Unity fixtures и golden reports.
- [ ] Android instrumentation tests для SAF permissions, provider stream behavior, Activity recreation и export.
- [x] PE Import/Thunk tests: DLL names, function names, hints, ordinals и parser-to-report JSON/Markdown path.
- [ ] Malformed PE descriptor/RVA matrix, delay imports, exports и расширенная Unity version/layout matrix.
- [ ] Property/fuzz tests и regression corpus для parser boundaries.
- [ ] JUnit XML и code coverage report; установить порог только после baseline измерения.
- [ ] Quick/Standard/Deep scan profiles.
- [x] Базовый progress callback и индикатор пути/счётчика во время inventory (обновление UI каждые 128 записей).
- [x] Кнопка запроса отмены анализа.
- [ ] Расширить progress до стадий и отдельных analyzers; текущая индикация не показывает прогресс PE/CLR/Unity parsing.
- [x] Добавить single-job locking и job-scoped cancellation через `ScanCoordinator`; шесть JVM-тестов проверяют state transitions.
- [x] Сохранять partial report при отмене: inventory плюс завершённые/queued AnalyzerRun outcomes и уже сформированные EVIDENCE-02 `findings[]`, `evidence[]`, `graphNodes[]`, `graphEdges[]`; builder и scanner cancellation tests проверяют поведение.
- [ ] Добавить Android lifecycle/SAF instrumentation tests.
- [ ] Поиск, фильтры и навигация по findings/graph.
- [ ] Переход к evidence из итоговых документов.
- [ ] Тесты на реальные легально распространяемые Unity Mono/IL2CPP fixtures.
- [ ] Golden reports, property/fuzz tests и regression suite.
- [ ] Android SAF tests: permission loss, non-seekable provider, large tree, Activity recreation, low memory.
- [ ] Публикация support matrix и точных ограничений.

**Критерий завершения:** заявленные форматы подтверждены реальными fixtures, CI стабильно зелёный, а долгий scan остаётся управляемым.

## EVIDENCE-03 — публикация внешних Unity PPtr как общих evidence/graph records

**Статус:** реализовано в candidate PR #35; code/test commit `1da9a29`, CI run [#45014386292](https://github.com/HelterFall/God/actions/runs/45014386292) — success.  
**Основа:** существующие `UnityExternalPointerResolver`, `unity_external_pointers` AnalyzerRun и `UnityExternalPointerResolution`; новый формат или новый parser не создаётся.

**Границы вертикального среза:**
- Подключить к `Finding`, `Evidence`, `GraphNode`, `GraphEdge` только уже разрешаемые sampled cross-file PPtrs из полей `gameObject`, `father`, `children`, `components`.
- Не дублировать `m_Script`: этот PPtr уже публикуется EVIDENCE-02 как `MonoBehaviour → MonoScript`.
- Связать Finding/Evidence/edge с настоящими owner `FileRecord.id` и `unity_external_pointers` `AnalyzerRun.id`; locator сохраняет точные Unity `fileID` и `PathID`, а finding identity также различает source PathID и поле.
- Создавать target GraphNode только если найден соответствующий object record в target metadata и есть настоящий target `FileRecord`. Для target вне sample, неразрешённого/ambiguous пути или непрочитанного target сохранять `targetHint`, не выдумывать вершину.
- Публиковать понятные направленные relations для четырёх полей; хранить `resolved-decoded-object` как decoded/confirmed, `resolved-object-record-not-decoded` как metadata-only/strong, а ограниченные/unresolved результаты — с честными partial/unresolved status и reason.
- Добавить Markdown-секцию внешних PPtr evidence/relations; сохранить существующие JSON schemaVersion 24 и full/partial массивы.

**Проверки при реализации:**
- [x] Resolved cross-file targets для `components` и `gameObject`: корректные Finding/Evidence, owner/target FileRecord IDs, AnalyzerRun ID, source/target PathID и relation endpoint IDs.
- [x] Metadata-only target: реальная object-record вершина с metadata-only/strong status.
- [x] Target вне bounded object sample: `targetHint` без фиктивной target GraphNode; unresolved resolver outcomes сохраняют причину и hint.
- [x] `father` и `children` остаются разными directed relations, а source/target nodes для повторных ссылок объединяются без дубликатов.
- [x] `m_Script` остаётся только в EVIDENCE-02 и не создаёт дублирующий external-pointer finding/edge.
- [x] Partial/cancelled report сохраняет уже созданные EVIDENCE-03 массивы; отдельный `PartialReportBuilderTest` и полный CI проходят.

**Не входит:** локальный Unity scene graph целиком, все Unity object fields, полная cross-file сцена, новые декодеры, CLR/PE/asset families и реальные игровые fixtures. Следующим отдельным этапом остаются более широкие scene/prefab/asset relationships и остальные analyzer families.

---

## EVIDENCE-04 — публикация локальных Unity scene PPtrs в общий граф

**Статус:** запланировано до изменения кода.  
**Основа:** существующие `UnityObjectDecoder`, `UnitySceneGraphResolver`, `unity_scene_graph` AnalyzerRun и `UnityDecodedObjectSummary`; новый parser или параллельная graph model не создаётся.

**Границы вертикального среза:**
- Подключить к `Finding`, `Evidence`, `GraphNode`, `GraphEdge` только уже декодированные локальные PPtrs (`fileID=0`) из полей `gameObject`, `father`, `children`, `components`.
- Не дублировать `m_Script`: он остаётся в EVIDENCE-02. Нелокальные PPtrs (`fileID>0`) остаются в EVIDENCE-03.
- Связывать Finding/Evidence/edge с owner `FileRecord.id` и существующим `unity_scene_graph` `AnalyzerRun.id`; Evidence сохраняет `fileID=0` и точный target `PathID`.
- Переиспользовать relation types EVIDENCE-03: `belongs-to-gameobject`, `has-parent-transform`, `has-child-transform`, `has-component`.
- Переиспользовать nodeType/key/fileRecord identity EVIDENCE-03, чтобы один Unity object имел один GraphNode при наличии локальных и внешних рёбер.
- Target node разрешается только при наличии реальной `objectSamples` object record. Декодированный target — `decoded/confirmed`; object record без payload — `metadata-only/strong`; PathID вне sample — `partial/weak` с `targetHint`. Не создавать вершины по одному PathID.
- Отразить локальные scene relations в Markdown; full/partial JSON schemaVersion 24 и существующие четыре массива сохранить.

**Проверки при реализации:**
- [ ] Локальные GameObject/Transform/Component pointers создают корректные source/target nodes и directed edges с настоящими FileRecord/AnalyzerRun IDs.
- [ ] Все четыре relation field сохраняют единый relation type независимо от локального/внешнего fileID.
- [ ] Target object record без декодированного payload становится metadata-only node; отсутствующий в bounded sample target остаётся hint без target node.
- [ ] Локальная и внешняя ссылка одного объекта используют один GraphNode и не теряют finding/evidence references при merge.
- [ ] `m_Script` не дублируется между EVIDENCE-02 и EVIDENCE-04; `fileID>0` не дублируется между EVIDENCE-03 и EVIDENCE-04.
- [ ] Partial/cancelled report сохраняет уже опубликованные local-scene records; тесты и полный CI проходят.

**Не входит:** расширение TypeTree decoder, невыбранные классы объектов, полный scene/prefab traversal, bundle/container resolution, script/asset/CLR/PE graph и реальные игровые fixtures. Следующими остаются расширение object coverage и остальные analyzer families.

---

## 4. Что делать первым — ближайшие маленькие задачи

Не начинать контейнеры и новые декодеры, пока не готова минимальная основа. Ближайшую серию нужно выполнить в следующем порядке:

1. **QA-02 lifecycle slice (основная JVM-часть реализована):** `ScanCoordinator`, job-scoped cancellation, безопасная работа worker и partial report с inventory, AnalyzerRun outcomes и уже сформированными EVIDENCE-02 model arrays; 13 тестов координатора и partial-report builder. Следом — Android lifecycle/SAF tests и проверки отмены на реальном provider.
2. **Data model:** `FileRecord` и `ScanIssue` добавлены в main; PR #30 добавил PE/CLR AnalyzerRun, PR #31 — Unity header/SerializedFile, PR #33 — local MonoScript payload. PR #35 candidate добавляет bounded `unity_scene_graph`, external MonoScript/PPtr/MonoBehaviour resolver outcomes, EVIDENCE-02 MonoBehaviour→MonoScript и EVIDENCE-03 sampled external Unity object relations. Code/test commit `1da9a29` прошёл run #45014386292 (144 JVM tests, debug APK и artifact upload). Следующий этап — подключать остальные resolver/parser families к тем же моделям, не переписывая работающие ScanCoverage и parser data classes.
3. **FileAccess:** расширить `ScanNode`/`ScanFileAccess` до range/positional reads, non-seekable fallback и EOF/oversized-range/cancellation tests.
4. **Scanner integration tests:** расширить fake-tree сценарии на контейнерные entries и связку `FileRecord` → AnalyzerRun → Finding/Evidence; текущие tests уже проверяют file IDs, parent links, duplicate names и partial inventory statuses.
5. **ReportWriter:** заменить сборку существующих Markdown/JSON строк в памяти на потоковую запись, сохранить schemaVersion=24, добавить JSON Schema/manifest.
6. **ZIP vertical slice:** открыть один archive entry виртуально и передать его существующему Unity header/SerializedFile parser.
7. **UnityFS vertical slice:** прочитать block table, один entry и SerializedFile внутри него.
8. Только после успешных вертикальных срезов расширять PE/CLR/IL и остальные Unity decoders.

**Первый реальный пользовательский milestone:** пользователь выбирает папку игры; God инвентаризирует её, находит ZIP/UnityFS, исследует доступные entries, обнаруживает Unity SerializedFiles и экспортирует partial/full report с точным coverage. Никакой ручной распаковки поддерживаемых контейнеров не требуется.

## 5. Definition of Done для каждой задачи

Задача не считается завершённой только потому, что код скомпилировался. Обязательны:

- unit tests на нормальный, пустой, обрезанный и ошибочный input;
- тесты границ offset/size и лимитов;
- evidence locator в результате;
- parser/status/coverage reporting;
- проверка, что неизвестные и неподдерживаемые случаи не трактуются как отсутствие данных;
- отсутствие неограниченного чтения/распаковки в память;
- зелёный CI: JVM tests и Android debug APK build;
- где применимо — scanner integration tests и Android instrumentation tests;
- test fixtures проверяют реальные binary formats, а не только synthetic inputs;
- обновление README/audit/roadmap только в той части, которая действительно изменилась.

## 6. Когда можно переходить к следующему этапу

Переходить дальше можно, когда критерии завершения текущего этапа проверены тестами и реальным fixture. Если этап частично завершён, не помечать его целиком как complete: указать конкретно работающую часть и оставшийся coverage gap.

Новые анализаторы должны расширять одну и ту же модель данных и доказательств. Не строить отдельные изолированные отчёты, которые потом невозможно связать.

## 7. Что не является этапом этого плана

- Создание импортёра отчётов God в Ramen или RamenUnity.
- Подключение God как runtime-библиотеки к другим приложениям.
- Реализация Unity runtime, Mono executor или Android graphics/audio/input backend внутри God.
- Обещание полностью декодировать произвольный зашифрованный или неизвестный формат.
- Подмена статического анализа запуском игровых бинарников.

God отвечает за исследование, доказательства, связи и технический план. Реализация переноса остаётся в целевом проекте.
