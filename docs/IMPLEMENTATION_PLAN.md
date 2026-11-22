# God — план дальнейших действий

**Дата:** 22 ноября 2026 года  
**Базовая ветка:** main  
**Последний коммит с изменениями исходного кода:** `33d268c9e43b504f75fa032a13df33dc0b975eab` (squash merge PR #28).
**Последний HEAD ветки main:** `e224b72f6cc80b701a9bd7adf96c4dd5bc9919b1` (коммит документации после последнего изменения кода).
**Последняя успешная проверка `main`:** run [#340](https://github.com/HelterFall/God/actions/runs/44982866804) проверил этот commit: `:app:testDebugUnitTest`, `:app:assembleDebug` и upload debug APK artifact завершились успешно.
**Текущая тестовая база:** 17 JVM test files / 92 @Test methods. CLR и Unity SerializedFile/MonoScript scanner integration fixtures остаются синтетическими; реальные binary fixtures и Android instrumentation отсутствуют.
**Связанные документы:** [назначение и конечный результат](GOD_PURPOSE_AND_OUTPUT.md), [полная дорожная карта](FULL_ANALYZER_ROADMAP.md), [технический аудит](AUDIT_2026-11-18.md), [аудит тестирования и CI](TEST_AUDIT_2026-11-19.md).

## 1. С чего начинаем

God уже умеет выполнять ограниченную инвентаризацию папки, показывать базовый прогресс, принимать запрос на отмену, экспортировать Markdown/JSON schemaVersion 24, собирать ScanCoverage, анализировать PE/CLR metadata и часть Unity SerializedFile/scene data. `ReportExporter`, `ScanCoordinator` и inventory-only partial report уже реализованы. PR #11 добавил bounded PE function/ordinal import parsing; PR #13 закрепил ARM64 recognition и негативные RVA-сценарии; PR #15 добавил успешный CLR metadata path в `GameScanner.scan()` с проверкой assembly identity, TypeDef, JSON/Markdown и analyzer coverage; PR #17 добавил успешный synthetic SerializedFile v22 path; PR #19 добавил scanner integration для неоднозначной внешней MonoScript-ссылки без случайного выбора; PR #21 добавил успешное чтение уникально разрешённого внешнего MonoScript payload с binding class/namespace/assembly/execution order до отчёта. PR #23 добавил scanner integration для ошибки открытия Unity candidate stream; PR #25 добавил тест исключения `listFiles()` у подкаталога, сохранив доступные sibling entries и выставив inventory partial с inaccessibleDirectoryCount=1. PR #28 добавил typed `ScanIssue` и JSON `issues[]`, сохранив legacy `warnings[]`; post-merge main CI #340 подтвердил тесты и сборку. QA-01 остаётся открытым для truncated/partial streams, остальных provider error cases, реальных бинарных fixtures, широкой Unity compatibility/resolver matrix, scene/PPtr coverage и Android SAF. Это не означает, что глубокое исследование игры уже реализовано.

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
- [x] При отмене на этапах inventory/PE/Unity/resolver возвращается явно помеченный partial report с уже собранным inventory; JSON сохраняет все entries, Markdown ограничен 20 000 строк.
- [x] Агрегированная ScanCoverage: inventory counters, PE/CLR/Unity analyzer candidate/attempt/parsed/partial/failed/limited/skipped counts и derived status.

**Ограничения:** прогресс пока относится преимущественно к обходу папок. Partial report содержит inventory, но не включает незавершённые результаты анализаторов; в `scanCoverage` analyzer list пуст. Отмена проверяется на границах parser/resolver этапов, а не внутри каждого блокирующего provider read. Android lifecycle/SAF instrumentation tests ещё отсутствуют.

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

- [ ] **QA-01 — scanner integration coverage (начальный срез реализован).** Восемь тестов `GameScannerIntegrationTest` запускают настоящий `GameScanner.scan()` на in-memory tree: inventory/progress и PE imports; CLR metadata; Unity SerializedFile v22 metadata/version; ambiguous external MonoScript без выбора target; successful unique external MonoScript payload decode; Unity file-stream open failure с warning и failed/partial analyzer coverage; directory-listing failure с сохранением доступного sibling inventory и partial status; cancellation с partial inventory. Осталось проверить error/partial-stream matrix и resolver paths на реальных файлах, расширить Unity version/TypeTree и scene/PPtr matrix, real binary fixtures и Android SAF.
- [ ] **QA-02 — scan lifecycle/cancellation race.** Реализированы single-job `ScanCoordinator`, job-scoped cancellation, Activity-safe worker и inventory-only partial report при отмене на проверяемых этапах. Пять тестов `PartialReportBuilderTest` проверяют метки partial/cancelled, полный JSON inventory, escaping и Markdown cap. Осталось: Android lifecycle/SAF instrumentation, cancellation внутри длинных provider reads и сохранение job/report после Activity recreation.
- [x] **QA-03 — export false success.** Исправлено: `ReportExporter` отклоняет null stream и передаёт ошибки открытия, записи и закрытия; пять JVM-тестов проверяют эти сценарии, UTF-8 и закрытие потока.
- [ ] **QA-04 — Android SAF tests.** Нет instrumentation tests для реальных document providers, URI permissions, stream errors, Activity recreation и export destination.
- [ ] **QA-05 — real binary fixtures.** Текущие 91 @Test используют преимущественно synthetic byte arrays/model objects и одно in-memory scanner tree; добавить реальные PE, CLR и Unity SerializedFile/TypeTree fixtures, включая Unity 6.
- [ ] **QA-06 — bounded PE imports реализованы; позитивная ARM64 и часть negative RVA checks добавлены.** `PeParserTest` проверяет PE32/PE32+, named imports с Hint, ordinal imports, OriginalFirstThunk/FirstThunk fallback, truncated thunk table, ARM64 machine recognition, Import Directory RVA вне секций и unreadable DLL-name RVA при продолжении чтения последующих descriptors. `GameScannerIntegrationTest` проверяет вывод DLL/function/ordinal symbols в JSON/Markdown. Осталось: широкая malformed descriptor/thunk matrix, ARM64 real samples, delay imports, exports/forwarded exports и реальные PE fixtures.
- [ ] **QA-07 — parser compatibility matrix.** Уточнить и расширить проверки CLR signatures/metadata, Unity header versions, SerializedFile v19–v26, TypeTree/shared subtree и scene graph limits. Synthetic tests оставить, но сверять их с реальными samples.
- [ ] **QA-08 — binary robustness.** Добавить property/fuzz tests для offsets, lengths, counts, overflow, malformed heaps, truncated streams и compression/container boundaries.

### P2 — надёжность разработки и CI

- [ ] **QA-09 — pull request validation and test artifacts.** Workflow теперь запускается также на pull_request; остаётся добавить сохранение JUnit XML и coverage reports, lint/static checks и отдельные Android instrumentation checks.
- [ ] **QA-10 — reproducible Gradle.** В репозитории нет Gradle Wrapper; добавить wrapper, закрепить Gradle distribution и использовать его локально/в CI.
- [ ] **QA-11 — deprecated Actions runtime.** Последние workflow logs предупреждают о Node.js 20 deprecation у используемых Actions major versions. Обновить actions до поддерживаемых runtime и проверить workflow повторно.
- [ ] **QA-12 — измеримость тестов.** Подключить JaCoCo/Kover или аналогичный отчёт. Сначала получить baseline, не вводить произвольный процентный gate до измерения.

**Текущая база:** 16 test files / 91 @Test methods, JUnit 4.13.2. Main run [#331](https://github.com/HelterFall/God/actions/runs/44799745772) подтвердил JVM tests, debug APK build и artifact upload на merge commit `513f1acbf7c08d6de93045d298db8ba0ab3bc517`. Scanner tests теперь включают external MonoScript success/ambiguity, Unity provider open failure и directory enumeration failure; реальные fixtures, broader stream/provider failure matrix и Android tests по-прежнему нужны.

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

В коде уже есть частные модели результатов парсеров, ScanEntry/ScanResult и агрегированная ScanCoverage. Не заменять их вторым параллельным набором DTO. Нужно постепенно собрать из них общий слой доказательных результатов.

**Переиспользовать:**
- ScanEntry как исходную основу для FileRecord; добавить стабильный ID и явные состояния, а не создавать второй инвентарь.
- AnalyzerCoverage, ScanCoverage и deriveAnalyzerStatus как уже работающую агрегированную модель покрытия.
- Существующие PeInfo, ClrAssemblySummary, UnityFileHeader, UnityMetadataSummary и scene graph models как parser-specific findings.
- ScanResult пока содержит только готовые Markdown/JSON строки; со временем заменить/расширить его структурированным ScanReport.

**Что ещё не существует как общий межанализаторный слой:**
- ContainerRecord для обычных и вложенных архивных entries.
- AnalyzerRun с parser version, input IDs, supported format range, limits и итоговым статусом.
- Finding и Evidence с точными source locators.
- ScanIssue для typed warning/error/skip reasons вместо только строк warnings.
- GraphNode и GraphEdge для межфайловых, native, managed, Unity и asset dependencies.

**Задачи:**
- [ ] Спроектировать общий Kotlin data model без зависимости от Android UI.
- [ ] Эволюционировать ScanEntry в FileRecord со стабильным ID, parent/container ID и terminal status.
- [ ] Ввести Finding/Evidence и точные locators: relative path, offset/range, RVA, metadata token, PathID, IL offset.
- [x] Ввести typed `ScanIssue` для диагностик с постоянным кодом, стадией, уровнем серьёзности, необязательным путём и сообщением. JSON `issues[]` добавлен без изменения существующего `warnings[]`; `schemaVersion=24` сохранён.
- [x] Проверить структурированные diagnostics на scanner integration paths и при cancellation; коды/стадии/пути проверяются, а legacy `warnings[]` и Markdown сохраняют исходные сообщения.
- [ ] Ввести GraphNode/GraphEdge здесь один раз; последующие этапы должны наполнять и связывать их, а не создавать новые типы.
- [ ] Добавить сериализацию/десериализацию и round-trip tests.
- [ ] Подключить один существующий parser end-to-end как образец и затем постепенно подключить остальные.
- [ ] Расширить per-file/per-analyzer statuses, не дублируя агрегированную ScanCoverage.

**Критерий завершения:** один тестовый scan сохраняет FileRecord, parser finding, Evidence и graph edge в единой модели; после сериализации сохраняются ID, связи и точный source locator.

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
- [ ] Добавить стабильные file IDs и per-file terminal status; сейчас JSON files[] хранит только path/directory/size, без статуса анализа.
- [ ] Сохранить aggregate ScanCoverage и расширить его связями с per-file/per-analyzer runs.
- [ ] Учитывать причины пропусков: permission denied, inaccessible, depth/entry limit, too large, unsupported и другие.
- [ ] Добавить analyzer jobs с per-file/per-analyzer status и избежать повторной обработки без необходимости.
- [x] Отключать кнопку повторного запуска, пока scan активен; `ScanCoordinator` запрещает второй job.
- [x] Заменить общий флаг на отдельный job-scoped cancellation token в `ScanCoordinator`.
- [x] При отмене сохранять уже собранную инвентаризацию в partial Markdown/JSON; незавершённые parser результаты не выдаются за готовые.
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

**Тестовая база feature branch:** 17 JVM test files / 92 @Test methods. Scanner fake-tree tests проверяют PE DLL/function/ordinal imports, synthetic CLR metadata, Unity SerializedFile v22 metadata, ambiguous and successful external MonoScript resolution, Unity provider open failure, directory enumeration failure и typed `ScanIssue`; PeParser tests дополнительно покрывают ARM64 recognition и два malformed RVA сценария. Реальные binary fixtures, Android instrumentation, fuzz/property tests и coverage reports пока отсутствуют. См. [test audit](TEST_AUDIT_2026-11-19.md).

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
- [x] Сохранять inventory-only partial report при отмене; пять JVM-тестов проверяют сериализацию и ограничения.
- [ ] Добавить Android lifecycle/SAF instrumentation tests.
- [ ] Поиск, фильтры и навигация по findings/graph.
- [ ] Переход к evidence из итоговых документов.
- [ ] Тесты на реальные легально распространяемые Unity Mono/IL2CPP fixtures.
- [ ] Golden reports, property/fuzz tests и regression suite.
- [ ] Android SAF tests: permission loss, non-seekable provider, large tree, Activity recreation, low memory.
- [ ] Публикация support matrix и точных ограничений.

**Критерий завершения:** заявленные форматы подтверждены реальными fixtures, CI стабильно зелёный, а долгий scan остаётся управляемым.

## 4. Что делать первым — ближайшие маленькие задачи

Не начинать контейнеры и новые декодеры, пока не готова минимальная основа. Ближайшую серию нужно выполнить в следующем порядке:

1. **QA-02 lifecycle slice (основная JVM-часть реализована):** `ScanCoordinator`, job-scoped cancellation, безопасная работа worker и inventory-only partial report; 11 тестов координатора и partial-report builder. Следом — Android lifecycle/SAF tests и проверки отмены на реальном provider.
2. **Data model:** первый вертикальный срез `ScanIssue` включён в main commit `33d268c` и прошёл post-merge CI #340; следующий шаг — расширить ScanEntry до FileRecord со стабильным ID/status, затем добавить Finding/Evidence/GraphNode/GraphEdge. Не переписывать уже работающие ScanCoverage и parser data classes.
3. **FileAccess:** расширить `ScanNode`/`ScanFileAccess` до range/positional reads, non-seekable fallback и EOF/oversized-range/cancellation tests.
4. **Scanner integration tests:** fake file tree → inventory → parsers → coverage → JSON/Markdown; сохранить текущий JSON files[] и добавить per-file ID/status.
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
