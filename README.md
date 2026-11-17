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
- Чтение metadata SerializedFile v19–v26: версия Unity, target platform, флаг TypeTree, число типов, до 500 записей объектов (PathID, смещение, размер и ClassID), а также числа и ограниченные выборки MonoScript-ссылок (fileID/pathID), внешних файлов (fileID/path) и первых 500 serialized type-записей (ClassID, scriptTypeIndex, scriptID). Проверяет границы object data ranges перед принятием записей.
- Отчёт по версии формата, размерам, data offset и endianness; UnityFS пока определяется только по заголовку.
- Список игровых сборок и UnityEngine modules.
- Экспорт Markdown и JSON.
- Отображение прогресса при обходе папок.
- Возможность остановить длительный анализ; незавершённый отчёт не экспортируется.

## Структура исходного кода
- `MainActivity.kt` — Android UI, выбор папки через SAF и экспорт.
- `GameScanner.kt` — обход дерева игры, эвристики Unity/Mono/IL2CPP и формирование отчётов.
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
- Лимиты: 100 000 записей, глубина 40.
- PE-анализ: первые 150 EXE/DLL, первые 1 MiB каждого файла.
- Таблица импортов не обнаруживает LoadLibrary, managed references или все P/Invoke calls.
- Версия Unity определяется эвристически.
- Полные TypeTree-графы и декодирование содержимого объектов ещё не реализованы; анализатор извлекает корневые типы и ограниченную выборку встроенных имён полей (common-string offsets пока не разрешаются), а также первые 500 объектов и MonoScript-ссылок на файл; внешние ссылки ограничены первыми 200 записями. object data ranges проверяются относительно data section. Внешние ссылки пока не разрешаются между файлами, а script references ещё не декодируют содержимое MonoScript и не сопоставляют пользовательские типы с классами/namespace/assembly из DLL. UnityFS не распаковывается.
- Метаданные форматов старше v26 пока не разбираются, чтобы не трактовать неизвестные структуры как известные.
- Для форматов до v9 нужен отдельный позиционный read для endian marker.
- Это анализатор, не runtime и не декомпилятор.
