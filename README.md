# God — Unity Game Folder Analyzer

Android-приложение для анализа Windows Unity-игр непосредственно на устройстве.

## Игра не копируется
Пользователь выбирает папку через Android Storage Access Framework (SAF). Приложение анализирует её на месте. EXE/DLL не запускаются и не изменяются.

## Возможности MVP 0.2
- Дерево папок и файлов.
- Поиск UnityPlayer.dll, Managed, MonoBleedingEdge, Assembly-CSharp.dll.
- Структурная классификация Mono / IL2CPP.
- Поиск версии Unity в globalgamemanagers.
- Разбор PE: x86, x86-64, ARM64.
- Определение CLR-флага для различения managed .NET assemblies и native PE.
- Чтение статической таблицы импортов PE и сводка DLL-зависимостей.
- Распознавание заголовков Unity SerializedFile (включая 64-битный формат v22+) и UnityFS bundles без распаковки данных.
- Чтение metadata SerializedFile v19–v26: версия Unity, target platform, флаг TypeTree, число типов, до 500 записей объектов (PathID, смещение, размер и ClassID) и точные счётчики объектов по ClassID по всей прочитанной таблице (включая GameObject, Transform, MonoBehaviour и MonoScript), а также числа и ограниченные выборки MonoScript-ссылок (fileID/pathID), внешних файлов (fileID/path) и первых 500 serialized type-записей (ClassID, scriptTypeIndex, scriptID), число SerializeReference-типов и до 200 их записей с className/namespace/assemblyName (если формат v21+). Связывает scriptTypeIndex с MonoScript fileID/pathID. Для локальных ссылок сохраняет до 1000 записей ClassID 115 и декодирует до 64 MonoScript-объектов (имя, executionOrder, className, namespace, assemblyName), если позиция не дальше 64 MiB от начала файла, а размер объекта не больше 64 KiB. Для внешних ссылок пытается сопоставить путь с файлом в выбранной папке по точному пути или уникальному имени; если целевой SerializedFile попал в ограниченный разбор и содержит нужный ClassID 115, декодирует внешний MonoScript теми же лимитами. Неоднозначные имена не угадываются. Для v26 считывается число shared TypeTree subtrees. Проверяет границы object data ranges перед принятием записей.
- Отчёт по версии формата, размерам, data offset и endianness; UnityFS пока определяется только по заголовку.
- Список игровых сборок и UnityEngine modules.
- Экспорт Markdown и JSON (JSON schemaVersion 12).
- Отображение прогресса при обходе папок.
- Возможность остановить длительный анализ; незавершённый отчёт не экспортируется.

## Структура исходного кода
- `MainActivity.kt` — Android UI, выбор папки через SAF и экспорт.
- `GameScanner.kt` — обход дерева игры, эвристики Unity/Mono/IL2CPP, связь MonoScript между файлами и формирование отчётов.
- `PeParser.kt` — независимый PE-парсер: архитектура, CLR-флаг и статические импорты.
- `UnityFileHeaderParser.kt` — ограниченный парсер заголовков SerializedFile и UnityFS.
- `UnityMetadataParser.kt` — ограниченный разбор metadata, таблиц объектов, MonoScript/external references, serialized type identities и TypeTree-узлов для SerializedFile v19–v26.
- `app/src/test/...` — JVM-тесты бинарных парсеров.

## Получение APK
GitHub Actions собирает debug APK при каждом push в main. Открой вкладку Actions, выбери последний успешный запуск Android APK и скачай artifact God-debug-apk. В ZIP находится app-debug.apk.

## Использование
1. Установить APK.
2. Выбрать корень распакованной игры.
3. Нажать «Анализировать».
4. Экспортировать Markdown или JSON.

## Ограничения
- SAF может не давать доступ к отдельным каталогам.
- Лимиты: 100 000 записей, глубина 40. Для первых 40 Unity-файлов-кандидатов читается до 8 MiB metadata prefix на файл; для остальных из 500 проверяемых кандидатов — до 256 байт.
- PE-анализ: первые 150 EXE/DLL, первые 1 MiB каждого файла.
- Таблица импортов не обнаруживает LoadLibrary, managed references или все P/Invoke calls.
- Версия Unity определяется эвристически.
- Полные TypeTree-графы и декодирование содержимого объектов ещё не реализованы; анализатор извлекает корневые типы и ограниченную выборку встроенных имён полей (common-string offsets пока не разрешаются), а также первые 500 объектов и MonoScript-ссылок на файл; внешние ссылки ограничены первыми 200 записями. object data ranges проверяются относительно data section. Внешние ссылки разрешаются только при однозначном совпадении пути/имени среди файлов папки и только если целевой SerializedFile попал в первые 40 файлов metadata-анализа; GUID и вложенные AssetBundle-пути пока не используются для поиска. Внешние MonoScript objects вне выборки не декодируются; managed DLL классы/namespace/assembly пока не сопоставляются. Локальное декодирование ограничено первыми 64 ссылками, размером объекта до 64 KiB и областью чтения до 64 MiB; отсутствие записи в выборке не доказывает отсутствие объекта. В v26 читается только число shared TypeTree subtrees, их payload пока не разбирается. Полный 4 MiB TypeTree теперь может помещаться в 8 MiB bounded metadata window; более крупные metadata-области по-прежнему могут быть частичными. UnityFS не распаковывается.
- Метаданные форматов старше v26 пока не разбираются, чтобы не трактовать неизвестные структуры как известные.
- Для форматов до v9 нужен отдельный позиционный read для endian marker.
- Это анализатор, не runtime и не декомпилятор.
