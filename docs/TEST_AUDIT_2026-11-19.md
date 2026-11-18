# God — аудит тестирования и качества

**Дата:** 19 ноября 2026 года  
**Проверенная ветка:** main  
**Исходный commit перед текущим изменением:** 1bad13cfa47ba6b13213f33187a858d4fad255c5  
**Последняя успешная проверка CI до текущего изменения:** run #256, commit 1bad13cfa47ba6b13213f33187a858d4fad255c5; workflow завершился успешно.

## 1. Итог

В God уже есть полезный набор unit-тестов для отдельных бинарных парсеров и resolver'ов. По состоянию на проверенную ветку:

- 13 JVM test files;
- 66 методов с аннотацией `@Test`;
- JUnit 4.13.2;
- CI запускает `:app:testDebugUnitTest` и `:app:assembleDebug`;
- последний запуск до этой правки — #256 — завершился успешно, включая компиляцию Kotlin tests, выполнение test task, сборку APK и загрузку artifact; новый CI запуск должен подтвердить добавленные пять тестов.

Это подтверждает работу изолированных JVM-тестов и сборку Android debug APK после успешного CI. Но **это не является доказательством корректности полного сканирования игры**. Реальные игровые файлы, Android SAF, UI, полный `GameScanner.scan()`, cancellation и Android export/provider lifecycle по-прежнему не покрыты интеграционными тестами. Запись отчёта дополнительно проверяется отдельными JVM-тестами `ReportExporterTest`.

Главная рекомендация: не наращивать число парсеров без расширения тестовой пирамиды. Сначала добавить проверки реального сквозного сценария и текущих проблем управления сканированием/экспортом.

## 2. Состояние существующих тестов

| Test file | Количество | Что реально проверяется |
|---|---:|---|
| ClrMetadataParserTest | 3 | Синтетический CLR metadata root, identity/types/fields/methods/AssemblyRef, некорректный signature и bounded window |
| ClrSignatureParserTest | 6 | Primitive field/method signatures, одномерный массив, generic VAR/MVAR, truncated/unknown signatures |
| MonoScriptParserTest | 5 | Little/big endian payload, чтение object из stream, malformed/out-of-range и traversal limit |
| PeParserTest | 3 | Non-PE/truncated input, x86 PE и CLR directory |
| ReportExporterTest | 5 | Null output stream, UTF-8 запись, ошибки открытия/записи/закрытия и закрытие потока |
| ScanCoverageTest | 2 | Чистая status policy для analyzer coverage и aggregate scan status |
| UnityExternalPathResolverTest | 4 | Exact path, unique basename, ambiguity и normalization separators/case |
| UnityFileHeaderParserTest | 7 | Legacy v9, modern v22, endian flag, UnityFS header recognition, malformed sizes/offsets и pre-v9 note |
| UnityManagedTypeResolverTest | 3 | Exact type match, missing/ambiguous assembly и отсутствие ложного вывода при truncated sample |
| UnityMetadataParserTest | 13 | Synthetic layouts v19/v21/v22/v23/v26, TypeTree summaries/blob, object records, MonoScript references, external refs и malformed counts |
| UnityObjectDecoderTest | 4 | GameObject, Transform, PPtr fields, incomplete TypeTree и oversized array |
| UnitySceneGraphResolverTest | 5 | Local parent/child/component links, unresolved/external links, external PPtr resolver и MonoScript bindings |
| UnityVersionResolverTest | 6 | Version string extraction, evidence priority, conflict handling и not-found |

Всего: **66 тестовых методов в 13 файлах**. В репозитории не найдено `src/test/resources` с настоящими игровыми бинарными fixtures и не найдено `src/androidTest` с Android instrumentation tests.

### Что уже хорошо

- Есть тесты как на корректные, так и на malformed/truncated data.
- Проверяются обе endianness для MonoScript payload и SerializedFile metadata.
- Есть проверки границ object sizes, offsets, counts и oversized arrays.
- Проверяется отказ от догадок при неоднозначных путях, assembly matches и version evidence.
- Есть отдельные тесты для политики ScanCoverage.
- Тесты используют самодельные бинарные структуры, поэтому они быстрые, воспроизводимые и не требуют распространять коммерческие игровые файлы.

Синтетические fixtures полезны и должны остаться. Проблема не в их наличии, а в том, что они пока не подкреплены реальными эталонными файлами и полным интеграционным scan test.

## 3. Подтверждённые пробелы тестового покрытия

### QA-01 — нет сквозных тестов GameScanner

В test source tree нет теста `GameScanner` или теста полного сканирования папки. Следовательно, тестами не проверяются вместе:

- directory traversal и лимиты inventory;
- candidate selection для PE/Unity файлов;
- подсчёты attempted/parsed/failed/skipped в `buildScanCoverage()`;
- сбор Unity version evidence из файлов;
- последовательность external MonoScript/PPtr/type resolvers;
- согласованность Markdown и JSON;
- schemaVersion=23 и содержимое `scanCoverage/files[]`;
- корректная обработка ошибок чтения внутри полного scan pipeline.

Unit tests парсеров не ловят ошибки их orchestration и подсчётов покрытия.

**Риск:** отдельный parser может пройти тесты, а итоговый отчёт всё равно неверно отразить количество кандидатов, успешных чтений или пропусков.

### QA-02 — нет тестов MainActivity, cancellation и lifecycle

В текущем UI:

- используется общий `@Volatile scanCancelled` для всех заданий;
- кнопка запуска анализа не блокируется на время активного scan;
- повторные нажатия могут поставить задания в очередь single-thread executor;
- отмена приводит к `ScanCancelledException` и сообщению «Отчёт не создан», то есть partial report теряется;
- `onDestroy()` вызывает `executor.shutdown()`, но не отменяет выполняющийся scan;
- `GameScanner` получает Activity и использует её ContentResolver, поэтому длительная операция может продолжить работу с уже уничтоженной Activity;
- при пересоздании Activity сохраняется URI папки, но не состояние анализа или полученный report.

Эти сценарии не покрываются ни JVM, ни Android instrumentation tests.

**Риск:** повторные задания, потеря результатов при отмене/повороте экрана и работа фоновой задачи после уничтожения Activity.

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

### QA-09 — CI пока проверяет только узкую часть качества

Текущий workflow:
- запускается на push в main и через workflow_dispatch;
- не имеет отдельного trigger `pull_request`;
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

1. Исправить жизненный цикл scan job: один активный job, job-scoped cancellation token, отключение повторного запуска, безопасная отмена при Activity destruction и сохранение частичного результата.
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
