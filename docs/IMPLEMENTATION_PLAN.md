# God — план дальнейших действий

**Дата:** 19 ноября 2026 года  
**Базовая ветка:** main  
**Source baseline, проверенный по коду:** cb69a252aa7c7c9aee8eaa206dbe7835b9f3c9c8  
**Контрольная точка документации при составлении плана:** 2ba10b3376accb46eeefbbbd5886a7d8aae555d3  
**Последняя проверка CI:** run #250 для документационного commit 7e737eb завершился успешно; после source baseline cb69a25 менялись только документы. Отдельный аудит тестов: 12 JVM test files / 61 @Test methods; Android instrumentation и end-to-end scanner tests пока отсутствуют.  
**Связанные документы:** [назначение и конечный результат](GOD_PURPOSE_AND_OUTPUT.md), [полная дорожная карта](FULL_ANALYZER_ROADMAP.md), [технический аудит](AUDIT_2026-11-18.md), [аудит тестирования и CI](TEST_AUDIT_2026-11-19.md).

## 1. С чего начинаем

God уже умеет выполнять ограниченную инвентаризацию папки, показывать базовый прогресс, принимать запрос на отмену, экспортировать Markdown/JSON, собирать ScanCoverage, анализировать PE/CLR metadata и часть Unity SerializedFile/scene data. Последний проверенный CI — run #250 (JVM parser tests и debug APK build успешны). Этот run проверял документальный commit; код приложения остаётся на source baseline cb69a25. Отдельный test audit обнаружил пробелы за пределами parser unit tests. Это подтверждает сборку и существующие тесты, но не означает, что глубокое исследование игры уже реализовано.

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
- JSON Schema и manifest нужны для целостности, воспроизводимости и будущей автоматизации отчётов. Уже существующий schemaVersion=23 считать текущей версией структуры JSON; не вводить второй номер версии с тем же смыслом.
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
- [x] Кнопка запроса отмены и проверки флага отмены во время обхода и отдельных циклов анализа.
- [x] Агрегированная ScanCoverage: inventory counters, PE/CLR/Unity analyzer candidate/attempt/parsed/partial/failed/limited/skipped counts и derived status.

**Ограничения, которые не следует путать с отсутствием функции:** прогресс пока относится преимущественно к обходу папок; отдельного прогресса каждого анализатора нет. Отмена использует один общий флаг Activity, не изолированный по scan job; отменённый анализ выбрасывает ScanCancelledException и не сохраняет частичный отчёт. Кнопка запуска анализа остаётся доступной во время работы, поэтому задания могут попасть в очередь однопоточного executor.

### Уже существующие форматы и экспорт

- [x] Экспорт Markdown и JSON.
- [x] JSON включает schemaVersion=23, scanCoverage и массив files для всех собранных inventory entries.
- [x] Markdown tree имеет отдельный предел 20 000 строк.
- [x] Текущие Markdown и JSON сначала формируются в памяти как строки; потокового ReportWriter пока нет.
- [x] Экспорт открывает пользовательский destination через SAF.

**Известный дефект:** openOutputStream(uri) вызывается через safe call. Если provider вернул null, код всё равно показывает «Отчёт сохранён». Это нужно исправить, а не реализовывать экспорт с нуля.

### Уже существующие анализаторы

- [x] Unity build inventory и эвристическая классификация Mono/IL2CPP.
- [x] Multi-source Unity version evidence с разрешением конфликтов без угадывания.
- [x] PE headers, машинная архитектура x86/x86-64/ARM64, ограниченное чтение sections/RVA и имена DLL из static Import Table.
- [x] CLR directory recognition и ограниченная CLR metadata: Assembly identity, Module, TypeDef, поля, методы, AssemblyRef и распространённые signatures.
- [x] Привязка полей/методов к TypeDef и ограниченное сопоставление MonoScript identity с managed TypeDef.
- [x] Unity SerializedFile headers и metadata v19–v26 в ограниченных окнах.
- [x] Sampled object records, ClassID counts, TypeTree summaries и выбранные полные TypeTrees.
- [x] Ограниченное чтение MonoScript payload, часть local/external PPtr resolution и MonoBehaviour script bindings.
- [x] Ограниченный decoder GameObject/Transform/MonoBehaviour и локальный GameObject–Transform–Component scene graph.
- [x] Распознавание UnityFS header.

