# God — аудит тестирования и качества

**Дата:** 22 ноября 2026 года  
**Проверенная база `main`:** `dcbdea1c39743f092f32795b9d54507590e763ad`.  
**Последний source commit в базе:** `33d268c9e43b504f75fa032a13df33dc0b975eab`.  
**Последняя успешная проверка `main`:** [run #343](https://github.com/HelterFall/God/actions/runs/44983187122) — 92 JVM tests, debug APK build и artifact upload завершились успешно.  
**Проверенная feature branch:** `feat/file-record-identities`, 18 test files / 95 `@Test` methods; PR [#29 / run #344](https://github.com/HelterFall/God/actions/runs/44984711337) завершился `success` — tests, debug APK build и artifact upload прошли.

## 1. Итог

В God уже есть полезный набор unit-тестов для отдельных бинарных парсеров и resolver'ов. По состоянию на проверенную ветку:

- 18 JVM test files в рабочей ветке (main baseline: 17);
- 95 методов с аннотацией `@Test` (main baseline: 92);
- JUnit 4.13.2;
- CI запускает `:app:testDebugUnitTest` и `:app:assembleDebug`;
- PR #13 run [#297](https://github.com/HelterFall/God/actions/runs/44690179691) прошёл с ARM64 и negative-RVA regression tests; PR #15 run [#301](https://github.com/HelterFall/God/actions/runs/44783788381) добавил успешный CLR metadata scanner integration. PR #11/#12 закрепили function/ordinal imports и schemaVersion 24.

Это подтверждает работу JVM unit- и начальных scanner integration tests, а также сборку Android debug APK. Но **это не является доказательством корректности глубокого сканирования реальной игры**. Восемь тестов запускают настоящий `GameScanner.scan()` на in-memory tree: inventory/PE candidate/import reporting; CLR metadata; synthetic Unity SerializedFile v22 metadata и version evidence; ambiguous external MonoScript basename не выбирает target; unique external MonoScript payload декодируется и попадает в JSON/Markdown; ошибка открытия Unity provider stream сохраняет inventory и warning, но отмечает header analyzer как failed и metadata analyzer как partial/skipped; ошибка `listFiles()` у подкаталога сохраняет доступный sibling inventory, warning и `inventory.status=partial` с `inaccessibleDirectoryCount=1`; partial inventory при отмене. В существующие сценарии добавлены проверки стабильных file IDs, `parentId`, `inventoryStatus` для недоступной папки и для обеих родительских папок при отмене внутри вложенного каталога. Дополнительно проверяются typed `issues[]` для ошибки provider/directory listing и cancellation; существующий `warnings[]` и текст Markdown сохраняются. Реальные игровые бинарные fixtures, Android SAF providers, UI/lifecycle и полный диапазон parser/resolver paths всё ещё не покрыты.

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
| PartialReportBuilderTest | 5 | Partial/cancelled markers, inventory JSON, JSON escaping, Markdown cap, полный список в JSON и structured `scan.cancelled` issue |
| ScanIssueTest | 1 | Stable code/stage/severity/message serialization and null path |
| FileRecordTest | 3 | Repeatable path-based IDs, provider-key identity across rename without URI disclosure, duplicate-path disambiguation |
| GameScannerIntegrationTest | 8 | Fake tree: inventory/progress, PE success/failure/import reporting, CLR path, Unity SerializedFile v22 metadata/version, repeatable FileRecord IDs and parent links, inaccessible directory status, partial parent statuses on nested cancellation, external MonoScript resolution, provider failures and legacy warning compatibility |
| ScanCoverageTest | 2 | Чистая status policy для analyzer coverage и aggregate scan status |
| UnityExternalPathResolverTest | 4 | Exact path, unique basename, ambiguity и normalization separators/case |
| UnityFileHeaderParserTest | 7 | Legacy v9, modern v22, endian flag, UnityFS header recognition, malformed sizes/offsets и pre-v9 note |
| UnityManagedTypeResolverTest | 3 | Exact type match, missing/ambiguous assembly и отсутствие ложного вывода при truncated sample |
| UnityMetadataParserTest | 13 | Synthetic layouts v19/v21/v22/v23/v26, TypeTree summaries/blob, object records, MonoScript references, external refs и malformed counts |
| UnityObjectDecoderTest | 4 | GameObject, Transform, PPtr fields, incomplete TypeTree и oversized array |
| UnitySceneGraphResolverTest | 5 | Local parent/child/component links, unresolved/external links, external PPtr resolver и MonoScript bindings |
| UnityVersionResolverTest | 6 | Version string extraction, evidence priority, conflict handling и not-found |

В feature branch всего: **92 тестовых метода в 17 файлах**. В репозитории не найдено `src/test/resources` с настоящими игровыми бинарными fixtures и не найдено `src/androidTest` с Android instrumentation tests.

### Что уже хорошо

- Есть тесты как на корректные, так и на malformed/truncated data.
- Проверяются обе endianness для MonoScript payload и SerializedFile metadata.
- Есть проверки границ object sizes, offsets, counts и oversized arrays.
- Проверяется отказ от догадок при неоднозначных путях, assembly matches и version evidence.
- Есть отдельные тесты для политики ScanCoverage.
- Тесты используют самодельные бинарные структуры, поэтому они быстрые, воспроизводимые и не требуют распространять коммерческие игровые файлы.

Синтетические fixtures полезны и должны остаться. Проблема не в их наличии, а в том, что они пока не подкреплены реальными эталонными файлами и полным интеграционным scan test.

## 3. Подтверждённые пробелы тестового покрытия

### QA-01 — CLR, SerializedFile и external MonoScript scanner paths покрыты; матрица сканера остаётся неполной

`GameScannerIntegrationTest` запускает настоящий `GameScanner.scan()` на in-memory `ScanNode` дереве. Тесты проверяют inventory/PE imports; synthetic CLR metadata; synthetic SerializedFile v22/version evidence; ambiguity no-guess и успешный unique external MonoScript payload; provider open failure с корректными analyzer statuses и typed `unity.header_analysis_failed` issue; directory listing failure с typed `inventory.directory_listing_failed` (stage/severity/path/message), partial inventory и сохранением доступного sibling; cancellation с partial inventory. Отдельно `PartialReportBuilderTest` проверяет `scan.cancelled` issue. Эти сценарии воспроизводимы на fake-tree и не заменяют Android SAF providers или реальные игровые binaries.

**Пока не покрыто:**

- реальные compiler-produced PE/CLR fixtures; CLR scanner integration пока подтверждён только синтетическим managed PE;
- реальные Unity SerializedFiles/TypeTrees и совместимость нескольких версий; проверка external MonoScript binding на compiler-produced/real game files; полная scene/PPtr resolver matrix; UnityFS block/directory reading и дальнейшие resolver stages;
- широкий набор лимитов, частичные/обрезанные stream reads, ошибки чтения посреди stream, дополнительные классы provider exceptions и non-seekable providers; покрыты одиночный `openInputStream` failure и один `listFiles` failure, но не вся матрица чтения;
- реальные binary fixtures, Android SAF provider и Activity lifecycle.

**Риск:** fake-tree test ловит ошибки orchestration inventory → PE/CLR metadata → report, включая успешную передачу CLR assembly/type results и coverage. Синтетический metadata root может повторять предположения парсера; тест не заменяет реальные compiler-produced assemblies или Android document providers.

### QA-02 — JVM lifecycle и partial report укреплены; Android lifecycle tests всё ещё отсутствуют

Реализовано:
- `ScanCoordinator` допускает только один активный job и использует job-scoped cancellation;
- кнопка повторного запуска блокируется; `onDestroy()` запрашивает отмену и вызывает `shutdownNow()`;
- `GameScanner` использует абстракцию `ScanFileAccess` и не зависит от Android API напрямую; SAF `ContentResolver` изолирован в `SafScanFileAccess`, а worker не удерживает Activity сильной ссылкой;
- отмена на проверяемых границах inventory/PE/Unity/resolver возвращает partial report;
- partial report сохраняет весь уже обнаруженный inventory в JSON, а Markdown ограничивает 20 000 строк; отчёт явно помечен `scanStatus=cancelled`, `partial=true`, `scanCoverage.status=partial` и предупреждает, что parser findings не завершены.

Пять тестов `PartialReportBuilderTest` проверяют pure report builder; fake-tree `GameScannerIntegrationTest` дополнительно проверяет отмену во время inventory traversal. Android instrumentation для Activity recreation/SAF и тест отмены во время блокирующего provider read всё ещё отсутствуют. Отмена внутри resolver возможна после завершения текущей операции/этапа, не мгновенно.

**Риск:** partial report пока inventory-only; результаты анализаторов, успевших частично выполниться, намеренно не включаются. UI/job state не переживает пересоздание Activity.

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

Проверенный актуальный main run:

https://github.com/HelterFall/God/actions/runs/44983187122

- Commit: `dcbdea1c39743f092f32795b9d54507590e763ad` (документационные обновления после source commit `33d268c`).
- `:app:testDebugUnitTest`: success.
- `:app:assembleDebug`: success.
- `BUILD SUCCESSFUL`; debug APK artifact успешно загружен.
- Проверенная main-база содержит 92 метода `@Test` в 17 JVM test files; текущая ветка расширяет её до 95 методов в 18 файлах.
- PR [#29 / run #344](https://github.com/HelterFall/God/actions/runs/44984711337) подтвердил новый FileRecord model и scanner assertions: `:app:testDebugUnitTest`, `:app:assembleDebug` и APK artifact upload завершились успешно.
- CI не запускает Android instrumentation, lint/static checks, release build или coverage report.

## 5. Рекомендуемая очередность закрытия проблем

1. Довести scan lifecycle: JVM single-job/cancellation и inventory-only partial report реализованы; добавить Android lifecycle/SAF instrumentation и проверить provider cancellation.
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
