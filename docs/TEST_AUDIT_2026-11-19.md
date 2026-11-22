# God — аудит тестирования и качества

**Дата:** 22 ноября 2026 года  
**Проверенная ветка:** `main`.  
**Проверенная база main:** HEAD `7f8256b4b6b2945971283b29fd6b46ba8aa1a616`; последний source-code commit main — `653e370c74beba48f2f5b8423276db84b6cecc51`.
**Последняя успешная проверка main:** [run #387](https://github.com/HelterFall/God/actions/runs/45000959608) — JVM tests, debug APK build и artifact upload завершились успешно.
**Проверенный кандидат PR #35:** code/test commit `8130363cb154efd30088eed99ff86d1359847fa4`; [run #397](https://github.com/HelterFall/God/actions/runs/45009371396) завершился `success`.

## 1. Итог

В God уже есть полезный набор unit-тестов для отдельных бинарных парсеров и resolver'ов. По состоянию на проверенную ветку:

- 19 JVM test files и 110 методов `@Test` в `main` после PR #34;
- 19 JVM test files и 116 методов `@Test` в candidate PR #35 (на 6 тестов больше main: три scene graph tests и три новых external-resolver integration tests);
- JUnit 4.13.2;
- CI запускает `:app:testDebugUnitTest` и `:app:assembleDebug`;
- PR #13 run [#297](https://github.com/HelterFall/God/actions/runs/44690179691) прошёл с ARM64 и negative-RVA regression tests; PR #15 run [#301](https://github.com/HelterFall/God/actions/runs/44783788381) добавил успешный CLR metadata scanner integration. PR #11/#12 закрепили function/ordinal imports и schemaVersion 24.

PR #31 добавил per-file AnalyzerRun для Unity header и SerializedFile metadata. PR #33 добавил local MonoScript payload AnalyzerRun. PR #35 candidate добавляет `unity_scene_graph` и per-file outcomes для `unity_external_monoscript`, `unity_external_monoscript_payload`, `unity_external_pointers` и `unity_monobehaviour_script_bindings`, связанные с owner/target `FileRecord.id`. Дополнительно проверяется обновление ранее skipped SerializedFile metadata run при reread внешнего target без дублирования analyzer/file IDs. Main run #387 прошёл базовые 110 тестов; candidate code/test run #397 прошёл 116 тестов, debug APK build и artifact upload. Но **это не является доказательством корректности глубокого сканирования реальной игры**.

Главная рекомендация: не наращивать число парсеров без расширения тестовой пирамиды. Сначала добавить проверки реального сквозного сценария и текущих проблем управления сканированием/экспортом.

## 2. Состояние существующих тестов

| Test file | Количество | Что реально проверяется |
|---|---:|---|
| ClrMetadataParserTest | 3 | Синтетический CLR metadata root, identity/types/fields/methods/AssemblyRef, некорректный signature и bounded window |
| ClrSignatureParserTest | 6 | Primitive field/method signatures, одномерный массив, generic VAR/MVAR, truncated/unknown signatures |
| MonoScriptParserTest | 5 | Little/big endian payload, чтение object из stream, malformed/out-of-range и traversal limit |
| PeParserTest | 9 | Non-PE/truncated input, x86 PE, CLR directory, DLL Import Directory, PE32/PE32+ named/ordinal thunks, Hint, OFT/FirstThunk fallback, truncated thunk, ARM64 recognition, invalid Import Directory RVA и unreadable DLL-name RVA |
| ReportExporterTest | 5 | Null output stream, UTF-8 запись, ошибки открытия/записи/закрытия и закрытие потока |
| ScanCoordinatorTest | 6 | Один активный job, job-scoped cancellation, cancelActive, безопасное завершение и повторное использование координатора |
| AnalyzerRunTest | 3 | Scan-scoped deterministic run IDs, file/analyzer linkage, stable status strings and JSON serialization |
| PartialReportBuilderTest | 6 | Partial/cancelled markers, inventory JSON, escaping, Markdown cap, полный список в JSON, structured `scan.cancelled` issue и сохранение completed/queued AnalyzerRuns |
| ScanIssueTest | 1 | Stable code/stage/severity/message serialization and null path |
| FileRecordTest | 3 | Repeatable path-based IDs, provider-key identity across rename without URI disclosure, duplicate-path disambiguation including duplicate parent folders |
| GameScannerIntegrationTest | 25 | Fake tree: PE/CLR/Unity header/SerializedFile/local MonoScript, `unity_scene_graph`, external MonoScript owner/target payload, external PPtr and MonoBehaviour script binding AnalyzerRuns with FileRecord IDs; success/partial/skipped/failed outcomes, limits 500/40, ambiguous paths, provider failures, extra external target metadata promotion beyond deep-read window, cancellation/queued outcomes |
| ScanCoverageTest | 2 | Чистая status policy для analyzer coverage и aggregate scan status |
| UnityExternalPathResolverTest | 4 | Exact path, unique basename, ambiguity и normalization separators/case |
| UnityFileHeaderParserTest | 7 | Legacy v9, modern v22, endian flag, UnityFS header recognition, malformed sizes/offsets и pre-v9 note |
| UnityManagedTypeResolverTest | 3 | Exact type match, missing/ambiguous assembly и отсутствие ложного вывода при truncated sample |
| UnityMetadataParserTest | 13 | Synthetic layouts v19/v21/v22/v23/v26, TypeTree summaries/blob, object records, MonoScript references, external refs и malformed counts |
| UnityObjectDecoderTest | 4 | GameObject, Transform, PPtr fields, incomplete TypeTree и oversized array |
| UnitySceneGraphResolverTest | 5 | Local parent/child/component links, unresolved/external links, external PPtr resolver и MonoScript bindings |
| UnityVersionResolverTest | 6 | Version string extraction, evidence priority, conflict handling и not-found |

`main` после PR #34 содержит **110 тестов в 19 JVM test files**; run #387 прошёл JVM tests, `:app:assembleDebug` и upload APK artifact. Кандидат PR #35 на code/test commit `8130363` содержит **116 тестов в тех же 19 файлах**; run #397 также прошёл все steps. В репозитории не найдено `src/test/resources` с настоящими игровыми бинарными fixtures и не найдено `src/androidTest` с Android instrumentation tests.

### Что уже хорошо

- Есть тесты как на корректные, так и на malformed/truncated data.
- Проверяются обе endianness для MonoScript payload и SerializedFile metadata.
- Есть проверки границ object sizes, offsets, counts и oversized arrays.
- Проверяется отказ от догадок при неоднозначных путях, assembly matches и version evidence.
- Есть отдельные тесты для политики ScanCoverage.
- Тесты используют самодельные бинарные структуры, поэтому они быстрые, воспроизводимые и не требуют распространять коммерческие игровые файлы.

Синтетические fixtures полезны и должны остаться. Проблема не в их наличии, а в том, что они пока не подкреплены реальными эталонными файлами и полным интеграционным scan test.

## 3. Подтверждённые пробелы тестового покрытия

### QA-01 — PE/CLR и Unity header/SerializedFile scanner paths покрыты; матрица сканера остаётся неполной

`GameScannerIntegrationTest` содержит 19 тестов в main и 25 в candidate PR #35. В кандидате проверяется per-file linkage PE/CLR/Unity header/SerializedFile/local MonoScript/`unity_scene_graph`, external MonoScript owner/target payload, external PPtr и MonoBehaviour script binding; включая analyzed/partial/skipped/failed outcomes, ambiguity, target provider failure и promotion skipped metadata run при дополнительном чтении внешнего файла. Scene graph по-прежнему ограничен локальными PPtr; external pointer resolver выдаёт отдельные resolutions, но не строит полный межфайловый scene graph.

**Пока не покрыто:**

- реальные compiler-produced PE/CLR fixtures; CLR scanner integration пока подтверждён только синтетическим managed PE;
- реальные Unity SerializedFiles/TypeTrees и совместимость нескольких версий; per-file resolver outcomes для внешних MonoScript/PPtr сценариев покрыты синтетическими fixtures, но проверка на compiler-produced/real game files и полная compatibility matrix всё ещё отсутствуют; UnityFS block/directory reading также не реализован;
- широкий набор лимитов, частичные/обрезанные stream reads, ошибки чтения посреди stream, дополнительные классы provider exceptions и non-seekable providers; покрыты одиночный `openInputStream` failure и один `listFiles` failure, но не вся матрица чтения;
- реальные binary fixtures, Android SAF provider и Activity lifecycle.

**Риск:** fake-tree test ловит orchestration inventory → PE/CLR/Unity header/SerializedFile → report, включая links и статусы runs. Синтетические metadata fixtures могут повторять предположения парсера; тест не заменяет реальные игровые сборки или Android document providers.

### QA-02 — JVM lifecycle и partial report укреплены; Android lifecycle tests всё ещё отсутствуют

Реализовано:
- `ScanCoordinator` допускает только один активный job и использует job-scoped cancellation;
- кнопка повторного запуска блокируется; `onDestroy()` запрашивает отмену и вызывает `shutdownNow()`;
- `GameScanner` использует абстракцию `ScanFileAccess` и не зависит от Android API напрямую; SAF `ContentResolver` изолирован в `SafScanFileAccess`, а worker не удерживает Activity сильной ссылкой;
- отмена на проверяемых границах inventory/PE/Unity/resolver возвращает partial report;
- partial report сохраняет весь уже обнаруженный inventory в JSON и ограничивает Markdown 20 000 строк; `analyzerRuns[]` сохраняет завершённые и queued результаты PE/CLR, Unity header/SerializedFile, local MonoScript payload, `unity_scene_graph` и (в PR #35) external MonoScript/PPtr/MonoBehaviour resolver stages, если соответствующие этапы были достигнуты до отмены.

Шесть тестов `PartialReportBuilderTest` проверяют pure report builder, в том числе сохранение completed/queued AnalyzerRuns; fake-tree `GameScannerIntegrationTest` дополнительно проверяет отмену во время inventory traversal и queued runs после inventory.

**Риск:** на candidate PR #35 per-file AnalyzerRun покрывает PE/CLR, Unity header/SerializedFile, локальный MonoScript payload, bounded Unity scene graph и внешние MonoScript/PPtr/MonoBehaviour resolver stages. UI/job state не переживает пересоздание Activity.

### QA-03 — исправлено: безопасная запись отчётов

Запись вынесена в `ReportExporter`. Если Android provider возвращает null вместо output stream, helper выбрасывает IOException. Ошибки открытия, записи и закрытия не подавляются, а вызов из `MainActivity` показывает «Отчёт сохранён» только после успешной записи и закрытия потока.

Пять тестов `ReportExporterTest` проверяют:

- null output stream;
- IOException при открытии;
- IOException при записи;
- IOException при закрытии;
- корректную UTF-8 запись и закрытие потока.

**Оставшаяся граница:** эти JVM-тесты проверяют helper, но не поведение настоящих Android document providers; для него остаётся QA-04 (instrumentation tests).

### QA-04 — нет Android SAF instrumentation tests

Папка игры и файлы читаются через Android Storage Access Framework и `DocumentFile`. Но нет тестов на реальном Android provider/emulator для:

- persistable URI permissions и revoked permissions;
- provider, возвращающего null или ошибку при открытии stream;
- больших каталогов и каталога с большим количеством соседних entries;
- чтения файла с неизвестной длиной;
- отмены чтения;
- пересоздания Activity во время анализа;
- создания экспортного документа.

**Риск:** JVM tests не моделируют поведение Android document providers и не подтверждают пользовательский сценарий на устройстве.

### QA-05 — нет настоящих Unity/PE/CLR fixtures

Все текущие parser inputs создаются программно в тестах либо задаются как Kotlin data classes. Это не заменяет проверки на настоящих файлах, созданных реальными версиями Unity/компиляторами.

Особенно важно проверить:
- реальные SerializedFiles и TypeTrees из нескольких Unity generations;
- Unity 6 build, поскольку это существенная целевая версия проекта;
- настоящий PE import table и различные варианты CLR metadata;
- MonoScript/MonoBehaviour payload из реальных игровых сборок;
- реальные bundle/AssetBundle samples после появления container reader.

**Риск:** синтетический тест может повторять те же предположения, что и parser, и тем самым подтверждать внутренне согласованную, но неверную интерпретацию формата.

### QA-06 — bounded function/ordinal Import Table реализована; расширенная матрица остаётся открытой

`PeParserTest` содержит синтетические PE32+ и PE32 fixtures: named imports с Hint, ordinal imports, корректная ширина thunk entries, приоритет `OriginalFirstThunk` и fallback на `FirstThunk`. Отдельный тест проверяет diagnostic для truncated thunk table и сохранение уже прочитанных symbols. `GameScannerIntegrationTest` подтверждает, что DLL names доходят до `nativeImports`, а function/ordinal records — до `nativeImportSymbols` и Markdown.

Покрыты синтетическое ARM64 machine recognition, Import Directory RVA вне readable sections и unreadable DLL-name RVA с продолжением последующих корректных descriptors. Пока не покрыты ARM64 real samples, широкая malformed descriptor/thunk matrix, delay imports, exports/forwarded exports и настоящие PE fixtures. Статический import не доказывает runtime load/use; dynamic loading и CLR P/Invoke остаются отдельными задачами.

### QA-07 — parser boundary coverage неполон

Примеры пробелов, которые нужно закрывать по мере развития:

- CLR signatures: тесты покрывают небольшой набор primitive/array/generic cases, но не полную матрицу ECMA-335 signatures.
- CLR metadata: нет реальных assembly fixtures и широкого набора malformed table/heap combinations.
- Unity header: тестируются v9 и v22 layouts, но не полная матрица версий и реальные варианты заголовков.
- Unity metadata: synthetic tests затрагивают v19/v21/v22/v23/v26, но не подтверждают каждую промежуточную версию реальными файлами; v26 test проверяет нулевой shared-subtree count, а не декодирование полного shared-subtree payload.
- Unity object/scene: есть небольшие happy-path tests и несколько boundary tests, но не тесты полного scene из настоящего SerializedFile, циклических/повторных ссылок и превышения graph limits.

Это не означает, что перечисленные форматы заведомо сломаны. Это означает, что заявленная поддержка недостаточно подтверждена.

### QA-08 — нет fuzz/property tests и измеряемого coverage

Не обнаружены:
- property-based tests;
- fuzz tests для binary parsers;
- автоматическое тестирование random offsets/lengths/overflow;
- JaCoCo/Kover или другой coverage report;
- trend или порог покрытия для регрессии.

Для бинарных форматов это существенный пробел, так как входные файлы могут быть обрезаны, повреждены или специально сформированы.

### QA-09 — CI проверяет pull request, но покрытие качества пока узкое

Текущий workflow:
- запускается на push в main, через workflow_dispatch и на pull_request в main (trigger добавлен в PR #1);
- выполняет только `:app:testDebugUnitTest` и `:app:assembleDebug`;
- не выполняет Android instrumentation tests, lint/static analysis, release build или проверку report schema;
- загружает debug APK, но не публикует JUnit XML test reports/coverage reports как отдельные artifacts.

**Важно:** run #250 не был пустым. Его log показывает компиляцию Kotlin unit tests, выполнение `:app:testDebugUnitTest`, сборку `:app:assembleDebug` и успешный build. Ограничение относится к ширине проверок, а не к тому, что CI якобы не запускал тесты.

### QA-10 — воспроизводимость Gradle

- В repository tree нет Gradle Wrapper (`gradlew`, wrapper JAR и wrapper properties). CI устанавливает Gradle 8.9 через setup action; версия сборочного инструмента не закреплена wrapper-файлами внутри самого репозитория.

### QA-11 — deprecated Actions runtime

- Последние CI logs выдают предупреждение о Node.js 20 deprecation для используемых major versions GitHub Actions. На проверенном запуске это предупреждение, а не причина падения. Версии Actions нужно обновить до совместимых поддерживаемых runtime и проверить после обновления.

### QA-12 — измеримость тестов

- Не подключён JaCoCo/Kover или аналогичный coverage report; фактическое покрытие кода неизвестно. Сначала следует получить baseline и публиковать его в CI. Не вводить произвольный percentage gate до измерения текущего состояния.

## 4. Проверка последнего CI

Проверенные CI revisions:

- Main HEAD after PR #34: `7f8256b4b6b2945971283b29fd6b46ba8aa1a616`; main run [#387](https://github.com/HelterFall/God/actions/runs/45000959608) succeeded.
- Candidate PR #35 code/test head: `8130363cb154efd30088eed99ff86d1359847fa4`; PR run [#397](https://github.com/HelterFall/God/actions/runs/45009371396) succeeded (116 JVM test methods, debug APK build, artifact upload).

- PR #31 code head `9ccb7f4683ed4e043bd3278d830a1d07710e5d11`; final PR head including docs `f1fb456f571ea2569bba5029ecf7af7a6a9ec57e`; PR run [#369](https://github.com/HelterFall/God/actions/runs/44997551388) completed successfully.
- `:app:testDebugUnitTest`: success.
- `:app:assembleDebug`: success.
- `BUILD SUCCESSFUL`; debug APK artifact успешно загружен.
- `main` содержит 110 методов `@Test` в 19 JVM test files; run #387 успешно выполнил `:app:testDebugUnitTest`, `:app:assembleDebug` и artifact upload.
- Candidate PR #35 содержит 116 методов `@Test` в 19 файлах; run #397 на code/test commit `8130363` подтвердил `BUILD SUCCESSFUL`, `:app:testDebugUnitTest`, `:app:assembleDebug` и upload debug APK artifact. CI пока не публикует JUnit XML и отчёт с числом фактически исполненных тестов.
- CI не запускает Android instrumentation, lint/static checks, release build или coverage report.

## 5. Рекомендуемая очередность закрытия проблем

1. Довести scan lifecycle: JVM single-job/cancellation и partial report с per-file PE/CLR/Unity header/SerializedFile/local MonoScript/scene graph/external resolver outcomes реализованы; добавить Android lifecycle/SAF instrumentation и проверить provider cancellation.
2. Расширить общий FileAccess: positional/range reads, sequential fallback, EOF/truncated input и cancellation-aware reads.
3. Добавить минимальный набор реальных, легально распространяемых PE/CLR/Unity fixtures и golden reports.
4. Добавить Android SAF instrumentation tests на emulator/device.
5. Расширить parser boundary tests и проверить заявленные Unity versions на реальных samples.
6. Добавить fuzz/property tests для binary readers и ограничений.
7. Улучшить CI: Gradle Wrapper, JUnit XML/coverage artifacts, lint/static checks и обновление deprecated action runtimes.
8. Продолжить общую Evidence/Finding/Graph model; текущий typed ScanIssue — только первый шаг, не замена findings/evidence.

Не нужно пытаться выполнить всё одним большим изменением. Каждый пункт следует закрывать отдельными тестируемыми commits; после закрытия QA-03 ближайшие пользовательские риски — отмена/жизненный цикл сканирования и отсутствие scanner integration tests.

## 6. Definition of Done для тестовой зрелости

- Для каждой важной parser family есть unit tests на valid, malformed, truncated и boundary input.
- Для поддерживаемых форматов есть настоящие эталонные fixtures, а не только synthetic byte arrays.
- Есть хотя бы один end-to-end scan fixture, проверяющий JSON, Markdown, coverage и evidence.
- Cancellation, repeated-start, Activity lifecycle и export failure проверяются автоматизированными тестами.
- SAF behavior проверяется Android instrumentation tests.
- CI выполняется на pull request и сохраняет результаты тестов.
- Property/fuzz tests запускаются в контролируемом профиле.
- Coverage измеряется и публикуется; процент не объявляется целью до получения честной исходной метрики.

Наличие зелёного CI — обязательное условие, но не замена этим критериям.