**Не подразумевается как завершённое:** распаковка UnityFS/AssetBundle/ZIP, полноценная PE function import/export analysis, P/Invoke map и IL bodies, IL2CPP metadata parsing, полный scene graph и asset payload decoding, общий cross-domain dependency graph и Porting Dossier generator.
## 2.2. Проблемы, выявленные аудитом тестирования и качества

Полная матрица тестов, проверенные CI steps и технические детали: [TEST_AUDIT_2026-11-19.md](TEST_AUDIT_2026-11-19.md).

### P1 — исправить до расширения функциональности

- [ ] **QA-01 — scanner integration coverage.** Нет теста полного GameScanner.scan(): inventory → candidate selection → analyzers → coverage → JSON/Markdown. Выделить testable file tree/access interfaces и добавить end-to-end fixture.
- [ ] **QA-02 — scan lifecycle/cancellation race.** Общий scanCancelled флаг, отсутствие scan lock и активная кнопка повторного запуска допускают queued jobs; отмена не создаёт partial report; выполняющаяся задача может пережить Activity.onDestroy(). Добавить job-scoped cancellation, один активный job, безопасное lifecycle handling и regression tests.
- [ ] **QA-03 — export false success.** Null из openOutputStream(uri) сейчас может быть ошибочно показан как успешное сохранение. Исправить и покрыть null/IOException/write failure tests.
- [ ] **QA-04 — Android SAF tests.** Нет instrumentation tests для реальных document providers, URI permissions, stream errors, Activity recreation и export destination.
- [ ] **QA-05 — real binary fixtures.** Текущие 61 @Test в основном используют synthetic byte arrays и вручную созданные model objects. Добавить небольшой набор реально созданных/легально распространяемых PE, CLR и Unity SerializedFile/TypeTree fixtures, включая Unity 6.
- [ ] **QA-06 — positive PE imports coverage.** Текущий PeParserTest не создаёт настоящий Import Directory с секцией и не проверяет успешно извлечённые DLL names; ARM64 и malformed import descriptors тоже не покрыты.
- [ ] **QA-07 — parser compatibility matrix.** Уточнить и расширить проверки CLR signatures/metadata, Unity header versions, SerializedFile v19–v26, TypeTree/shared subtree и scene graph limits. Synthetic tests оставить, но сверять их с реальными samples.
- [ ] **QA-08 — binary robustness.** Добавить property/fuzz tests для offsets, lengths, counts, overflow, malformed heaps, truncated streams и compression/container boundaries.

### P2 — надёжность разработки и CI

- [ ] **QA-09 — pull request validation and test artifacts.** Workflow сейчас запускается на push в main и вручную; добавить pull_request, сохранение JUnit XML и coverage reports, lint/static checks и отдельные Android instrumentation checks.
- [ ] **QA-10 — reproducible Gradle.** В репозитории нет Gradle Wrapper; добавить wrapper, закрепить Gradle distribution и использовать его локально/в CI.
- [ ] **QA-11 — deprecated Actions runtime.** Последние workflow logs предупреждают о Node.js 20 deprecation у используемых Actions major versions. Обновить actions до поддерживаемых runtime и проверить workflow повторно.
- [ ] **QA-12 — измеримость тестов.** Подключить JaCoCo/Kover или аналогичный отчёт. Сначала получить baseline, не вводить произвольный процентный gate до измерения.

**Текущая база:** 12 test files / 61 @Test methods, JUnit 4.13.2. Успешный run #250 подтверждает выполнение `:app:testDebugUnitTest` и `:app:assembleDebug`, но не заменяет интеграционные/Android tests. Зелёный run не помечает ни один QA-01…QA-12 пункт выполненным.

## 3. Последовательность реализации

### Этап 0 — зафиксировать исходную точку

**Статус:** [x] выполнено для базового commit.

- [x] Проверить текущую ветку и зафиксировать исходный commit.
- [x] Проверить зелёный CI: последний подтверждённый run #248 (JVM parser tests и debug APK build успешны); source code после baseline cb69a25 не менялся.
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
- [ ] Добавить typed ScanIssue, сохранив текущие warnings при переходном периоде.
- [ ] Ввести GraphNode/GraphEdge здесь один раз; последующие этапы должны наполнять и связывать их, а не создавать новые типы.
- [ ] Добавить сериализацию/десериализацию и round-trip tests.
- [ ] Подключить один существующий parser end-to-end как образец и затем постепенно подключить остальные.
- [ ] Расширить per-file/per-analyzer statuses, не дублируя агрегированную ScanCoverage.

