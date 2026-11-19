# God — аудит тестирования и качества

**Дата:** 19 ноября 2026 года  
**Проверенная ветка:** `main`  
**Проверенный commit:** f0da77b9c5efc3f11074ecc8389d269ee651da4c
**Последняя успешная проверка CI:** run [#275](https://github.com/HelterFall/God/actions/runs/44432016114) успешно проверил main commit `f0da77b9c5efc3f11074ecc8389d269ee651da4c` (79 JVM tests, debug APK и artifact upload).

## 1. Итог

В God уже есть полезный набор unit-тестов для отдельных бинарных парсеров и resolver'ов. По состоянию на проверенную ветку:

- 16 JVM test files;
- 79 методов с аннотацией `@Test`;
- JUnit 4.13.2;
- CI запускает `:app:testDebugUnitTest` и `:app:assembleDebug`;
- run [#274](https://github.com/HelterFall/God/actions/runs/44431813324) успешно выполнил Kotlin unit tests, сборку APK и upload artifact для PR #4; main run #275 повторил успешную проверку после merge.

Это подтверждает работу JVM unit- и начальных scanner integration tests, а также сборку Android debug APK. Но **это не является доказательством корректности глубокого сканирования реальной игры**. Два теста запускают настоящий `GameScanner.scan()` на in-memory tree и проверяют основной путь inventory → PE candidate → coverage → JSON/Markdown, а также partial inventory при отмене. Реальные игровые бинарные fixtures, Android SAF providers, UI/lifecycle и полный диапазон parser/resolver paths всё ещё не покрыты.

Главная рекомендация: не наращивать число парсеров без расширения тестовой пирамиды. Сначала добавить проверки реального сквозного сценария и текущих проблем управления сканированием/экспортом.

## 2. Состояние существующих тестов

| Test file | Количество | Что реально проверяется |
|---|---:|---|
| ClrMetadataParserTest | 3 | Синтетический CLR metadata root, identity/types/fields/methods/AssemblyRef, некорректный signature и bounded window |
| ClrSignatureParserTest | 6 | Primitive field/method signatures, одномерный массив, generic VAR/MVAR, truncated/unknown signatures |
| MonoScriptParserTest | 5 | Little/big endian payload, чтение object из stream, malformed/out-of-range и traversal limit |
| PeParserTest | 3 | Non-PE/truncated input, x86 PE и CLR directory |
| ReportExporterTest | 5 | Null output stream, UTF-8 запись, ошибки открытия/записи/закрытия и закрытие потока |
| ScanCoordinatorTest | 6 | Один активный job, job-scoped cancellation, cancelActive, безопасное завершение и повторное использование координатора |
| PartialReportBuilderTest | 5 | Partial/cancelled markers, inventory JSON, JSON escaping, Markdown cap и полный список в JSON |
| GameScannerIntegrationTest | 2 | Fake tree: inventory/progress, PE candidate and failed accounting, ScanCoverage, Markdown/JSON and partial inventory on cancellation |
| ScanCoverageTest | 2 | Чистая status policy для analyzer coverage и aggregate scan status |
| UnityExternalPathResolverTest | 4 | Exact path, unique basename, ambiguity и normalization separators/case |
| UnityFileHeaderParserTest | 7 | Legacy v9, modern v22, endian flag, UnityFS header recognition, malformed sizes/offsets и pre-v9 note |
| UnityManagedTypeResolverTest | 3 | Exact type match, missing/ambiguous assembly и отсутствие ложного вывода при truncated sample |
| UnityMetadataParserTest | 13 | Synthetic layouts v19/v21/v22/v23/v26, TypeTree summaries/blob, object records, MonoScript references, external refs и malformed counts |
| UnityObjectDecoderTest | 4 | GameObject, Transform, PPtr fields, incomplete TypeTree и oversized array |
| UnitySceneGraphResolverTest | 5 | Local parent/child/component links, unresolved/external links, external PPtr resolver и MonoScript bindings |
| UnityVersionResolverTest | 6 | Version string extraction, evidence priority, conflict handling и not-found |

Всего: **79 тестовых методов в 16 файлах**. В репозитории не найдено `src/test/resources` с настоящими игровыми бинарными fixtures и не найдено `src/androidTest` с Android instrumentation tests.

### Что уже хорошо

- Есть тесты как на корректные, так и на malformed/truncated data.
- Проверяются обе endianness для MonoScript payload и SerializedFile metadata.
- Есть проверки границ object sizes, offsets, counts и oversized arrays.
- Проверяется отказ от догадок при неоднозначных путях, assembly matches и version evidence.
- Есть отдельные тесты для политики ScanCoverage.
- Тесты используют самодельные бинарные структуры, поэтому они быстрые, воспроизводимые и не требуют распространять коммерческие игровые файлы.

Синтетические fixtures полезны и должны остаться. Проблема не в их наличии, а в том, что они пока не подкреплены реальными эталонными файлами и полным интеграционным scan test.

## 3. Подтверждённые пробелы тестового покрытия

### QA-01 — начальная fake-tree integration coverage добавлена; матрица сканера остаётся неполной

`GameScannerIntegrationTest` запускает настоящий `GameScanner.scan()` на in-memory `ScanNode` дереве. Первый тест проверяет directory traversal, inventory/progress, PE candidate selection и accounting неудачного PE parse, `ScanCoverage`, schemaVersion=23, JSON files[] и Markdown. Второй тест отменяет обход посреди дерева и проверяет, что partial report включает уже посещённые entries, но не более поздние.

**Пока не покрыто:**

- успешный parse PE/CLR и реальные managed metadata fixtures;
- успешное чтение SerializedFile, UnityFS и дальнейшие resolver stages;
- все лимиты, недоступные каталоги, ошибки/обрывы потоков и non-seekable providers;
- реальные binary fixtures, Android SAF provider и Activity lifecycle.

**Риск:** начальный fake-tree test ловит ошибки orchestration inventory → PE → coverage → report, но не подтверждает точность остальных анализаторов или поведение настоящих Android document providers.

### QA-02 — JVM lifecycle и partial report укреплены; Android lifecycle tests всё ещё отсутствуют

Реализовано:
- `ScanCoordinator` допускает только один активный job и использует job-scoped cancellation;
- кнопка повторного запуска блокируется; `onDestroy()` запрашивает отмену и вызывает `shutdownNow()`;
- `GameScanner` использует абстракцию `ScanFileAccess` и не зависит от Android API напрямую; SAF `ContentResolver` изолирован в `SafScanFileAccess`, а worker не удерживает Activity сильной ссылкой;
- отмена на проверяемых границах inventory/PE/Unity/resolver возвращает partial report;
- partial report сохраняет весь уже обнаруженный inventory в JSON, а Markdown ограничивает 20 000 строк; отчёт явно помечен `scanStatus=cancelled`, `partial=true`, `scanCoverage.status=partial` и предупреждает, что parser findings не завершены.

Пять тестов `PartialReportBuilderTest` проверяют pure report builder; новый fake-tree `GameScannerIntegrationTest` дополнительно проверяет отмену во время inventory traversal. Android instrumentation для Activity recreation/SAF и тест отмены во время блокирующего provider read всё ещё отсутствуют. Отмена внутри resolver возможна после завершения текущей операции/этапа, не мгновенно.

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

### QA-06 — PE parser почти не проверен на рабочие imports

Сейчас `PeParserTest` содержит три теста: non-PE/truncated, x86 architecture и CLR directory. Нет положительного теста, который создаёт секции и Import Directory и проверяет извлечение имени импортируемой DLL. Не покрыты ARM64 и malformed import descriptor/RVA scenarios.

Это особенно важно, потому что текущая PE функциональность включает чтение имён DLL из static Import Table.

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

Проверенный run:

https://github.com/HelterFall/God/actions/runs/44398435979

- Commit: `7e737eb6f7f4da28879937ffaf064aa1cdb76d05`.
- Step `Run JVM parser tests and build debug APK`: success.
- В log есть `compileDebugUnitTestKotlin`, `testDebugUnitTest`, `assembleDebug` и `BUILD SUCCESSFUL`.
- Step `Upload APK`: success.
- Workflow не публикует в log сводку вида «61 tests passed» и не загружает JUnit XML. Число 61 получено подсчётом `@Test` методов в 12 test source files, а не из отдельного test report artifact.

После source baseline `cb69a25` в main менялась документация; успешный CI для документальных commit повторно проверяет тот же кодовый baseline, а не новые runtime-функции.

## 5. Рекомендуемая очередность закрытия проблем

1. Довести scan lifecycle: JVM single-job/cancellation и inventory-only partial report реализованы; добавить Android lifecycle/SAF instrumentation и проверить provider cancellation.
2. Исправить экспорт: null stream и любые write/close errors должны приводить к статусу failure; добавить тесты.
3. Выделить testable scanner/file-access interfaces, чтобы запускать GameScanner над fake file tree и проверять inventory → analyzers → coverage → JSON/Markdown.
4. Добавить минимальный набор реальных, легально распространяемых PE/CLR/Unity fixtures и golden reports.
5. Добавить положительные PE import tests, расширить parser boundary tests и проверить заявленные Unity versions.
6. Добавить Android SAF instrumentation tests на emulator/device.
7. Добавить fuzz/property tests для binary readers и ограничений.
8. Улучшить CI: pull_request trigger, Gradle Wrapper, JUnit XML/coverage artifacts, lint/static checks и обновление deprecated action runtimes.

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
