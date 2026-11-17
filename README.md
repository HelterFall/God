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
- Список игровых сборок и UnityEngine modules.
- Экспорт Markdown и JSON.
- Отображение прогресса при обходе папок.
- Возможность остановить длительный анализ; незавершённый отчёт не экспортируется.

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
- Это анализатор, не runtime и не декомпилятор.