**Критерий завершения:** один тестовый scan сохраняет FileRecord, parser finding, Evidence и graph edge в единой модели; после сериализации сохраняются ID, связи и точный source locator.

### Этап 2 — единый файловый слой поверх SAF

**Приоритет:** P0  
**Зависимости:** этап 1.

Сейчас GameScanner использует DocumentFile и отдельный readPrefix(uri, maximum), который открывает ContentResolver InputStream и копит ограниченный ByteArrayOutputStream. Это bounded prefix read, но не общий файловый API и не random access.

**Задачи:**
- [ ] Ввести FileAccess / ReadableEntry abstraction на базе уже используемых SAF URI: relative path, size, stream и capability flags.
- [ ] Поддержать sequential stream и отдельную операцию positional read, если SAF provider действительно её предоставляет.
- [ ] Для provider без seek использовать честный sequential fallback; не обещать random access, которого нет.
- [ ] Расширить существующий shouldCancel callback до job-scoped cancellation token, передаваемого в операции чтения и декомпрессии.
- [ ] Проверять offset, длины, integer overflow и границы диапазонов до чтения.
- [ ] Не делать полную копию файла в RAM ради чтения небольшого участка.
- [ ] Добавить fake in-memory и file-backed test implementations.

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
- [ ] Отключать кнопку повторного запуска, пока scan активен; сейчас повторные нажатия могут поставить новые задания в single-thread executor.
- [ ] Заменить общий scanCancelled на отдельный cancellation token для каждого задания; общий флаг сейчас сбрасывается при новом вызове и не изолирует queued jobs.
- [ ] При отмене сохранять уже собранные данные и формировать честный partial report вместо текущего поведения «отчёт не создан».
- [ ] Добавить прогресс по стадиям и анализаторам; текущий счётчик/путь отражает в основном обход папок.
- [ ] Сохранять результат/состояние scan при пересоздании Activity; сейчас сохраняется URI папки, но не отчёт и не состояние задания.

**Обязательные регрессионные тесты:** повторный старт во время активного scan не ставит второй job; отмена действует только на соответствующий job; Activity destruction не оставляет задачу, обращающуюся к уничтоженной Activity; отмена сохраняет partial report и его coverage.

**Критерий завершения:** на тестовом дереве каждый собранный entry имеет stable ID и terminal status; одновременно существует не более одного активного scan; отмена одного задания не затрагивает другое и создаёт корректный partial report.

### Этап 4 — потоковое сохранение отчёта

**Приоритет:** P0  
**Зависимости:** этапы 1–3.

Экспорт Markdown и JSON уже работает. JSON содержит schemaVersion=23 и files[] с собранными inventory entries. Но buildMarkdown/buildJson возвращают большие строки, а MainActivity хранит обе строки в памяти до экспорта. Отдельного ReportWriter, JSON Schema и manifest пока нет.

**Задачи:**
- [ ] Создать отдельный ReportWriter, не связанный с UI и GameScanner.
- [ ] Записывать крупные секции потоково, не собирая весь отчёт в одну большую строку.
- [ ] Разделить краткое резюме и подробные данные (inventory/findings/graph edges), сохранив текущие Markdown/JSON outputs как совместимый переходный формат.
- [ ] Формализовать существующий schemaVersion=23 как версию структуры JSON: определить правила bump/backward compatibility. Не вводить второй report format version с тем же смыслом.
- [ ] Добавить JSON Schema и тест валидации текущего и будущего report format.
- [ ] Добавить manifest с версией God/analyzer, schemaVersion, временем, coverage и hashes частей отчёта.
- [ ] Проверять null output stream и ошибки записи; сейчас openOutputStream()?.use(...) может завершиться без записи, но UI всё равно сообщает «Отчёт сохранён».
- [ ] Поддержать partial/cancelled report с явной маркировкой.

**Обязательные регрессионные тесты:** null output stream, IOException при открытии и записи, write/close failure, partial report после cancellation и повторное чтение сформированного JSON.

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
- [x] Чтение имён DLL из static Import Table в пределах доступного окна чтения.

**Ограничение текущего импорта:** PeInfo.imports содержит имена импортируемых DLL, а не полные имена импортируемых функций; экспорты и delay imports не анализируются. PE parser получает prefix до 1 MiB, а managed PE может перечитываться до 4 MiB.

**Что добавить:**
- [ ] Расширить чтение PE sections/data directories с positional reads, чтобы не зависеть от небольшого prefix.
- [ ] Import Table с function names, ordinal imports и подробными thunk records.
- [ ] Добавить positive PE fixture с секцией и Import Directory; отдельно проверить DLL names, function thunks, ordinal imports и malformed descriptors.
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

**Текущая тестовая база:** 12 JVM test files / 61 @Test methods. Существующие тесты покрывают отдельные parser/resolver functions на synthetic data; нет end-to-end GameScanner tests, Android instrumentation tests, real binary fixtures, fuzz/property tests или coverage reports. См. [test audit](TEST_AUDIT_2026-11-19.md).

- [ ] Добавить scanner integration tests для inventory, candidate selection, ScanCoverage, report JSON/Markdown и partial failures.
- [ ] Добавить tests для scan locking, job-scoped cancellation, Activity lifecycle и partial reports.
- [ ] Добавить tests для export null stream/write/close failures.
- [ ] Добавить настоящие legal/redistributable PE/CLR/Unity fixtures и golden reports.
- [ ] Android instrumentation tests для SAF permissions, provider stream behavior, Activity recreation и export.
- [ ] Positive PE Import Directory tests и расширенная Unity version/layout matrix.
- [ ] Property/fuzz tests и regression corpus для parser boundaries.
- [ ] JUnit XML и code coverage report; установить порог только после baseline измерения.
- [ ] Quick/Standard/Deep scan profiles.
- [x] Базовый progress callback и индикатор пути/счётчика во время inventory (обновление UI каждые 128 записей).
- [x] Кнопка запроса отмены анализа.
- [ ] Расширить progress до стадий и отдельных analyzers; текущая индикация не показывает прогресс PE/CLR/Unity parsing.
- [ ] Добавить корректное job locking и job-scoped cancellation; это не обеспечивается текущим глобальным флагом.
- [ ] Поиск, фильтры и навигация по findings/graph.
- [ ] Переход к evidence из итоговых документов.
- [ ] Тесты на реальные легально распространяемые Unity Mono/IL2CPP fixtures.
- [ ] Golden reports, property/fuzz tests и regression suite.
- [ ] Android SAF tests: permission loss, non-seekable provider, large tree, Activity recreation, low memory.
- [ ] Публикация support matrix и точных ограничений.

**Критерий завершения:** заявленные форматы подтверждены реальными fixtures, CI стабильно зелёный, а долгий scan остаётся управляемым.

## 4. Что делать первым — ближайшие маленькие задачи

Не начинать контейнеры и новые декодеры, пока не готова минимальная основа. Ближайшую серию нужно выполнить в следующем порядке:

1. **Регрессионные тесты текущих дефектов:** повторный запуск, отмена и Activity destruction; null output stream и ошибки экспорта. Сначала зафиксировать ожидаемое поведение.
2. **ScanCoordinator:** изолировать уже существующие progress/cancel механизмы на уровне job, убрать повторный запуск, не удерживать уничтоженную Activity и сохранять partial state.
3. **Safe export:** исправить null stream false-success и покрыть все failure paths тестами.
4. **Data model:** расширить существующие ScanEntry/ScanCoverage и parser outputs общими Finding/Evidence/GraphNode/GraphEdge. Не переписывать уже работающие ScanCoverage и parser data classes.
5. **FileAccess:** добавить read-only abstraction поверх текущего readPrefix и протестировать stream/positional-read-capability/non-seekable/EOF/cancel.
6. **Scanner integration tests:** fake file tree → inventory → parsers → coverage → JSON/Markdown; сохранить текущий JSON files[] и добавить per-file ID/status.
7. **ReportWriter:** заменить сборку существующих Markdown/JSON строк в памяти на потоковую запись, сохранить schemaVersion=23, добавить JSON Schema/manifest.
8. **ZIP vertical slice:** открыть один archive entry виртуально и передать его существующему Unity header/SerializedFile parser.
9. **UnityFS vertical slice:** прочитать block table, один entry и SerializedFile внутри него.
10. Только после успешных вертикальных срезов расширять PE/CLR/IL и остальные Unity decoders.

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
