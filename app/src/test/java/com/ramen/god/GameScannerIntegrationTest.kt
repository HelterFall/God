package com.ramen.god

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameScannerIntegrationTest {
    @Test
    fun scansFakeTreeThroughInventoryCandidateSelectionCoverageAndReports() {
        val root = MemoryScanNode(
            name = "FixtureGame",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    name = "Data",
                    isDirectory = true,
                    children = listOf(
                        MemoryScanNode("manifest.txt", false, "fixture".toByteArray())
                    )
                ),
                // Deliberately not a PE file: the scanner should report one
                // attempted and failed PE candidate rather than hide the failure.
                MemoryScanNode("Assembly-CSharp.dll", false, byteArrayOf(0, 1, 2, 3)),
                MemoryScanNode("UnityPlayer.dll", false, minimalNativePe()),
                MemoryScanNode("readme.txt", false, "hello".toByteArray())
            )
        )
        val progress = mutableListOf<Pair<Int, String>>()

        val scanner = GameScanner(MemoryScanFileAccess())
        val result = scanner.scan(
            root,
            shouldCancel = { false }
        ) { count, path ->
            progress += count to path
        }
        val repeatedResult = scanner.scan(root, shouldCancel = { false }) { _, _ -> }

        assertFalse(result.isPartial)
        val firstIds = JSONObject(result.json).getJSONArray("files").let { files ->
            (0 until files.length()).map { files.getJSONObject(it).getString("id") }
        }
        val repeatedIds = JSONObject(repeatedResult.json).getJSONArray("files").let { files ->
            (0 until files.length()).map { files.getJSONObject(it).getString("id") }
        }
        assertEquals(firstIds, repeatedIds)
        assertTrue(result.markdown.contains("# Unity Game Architecture Report"))
        assertTrue(result.markdown.contains("Indexed entries: 5"))
        assertEquals(listOf(1, 2, 3, 4, 5, 5), progress.map { it.first })
        assertEquals("readme.txt", progress[4].second)
        assertEquals("Инвентаризация завершена; анализирую PE-файлы", progress.last().second)

        val report = JSONObject(result.json)
        assertEquals(24, report.getInt("schemaVersion"))
        assertEquals(5, report.getInt("entryCount"))

        val files = report.getJSONArray("files")
        val paths = (0 until files.length()).map { files.getJSONObject(it).getString("path") }
        assertEquals(
            listOf("Data", "Data/manifest.txt", "Assembly-CSharp.dll", "UnityPlayer.dll", "readme.txt"),
            paths
        )
        val dataRecord = (0 until files.length())
            .map { files.getJSONObject(it) }
            .first { it.getString("path") == "Data" }
        val manifestRecord = (0 until files.length())
            .map { files.getJSONObject(it) }
            .first { it.getString("path") == "Data/manifest.txt" }
        assertTrue(dataRecord.getString("id").startsWith("file:"))
        assertEquals("relative-path", dataRecord.getString("identitySource"))
        assertTrue(dataRecord.isNull("parentId"))
        assertEquals("enumerated", dataRecord.getString("inventoryStatus"))
        assertEquals(dataRecord.getString("id"), manifestRecord.getString("parentId"))
        assertEquals("discovered", manifestRecord.getString("inventoryStatus"))

        val fileIdsByPath = (0 until files.length()).associate { index ->
            val file = files.getJSONObject(index)
            file.getString("path") to file.getString("id")
        }
        val analyzerRuns = report.getJSONArray("analyzerRuns")
        val peRuns = (0 until analyzerRuns.length())
            .map { analyzerRuns.getJSONObject(it) }
            .filter { it.getString("analyzerId") == "pe" }
        assertEquals(2, peRuns.size)
        val failedPeRun = peRuns.first { it.getString("inputFileId") == fileIdsByPath.getValue("Assembly-CSharp.dll") }
        val successfulPeRun = peRuns.first { it.getString("inputFileId") == fileIdsByPath.getValue("UnityPlayer.dll") }
        assertEquals("failed", failedPeRun.getString("status"))
        assertEquals("analyzed", successfulPeRun.getString("status"))
        assertTrue(failedPeRun.getString("id").startsWith("run:"))
        assertTrue(!report.getString("scanId").isBlank())
        assertTrue(report.getString("scanId") != JSONObject(repeatedResult.json).getString("scanId"))

        val coverage = report.getJSONObject("scanCoverage")
        val inventory = coverage.getJSONObject("inventory")
        assertEquals("completed", inventory.getString("status"))
        assertEquals(5, inventory.getInt("entriesDiscovered"))
        assertEquals(4, inventory.getInt("filesDiscovered"))
        assertEquals(1, inventory.getInt("directoriesDiscovered"))
        assertEquals("partial", coverage.getString("status"))

        val analyzers = coverage.getJSONArray("analyzers")
        val pe = (0 until analyzers.length())
            .map { analyzers.getJSONObject(it) }
            .first { it.getString("id") == "pe" }
        assertEquals(2, pe.getInt("candidateCount"))
        assertEquals(2, pe.getInt("attemptedCount"))
        assertEquals(1, pe.getInt("parsedCount"))
        assertEquals(1, pe.getInt("failedCount"))
        assertEquals("partial", pe.getString("status"))
        val nativeBinaries = report.getJSONArray("nativePeBinaries")
        assertEquals("UnityPlayer.dll", nativeBinaries.getString(0))

        val nativeImports = report.getJSONArray("nativeImports")
        val importedDllNames = (0 until nativeImports.length())
            .map { nativeImports.getJSONObject(it).getString("dll") }
            .toSet()
        assertEquals(setOf("kernel32.dll", "user32.dll"), importedDllNames)
        assertTrue((0 until nativeImports.length()).all {
            nativeImports.getJSONObject(it).getInt("referencedByCount") == 1
        })
        assertTrue(result.markdown.contains("## Static native DLL imports"))
        assertTrue(result.markdown.contains("kernel32.dll"))
        assertTrue(result.markdown.contains("user32.dll"))

        val importedSymbols = report.getJSONArray("nativeImportSymbols")
        val symbolDescriptions = (0 until importedSymbols.length()).map { index ->
            val symbol = importedSymbols.getJSONObject(index)
            val name = if (symbol.isNull("name")) {
                "ordinal #${symbol.getInt("ordinal")}"
            } else {
                symbol.getString("name")
            }
            "${symbol.getString("dll").lowercase()}!$name"
        }.toSet()
        assertEquals(
            setOf("kernel32.dll!CreateFileW", "kernel32.dll!ordinal #7", "user32.dll!MessageBoxW"),
            symbolDescriptions
        )
        val hints = (0 until importedSymbols.length()).mapNotNull { index ->
            importedSymbols.getJSONObject(index).takeIf { !it.isNull("hint") }?.getInt("hint")
        }.toSet()
        assertEquals(setOf(5, 1), hints)
        assertTrue(result.markdown.contains("CreateFileW"))
        assertTrue(result.markdown.contains("ordinal #7"))
        assertTrue(result.markdown.contains("MessageBoxW"))
    }

    @Test
    fun cancellationInsideClrMetadataParserMarksActiveAnalyzerRunsPartial() {
        val bytes = managedPeWithMetadata()
        var readCompleted = false
        var checksAfterRead = 0
        val root = MemoryScanNode(
            name = "ClrCancellationFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    name = "Assembly-CSharp.dll",
                    isDirectory = false,
                    contents = bytes,
                    inputStreamFactory = {
                        readCompleted = false
                        object : InputStream() {
                            private val delegate = ByteArrayInputStream(bytes)
                            override fun read(): Int {
                                val value = delegate.read()
                                if (value < 0) readCompleted = true
                                return value
                            }
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                val value = delegate.read(buffer, offset, length)
                                if (value < 0) readCompleted = true
                                return value
                            }
                        }
                    }
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { readCompleted && ++checksAfterRead >= 3 },
            onProgress = { _, _ -> }
        )

        assertTrue(readCompleted)
        assertEquals(3, checksAfterRead)
        assertTrue(result.isPartial)
        val report = JSONObject(result.json)
        assertEquals("cancelled", report.getString("scanStatus"))
        val runs = report.getJSONArray("analyzerRuns")
        val peRun = (0 until runs.length()).map { runs.getJSONObject(it) }
            .single { it.getString("analyzerId") == "pe" }
        val clrRun = (0 until runs.length()).map { runs.getJSONObject(it) }
            .single { it.getString("analyzerId") == "clr_metadata" }
        assertEquals("partial", peRun.getString("status"))
        assertEquals("partial", clrRun.getString("status"))
        assertTrue(peRun.getString("reason").contains("Cancelled"))
        assertTrue(clrRun.getString("reason").contains("Cancelled"))
    }

    @Test
    fun cancellationInsideSerializedFileObjectTableMarksMetadataRunPartial() {
        val objects = (0 until 501).map { index ->
            (1000L + index) to minimalGameObjectPayload("Object-$index", active = true)
        }
        val bytes = unitySerializedFileWithGameObjectScene(objects)
        var streamReadCompleted = false
        var checksAfterRead = 0
        val root = MemoryScanNode(
            name = "UnityMetadataCancellationFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    name = "globalgamemanagers",
                    isDirectory = false,
                    contents = bytes,
                    inputStreamFactory = {
                        streamReadCompleted = false
                        object : InputStream() {
                            private val delegate = ByteArrayInputStream(bytes)
                            override fun read(): Int {
                                val value = delegate.read()
                                if (value < 0) streamReadCompleted = true
                                return value
                            }
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                val value = delegate.read(buffer, offset, length)
                                if (value < 0) streamReadCompleted = true
                                return value
                            }
                        }
                    }
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { streamReadCompleted && ++checksAfterRead >= 3 },
            onProgress = { _, _ -> }
        )

        assertTrue(streamReadCompleted)
        assertEquals(3, checksAfterRead)
        assertTrue(result.isPartial)
        val report = JSONObject(result.json)
        assertEquals("cancelled", report.getString("scanStatus"))
        val runs = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
        val headerRun = runs.single { it.getString("analyzerId") == "unity_file_headers" }
        val metadataRun = runs.single { it.getString("analyzerId") == "serialized_file_metadata" }

        assertEquals("analyzed", headerRun.getString("status"))
        assertEquals("partial", metadataRun.getString("status"))
        assertTrue(metadataRun.getString("reason").contains("Cancelled"))
    }

    @Test
    fun reportsSuccessfulClrMetadataThroughScannerCoverageAndBothReports() {
        val root = MemoryScanNode(
            name = "MonoFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "Managed",
                    true,
                    children = listOf(
                        MemoryScanNode("Assembly-CSharp.dll", false, managedPeWithMetadata())
                    )
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )

        assertFalse(result.isPartial)
        assertTrue(result.markdown.contains("## CLR managed assembly metadata"))
        assertTrue(result.markdown.contains("Managed/Assembly-CSharp.dll: Assembly-CSharp"))
        assertTrue(result.markdown.contains("Game.PlayerController"))

        val report = JSONObject(result.json)
        val managed = report.getJSONArray("managedPeAssemblies")
        assertEquals(1, managed.length())
        assertEquals("Managed/Assembly-CSharp.dll", managed.getString(0))

        val metadata = report.getJSONArray("managedAssemblyMetadata")
        assertEquals(1, metadata.length())
        val assembly = metadata.getJSONObject(0)
        assertEquals("Managed/Assembly-CSharp.dll", assembly.getString("path"))
        assertEquals("Assembly-CSharp", assembly.getString("name"))
        assertEquals("1.2.3.4", assembly.getString("version"))
        assertEquals("Sample.dll", assembly.getString("moduleName"))
        assertEquals("parsed", assembly.getString("status"))
        val inventoryFiles = report.getJSONArray("files")
        val assemblyFileId = (0 until inventoryFiles.length())
            .map { inventoryFiles.getJSONObject(it) }
            .first { it.getString("path") == "Managed/Assembly-CSharp.dll" }
            .getString("id")
        val clrRun = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
            .first { it.getString("analyzerId") == "clr_metadata" }
        assertEquals(assemblyFileId, clrRun.getString("inputFileId"))
        assertEquals("analyzed", clrRun.getString("status"))
        assertEquals(1, assembly.getInt("typeCount"))
        val types = assembly.getJSONArray("types")
        assertEquals(1, types.length())
        assertEquals("PlayerController", types.getJSONObject(0).getString("name"))
        assertEquals("Game", types.getJSONObject(0).getString("namespace"))

        val analyzers = report.getJSONObject("scanCoverage").getJSONArray("analyzers")
        val clr = (0 until analyzers.length())
            .map { analyzers.getJSONObject(it) }
            .first { it.getString("id") == "clr_metadata" }
        assertEquals(1, clr.getInt("candidateCount"))
        assertEquals(1, clr.getInt("attemptedCount"))
        assertEquals(1, clr.getInt("parsedCount"))
        assertEquals(0, clr.getInt("partialCount"))
        assertEquals(0, clr.getInt("failedCount"))
        assertEquals("completed", clr.getString("status"))
    }

    @Test
    fun reportsSuccessfulSerializedFileMetadataThroughScannerCoverageAndBothReports() {
        val root = MemoryScanNode(
            name = "UnityFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "Data",
                    true,
                    children = listOf(
                        MemoryScanNode("globalgamemanagers", false, minimalUnitySerializedFile())
                    )
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )

        assertFalse(result.isPartial)
        assertTrue(result.markdown.contains("## Unity serialized files and bundles"))
        assertTrue(result.markdown.contains("Data/globalgamemanagers: SerializedFile; format=22"))
        assertTrue(result.markdown.contains("## Unity metadata (leading fields)"))
        assertTrue(result.markdown.contains("Data/globalgamemanagers; Unity=2022.3.10f1"))

        val report = JSONObject(result.json)
        assertEquals("2022.3.10f1", report.getString("unityVersion"))
        val metadata = report.getJSONArray("unityMetadata")
        assertEquals(1, metadata.length())
        val serializedFile = metadata.getJSONObject(0)
        assertEquals("Data/globalgamemanagers", serializedFile.getString("path"))
        assertEquals("2022.3.10f1", serializedFile.getString("unityVersion"))
        assertEquals(5, serializedFile.getInt("targetPlatform"))
        assertFalse(serializedFile.getBoolean("typeTreeEnabled"))
        assertEquals(0, serializedFile.getInt("typeCount"))
        assertEquals(0, serializedFile.getInt("objectCount"))

        val inventoryFiles = report.getJSONArray("files")
        val sourceFileId = (0 until inventoryFiles.length())
            .map { inventoryFiles.getJSONObject(it) }
            .first { it.getString("path") == "Data/globalgamemanagers" }
            .getString("id")
        val analyzerRuns = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
        val headerRun = analyzerRuns.first { it.getString("analyzerId") == "unity_file_headers" }
        val metadataRun = analyzerRuns.first { it.getString("analyzerId") == "serialized_file_metadata" }
        assertEquals(sourceFileId, headerRun.getString("inputFileId"))
        assertEquals("analyzed", headerRun.getString("status"))
        assertEquals(sourceFileId, metadataRun.getString("inputFileId"))
        assertEquals("analyzed", metadataRun.getString("status"))
        val sceneRun = analyzerRuns.first { it.getString("analyzerId") == "unity_scene_graph" }
        assertEquals(sourceFileId, sceneRun.getString("inputFileId"))
        assertEquals("skipped", sceneRun.getString("status"))
        assertTrue(sceneRun.getString("reason").contains("TypeTree"))

        val analyzers = report.getJSONObject("scanCoverage").getJSONArray("analyzers")
        val unityHeaders = (0 until analyzers.length())
            .map { analyzers.getJSONObject(it) }
            .first { it.getString("id") == "unity_file_headers" }
        assertEquals(1, unityHeaders.getInt("candidateCount"))
        assertEquals(1, unityHeaders.getInt("attemptedCount"))
        assertEquals(1, unityHeaders.getInt("parsedCount"))
        assertEquals("completed", unityHeaders.getString("status"))

        val serializedMetadata = (0 until analyzers.length())
            .map { analyzers.getJSONObject(it) }
            .first { it.getString("id") == "serialized_file_metadata" }
        assertEquals(1, serializedMetadata.getInt("candidateCount"))
        assertEquals(1, serializedMetadata.getInt("attemptedCount"))
        assertEquals(1, serializedMetadata.getInt("parsedCount"))
        assertEquals(0, serializedMetadata.getInt("failedCount"))
        assertEquals(0, serializedMetadata.getInt("limitedCount"))
        assertEquals("completed", serializedMetadata.getString("status"))
    }

    @Test
    fun marksUnitySceneGraphPartialWhenDuplicatePathIdsDisableObjectSamples() {
        val root = MemoryScanNode(
            name = "DuplicateUnityPathIdScene",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithGameObjectScene(
                        listOf(
                            42L to minimalGameObjectPayload("First", active = true),
                            42L to minimalGameObjectPayload("Second", active = true)
                        )
                    )
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val runs = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
        val metadataRun = runs.single { it.getString("analyzerId") == "serialized_file_metadata" }
        val sceneRun = runs.single { it.getString("analyzerId") == "unity_scene_graph" }

        assertEquals("partial", metadataRun.getString("status"))
        assertTrue(metadataRun.getString("reason").contains("повторяющийся PathID"))
        assertEquals("partial", sceneRun.getString("status"))
        assertTrue(sceneRun.getString("reason").contains("повторяющийся PathID"))
    }

    @Test
    fun recordsSuccessfulUnitySceneGraphRunAgainstItsFileRecord() {
        val root = MemoryScanNode(
            name = "UnitySceneFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithLocalSceneObjects(
                        listOf(
                            localGameObject(42L, "Hero", true, listOf(0 to 43L)),
                            localTransform(43L, gameObjectPathId = 42L)
                        )
                    )
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val fileId = (0 until report.getJSONArray("files").length())
            .map { report.getJSONArray("files").getJSONObject(it) }
            .first { it.getString("path") == "globalgamemanagers" }
            .getString("id")
        val run = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
            .first { it.getString("analyzerId") == "unity_scene_graph" }

        assertEquals(fileId, run.getString("inputFileId"))
        assertEquals("analyzed", run.getString("status"))
        assertTrue(run.isNull("reason"))

        val scene = (0 until report.getJSONArray("unityMetadata").length())
            .map { report.getJSONArray("unityMetadata").getJSONObject(it) }
            .first { it.getString("path") == "globalgamemanagers" }
        val graph = scene.getJSONObject("sceneGraph")
        assertEquals(1, graph.getJSONArray("nodes").length())
        assertEquals("Hero", graph.getJSONArray("nodes").getJSONObject(0).getString("name"))
        assertTrue(graph.getJSONArray("nodes").getJSONObject(0).getBoolean("isActive"))
    }

    @Test
    fun marksSceneGraphPartialWhenRelevantObjectsExceedMetadataSampleLimit() {
        val objects = (0 until 501).map { index ->
            (10_000L + index) to minimalGameObjectPayload("Object-$index", active = true)
        }
        val root = MemoryScanNode(
            name = "SampleLimitedUnitySceneFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithGameObjectScene(objects)
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val run = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
            .single { it.getString("analyzerId") == "unity_scene_graph" }

        assertEquals("partial", run.getString("status"))
        assertTrue(run.getString("reason").contains("1 of 501 scene-object records were not decoded"))
    }

    @Test
    fun marksSceneGraphPartialWhenLocalPointersRemainUnresolved() {
        val root = MemoryScanNode(
            name = "UnresolvedUnitySceneFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithLocalSceneObjects(
                        listOf(
                            localGameObject(100L, "Broken", true, listOf(0 to 999L)),
                            localTransform(200L, gameObjectPathId = 100L)
                        )
                    )
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val run = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
            .single { it.getString("analyzerId") == "unity_scene_graph" }

        assertEquals("partial", run.getString("status"))
        assertTrue(run.getString("reason").contains("1 local/external scene pointers"))
        val scene = (0 until report.getJSONArray("unityMetadata").length())
            .map { report.getJSONArray("unityMetadata").getJSONObject(it) }
            .single { it.getString("path") == "globalgamemanagers" }
        assertEquals(1, scene.getJSONObject("sceneGraph").getInt("unresolvedReferenceCount"))
    }

    @Test
    fun publishesResolvedLocalScenePointersAsEvidenceBackedGraphRelations() {
        val root = MemoryScanNode(
            name = "LocalSceneEvidenceFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithLocalSceneObjects(
                        listOf(
                            localGameObject(100L, "Root", true, listOf(0 to 200L)),
                            localGameObject(101L, "Child", false, listOf(0 to 201L)),
                            localTransform(200L, gameObjectPathId = 100L, children = listOf(0 to 201L)),
                            localTransform(201L, gameObjectPathId = 101L, father = 0 to 200L)
                        )
                    )
                )
            )
        )
        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val fileId = (0 until report.getJSONArray("files").length())
            .map { report.getJSONArray("files").getJSONObject(it) }
            .single { it.getString("path") == "globalgamemanagers" }
            .getString("id")
        val run = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
            .single { it.getString("analyzerId") == "unity_scene_graph" }
        assertEquals(fileId, run.getString("inputFileId"))
        assertEquals("analyzed", run.getString("status"))

        val findings = (0 until report.getJSONArray("findings").length())
            .map { report.getJSONArray("findings").getJSONObject(it) }
            .filter { it.getString("category") == "unity.local-scene-pointer" }
        assertEquals(6, findings.size)
        assertTrue(findings.all {
            it.getString("status") == "decoded" &&
                it.getString("confidence") == "confirmed" &&
                it.getString("sourceFileId") == fileId &&
                it.getString("analyzerRunId") == run.getString("id")
        })

        val edges = (0 until report.getJSONArray("graphEdges").length())
            .map { report.getJSONArray("graphEdges").getJSONObject(it) }
            .filter { it.getString("analyzerRunId") == run.getString("id") }
        assertEquals(6, edges.size)
        assertEquals(
            setOf("belongs-to-gameobject", "has-parent-transform", "has-child-transform", "has-component"),
            edges.map { it.getString("relationType") }.toSet()
        )
        assertTrue(edges.all {
            !it.isNull("targetNodeId") &&
                it.isNull("targetHint") &&
                it.getString("status") == "decoded"
        })

        val evidence = (0 until report.getJSONArray("evidence").length())
            .map { report.getJSONArray("evidence").getJSONObject(it) }
            .filter { it.getString("analyzerRunId") == run.getString("id") }
        assertEquals(6, evidence.size)
        assertTrue(evidence.all {
            it.getString("method") == "resolved-pointer" &&
                it.getString("sourceFileId") == fileId &&
                it.getJSONObject("locator").getInt("unityFileId") == 0
        })
        assertTrue(evidence.any { it.getJSONObject("locator").getLong("pathId") == 200L })
        assertTrue(evidence.any { it.getJSONObject("locator").getLong("pathId") == 201L })
        assertTrue(result.markdown.contains("Evidence-backed local Unity scene relations"))
        assertTrue(result.markdown.contains("has-parent-transform"))
    }

    @Test
    fun preservesLocalSceneTargetOutsideSampleAsHintWithoutInventingNode() {
        val root = MemoryScanNode(
            name = "UnresolvedLocalSceneFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithLocalSceneObjects(
                        listOf(localGameObject(100L, "Root", true, listOf(0 to 999L)))
                    )
                )
            )
        )
        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val fileId = (0 until report.getJSONArray("files").length())
            .map { report.getJSONArray("files").getJSONObject(it) }
            .single { it.getString("path") == "globalgamemanagers" }
            .getString("id")
        val finding = (0 until report.getJSONArray("findings").length())
            .map { report.getJSONArray("findings").getJSONObject(it) }
            .single { it.getString("category") == "unity.local-scene-pointer" }
        assertEquals("partial", finding.getString("status"))
        assertEquals("weak", finding.getString("confidence"))
        assertEquals(fileId, finding.getString("sourceFileId"))
        assertTrue(finding.getString("reason").contains("outside the bounded object sample"))

        val nodes = (0 until report.getJSONArray("graphNodes").length())
            .map { report.getJSONArray("graphNodes").getJSONObject(it) }
        assertFalse(nodes.any {
            it.getString("fileRecordId") == fileId &&
                it.getJSONObject("locator").getLong("pathId") == 999L
        })
        val edge = (0 until report.getJSONArray("graphEdges").length())
            .map { report.getJSONArray("graphEdges").getJSONObject(it) }
            .single { finding.getString("id") in (0 until it.getJSONArray("findingIds").length())
                .map { index -> it.getJSONArray("findingIds").getString(index) } }
        assertTrue(edge.isNull("targetNodeId"))
        assertTrue(edge.getString("targetHint").contains("fileID=0"))
        assertTrue(edge.getString("targetHint").contains("PathID=999"))
        assertEquals("has-component", edge.getString("relationType"))
    }

    @Test
    fun preservesMetadataOnlyLocalSceneTargetAsRealGraphNode() {
        val root = MemoryScanNode(
            name = "MetadataOnlyLocalSceneFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithLocalSceneObjects(
                        listOf(
                            localGameObject(100L, "Root", true, listOf(0 to 200L)),
                            LocalSceneObject(200L, 4, byteArrayOf(1))
                        )
                    )
                )
            )
        )
        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val fileId = (0 until report.getJSONArray("files").length())
            .map { report.getJSONArray("files").getJSONObject(it) }
            .single { it.getString("path") == "globalgamemanagers" }
            .getString("id")
        val finding = (0 until report.getJSONArray("findings").length())
            .map { report.getJSONArray("findings").getJSONObject(it) }
            .single { it.getString("category") == "unity.local-scene-pointer" }
        assertEquals("metadata-only", finding.getString("status"))
        assertEquals("strong", finding.getString("confidence"))

        val targetNode = (0 until report.getJSONArray("graphNodes").length())
            .map { report.getJSONArray("graphNodes").getJSONObject(it) }
            .single {
                it.getString("fileRecordId") == fileId &&
                    it.getJSONObject("locator").getLong("pathId") == 200L
            }
        assertEquals("unity-transform", targetNode.getString("nodeType"))
        val edge = (0 until report.getJSONArray("graphEdges").length())
            .map { report.getJSONArray("graphEdges").getJSONObject(it) }
            .single { it.getString("relationType") == "has-component" }
        assertEquals(targetNode.getString("id"), edge.getString("targetNodeId"))
        assertTrue(edge.isNull("targetHint"))
    }

    @Test
    fun mergesLocalAndExternalScenePointersIntoOneSharedGameObjectNode() {
        val targetPath = "external-scene.assets"
        val externalPath = "archive:/CAB-123/external-scene.assets"
        val root = MemoryScanNode(
            name = "MixedLocalExternalSceneFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithLocalSceneObjects(
                        listOf(
                            localGameObject(
                                100L,
                                "Root",
                                true,
                                components = listOf(0 to 200L, 1 to 10L)
                            ),
                            localTransform(200L, gameObjectPathId = 100L)
                        ),
                        externalPaths = listOf(externalPath)
                    )
                ),
                MemoryScanNode(
                    targetPath,
                    false,
                    unitySerializedFileWithExternalTransformPointers(
                        externalPath = null,
                        pathId = 10L
                    )
                )
            )
        )
        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val fileIds = (0 until report.getJSONArray("files").length()).associate { index ->
            val file = report.getJSONArray("files").getJSONObject(index)
            file.getString("path") to file.getString("id")
        }
        val ownerId = fileIds.getValue("globalgamemanagers")
        val externalId = fileIds.getValue(targetPath)
        val ownerMetadata = (0 until report.getJSONArray("unityMetadata").length())
            .map { report.getJSONArray("unityMetadata").getJSONObject(it) }
            .single { it.getString("path") == "globalgamemanagers" }
        assertEquals(1, ownerMetadata.getJSONArray("externalReferences").length())
        val externalComponentLinks = (0 until ownerMetadata.getJSONArray("externalPointerResolutions").length())
            .map { ownerMetadata.getJSONArray("externalPointerResolutions").getJSONObject(it) }
            .filter { it.getString("field") == "components" }
        assertEquals(1, externalComponentLinks.size)
        assertEquals(100L, externalComponentLinks.single().getLong("sourcePathId"))
        assertEquals(1, externalComponentLinks.single().getInt("fileId"))
        assertEquals(10L, externalComponentLinks.single().getLong("pathId"))
        val rootNodes = (0 until report.getJSONArray("graphNodes").length())
            .map { report.getJSONArray("graphNodes").getJSONObject(it) }
            .filter {
                it.getString("nodeType") == "unity-gameobject" &&
                    it.getString("fileRecordId") == ownerId &&
                    it.getJSONObject("locator").getLong("pathId") == 100L
            }
        assertEquals(1, rootNodes.size)
        val rootNode = rootNodes.single()
        val externalFindings = (0 until report.getJSONArray("findings").length())
            .map { report.getJSONArray("findings").getJSONObject(it) }
            .filter { it.getString("category") == "unity.external-pointer" }
        assertEquals(1, externalFindings.size)
        assertEquals(ownerId, externalFindings.single().getString("sourceFileId"))
        assertEquals(100L, externalComponentLinks.single().getLong("sourcePathId"))
        val externalSourceNodeId = externalFindings.single()
            .getJSONArray("graphNodeIds").getString(0)
        val externalSourceNode = (0 until report.getJSONArray("graphNodes").length())
            .map { report.getJSONArray("graphNodes").getJSONObject(it) }
            .single { it.getString("id") == externalSourceNodeId }
        assertEquals(
            "expected shared node; external node type=" + externalSourceNode.getString("nodeType") +
                ", file=" + externalSourceNode.getString("fileRecordId") +
                ", locator=" + externalSourceNode.optJSONObject("locator"),
            rootNode.getString("id"),
            externalSourceNodeId
        )
        // The shared GameObject is both source and target of the two local
        // PPtrs, and source of the external component pointer.
        assertEquals(3, rootNode.getJSONArray("findingIds").length())
        assertEquals(3, rootNode.getJSONArray("evidenceIds").length())

        val edges = (0 until report.getJSONArray("graphEdges").length())
            .map { report.getJSONArray("graphEdges").getJSONObject(it) }
            .filter {
                it.getString("relationType") == "has-component" &&
                    it.getString("sourceNodeId") == rootNode.getString("id")
            }
        assertEquals(2, edges.size)
        assertTrue(edges.any {
            it.getString("analyzerRunId") == (0 until report.getJSONArray("analyzerRuns").length())
                .map { index -> report.getJSONArray("analyzerRuns").getJSONObject(index) }
                .single { run -> run.getString("analyzerId") == "unity_scene_graph" &&
                    run.getString("inputFileId") == ownerId }
                .getString("id") &&
                it.getString("targetNodeId") != rootNode.getString("id")
        })
        assertTrue(edges.any {
            it.getString("targetNodeId") != null &&
                it.getString("targetNodeId") != rootNode.getString("id") &&
                (0 until report.getJSONArray("graphNodes").length()).any { index ->
                    val node = report.getJSONArray("graphNodes").getJSONObject(index)
                    node.getString("id") == it.getString("targetNodeId") &&
                        node.getString("fileRecordId") == externalId
                }
        })
    }

    @Test
    fun recordsPartialUnitySceneGraphRunWhenSomeObjectsCannotBeDecoded() {
        val root = MemoryScanNode(
            name = "PartialUnitySceneFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithGameObjectScene(
                        listOf(
                            42L to minimalGameObjectPayload("Hero", active = true),
                            43L to byteArrayOf(1)
                        )
                    )
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val run = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
            .first { it.getString("analyzerId") == "unity_scene_graph" }

        assertEquals("partial", run.getString("status"))
        assertTrue(run.getString("reason").contains("Декодировано 1 scene objects"))
        val scene = (0 until report.getJSONArray("unityMetadata").length())
            .map { report.getJSONArray("unityMetadata").getJSONObject(it) }
            .first { it.getString("path") == "globalgamemanagers" }
        assertEquals(1, scene.getJSONObject("sceneGraph").getJSONArray("nodes").length())
    }

    @Test
    fun recordsFailedUnitySceneGraphRunWhenProviderCannotOpenPayloadStream() {
        val root = MemoryScanNode(
            name = "FailedUnitySceneFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithGameObjectScene(
                        listOf(42L to minimalGameObjectPayload("Hero", active = true))
                    )
                )
            )
        )
        val openCounts = mutableMapOf<String?, Int>()
        val fileAccess = ScanFileAccess { node ->
            val file = node as MemoryScanNode
            val count = (openCounts[file.name] ?: 0) + 1
            openCounts[file.name] = count
            if (file.name == "globalgamemanagers" && count == 2) {
                null
            } else if (file.isDirectory) {
                null
            } else {
                ByteArrayInputStream(file.contents)
            }
        }

        val result = GameScanner(fileAccess).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val fileId = (0 until report.getJSONArray("files").length())
            .map { report.getJSONArray("files").getJSONObject(it) }
            .first { it.getString("path") == "globalgamemanagers" }
            .getString("id")
        val run = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
            .first { it.getString("analyzerId") == "unity_scene_graph" }

        assertEquals(fileId, run.getString("inputFileId"))
        assertEquals("failed", run.getString("status"))
        assertTrue(run.getString("reason").contains("Не удалось открыть файл"))
    }

    @Test
    fun reportsUnsupportedSerializedFileMetadataVersionPerFile() {
        val root = MemoryScanNode(
            name = "UnsupportedUnityFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode("globalgamemanagers", false, minimalUnitySerializedFile(formatVersion = 18))
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val files = report.getJSONArray("files")
        val fileId = (0 until files.length())
            .map { files.getJSONObject(it) }
            .first { it.getString("path") == "globalgamemanagers" }
            .getString("id")
        val runs = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
        val headerRun = runs.first { it.getString("analyzerId") == "unity_file_headers" }
        val metadataRun = runs.first { it.getString("analyzerId") == "serialized_file_metadata" }

        assertEquals(fileId, headerRun.getString("inputFileId"))
        assertEquals("analyzed", headerRun.getString("status"))
        assertEquals(fileId, metadataRun.getString("inputFileId"))
        assertEquals("unsupported", metadataRun.getString("status"))
        assertTrue(metadataRun.getString("reason").contains("19"))
        assertTrue(metadataRun.getString("reason").contains("26"))
    }

    @Test
    fun reportsPartialSerializedFileMetadataWhenTheMetadataTableIsTruncated() {
        val root = MemoryScanNode(
            name = "PartialUnityMetadataFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode("globalgamemanagers", false, unitySerializedFileWithTruncatedMetadata())
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val metadataRun = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
            .first { it.getString("analyzerId") == "serialized_file_metadata" }

        assertEquals("partial", metadataRun.getString("status"))
        assertTrue(metadataRun.getString("reason").contains("MonoScript"))
    }

    @Test
    fun recordsSerializedMetadataAsSkippedWhenDeepReadWindowIsReached() {
        val root = MemoryScanNode(
            name = "UnityMetadataWindowFixture",
            isDirectory = true,
            children = (0 until 41).map { index ->
                MemoryScanNode(
                    "asset-%03d.assets".format(index),
                    false,
                    minimalUnitySerializedFile()
                )
            }
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val runs = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
        val headerRuns = runs.filter { it.getString("analyzerId") == "unity_file_headers" }
        val metadataRuns = runs.filter { it.getString("analyzerId") == "serialized_file_metadata" }

        assertEquals(41, headerRuns.size)
        assertTrue(headerRuns.all { it.getString("status") == "analyzed" })
        assertEquals(41, metadataRuns.size)
        assertEquals(40, metadataRuns.count { it.getString("status") == "analyzed" })
        assertEquals(1, metadataRuns.count { it.getString("status") == "skipped" })
        val limitedRun = metadataRuns.single { it.getString("status") == "skipped" }
        assertEquals(
            "asset-040.assets",
            (0 until report.getJSONArray("files").length())
                .map { report.getJSONArray("files").getJSONObject(it) }
                .first { it.getString("id") == limitedRun.getString("inputFileId") }
                .getString("path")
        )
        assertTrue(limitedRun.getString("reason").contains("first 40 candidates"))
    }

    @Test
    fun recordsUnityHeaderCandidateLimitAndUninspectedMetadataAsSkipped() {
        val root = MemoryScanNode(
            name = "UnityHeaderLimitFixture",
            isDirectory = true,
            children = (0 until 501).map { index ->
                MemoryScanNode("candidate-%03d.assets".format(index), false, byteArrayOf(1, 2, 3))
            }
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val runs = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
        val headerRuns = runs.filter { it.getString("analyzerId") == "unity_file_headers" }
        val metadataRuns = runs.filter { it.getString("analyzerId") == "serialized_file_metadata" }

        assertEquals(501, headerRuns.size)
        assertEquals(500, headerRuns.count { it.getString("status") == "failed" })
        assertEquals(1, headerRuns.count { it.getString("status") == "skipped" })
        assertEquals(501, metadataRuns.size)
        assertEquals(501, metadataRuns.count { it.getString("status") == "skipped" })
        val limitedHeader = headerRuns.single { it.getString("status") == "skipped" }
        val limitedMetadata = metadataRuns.single {
            it.getString("reason").contains("candidate limit reached")
        }
        assertEquals(
            "candidate-500.assets",
            (0 until report.getJSONArray("files").length())
                .map { report.getJSONArray("files").getJSONObject(it) }
                .first { it.getString("id") == limitedHeader.getString("inputFileId") }
                .getString("path")
        )
        assertEquals(limitedHeader.getString("inputFileId"), limitedMetadata.getString("inputFileId"))
    }

    @Test
    fun preservesQueuedUnityHeaderRunsWhenCancellationStartsUnityStage() {
        var cancelAfterInventory = false
        val root = MemoryScanNode(
            name = "UnityCancellationFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode("globalgamemanagers", false, minimalUnitySerializedFile())
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { cancelAfterInventory },
            onProgress = { _, stage ->
                if (stage == "Инвентаризация завершена; анализирую PE-файлы") {
                    cancelAfterInventory = true
                }
            }
        )

        assertTrue(result.isPartial)
        val report = JSONObject(result.json)
        val files = report.getJSONArray("files")
        val fileId = files.getJSONObject(0).getString("id")
        val runs = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
        assertEquals(1, runs.size)
        assertEquals("unity_file_headers", runs.single().getString("analyzerId"))
        assertEquals(fileId, runs.single().getString("inputFileId"))
        assertEquals("queued", runs.single().getString("status"))
    }

    @Test
    fun cancelsInsideChunkedUnityPrefixReadAndMarksActiveHeaderRunPartial() {
        var prefixStreamOpened = false
        var checksAfterOpen = 0
        val bytes = ByteArray(64 * 1024)
        val root = MemoryScanNode(
            name = "PrefixReadCancellationFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    name = "globalgamemanagers",
                    isDirectory = false,
                    contents = bytes,
                    inputStreamFactory = {
                        prefixStreamOpened = true
                        object : InputStream() {
                            private val delegate = ByteArrayInputStream(bytes)
                            override fun read(): Int = delegate.read()
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                                delegate.read(buffer, offset, minOf(length, 1024))
                        }
                    }
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = {
                prefixStreamOpened && ++checksAfterOpen >= 2
            },
            onProgress = { _, _ -> }
        )

        assertTrue(prefixStreamOpened)
        assertEquals(2, checksAfterOpen)
        assertTrue(result.isPartial)
        val report = JSONObject(result.json)
        assertEquals("cancelled", report.getString("scanStatus"))
        val runs = report.getJSONArray("analyzerRuns")
        assertEquals(1, runs.length())
        assertEquals("unity_file_headers", runs.getJSONObject(0).getString("analyzerId"))
        assertEquals("partial", runs.getJSONObject(0).getString("status"))
        assertTrue(runs.getJSONObject(0).getString("reason").contains("Cancelled"))
    }

    @Test
    fun doesNotGuessAmbiguousExternalMonoScriptFileFromDuplicateBasenames() {
        val externalPath = "archive:/CAB-123/sharedassets0.assets"
        val root = MemoryScanNode(
            name = "ExternalReferenceFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "Data",
                    true,
                    children = listOf(
                        MemoryScanNode(
                            "globalgamemanagers",
                            false,
                            unitySerializedFileWithExternalMonoScriptReference(externalPath)
                        ),
                        MemoryScanNode(
                            "Scenes",
                            true,
                            children = listOf(
                                MemoryScanNode("sharedassets0.assets", false, minimalUnitySerializedFile())
                            )
                        ),
                        MemoryScanNode(
                            "Resources",
                            true,
                            children = listOf(
                                MemoryScanNode("sharedassets0.assets", false, minimalUnitySerializedFile())
                            )
                        )
                    )
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )

        assertFalse(result.isPartial)
        assertTrue(result.markdown.contains("external-file-name-ambiguous"))
        assertTrue(result.markdown.contains(externalPath))

        val report = JSONObject(result.json)
        assertEquals("completed", report.getJSONObject("scanCoverage").getString("status"))
        val metadata = report.getJSONArray("unityMetadata")
        val owner = (0 until metadata.length())
            .map { metadata.getJSONObject(it) }
            .first { it.getString("path") == "Data/globalgamemanagers" }
        val references = owner.getJSONArray("externalReferences")
        assertEquals(1, references.length())
        assertEquals(1, references.getJSONObject(0).getInt("fileId"))
        assertEquals(externalPath, references.getJSONObject(0).getString("path"))

        val scriptBindings = owner.getJSONArray("scriptTypeBindings")
        assertEquals(1, scriptBindings.length())
        val binding = scriptBindings.getJSONObject(0)
        assertEquals(114, binding.getInt("classId"))
        assertEquals(0, binding.getInt("scriptTypeIndex"))
        assertEquals(1, binding.getInt("fileId"))
        assertEquals(77L, binding.getLong("pathId"))
        assertEquals(externalPath, binding.getString("externalPath"))
        assertEquals("external-file-name-ambiguous", binding.getString("resolution"))
        assertTrue(binding.isNull("matchedExternalFilePath"))

        val headers = report.getJSONObject("scanCoverage").getJSONArray("analyzers")
            .let { analyzers ->
                (0 until analyzers.length())
                    .map { analyzers.getJSONObject(it) }
                    .first { it.getString("id") == "unity_file_headers" }
            }
        assertEquals(3, headers.getInt("candidateCount"))
        assertEquals(3, headers.getInt("parsedCount"))
        assertEquals("completed", headers.getString("status"))

        val metadataCoverage = report.getJSONObject("scanCoverage").getJSONArray("analyzers")
            .let { analyzers ->
                (0 until analyzers.length())
                    .map { analyzers.getJSONObject(it) }
                    .first { it.getString("id") == "serialized_file_metadata" }
            }
        assertEquals(3, metadataCoverage.getInt("candidateCount"))
        assertEquals(3, metadataCoverage.getInt("parsedCount"))
        assertEquals("completed", metadataCoverage.getString("status"))

        val files = report.getJSONArray("files")
        val ownerFileId = (0 until files.length()).map { files.getJSONObject(it) }
            .first { it.getString("path") == "Data/globalgamemanagers" }.getString("id")
        val runs = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
        val externalRun = runs.single { it.getString("analyzerId") == "external_mono_script_payload" &&
            it.getString("inputFileId") == ownerFileId }
        assertEquals("partial", externalRun.getString("status"))
        assertTrue(externalRun.getString("reason").contains("unresolved"))
        assertTrue(runs.none { it.getString("analyzerId") == "unity_external_monoscript_payload" })
    }

    @Test
    fun keepsIncompleteExternalMonoScriptReferencesVisibleAsPartialAnalyzerOutcomes() {
        val root = MemoryScanNode(
            name = "IncompleteExternalMonoScriptFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "Data",
                    true,
                    children = listOf(
                        MemoryScanNode(
                            "globalgamemanagers",
                            false,
                            unitySerializedFileWithExternalMonoScriptReference("")
                        )
                    )
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val metadata = report.getJSONArray("unityMetadata")
        val owner = (0 until metadata.length())
            .map { metadata.getJSONObject(it) }
            .single { it.getString("path") == "Data/globalgamemanagers" }
        val binding = owner.getJSONArray("scriptTypeBindings").getJSONObject(0)

        assertEquals("external-file-reference-unavailable", binding.getString("resolution"))

        val files = report.getJSONArray("files")
        val ownerId = (0 until files.length())
            .map { files.getJSONObject(it) }
            .single { it.getString("path") == "Data/globalgamemanagers" }
            .getString("id")
        val runs = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
        val ownerRun = runs.single {
            it.getString("analyzerId") == "external_mono_script_payload" &&
                it.getString("inputFileId") == ownerId
        }

        assertEquals("partial", ownerRun.getString("status"))
        assertTrue(ownerRun.getString("reason").contains("unresolved"))
        assertTrue(runs.none {
            it.getString("analyzerId") == "unity_external_monoscript_payload"
        })
    }

    @Test
    fun resolvesExternalMonoScriptPayloadThroughScannerReportsAndCoverage() {
        val externalPath = "archive:/CAB-123/sharedassets0.assets"
        val targetPath = "Data/Resources/sharedassets0.assets"
        val root = MemoryScanNode(
            name = "ExternalMonoScriptFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "Data",
                    true,
                    children = listOf(
                        MemoryScanNode(
                            "globalgamemanagers",
                            false,
                            unitySerializedFileWithExternalMonoScriptReference(externalPath)
                        ),
                        MemoryScanNode(
                            "Resources",
                            true,
                            children = listOf(
                                MemoryScanNode(
                                    "sharedassets0.assets",
                                    false,
                                    unitySerializedFileWithMonoScriptObject(
                                        minimalMonoScriptPayload(
                                            name = "HeroController",
                                            className = "HeroController",
                                            namespace = "Game",
                                            assemblyName = "Assembly-CSharp",
                                            executionOrder = -12
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )

        assertFalse(result.isPartial)
        assertTrue(result.markdown.contains("external-monoscript-decoded"))
        assertTrue(result.markdown.contains("class=Game.HeroController"))
        assertTrue(result.markdown.contains("assembly=Assembly-CSharp"))
        assertTrue(result.markdown.contains("source=$targetPath"))

        val report = JSONObject(result.json)
        assertEquals("completed", report.getJSONObject("scanCoverage").getString("status"))
        val metadata = report.getJSONArray("unityMetadata")
        val owner = (0 until metadata.length())
            .map { metadata.getJSONObject(it) }
            .first { it.getString("path") == "Data/globalgamemanagers" }

        val binding = owner.getJSONArray("scriptTypeBindings").getJSONObject(0)
        assertEquals("external-monoscript-decoded", binding.getString("resolution"))
        assertEquals(targetPath, binding.getString("matchedExternalFilePath"))
        assertEquals("HeroController", binding.getString("monoScriptName"))
        assertEquals("HeroController", binding.getString("className"))
        assertEquals("Game", binding.getString("namespace"))
        assertEquals("Assembly-CSharp", binding.getString("assemblyName"))
        assertEquals(-12, binding.getInt("executionOrder"))

        val scripts = owner.getJSONArray("monoScripts")
        assertEquals(1, scripts.length())
        assertEquals(77L, scripts.getJSONObject(0).getLong("pathId"))
        assertEquals("HeroController", scripts.getJSONObject(0).getString("name"))
        assertEquals(targetPath, scripts.getJSONObject(0).getString("sourceFilePath"))

        val analyzers = report.getJSONObject("scanCoverage").getJSONArray("analyzers")
        val headerCoverage = (0 until analyzers.length())
            .map { analyzers.getJSONObject(it) }
            .first { it.getString("id") == "unity_file_headers" }
        assertEquals(2, headerCoverage.getInt("candidateCount"))
        assertEquals(2, headerCoverage.getInt("parsedCount"))
        assertEquals("completed", headerCoverage.getString("status"))

        val metadataCoverage = (0 until analyzers.length())
            .map { analyzers.getJSONObject(it) }
            .first { it.getString("id") == "serialized_file_metadata" }
        assertEquals(2, metadataCoverage.getInt("candidateCount"))
        assertEquals(2, metadataCoverage.getInt("parsedCount"))
        assertEquals("completed", metadataCoverage.getString("status"))

        val files = report.getJSONArray("files")
        val fileIds = (0 until files.length()).associate { index ->
            val file = files.getJSONObject(index)
            file.getString("path") to file.getString("id")
        }
        val runs = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
        val ownerRun = runs.single {
            it.getString("analyzerId") == "external_mono_script_payload" &&
                it.getString("inputFileId") == fileIds.getValue("Data/globalgamemanagers")
        }
        assertEquals("analyzed", ownerRun.getString("status"))
        val payloadRun = runs.single {
            it.getString("analyzerId") == "unity_external_monoscript_payload" &&
                it.getString("inputFileId") == fileIds.getValue(targetPath)
        }
        assertEquals("analyzed", payloadRun.getString("status"))
        val targetMetadata = (0 until metadata.length()).map { metadata.getJSONObject(it) }
            .first { it.getString("path") == targetPath }
        assertEquals("HeroController", targetMetadata.getJSONArray("monoScripts")
            .getJSONObject(0).getString("className"))
        val targetMetadataRuns = runs.filter {
            it.getString("analyzerId") == "serialized_file_metadata" &&
                it.getString("inputFileId") == fileIds.getValue(targetPath)
        }
        assertEquals("one deterministic metadata run per target FileRecord", 1, targetMetadataRuns.size)
    }

    @Test
    fun decodesOneExternalMonoScriptTargetRequestedByMultipleBindings() {
        val externalPath = "archive:/CAB-123/sharedassets0.assets"
        val targetPath = "Data/Resources/sharedassets0.assets"
        val root = MemoryScanNode(
            name = "RepeatedExternalMonoScriptRequestFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "Data",
                    true,
                    children = listOf(
                        MemoryScanNode(
                            "GameA",
                            true,
                            children = listOf(
                                MemoryScanNode(
                                    "globalgamemanagers",
                                    false,
                                    unitySerializedFileWithExternalMonoScriptReference(externalPath)
                                )
                            )
                        ),
                        MemoryScanNode(
                            "GameB",
                            true,
                            children = listOf(
                                MemoryScanNode(
                                    "globalgamemanagers",
                                    false,
                                    unitySerializedFileWithExternalMonoScriptReference(externalPath)
                                )
                            )
                        ),
                        MemoryScanNode(
                            "Resources",
                            true,
                            children = listOf(
                                MemoryScanNode(
                                    "sharedassets0.assets",
                                    false,
                                    unitySerializedFileWithMonoScriptObject(
                                        minimalMonoScriptPayload(
                                            name = "HeroController",
                                            className = "HeroController",
                                            namespace = "Game",
                                            assemblyName = "Assembly-CSharp",
                                            executionOrder = -12
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )

        assertFalse(result.isPartial)
        val report = JSONObject(result.json)
        val metadata = report.getJSONArray("unityMetadata")
        val owners = listOf("Data/GameA/globalgamemanagers", "Data/GameB/globalgamemanagers")
            .map { ownerPath ->
                (0 until metadata.length()).map { metadata.getJSONObject(it) }
                    .single { it.getString("path") == ownerPath }
            }
        for (owner in owners) {
            val binding = owner.getJSONArray("scriptTypeBindings").getJSONObject(0)
            assertEquals("external-monoscript-decoded", binding.getString("resolution"))
            assertEquals("HeroController", binding.getString("className"))
            assertEquals(targetPath, binding.getString("matchedExternalFilePath"))
            assertEquals(1, owner.getJSONArray("monoScripts").length())
        }

        val files = report.getJSONArray("files")
        val fileIds = (0 until files.length()).associate { index ->
            val file = files.getJSONObject(index)
            file.getString("path") to file.getString("id")
        }
        val payloadRuns = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
            .filter {
                it.getString("analyzerId") == "unity_external_monoscript_payload" &&
                    it.getString("inputFileId") == fileIds.getValue(targetPath)
            }
        assertEquals(1, payloadRuns.size)
        assertEquals("analyzed", payloadRuns.single().getString("status"))
    }

    @Test
    fun recordsFailedExternalMonoScriptPayloadAgainstTargetFileRecord() {
        val externalPath = "archive:/CAB-123/sharedassets0.assets"
        val root = MemoryScanNode(
            name = "ExternalPayloadFailureFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithExternalMonoScriptReference(externalPath)
                ),
                MemoryScanNode(
                    "sharedassets0.assets",
                    false,
                    unitySerializedFileWithMonoScriptObject(
                        minimalMonoScriptPayload(
                            name = "HeroController",
                            className = "HeroController",
                            namespace = "Game",
                            assemblyName = "Assembly-CSharp",
                            executionOrder = -12
                        )
                    )
                )
            )
        )
        val openCounts = mutableMapOf<String?, Int>()
        val fileAccess = ScanFileAccess { node ->
            val file = node as MemoryScanNode
            val count = (openCounts[file.name] ?: 0) + 1
            openCounts[file.name] = count
            if (file.name == "sharedassets0.assets" && count == 2) {
                null
            } else if (file.isDirectory) {
                null
            } else {
                ByteArrayInputStream(file.contents)
            }
        }

        val result = GameScanner(fileAccess).scan(root, shouldCancel = { false }) { _, _ -> }
        val report = JSONObject(result.json)
        val files = report.getJSONArray("files")
        val fileIds = (0 until files.length()).associate { index ->
            val file = files.getJSONObject(index)
            file.getString("path") to file.getString("id")
        }
        val runs = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
        val ownerRun = runs.single {
            it.getString("analyzerId") == "external_mono_script_payload" &&
                it.getString("inputFileId") == fileIds.getValue("globalgamemanagers")
        }
        assertEquals("partial", ownerRun.getString("status"))
        val targetRun = runs.single {
            it.getString("analyzerId") == "unity_external_monoscript_payload" &&
                it.getString("inputFileId") == fileIds.getValue("sharedassets0.assets")
        }
        assertEquals("failed", targetRun.getString("status"))
        assertTrue(targetRun.getString("reason").contains("no input stream"))
    }

    @Test
    fun tracksExternalPPtrsAndMonoBehaviourScriptBindingsPerOwnerFile() {
        val externalPath = "archive:/CAB-123/sharedassets0.assets"
        val targetPath = "Data/Resources/sharedassets0.assets"
        val root = MemoryScanNode(
            name = "ExternalPPtrFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "Data",
                    true,
                    children = listOf(
                        MemoryScanNode(
                            "globalgamemanagers",
                            false,
                            unitySerializedFileWithExternalMonoBehaviourScriptPointer(externalPath)
                        ),
                        MemoryScanNode(
                            "Resources",
                            true,
                            children = listOf(
                                MemoryScanNode(
                                    "sharedassets0.assets",
                                    false,
                                    unitySerializedFileWithMonoScriptObject(
                                        minimalMonoScriptPayload(
                                            name = "HeroController",
                                            className = "HeroController",
                                            namespace = "Game",
                                            assemblyName = "Assembly-CSharp",
                                            executionOrder = -12
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(root, shouldCancel = { false }) { _, _ -> }
        val report = JSONObject(result.json)
        val files = report.getJSONArray("files")
        val fileIds = (0 until files.length()).associate { index ->
            val file = files.getJSONObject(index)
            file.getString("path") to file.getString("id")
        }
        val ownerId = fileIds.getValue("Data/globalgamemanagers")
        val targetId = fileIds.getValue(targetPath)
        val runs = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }

        val pointerRun = runs.single {
            it.getString("analyzerId") == "unity_external_pointers" &&
                it.getString("inputFileId") == ownerId
        }
        assertEquals("analyzed", pointerRun.getString("status"))
        val bindingRun = runs.single {
            it.getString("analyzerId") == "unity_monobehaviour_script_bindings" &&
                it.getString("inputFileId") == ownerId
        }
        assertEquals("analyzed", bindingRun.getString("status"))
        val targetPayloadRun = runs.single {
            it.getString("analyzerId") == "unity_external_monoscript_payload" &&
                it.getString("inputFileId") == targetId
        }
        assertEquals("analyzed", targetPayloadRun.getString("status"))

        val metadata = report.getJSONArray("unityMetadata")
        val owner = (0 until metadata.length()).map { metadata.getJSONObject(it) }
            .first { it.getString("path") == "Data/globalgamemanagers" }
        val pointer = owner.getJSONArray("externalPointerResolutions").getJSONObject(0)
        assertEquals("resolved-object-record-not-decoded", pointer.getString("status"))
        assertEquals(targetPath, pointer.getString("targetFilePath"))
        val binding = owner.getJSONArray("monoBehaviourScriptBindings").getJSONObject(0)
        assertEquals("resolved-monoscript-class", binding.getString("status"))
        assertEquals("HeroController", binding.getString("className"))
        assertEquals("Game", binding.getString("namespace"))
        assertEquals(targetPath, binding.getString("targetFilePath"))

        val findings = report.getJSONArray("findings")
        assertEquals(1, findings.length())
        val finding = findings.getJSONObject(0)
        assertEquals("unity.monobehaviour-script-binding", finding.getString("category"))
        assertEquals("decoded", finding.getString("status"))
        assertEquals("confirmed", finding.getString("confidence"))
        assertEquals(ownerId, finding.getString("sourceFileId"))
        assertEquals(bindingRun.getString("id"), finding.getString("analyzerRunId"))
        assertEquals(1, finding.getJSONArray("evidenceIds").length())
        assertEquals(2, finding.getJSONArray("graphNodeIds").length())
        assertEquals(1, finding.getJSONArray("graphEdgeIds").length())

        val evidenceItems = report.getJSONArray("evidence")
        assertEquals(1, evidenceItems.length())
        val evidence = evidenceItems.getJSONObject(0)
        assertEquals(finding.getString("id"), evidence.getString("findingId"))
        assertEquals(bindingRun.getString("id"), evidence.getString("analyzerRunId"))
        assertEquals(ownerId, evidence.getString("sourceFileId"))
        assertEquals("resolved-pointer", evidence.getString("method"))
        assertEquals("confirmed", evidence.getString("confidence"))
        assertEquals(1, evidence.getJSONObject("locator").getInt("unityFileId"))
        assertEquals(77L, evidence.getJSONObject("locator").getLong("pathId"))

        val graphNodes = report.getJSONArray("graphNodes")
        assertEquals(2, graphNodes.length())
        val sourceNode = (0 until graphNodes.length()).map { graphNodes.getJSONObject(it) }
            .single { it.getString("nodeType") == "unity-monobehaviour" }
        val targetNode = (0 until graphNodes.length()).map { graphNodes.getJSONObject(it) }
            .single { it.getString("nodeType") == "unity-monoscript" }
        assertEquals(ownerId, sourceNode.getString("fileRecordId"))
        assertEquals(10L, sourceNode.getJSONObject("locator").getLong("pathId"))
        assertEquals(targetId, targetNode.getString("fileRecordId"))
        assertEquals(77L, targetNode.getJSONObject("locator").getLong("pathId"))

        val graphEdges = report.getJSONArray("graphEdges")
        assertEquals(1, graphEdges.length())
        val edge = graphEdges.getJSONObject(0)
        assertEquals("attaches-script", edge.getString("relationType"))
        assertEquals("decoded", edge.getString("status"))
        assertEquals("confirmed", edge.getString("confidence"))
        assertEquals(sourceNode.getString("id"), edge.getString("sourceNodeId"))
        assertEquals(targetNode.getString("id"), edge.getString("targetNodeId"))
        assertEquals(bindingRun.getString("id"), edge.getString("analyzerRunId"))

        val knownFileIds = fileIds.values.toSet()
        val knownRunIds = runs.map { it.getString("id") }.toSet()
        val findingIds = (0 until findings.length()).map { findings.getJSONObject(it).getString("id") }.toSet()
        val evidenceIds = (0 until evidenceItems.length()).map { evidenceItems.getJSONObject(it).getString("id") }.toSet()
        assertTrue(finding.getString("sourceFileId") in knownFileIds)
        assertTrue(finding.getString("analyzerRunId") in knownRunIds)
        assertTrue(evidence.getString("findingId") in findingIds)
        assertTrue(evidence.getString("sourceFileId") in knownFileIds)
        assertTrue(evidence.getString("analyzerRunId") in knownRunIds)
        assertTrue(sourceNode.getJSONArray("findingIds").getString(0) in findingIds)
        assertTrue(sourceNode.getJSONArray("evidenceIds").getString(0) in evidenceIds)
        assertTrue(edge.getJSONArray("findingIds").getString(0) in findingIds)
        assertTrue(edge.getJSONArray("evidenceIds").getString(0) in evidenceIds)
        assertTrue(result.markdown.contains("Evidence-backed Unity script bindings"))
        assertTrue(result.markdown.contains("HeroController"))
    }

    @Test
    fun publishesResolvedExternalComponentPPtrAsEvidenceAndGraphEdge() {
        val targetPath = "components.assets"
        val externalPath = "archive:/CAB-123/components.assets"
        val root = MemoryScanNode(
            name = "ExternalComponentPPtrFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithExternalGameObjectComponentPointer(externalPath)
                ),
                MemoryScanNode(
                    targetPath,
                    false,
                    unitySerializedFileWithExternalMonoBehaviourScriptPointer(
                        "archive:/CAB-123/missing-scripts.assets"
                    )
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val fileIds = (0 until report.getJSONArray("files").length()).associate { index ->
            val file = report.getJSONArray("files").getJSONObject(index)
            file.getString("path") to file.getString("id")
        }
        val ownerId = fileIds.getValue("globalgamemanagers")
        val targetId = fileIds.getValue(targetPath)
        val runs = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
        val pointerRun = runs.single {
            it.getString("analyzerId") == "unity_external_pointers" &&
                it.getString("inputFileId") == ownerId
        }
        assertEquals("analyzed", pointerRun.getString("status"))

        val owner = (0 until report.getJSONArray("unityMetadata").length())
            .map { report.getJSONArray("unityMetadata").getJSONObject(it) }
            .first { it.getString("path") == "globalgamemanagers" }
        val resolution = (0 until owner.getJSONArray("externalPointerResolutions").length())
            .map { owner.getJSONArray("externalPointerResolutions").getJSONObject(it) }
            .single { it.getString("field") == "components" }
        assertEquals("resolved-decoded-object", resolution.getString("status"))
        assertEquals(targetPath, resolution.getString("targetFilePath"))

        val findings = report.getJSONArray("findings")
        val finding = (0 until findings.length())
            .map { findings.getJSONObject(it) }
            .single { it.getString("category") == "unity.external-pointer" }
        assertEquals("decoded", finding.getString("status"))
        assertEquals("confirmed", finding.getString("confidence"))
        assertEquals(ownerId, finding.getString("sourceFileId"))
        assertEquals(pointerRun.getString("id"), finding.getString("analyzerRunId"))
        assertEquals(1, finding.getJSONArray("evidenceIds").length())
        assertEquals(2, finding.getJSONArray("graphNodeIds").length())
        assertEquals(1, finding.getJSONArray("graphEdgeIds").length())

        val evidence = (0 until report.getJSONArray("evidence").length())
            .map { report.getJSONArray("evidence").getJSONObject(it) }
            .single { it.getString("findingId") == finding.getString("id") }
        assertEquals(ownerId, evidence.getString("sourceFileId"))
        assertEquals(pointerRun.getString("id"), evidence.getString("analyzerRunId"))
        assertEquals("resolved-pointer", evidence.getString("method"))
        assertEquals(1, evidence.getJSONObject("locator").getInt("unityFileId"))
        assertEquals(10L, evidence.getJSONObject("locator").getLong("pathId"))

        val nodes = (0 until report.getJSONArray("graphNodes").length())
            .map { report.getJSONArray("graphNodes").getJSONObject(it) }
        val sourceNode = nodes.single { it.getString("nodeType") == "unity-gameobject" }
        val targetNode = nodes.single { it.getString("nodeType") == "unity-monobehaviour" }
        assertEquals(ownerId, sourceNode.getString("fileRecordId"))
        assertEquals(1L, sourceNode.getJSONObject("locator").getLong("pathId"))
        assertEquals(targetId, targetNode.getString("fileRecordId"))
        assertEquals(10L, targetNode.getJSONObject("locator").getLong("pathId"))

        val edge = (0 until report.getJSONArray("graphEdges").length())
            .map { report.getJSONArray("graphEdges").getJSONObject(it) }
            .single { it.getString("relationType") == "has-component" }
        assertEquals("decoded", edge.getString("status"))
        assertEquals("confirmed", edge.getString("confidence"))
        assertEquals(sourceNode.getString("id"), edge.getString("sourceNodeId"))
        assertEquals(targetNode.getString("id"), edge.getString("targetNodeId"))
        assertEquals(pointerRun.getString("id"), edge.getString("analyzerRunId"))
        assertTrue(result.markdown.contains("Evidence-backed external Unity PPtr relations"))
        assertTrue(result.markdown.contains("has-component"))
    }

    @Test
    fun publishesMetadataOnlyExternalComponentPPtrWithRealTargetNode() {
        val targetPath = "components.assets"
        val externalPath = "archive:/CAB-123/components.assets"
        val root = MemoryScanNode(
            name = "MetadataOnlyExternalComponentPPtrFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithExternalGameObjectComponentPointer(externalPath)
                ),
                MemoryScanNode(
                    targetPath,
                    false,
                    unitySerializedFileWithExternalMonoBehaviourScriptPointer(
                        "archive:/CAB-123/missing-scripts.assets",
                        payloadOverride = byteArrayOf(1)
                    )
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val fileIds = (0 until report.getJSONArray("files").length()).associate { index ->
            val file = report.getJSONArray("files").getJSONObject(index)
            file.getString("path") to file.getString("id")
        }
        val finding = (0 until report.getJSONArray("findings").length())
            .map { report.getJSONArray("findings").getJSONObject(it) }
            .single { it.getString("category") == "unity.external-pointer" }
        assertEquals("metadata-only", finding.getString("status"))
        assertEquals("strong", finding.getString("confidence"))
        assertEquals(fileIds.getValue("globalgamemanagers"), finding.getString("sourceFileId"))

        val targetNode = (0 until report.getJSONArray("graphNodes").length())
            .map { report.getJSONArray("graphNodes").getJSONObject(it) }
            .single {
                it.getString("fileRecordId") == fileIds.getValue(targetPath) &&
                    it.getJSONObject("locator").getLong("pathId") == 10L
            }
        assertEquals("unity-monobehaviour", targetNode.getString("nodeType"))
        val edge = (0 until report.getJSONArray("graphEdges").length())
            .map { report.getJSONArray("graphEdges").getJSONObject(it) }
            .single { it.getString("relationType") == "has-component" }
        assertEquals(targetNode.getString("id"), edge.getString("targetNodeId"))
        assertTrue(edge.isNull("targetHint"))
    }

    @Test
    fun preservesExternalComponentTargetHintOutsideBoundedObjectSample() {
        val targetPath = "components.assets"
        val externalPath = "archive:/CAB-123/components.assets"
        val root = MemoryScanNode(
            name = "UnresolvedExternalComponentPPtrFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithExternalGameObjectComponentPointer(
                        externalPath,
                        componentPathId = 99L
                    )
                ),
                MemoryScanNode(
                    targetPath,
                    false,
                    unitySerializedFileWithExternalMonoBehaviourScriptPointer(
                        "archive:/CAB-123/missing-scripts.assets"
                    )
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val fileIds = (0 until report.getJSONArray("files").length()).associate { index ->
            val file = report.getJSONArray("files").getJSONObject(index)
            file.getString("path") to file.getString("id")
        }
        val finding = (0 until report.getJSONArray("findings").length())
            .map { report.getJSONArray("findings").getJSONObject(it) }
            .single { it.getString("category") == "unity.external-pointer" }
        assertEquals("partial", finding.getString("status"))
        assertEquals("weak", finding.getString("confidence"))
        assertTrue(finding.getString("reason").contains("bounded object sample"))

        val nodes = (0 until report.getJSONArray("graphNodes").length())
            .map { report.getJSONArray("graphNodes").getJSONObject(it) }
        val sourceNode = nodes.single {
            it.getString("nodeType") == "unity-gameobject" &&
                it.getString("fileRecordId") == fileIds.getValue("globalgamemanagers")
        }
        assertFalse(nodes.any {
            it.getString("fileRecordId") == fileIds.getValue(targetPath) &&
                it.getJSONObject("locator").getLong("pathId") == 99L
        })
        val edge = (0 until report.getJSONArray("graphEdges").length())
            .map { report.getJSONArray("graphEdges").getJSONObject(it) }
            .single { it.getString("relationType") == "has-component" }
        assertEquals(sourceNode.getString("id"), edge.getString("sourceNodeId"))
        assertTrue(edge.isNull("targetNodeId"))
        assertTrue(edge.getString("targetHint").contains("fileID=1"))
        assertTrue(edge.getString("targetHint").contains("PathID=99"))
        assertTrue(edge.getString("targetHint").contains("pathID-not-in-object-sample"))
    }

    @Test
    fun publishesExternalTransformGameObjectPPtrAsResolvedGraphRelation() {
        val targetPath = "scene.assets"
        val externalPath = "archive:/CAB-123/scene.assets"
        val root = MemoryScanNode(
            name = "ExternalTransformGameObjectPPtrFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithExternalTransformGameObjectPointer(externalPath)
                ),
                MemoryScanNode(
                    targetPath,
                    false,
                    unitySerializedFileWithGameObjectScene(
                        listOf(1L to minimalGameObjectPayload("Root", active = true))
                    )
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val fileIds = (0 until report.getJSONArray("files").length()).associate { index ->
            val file = report.getJSONArray("files").getJSONObject(index)
            file.getString("path") to file.getString("id")
        }
        val ownerId = fileIds.getValue("globalgamemanagers")
        val targetId = fileIds.getValue(targetPath)
        val finding = (0 until report.getJSONArray("findings").length())
            .map { report.getJSONArray("findings").getJSONObject(it) }
            .single { it.getString("category") == "unity.external-pointer" }
        assertEquals("decoded", finding.getString("status"))
        assertEquals("confirmed", finding.getString("confidence"))
        assertEquals(ownerId, finding.getString("sourceFileId"))

        val targetNode = (0 until report.getJSONArray("graphNodes").length())
            .map { report.getJSONArray("graphNodes").getJSONObject(it) }
            .single {
                it.getString("nodeType") == "unity-gameobject" &&
                    it.getString("fileRecordId") == targetId &&
                    it.getJSONObject("locator").getLong("pathId") == 1L
            }
        assertEquals("Root", targetNode.getString("label"))
        val edge = (0 until report.getJSONArray("graphEdges").length())
            .map { report.getJSONArray("graphEdges").getJSONObject(it) }
            .single { it.getString("relationType") == "belongs-to-gameobject" }
        assertEquals(targetNode.getString("id"), edge.getString("targetNodeId"))
        assertTrue(edge.isNull("targetHint"))
        assertTrue(finding.getString("summary").contains("field=gameObject"))
    }

    @Test
    fun keepsDifferentExternalTransformFieldsAsSeparateRelationsAndMergesSharedNodes() {
        val targetPath = "transforms.assets"
        val externalPath = "archive:/CAB-123/transforms.assets"
        val root = MemoryScanNode(
            name = "ExternalTransformPPtrFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithExternalTransformPointers(externalPath)
                ),
                MemoryScanNode(
                    targetPath,
                    false,
                    unitySerializedFileWithExternalTransformPointers(externalPath = null, pathId = 10L)
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val fileIds = (0 until report.getJSONArray("files").length()).associate { index ->
            val file = report.getJSONArray("files").getJSONObject(index)
            file.getString("path") to file.getString("id")
        }
        val ownerId = fileIds.getValue("globalgamemanagers")
        val targetId = fileIds.getValue(targetPath)
        val findings = (0 until report.getJSONArray("findings").length())
            .map { report.getJSONArray("findings").getJSONObject(it) }
            .filter { it.getString("category") == "unity.external-pointer" }
        assertEquals(2, findings.size)
        assertTrue(findings.any { it.getString("summary").contains("field=father") })
        assertTrue(findings.any { it.getString("summary").contains("field=children") })

        val nodes = (0 until report.getJSONArray("graphNodes").length())
            .map { report.getJSONArray("graphNodes").getJSONObject(it) }
        val sourceNodes = nodes.filter {
            it.getString("fileRecordId") == ownerId &&
                it.getString("nodeType") == "unity-transform" &&
                it.getJSONObject("locator").getLong("pathId") == 2L
        }
        val targetNodes = nodes.filter {
            it.getString("fileRecordId") == targetId &&
                it.getString("nodeType") == "unity-transform" &&
                it.getJSONObject("locator").getLong("pathId") == 10L
        }
        assertEquals(1, sourceNodes.size)
        assertEquals(1, targetNodes.size)

        val edges = (0 until report.getJSONArray("graphEdges").length())
            .map { report.getJSONArray("graphEdges").getJSONObject(it) }
        val externalEdges = edges.filter { it.getString("relationType") in setOf(
            "has-parent-transform", "has-child-transform"
        ) }
        assertEquals(2, externalEdges.size)
        assertEquals(
            setOf("has-parent-transform", "has-child-transform"),
            externalEdges.map { it.getString("relationType") }.toSet()
        )
        assertTrue(externalEdges.all {
            it.getString("sourceNodeId") == sourceNodes.single().getString("id") &&
                it.getString("targetNodeId") == targetNodes.single().getString("id")
        })
    }

    @Test
    fun preservesMetadataOnlyMonoBehaviourScriptWhenTargetPayloadCannotBeRead() {
        val externalPath = "archive:/CAB-123/sharedassets0.assets"
        val targetPath = "sharedassets0.assets"
        val root = MemoryScanNode(
            name = "MetadataOnlyMonoBehaviourFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithExternalMonoBehaviourScriptPointer(externalPath)
                ),
                MemoryScanNode(
                    targetPath,
                    false,
                    unitySerializedFileWithMonoScriptObject(
                        minimalMonoScriptPayload(
                            name = "HeroController",
                            className = "HeroController",
                            namespace = "Game",
                            assemblyName = "Assembly-CSharp",
                            executionOrder = -12
                        )
                    )
                )
            )
        )
        val openCounts = mutableMapOf<String?, Int>()
        val fileAccess = ScanFileAccess { node ->
            val file = node as MemoryScanNode
            val count = (openCounts[file.name] ?: 0) + 1
            openCounts[file.name] = count
            if (file.name == targetPath && count == 2) {
                null
            } else if (file.isDirectory) {
                null
            } else {
                ByteArrayInputStream(file.contents)
            }
        }

        val result = GameScanner(fileAccess).scan(root, shouldCancel = { false }) { _, _ -> }
        val report = JSONObject(result.json)
        val files = report.getJSONArray("files")
        val fileIds = (0 until files.length()).associate { index ->
            val file = files.getJSONObject(index)
            file.getString("path") to file.getString("id")
        }
        val runs = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
        val ownerId = fileIds.getValue("globalgamemanagers")
        val targetId = fileIds.getValue(targetPath)
        val bindingRun = runs.single {
            it.getString("analyzerId") == "unity_monobehaviour_script_bindings" &&
                it.getString("inputFileId") == ownerId
        }
        val payloadRun = runs.single {
            it.getString("analyzerId") == "unity_external_monoscript_payload" &&
                it.getString("inputFileId") == targetId
        }
        assertEquals("failed", payloadRun.getString("status"))

        val metadata = report.getJSONArray("unityMetadata")
        val owner = (0 until metadata.length()).map { metadata.getJSONObject(it) }
            .single { it.getString("path") == "globalgamemanagers" }
        assertEquals(
            "monoscript-object-found-payload-unavailable",
            owner.getJSONArray("monoBehaviourScriptBindings").getJSONObject(0).getString("status")
        )

        val finding = report.getJSONArray("findings").getJSONObject(0)
        assertEquals("metadata-only", finding.getString("status"))
        assertEquals("strong", finding.getString("confidence"))
        assertEquals(ownerId, finding.getString("sourceFileId"))
        assertEquals(bindingRun.getString("id"), finding.getString("analyzerRunId"))

        val targetNode = (0 until report.getJSONArray("graphNodes").length())
            .map { report.getJSONArray("graphNodes").getJSONObject(it) }
            .single { it.getString("nodeType") == "unity-monoscript" }
        assertEquals(targetId, targetNode.getString("fileRecordId"))
        assertTrue(targetNode.getString("label").contains("payload unavailable"))

        val edge = report.getJSONArray("graphEdges").getJSONObject(0)
        assertEquals("metadata-only", edge.getString("status"))
        assertEquals(targetNode.getString("id"), edge.getString("targetNodeId"))
    }

    @Test
    fun keepsUnresolvedMonoBehaviourScriptAsHintWithoutInventingTargetNode() {
        val externalPath = "archive:/CAB-123/missing-script.assets"
        val root = MemoryScanNode(
            name = "UnresolvedMonoBehaviourFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithExternalMonoBehaviourScriptPointer(externalPath)
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(root, shouldCancel = { false }) { _, _ -> }
        val report = JSONObject(result.json)
        val files = report.getJSONArray("files")
        val ownerId = files.getJSONObject(0).getString("id")
        val runs = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
        val bindingRun = runs.single { it.getString("analyzerId") == "unity_monobehaviour_script_bindings" }

        val findings = report.getJSONArray("findings")
        assertEquals(1, findings.length())
        val finding = findings.getJSONObject(0)
        assertEquals("unresolved", finding.getString("status"))
        assertEquals("unknown", finding.getString("confidence"))
        assertEquals(ownerId, finding.getString("sourceFileId"))
        assertEquals(bindingRun.getString("id"), finding.getString("analyzerRunId"))

        val graphNodes = report.getJSONArray("graphNodes")
        assertEquals(1, graphNodes.length())
        assertEquals("unity-monobehaviour", graphNodes.getJSONObject(0).getString("nodeType"))
        assertEquals(ownerId, graphNodes.getJSONObject(0).getString("fileRecordId"))

        val graphEdges = report.getJSONArray("graphEdges")
        assertEquals(1, graphEdges.length())
        val edge = graphEdges.getJSONObject(0)
        assertTrue(edge.isNull("targetNodeId"))
        assertTrue(edge.getString("targetHint").contains("fileID=1"))
        assertTrue(edge.getString("targetHint").contains("PathID=77"))
        assertTrue(edge.getString("targetHint").contains("external-file-path-unmatched-or-ambiguous"))
    }

    @Test
    fun recordsNullMonoBehaviourScriptReferenceAsSkippedWithoutGraphEdge() {
        val root = MemoryScanNode(
            name = "NullMonoBehaviourScriptFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithExternalMonoBehaviourScriptPointer(
                        "archive:/CAB-123/unused.assets",
                        scriptPathId = 0L
                    )
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(root, shouldCancel = { false }) { _, _ -> }
        val report = JSONObject(result.json)
        val findings = report.getJSONArray("findings")
        assertEquals(1, findings.length())
        assertEquals("skipped", findings.getJSONObject(0).getString("status"))
        assertEquals("confirmed", findings.getJSONObject(0).getString("confidence"))
        assertTrue(findings.getJSONObject(0).getString("reason").contains("null PPtr"))
        assertEquals(1, report.getJSONArray("graphNodes").length())
        assertEquals(0, report.getJSONArray("graphEdges").length())
        assertEquals(1, report.getJSONArray("evidence").length())
        assertEquals(0L, report.getJSONArray("evidence").getJSONObject(0)
            .getJSONObject("locator").getLong("pathId"))
    }

    @Test
    fun promotesExternalTargetMetadataRunWhenItWasOutsideTheInitialDeepReadWindow() {
        val externalPath = "archive:/CAB-123/zz-sharedassets0.assets"
        val targetName = "zz-sharedassets0.assets"
        val root = MemoryScanNode(
            name = "ExternalMetadataPromotionFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithExternalMonoScriptReference(externalPath)
                )
            ) + (0 until 39).map { index ->
                MemoryScanNode("asset-%03d.assets".format(index), false, minimalUnitySerializedFile())
            } + listOf(
                MemoryScanNode(
                    targetName,
                    false,
                    unitySerializedFileWithMonoScriptObject(
                        minimalMonoScriptPayload(
                            name = "WindowPromoted",
                            className = "WindowPromoted",
                            namespace = "Game",
                            assemblyName = "Assembly-CSharp",
                            executionOrder = 0
                        )
                    )
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(root, shouldCancel = { false }) { _, _ -> }
        val report = JSONObject(result.json)
        val files = report.getJSONArray("files")
        val targetId = (0 until files.length()).map { files.getJSONObject(it) }
            .first { it.getString("path") == targetName }.getString("id")
        val analyzerRuns = report.getJSONArray("analyzerRuns")
        val runs = (0 until analyzerRuns.length())
            .map { analyzerRuns.getJSONObject(it) }
        val targetMetadataRun = runs.single {
            it.getString("analyzerId") == "serialized_file_metadata" &&
                it.getString("inputFileId") == targetId
        }
        assertEquals("analyzed", targetMetadataRun.getString("status"))
        assertTrue(targetMetadataRun.isNull("reason"))
        assertEquals(1, runs.count {
            it.getString("analyzerId") == "serialized_file_metadata" &&
                it.getString("inputFileId") == targetId
        })

        val analyzers = report.getJSONObject("scanCoverage").getJSONArray("analyzers")
        val metadataCoverage = (0 until analyzers.length()).map { analyzers.getJSONObject(it) }
            .first { it.getString("id") == "serialized_file_metadata" }
        assertEquals(41, metadataCoverage.getInt("candidateCount"))
        assertEquals(41, metadataCoverage.getInt("attemptedCount"))
        assertEquals(0, metadataCoverage.getInt("limitedCount"))
        assertEquals(41, metadataCoverage.getInt("parsedCount"))
    }

    @Test
    fun recordsSuccessfulLocalMonoScriptPayloadRunAgainstItsFileRecord() {
        val root = MemoryScanNode(
            name = "LocalMonoScriptFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithLocalMonoScriptObjects(
                        listOf(
                            77L to minimalMonoScriptPayload(
                                name = "HeroController",
                                className = "HeroController",
                                namespace = "Game",
                                assemblyName = "Assembly-CSharp",
                                executionOrder = -12
                            )
                        )
                    )
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val files = report.getJSONArray("files")
        val fileId = (0 until files.length())
            .map { files.getJSONObject(it) }
            .first { it.getString("path") == "globalgamemanagers" }
            .getString("id")
        val run = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
            .first { it.getString("analyzerId") == "mono_script_payload" }

        assertEquals(fileId, run.getString("inputFileId"))
        assertEquals("analyzed", run.getString("status"))
        assertTrue(run.isNull("reason"))

        val metadata = (0 until report.getJSONArray("unityMetadata").length())
            .map { report.getJSONArray("unityMetadata").getJSONObject(it) }
            .first { it.getString("path") == "globalgamemanagers" }
        val binding = metadata.getJSONArray("scriptTypeBindings").getJSONObject(0)
        assertEquals("local-monoscript-decoded", binding.getString("resolution"))
        assertEquals("HeroController", binding.getString("className"))
        assertEquals("Game", binding.getString("namespace"))
        assertEquals("Assembly-CSharp", binding.getString("assemblyName"))
        assertEquals(-12, binding.getInt("executionOrder"))
    }

    @Test
    fun recordsPartialLocalMonoScriptPayloadWhenSomeObjectsAreUnreadable() {
        val root = MemoryScanNode(
            name = "PartialMonoScriptFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithLocalMonoScriptObjects(
                        listOf(
                            77L to minimalMonoScriptPayload(
                                name = "HeroController",
                                className = "HeroController",
                                namespace = "Game",
                                assemblyName = "Assembly-CSharp",
                                executionOrder = -12
                            ),
                            78L to byteArrayOf(1)
                        )
                    )
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val run = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
            .first { it.getString("analyzerId") == "mono_script_payload" }

        assertEquals("partial", run.getString("status"))
        assertTrue(run.getString("reason").contains("Прочитано 1 MonoScript objects"))
        assertTrue(run.getString("reason").contains("пропущено 1"))
    }

    @Test
    fun recordsMonoScriptPayloadAsSkippedWhenNoLocalTargetsArePresent() {
        val root = MemoryScanNode(
            name = "NoLocalMonoScriptFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode("globalgamemanagers", false, minimalUnitySerializedFile())
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val fileId = (0 until report.getJSONArray("files").length())
            .map { report.getJSONArray("files").getJSONObject(it) }
            .first { it.getString("path") == "globalgamemanagers" }
            .getString("id")
        val run = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
            .first { it.getString("analyzerId") == "mono_script_payload" }

        assertEquals(fileId, run.getString("inputFileId"))
        assertEquals("skipped", run.getString("status"))
        assertTrue(run.getString("reason").contains("No local MonoScript object targets"))
    }

    @Test
    fun recordsFailedMonoScriptPayloadWhenTheProviderCannotOpenItsStream() {
        val root = MemoryScanNode(
            name = "MonoScriptProviderFailureFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "globalgamemanagers",
                    false,
                    unitySerializedFileWithLocalMonoScriptObjects(
                        listOf(
                            77L to minimalMonoScriptPayload(
                                name = "HeroController",
                                className = "HeroController",
                                namespace = "Game",
                                assemblyName = "Assembly-CSharp",
                                executionOrder = -12
                            )
                        )
                    )
                )
            )
        )
        val openCounts = mutableMapOf<String?, Int>()
        val fileAccess = ScanFileAccess { node ->
            val file = node as MemoryScanNode
            val count = (openCounts[file.name] ?: 0) + 1
            openCounts[file.name] = count
            if (file.name == "globalgamemanagers" && count == 2) {
                null
            } else if (file.isDirectory) {
                null
            } else {
                ByteArrayInputStream(file.contents)
            }
        }

        val result = GameScanner(fileAccess).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val fileId = (0 until report.getJSONArray("files").length())
            .map { report.getJSONArray("files").getJSONObject(it) }
            .first { it.getString("path") == "globalgamemanagers" }
            .getString("id")
        val run = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
            .first { it.getString("analyzerId") == "mono_script_payload" }

        assertEquals(fileId, run.getString("inputFileId"))
        assertEquals("failed", run.getString("status"))
        assertTrue(run.getString("reason").contains("no input stream"))
    }

    @Test
    fun reportsUnityProviderReadFailureWithoutHidingInventoryOrCoverage() {
        val root = MemoryScanNode(
            name = "UnreadableUnityFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "Data",
                    true,
                    children = listOf(
                        MemoryScanNode(
                            "globalgamemanagers",
                            false,
                            byteArrayOf(1, 2, 3),
                            failOnOpen = true
                        ),
                        MemoryScanNode("readme.txt", false, "inventory survives".toByteArray())
                    )
                )
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )

        val report = JSONObject(result.json)
        val inventoryFiles = report.getJSONArray("files")
        val sourceFileId = (0 until inventoryFiles.length())
            .map { inventoryFiles.getJSONObject(it) }
            .first { it.getString("path") == "Data/globalgamemanagers" }
            .getString("id")
        val analyzerRuns = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
        val headerRun = analyzerRuns.first { it.getString("analyzerId") == "unity_file_headers" }
        val metadataRun = analyzerRuns.first { it.getString("analyzerId") == "serialized_file_metadata" }
        assertEquals(sourceFileId, headerRun.getString("inputFileId"))
        assertEquals("failed", headerRun.getString("status"))
        assertEquals(sourceFileId, metadataRun.getString("inputFileId"))
        assertEquals("skipped", metadataRun.getString("status"))
        assertTrue(metadataRun.getString("reason").contains("header was not recognized"))

        val coverage = report.getJSONObject("scanCoverage")
        assertEquals("partial", coverage.getString("status"))
        assertEquals("completed", coverage.getJSONObject("inventory").getString("status"))
        assertEquals(3, coverage.getJSONObject("inventory").getInt("entriesDiscovered"))

        val analyzers = coverage.getJSONArray("analyzers")
        val headerCoverage = (0 until analyzers.length())
            .map { analyzers.getJSONObject(it) }
            .first { it.getString("id") == "unity_file_headers" }
        assertEquals(1, headerCoverage.getInt("candidateCount"))
        assertEquals(1, headerCoverage.getInt("attemptedCount"))
        assertEquals(0, headerCoverage.getInt("parsedCount"))
        assertEquals(1, headerCoverage.getInt("failedCount"))
        assertEquals("failed", headerCoverage.getString("status"))

        val metadataCoverage = (0 until analyzers.length())
            .map { analyzers.getJSONObject(it) }
            .first { it.getString("id") == "serialized_file_metadata" }
        assertEquals(1, metadataCoverage.getInt("candidateCount"))
        assertEquals(0, metadataCoverage.getInt("attemptedCount"))
        assertEquals(0, metadataCoverage.getInt("parsedCount"))
        assertEquals(1, metadataCoverage.getInt("skippedCount"))
        assertEquals("partial", metadataCoverage.getString("status"))

        val files = report.getJSONArray("files")
        assertTrue((0 until files.length()).any {
            files.getJSONObject(it).getString("path") == "Data/readme.txt"
        })
        assertEquals(0, report.getJSONArray("unityFileHeaders").length())
        assertEquals(0, report.getJSONArray("unityMetadata").length())
        val warnings = report.getJSONArray("warnings")
        val legacyProviderWarning = "Unity-анализ Data/globalgamemanagers: synthetic provider read failure"
        assertTrue((0 until warnings.length()).any {
            warnings.getString(it) == legacyProviderWarning
        })
        val issues = report.getJSONArray("issues")
        val providerIssue = (0 until issues.length())
            .map { issues.getJSONObject(it) }
            .first { it.getString("code") == "unity.header_analysis_failed" }
        assertEquals("unity_file_headers", providerIssue.getString("stage"))
        assertEquals("warning", providerIssue.getString("severity"))
        assertEquals("Data/globalgamemanagers", providerIssue.getString("path"))
        assertEquals(legacyProviderWarning, providerIssue.getString("message"))
        assertTrue(result.markdown.contains("## Scan coverage"))
        assertTrue(result.markdown.contains("- unity_file_headers: failed; candidates=1; attempted=1; parsed=0"))
        assertTrue(result.markdown.contains("- serialized_file_metadata: partial; candidates=1; attempted=0; parsed=0"))
        assertTrue(result.markdown.contains("synthetic provider read failure"))
    }

    @Test
    fun preservesInventoryAndReportsPartialCoverageWhenDirectoryCannotBeListed() {
        val root = MemoryScanNode(
            name = "DirectoryReadFailureFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    "Data",
                    true,
                    children = listOf(MemoryScanNode("hidden.asset", false, minimalUnitySerializedFile())),
                    failOnList = true
                ),
                MemoryScanNode("readme.txt", false, "visible sibling".toByteArray())
            )
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )

        val report = JSONObject(result.json)
        val coverage = report.getJSONObject("scanCoverage")
        assertEquals("partial", coverage.getString("status"))
        val inventory = coverage.getJSONObject("inventory")
        assertEquals("partial", inventory.getString("status"))
        assertEquals(2, inventory.getInt("entriesDiscovered"))
        assertEquals(1, inventory.getInt("filesDiscovered"))
        assertEquals(1, inventory.getInt("directoriesDiscovered"))
        assertEquals(1, inventory.getInt("inaccessibleDirectoryCount"))

        val files = report.getJSONArray("files")
        val records = (0 until files.length()).map { files.getJSONObject(it) }
        val paths = records.map { it.getString("path") }
        assertEquals(listOf("Data", "readme.txt"), paths)
        assertTrue(paths.none { it.contains("hidden.asset") })
        val inaccessibleDirectory = records.first { it.getString("path") == "Data" }
        assertEquals("inaccessible", inaccessibleDirectory.getString("inventoryStatus"))
        val visibleSibling = records.first { it.getString("path") == "readme.txt" }
        assertEquals("discovered", visibleSibling.getString("inventoryStatus"))
        assertTrue(visibleSibling.isNull("parentId"))

        val warnings = report.getJSONArray("warnings")
        val legacyDirectoryWarning = "Не удалось прочитать каталог Data: synthetic directory listing failure"
        assertTrue((0 until warnings.length()).any { warnings.getString(it) == legacyDirectoryWarning })
        val issues = report.getJSONArray("issues")
        val directoryIssue = (0 until issues.length())
            .map { issues.getJSONObject(it) }
            .first { it.getString("code") == "inventory.directory_listing_failed" }
        assertEquals("inventory", directoryIssue.getString("stage"))
        assertEquals("error", directoryIssue.getString("severity"))
        assertEquals("Data", directoryIssue.getString("path"))
        assertEquals(legacyDirectoryWarning, directoryIssue.getString("message"))
        assertTrue(result.markdown.contains("- Inventory status: partial"))
        assertTrue(result.markdown.contains("Не удалось прочитать каталог Data: synthetic directory listing failure"))
        assertTrue(result.markdown.contains("readme.txt"))
        assertFalse(result.markdown.contains("hidden.asset"))
    }

    @Test
    fun recordsSkippedPeCandidatesWhenTheCandidateLimitIsReached() {
        val root = MemoryScanNode(
            name = "PeLimitFixture",
            isDirectory = true,
            children = (0..150).map { index ->
                MemoryScanNode("candidate-$index.dll", false, byteArrayOf(0, 1, 2, 3))
            }
        )

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false },
            onProgress = { _, _ -> }
        )
        val report = JSONObject(result.json)
        val runs = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
        assertEquals(151, runs.size)
        assertEquals(150, runs.count { it.getString("status") == "failed" })
        assertEquals(1, runs.count { it.getString("status") == "skipped" })
        assertEquals(
            "Candidate limit reached (150).",
            runs.first { it.getString("status") == "skipped" }.getString("reason")
        )
    }

    @Test
    fun preservesQueuedAnalyzerRunsWhenCancellationOccursAfterInventory() {
        val root = MemoryScanNode(
            name = "QueuedFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode("first.exe", false, byteArrayOf(1)),
                MemoryScanNode("second.dll", false, byteArrayOf(2))
            )
        )
        var cancellationChecks = 0
        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = {
                cancellationChecks++
                cancellationChecks >= 5
            },
            onProgress = { _, _ -> }
        )

        assertTrue(result.isPartial)
        val report = JSONObject(result.json)
        assertEquals(2, report.getJSONArray("analyzerRuns").length())
        val runs = (0 until report.getJSONArray("analyzerRuns").length())
            .map { report.getJSONArray("analyzerRuns").getJSONObject(it) }
        assertTrue(runs.all { it.getString("status") == "queued" })
        assertTrue(report.getString("scanId").isNotBlank())
        assertEquals(
            "completed",
            report.getJSONObject("scanCoverage").getJSONObject("inventory").getString("status")
        )
        assertTrue(result.markdown.contains("queued-записи не завершены"))
    }

    @Test
    fun preservesInventoryIssuesAndCoverageWhenCancellationFollowsDirectoryFailure() {
        val root = MemoryScanNode(
            name = "CancelledInventoryFailureFixture",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    name = "Data",
                    isDirectory = true,
                    failOnList = true
                ),
                MemoryScanNode("known.txt", false, byteArrayOf(1, 2, 3))
            )
        )
        var checks = 0
        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = {
                checks++
                checks >= 5
            },
            onProgress = { _, _ -> }
        )

        assertTrue(result.isPartial)
        val report = JSONObject(result.json)
        val coverage = report.getJSONObject("scanCoverage")
        assertEquals("partial", coverage.getString("status"))
        val inventory = coverage.getJSONObject("inventory")
        assertEquals(2, inventory.getInt("entriesDiscovered"))
        assertEquals(1, inventory.getInt("filesDiscovered"))
        assertEquals(1, inventory.getInt("directoriesDiscovered"))
        assertEquals(1, inventory.getInt("inaccessibleDirectoryCount"))

        val issues = report.getJSONArray("issues")
        assertEquals(2, issues.length())
        assertEquals("inventory.directory_listing_failed", issues.getJSONObject(0).getString("code"))
        assertEquals("scan.cancelled", issues.getJSONObject(1).getString("code"))
        assertEquals(issues.getJSONObject(0).getString("message"),
            report.getJSONArray("warnings").getString(0))
        assertTrue(result.markdown.contains("synthetic directory listing failure"))
    }

    @Test
    fun returnsPartialReportWithExactlyTheInventoryCollectedBeforeCancellation() {
        val root = MemoryScanNode(
            name = "FixtureGame",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    name = "Assets",
                    isDirectory = true,
                    children = listOf(
                        MemoryScanNode(
                            "nested",
                            true,
                            children = listOf(
                                MemoryScanNode("first.txt", false, byteArrayOf(1)),
                                MemoryScanNode("later-inside.txt", false, byteArrayOf(2))
                            )
                        )
                    )
                ),
                MemoryScanNode("later.txt", false, byteArrayOf(3))
            )
        )
        var cancellationChecks = 0

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = {
                cancellationChecks++
                cancellationChecks >= 7
            },
            onProgress = { _, _ -> }
        )

        assertTrue(result.isPartial)
        assertTrue(result.markdown.contains("Assets/nested/first.txt"))
        assertTrue(result.json.contains("\"scanStatus\":\"cancelled\""))
        assertTrue(result.json.contains("Assets/nested/first.txt"))
        assertFalse(result.json.contains("later-inside.txt"))
        assertFalse(result.json.contains("\"path\":\"later.txt\""))
        assertTrue(result.json.contains("\"entryCount\":3"))
        assertEquals(
            "partial",
            JSONObject(result.json).getJSONObject("scanCoverage")
                .getJSONObject("inventory").getString("status")
        )

        val files = JSONObject(result.json).getJSONArray("files")
        val records = (0 until files.length()).map { files.getJSONObject(it) }
        assertEquals("partial", records.first { it.getString("path") == "Assets" }.getString("inventoryStatus"))
        assertEquals("partial", records.first { it.getString("path") == "Assets/nested" }.getString("inventoryStatus"))
        assertEquals("discovered", records.first { it.getString("path") == "Assets/nested/first.txt" }.getString("inventoryStatus"))
    }
}

private fun unitySerializedFileWithRepeatedExternalMonoScriptReference(externalPath: String): ByteArray {
    val metadata = ByteArrayOutputStream().apply {
        write("2022.3.10f1".toByteArray(Charsets.UTF_8))
        write(0)
        u32le(5)
        write(0)
        u32le(2) // two MonoBehaviour serialized types sharing one script reference
        repeat(2) {
            u32le(114)
            write(0)
            u16le(0) // both point to the same scriptTypeReferences row
            write(ByteArray(16))
            write(ByteArray(16))
            u32le(0)
        }
        u32le(0) // object count
        u32le(1) // one shared script type reference
        u32le(1)
        u64le(77)
        u32le(1) // one external reference
        write("CAB-123".toByteArray(Charsets.US_ASCII))
        write(0)
        write(ByteArray(16))
        u32le(0)
        write(externalPath.toByteArray(Charsets.US_ASCII))
        write(0)
        u32le(0)
    }.toByteArray()

    val dataOffset = 48 + metadata.size
    val bytes = ByteArray(dataOffset)
    put32be(bytes, 8, 22)
    put64be(bytes, 16, metadata.size.toLong())
    put64be(bytes, 24, bytes.size.toLong())
    put64be(bytes, 32, dataOffset.toLong())
    bytes[40] = 0
    metadata.copyInto(bytes, 48)
    return bytes
}

private fun unitySerializedFileWithExternalMonoScriptReference(externalPath: String): ByteArray {
    val metadata = ByteArrayOutputStream().apply {
        write("2022.3.10f1".toByteArray(Charsets.UTF_8))
        write(0)
        u32le(5) // target platform
        write(0) // TypeTree disabled
        u32le(1) // serialized type count
        u32le(114) // MonoBehaviour class ID
        write(0) // stripped
        u16le(0) // script type index
        write(ByteArray(16)) // script ID
        write(ByteArray(16)) // old type hash
        u32le(0) // type dependencies
        u32le(0) // object count
        u32le(1) // script type reference count
        u32le(1) // external file ID
        u64le(77) // target MonoScript path ID
        u32le(1) // external reference count
        write("CAB-123".toByteArray(Charsets.US_ASCII))
        write(0)
        write(ByteArray(16)) // GUID
        u32le(0) // external reference type
        write(externalPath.toByteArray(Charsets.US_ASCII))
        write(0)
        u32le(0) // SerializeReference type count
    }.toByteArray()

    val dataOffset = 48 + metadata.size
    val bytes = ByteArray(dataOffset)
    put32be(bytes, 8, 22)
    put64be(bytes, 16, metadata.size.toLong())
    put64be(bytes, 24, bytes.size.toLong())
    put64be(bytes, 32, dataOffset.toLong())
    bytes[40] = 0
    metadata.copyInto(bytes, 48)
    return bytes
}

private fun unitySerializedFileWithLocalMonoScriptObjects(
    scripts: List<Pair<Long, ByteArray>>
): ByteArray {
    require(scripts.isNotEmpty()) { "At least one MonoScript fixture is required." }
    val monoScriptTypeIndex = scripts.size
    val metadata = ByteArrayOutputStream().apply {
        write("2022.3.10f1".toByteArray(Charsets.UTF_8))
        write(0)
        u32le(5) // target platform
        write(0) // TypeTree disabled
        u32le((scripts.size + 1).toLong()) // MonoBehaviour types plus one MonoScript type
        scripts.indices.forEach { scriptIndex ->
            u32le(114) // MonoBehaviour class ID
            write(0) // stripped
            u16le(scriptIndex) // script type reference index
            write(ByteArray(16)) // script ID
            write(ByteArray(16)) // old type hash
            u32le(0) // type dependencies
        }
        u32le(115) // MonoScript class ID
        write(0) // stripped
        u16le(0xffff) // no script type index
        write(ByteArray(16)) // old type hash
        u32le(0) // type dependencies
        u32le(scripts.size.toLong()) // object count
        while (size() % 4 != 0) write(0) // align object table after the count
        var byteStart = 0L
        scripts.forEach { (pathId, payload) ->
            u64le(pathId)
            u64le(byteStart)
            u32le(payload.size.toLong())
            u32le(monoScriptTypeIndex.toLong())
            byteStart += payload.size
        }
        u32le(scripts.size.toLong()) // script type reference count
        scripts.forEach { (pathId, _) ->
            u32le(0) // local file ID
            u64le(pathId)
        }
        u32le(0) // external reference count
        u32le(0) // SerializeReference type count
    }.toByteArray()

    val dataOffset = 48 + metadata.size
    val payloadSize = scripts.sumOf { it.second.size }
    val bytes = ByteArray(dataOffset + payloadSize)
    put32be(bytes, 8, 22)
    put64be(bytes, 16, metadata.size.toLong())
    put64be(bytes, 24, bytes.size.toLong())
    put64be(bytes, 32, dataOffset.toLong())
    bytes[40] = 0
    metadata.copyInto(bytes, 48)
    var position = dataOffset
    scripts.forEach { (_, payload) ->
        payload.copyInto(bytes, position)
        position += payload.size
    }
    return bytes
}

private fun unitySerializedFileWithExternalMonoBehaviourScriptPointer(
    externalPath: String,
    scriptPathId: Long = 77L,
    payloadOverride: ByteArray? = null
): ByteArray {
    val treeNodes = listOf(
        Triple(0, "MonoBehaviour", "Base") to -1,
        Triple(1, "PPtr<MonoScript>", "m_Script") to -1,
        Triple(2, "int", "m_FileID") to 4,
        Triple(2, "SInt64", "m_PathID") to 8
    )
    val stringTable = ByteArrayOutputStream()
    val offsets = linkedMapOf<String, Long>()
    treeNodes.forEach { (node, _) ->
        listOf(node.second, node.third).forEach { value ->
            if (value !in offsets) {
                offsets[value] = stringTable.size().toLong()
                stringTable.write(value.toByteArray(Charsets.UTF_8))
                stringTable.write(0)
            }
        }
    }
    val treeBytes = ByteArrayOutputStream().apply {
        u32le(treeNodes.size.toLong())
        u32le(stringTable.size().toLong())
        treeNodes.forEachIndexed { index, (node, byteSize) ->
            u16le(1)
            write(node.first)
            write(0)
            u32le(offsets.getValue(node.second))
            u32le(offsets.getValue(node.third))
            u32le(byteSize.toLong())
            u32le(index.toLong())
            u32le(0)
            u64le(0)
        }
        write(stringTable.toByteArray())
    }.toByteArray()
    val payload = payloadOverride ?: ByteArrayOutputStream().apply {
        u32le(1) // external fileID
        u64le(scriptPathId) // MonoScript pathID
    }.toByteArray()
    val metadata = ByteArrayOutputStream().apply {
        write("2022.3.10f1".toByteArray(Charsets.UTF_8))
        write(0)
        u32le(5)
        write(1) // TypeTree enabled
        u32le(1) // serialized type count
        u32le(114) // MonoBehaviour class ID
        write(0)
        u16le(0) // script type reference index
        write(ByteArray(16)) // script ID
        write(ByteArray(16)) // old type hash
        write(treeBytes)
        u32le(0) // type dependencies
        u32le(1) // object count
        while (size() % 4 != 0) write(0)
        u64le(10) // MonoBehaviour object pathID
        u64le(0) // byte start
        u32le(payload.size.toLong())
        u32le(0) // type index
        u32le(1) // script type reference count
        u32le(1) // external fileID
        u64le(scriptPathId) // external MonoScript pathID
        u32le(1) // external reference count
        write("CAB-123".toByteArray(Charsets.US_ASCII))
        write(0)
        write(ByteArray(16)) // GUID
        u32le(0) // external reference type
        write(externalPath.toByteArray(Charsets.US_ASCII))
        write(0)
        u32le(0) // SerializeReference type count
    }.toByteArray()

    val dataOffset = 48 + metadata.size
    val bytes = ByteArray(dataOffset + payload.size)
    put32be(bytes, 8, 22)
    put64be(bytes, 16, metadata.size.toLong())
    put64be(bytes, 24, bytes.size.toLong())
    put64be(bytes, 32, dataOffset.toLong())
    bytes[40] = 0
    metadata.copyInto(bytes, 48)
    payload.copyInto(bytes, dataOffset)
    return bytes
}

private fun unitySerializedFileWithMonoScriptObject(payload: ByteArray): ByteArray {
    val metadata = ByteArrayOutputStream().apply {
        write("2022.3.10f1".toByteArray(Charsets.UTF_8))
        write(0)
        u32le(5) // target platform
        write(0) // TypeTree disabled
        u32le(1) // serialized type count
        u32le(115) // MonoScript class ID
        write(0) // stripped
        u16le(0xffff) // no script type index
        write(ByteArray(16)) // old type hash
        u32le(0) // type dependencies
        u32le(1) // object count
        u64le(77) // MonoScript path ID
        u64le(0) // byte start within data section
        u32le(payload.size.toLong())
        u32le(0) // type index
        u32le(0) // script type reference count
        u32le(0) // external reference count
        u32le(0) // SerializeReference type count
    }.toByteArray()

    val dataOffset = 48 + metadata.size
    val bytes = ByteArray(dataOffset + payload.size)
    put32be(bytes, 8, 22)
    put64be(bytes, 16, metadata.size.toLong())
    put64be(bytes, 24, bytes.size.toLong())
    put64be(bytes, 32, dataOffset.toLong())
    bytes[40] = 0
    metadata.copyInto(bytes, 48)
    payload.copyInto(bytes, dataOffset)
    return bytes
}

private fun minimalMonoScriptPayload(
    name: String,
    className: String,
    namespace: String,
    assemblyName: String,
    executionOrder: Int
): ByteArray {
    val output = ByteArrayOutputStream()
    fun writeString(value: String, align: Boolean) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        output.u32le(encoded.size.toLong())
        output.write(encoded)
        if (align) while (output.size() % 4 != 0) output.write(0)
    }
    writeString(name, align = true)
    output.u32le(executionOrder.toLong())
    output.write(ByteArray(16)) // properties hash
    writeString(className, align = true)
    writeString(namespace, align = true)
    writeString(assemblyName, align = false)
    return output.toByteArray()
}

private fun unitySerializedFileWithTruncatedMetadata(): ByteArray {
    val complete = minimalUnitySerializedFile()
    val truncatedSize = complete.size - 12
    val bytes = complete.copyOf(truncatedSize)
    val metadataSize = truncatedSize - 48
    put64be(bytes, 16, metadataSize.toLong())
    put64be(bytes, 24, truncatedSize.toLong())
    put64be(bytes, 32, truncatedSize.toLong())
    return bytes
}

private fun unitySerializedFileWithExternalTransformGameObjectPointer(
    externalPath: String,
    targetPathId: Long = 1L
): ByteArray {
    val treeNodes = listOf(
        Triple(0, "Transform", "Base") to -1,
        Triple(1, "PPtr<GameObject>", "m_GameObject") to -1,
        Triple(2, "int", "m_FileID") to 4,
        Triple(2, "SInt64", "m_PathID") to 8
    )
    val stringTable = ByteArrayOutputStream()
    val offsets = linkedMapOf<String, Long>()
    treeNodes.forEach { (node, _) ->
        listOf(node.second, node.third).forEach { value ->
            if (value !in offsets) {
                offsets[value] = stringTable.size().toLong()
                stringTable.write(value.toByteArray(Charsets.UTF_8))
                stringTable.write(0)
            }
        }
    }
    val treeBytes = ByteArrayOutputStream().apply {
        u32le(treeNodes.size.toLong())
        u32le(stringTable.size().toLong())
        treeNodes.forEachIndexed { index, (node, byteSize) ->
            u16le(1)
            write(node.first)
            write(0)
            u32le(offsets.getValue(node.second))
            u32le(offsets.getValue(node.third))
            u32le(byteSize.toLong())
            u32le(index.toLong())
            u32le(0)
            u64le(0)
        }
        write(stringTable.toByteArray())
    }.toByteArray()
    val payload = ByteArrayOutputStream().apply {
        u32le(1) // external fileID
        u64le(targetPathId)
    }.toByteArray()
    val metadata = ByteArrayOutputStream().apply {
        write("2022.3.10f1".toByteArray(Charsets.UTF_8))
        write(0)
        u32le(5)
        write(1) // TypeTree enabled
        u32le(1) // serialized type count
        u32le(4) // Transform class ID
        write(0) // not stripped
        u16le(0xffff) // no script type index
        write(ByteArray(16)) // old type hash
        write(treeBytes)
        u32le(0) // type dependencies
        u32le(1) // object count
        while (size() % 4 != 0) write(0)
        u64le(2) // source Transform PathID
        u64le(0) // byte start
        u32le(payload.size.toLong())
        u32le(0) // type index
        u32le(0) // MonoScript references
        u32le(1) // external reference count
        write("CAB-123".toByteArray(Charsets.US_ASCII))
        write(0)
        write(ByteArray(16)) // GUID
        u32le(0) // external reference type
        write(externalPath.toByteArray(Charsets.US_ASCII))
        write(0)
        u32le(0) // SerializeReference type count
    }.toByteArray()

    val dataOffset = 48 + metadata.size
    val bytes = ByteArray(dataOffset + payload.size)
    put32be(bytes, 8, 22)
    put64be(bytes, 16, metadata.size.toLong())
    put64be(bytes, 24, bytes.size.toLong())
    put64be(bytes, 32, dataOffset.toLong())
    bytes[40] = 0
    metadata.copyInto(bytes, 48)
    payload.copyInto(bytes, dataOffset)
    return bytes
}

private fun unitySerializedFileWithExternalTransformPointers(
    externalPath: String?,
    pathId: Long = 2L
): ByteArray {
    val treeNodes = listOf(
        Triple(0, "Transform", "Base") to -1,
        Triple(1, "PPtr<GameObject>", "m_GameObject") to -1,
        Triple(2, "int", "m_FileID") to 4,
        Triple(2, "SInt64", "m_PathID") to 8,
        Triple(1, "PPtr<Transform>", "m_Father") to -1,
        Triple(2, "int", "m_FileID") to 4,
        Triple(2, "SInt64", "m_PathID") to 8,
        Triple(1, "vector", "m_Children") to -1,
        Triple(2, "Array", "Array") to -1,
        Triple(3, "int", "size") to 4,
        Triple(3, "PPtr<Transform>", "data") to -1,
        Triple(4, "int", "m_FileID") to 4,
        Triple(4, "SInt64", "m_PathID") to 8
    )
    val stringTable = ByteArrayOutputStream()
    val offsets = linkedMapOf<String, Long>()
    treeNodes.forEach { (node, _) ->
        listOf(node.second, node.third).forEach { value ->
            if (value !in offsets) {
                offsets[value] = stringTable.size().toLong()
                stringTable.write(value.toByteArray(Charsets.UTF_8))
                stringTable.write(0)
            }
        }
    }
    val treeBytes = ByteArrayOutputStream().apply {
        u32le(treeNodes.size.toLong())
        u32le(stringTable.size().toLong())
        treeNodes.forEachIndexed { index, (node, byteSize) ->
            u16le(1)
            write(node.first)
            write(0)
            u32le(offsets.getValue(node.second))
            u32le(offsets.getValue(node.third))
            u32le(byteSize.toLong())
            u32le(index.toLong())
            u32le(0)
            u64le(0)
        }
        write(stringTable.toByteArray())
    }.toByteArray()

    val externalFileId = if (externalPath == null) 0 else 1
    val externalPathId = if (externalPath == null) 0L else 10L
    val payload = ByteArrayOutputStream().apply {
        u32le(0) // local null GameObject PPtr
        u64le(0)
        u32le(externalFileId.toLong()) // father PPtr
        u64le(externalPathId)
        u32le(if (externalPath == null) 0 else 1) // children array size
        if (externalPath != null) {
            u32le(externalFileId.toLong())
            u64le(externalPathId)
        }
    }.toByteArray()

    val metadata = ByteArrayOutputStream().apply {
        write("2022.3.10f1".toByteArray(Charsets.UTF_8))
        write(0)
        u32le(5)
        write(1) // TypeTree enabled
        u32le(1) // serialized type count
        u32le(4) // Transform class ID
        write(0) // not stripped
        u16le(0xffff) // no script type index
        write(ByteArray(16)) // old type hash
        write(treeBytes)
        u32le(0) // type dependencies
        u32le(1) // object count
        while (size() % 4 != 0) write(0)
        u64le(pathId)
        u64le(0) // byte start
        u32le(payload.size.toLong())
        u32le(0) // type index
        u32le(0) // MonoScript references
        u32le(if (externalPath == null) 0 else 1)
        if (externalPath != null) {
            write("CAB-123".toByteArray(Charsets.US_ASCII))
            write(0)
            write(ByteArray(16)) // GUID
            u32le(0) // external reference type
            write(externalPath.toByteArray(Charsets.US_ASCII))
            write(0)
        }
        u32le(0) // SerializeReference type count
    }.toByteArray()

    val dataOffset = 48 + metadata.size
    val bytes = ByteArray(dataOffset + payload.size)
    put32be(bytes, 8, 22)
    put64be(bytes, 16, metadata.size.toLong())
    put64be(bytes, 24, bytes.size.toLong())
    put64be(bytes, 32, dataOffset.toLong())
    bytes[40] = 0
    metadata.copyInto(bytes, 48)
    payload.copyInto(bytes, dataOffset)
    return bytes
}

private fun unitySerializedFileWithExternalGameObjectComponentPointer(
    externalPath: String,
    componentPathId: Long = 10L
): ByteArray {
    val treeNodes = listOf(
        Triple(0, "GameObject", "Base") to -1,
        Triple(1, "vector", "m_Component") to -1,
        Triple(2, "Array", "Array") to -1,
        Triple(3, "int", "size") to 4,
        Triple(3, "Component", "data") to -1,
        Triple(4, "PPtr<Component>", "component") to -1,
        Triple(5, "int", "m_FileID") to 4,
        Triple(5, "SInt64", "m_PathID") to 8,
        Triple(1, "string", "m_Name") to -1,
        Triple(1, "bool", "m_IsActive") to 1
    )
    val stringTable = ByteArrayOutputStream()
    val offsets = linkedMapOf<String, Long>()
    treeNodes.forEach { (node, _) ->
        listOf(node.second, node.third).forEach { value ->
            if (value !in offsets) {
                offsets[value] = stringTable.size().toLong()
                stringTable.write(value.toByteArray(Charsets.UTF_8))
                stringTable.write(0)
            }
        }
    }
    val treeBytes = ByteArrayOutputStream().apply {
        u32le(treeNodes.size.toLong())
        u32le(stringTable.size().toLong())
        treeNodes.forEachIndexed { index, (node, byteSize) ->
            u16le(1)
            write(node.first)
            write(0)
            u32le(offsets.getValue(node.second))
            u32le(offsets.getValue(node.third))
            u32le(byteSize.toLong())
            u32le(index.toLong())
            u32le(0)
            u64le(0)
        }
        write(stringTable.toByteArray())
    }.toByteArray()
    val payload = ByteArrayOutputStream().apply {
        u32le(1) // component array size
        u32le(1) // external fileID
        u64le(componentPathId) // component PathID
        u32le(0) // empty GameObject name
        write(1) // active
    }.toByteArray()
    val metadata = ByteArrayOutputStream().apply {
        write("2022.3.10f1".toByteArray(Charsets.UTF_8))
        write(0)
        u32le(5)
        write(1) // TypeTree enabled
        u32le(1) // serialized type count
        u32le(1) // GameObject class ID
        write(0) // not stripped
        u16le(0xffff) // no script type index
        write(ByteArray(16)) // old type hash
        write(treeBytes)
        u32le(0) // type dependencies
        u32le(1) // object count
        while (size() % 4 != 0) write(0)
        u64le(1) // source GameObject PathID
        u64le(0) // byte start
        u32le(payload.size.toLong())
        u32le(0) // type index
        u32le(0) // MonoScript references
        u32le(1) // external reference count
        write("CAB-123".toByteArray(Charsets.US_ASCII))
        write(0)
        write(ByteArray(16)) // GUID
        u32le(0) // external reference type
        write(externalPath.toByteArray(Charsets.US_ASCII))
        write(0)
        u32le(0) // SerializeReference type count
    }.toByteArray()

    val dataOffset = 48 + metadata.size
    val bytes = ByteArray(dataOffset + payload.size)
    put32be(bytes, 8, 22)
    put64be(bytes, 16, metadata.size.toLong())
    put64be(bytes, 24, bytes.size.toLong())
    put64be(bytes, 32, dataOffset.toLong())
    bytes[40] = 0
    metadata.copyInto(bytes, 48)
    payload.copyInto(bytes, dataOffset)
    return bytes
}

private data class LocalSceneObject(
    val pathId: Long,
    val classId: Int,
    val payload: ByteArray
)

private fun localGameObject(
    pathId: Long,
    name: String,
    active: Boolean,
    components: List<Pair<Int, Long>>
): LocalSceneObject {
    val payload = ByteArrayOutputStream().apply {
        val encoded = name.toByteArray(Charsets.UTF_8)
        u32le(encoded.size.toLong())
        write(encoded)
        while (size() % 4 != 0) write(0)
        write(if (active) 1 else 0)
        u32le(components.size.toLong())
        components.forEach { (fileId, targetPathId) ->
            u32le(fileId.toLong())
            u64le(targetPathId)
        }
    }.toByteArray()
    return LocalSceneObject(pathId, 1, payload)
}

private fun localTransform(
    pathId: Long,
    gameObjectPathId: Long,
    father: Pair<Int, Long> = 0 to 0L,
    children: List<Pair<Int, Long>> = emptyList()
): LocalSceneObject {
    val payload = ByteArrayOutputStream().apply {
        u32le(0) // local GameObject fileID
        u64le(gameObjectPathId)
        u32le(father.first.toLong())
        u64le(father.second)
        u32le(children.size.toLong())
        children.forEach { (fileId, targetPathId) ->
            u32le(fileId.toLong())
            u64le(targetPathId)
        }
    }.toByteArray()
    return LocalSceneObject(pathId, 4, payload)
}

private fun unitySerializedFileWithLocalSceneObjects(
    objects: List<LocalSceneObject>,
    externalPaths: List<String> = emptyList()
): ByteArray {
    require(objects.isNotEmpty()) { "At least one local scene object is required." }
    val gameObjectTree = listOf(
        Triple(0, "GameObject", "Base") to -1,
        Triple(1, "string", "m_Name") to -1,
        Triple(1, "bool", "m_IsActive") to 1,
        Triple(1, "vector", "m_Component") to -1,
        Triple(2, "Array", "Array") to -1,
        Triple(3, "int", "size") to 4,
        Triple(3, "Component", "data") to -1,
        Triple(4, "PPtr<Component>", "component") to -1,
        Triple(5, "int", "m_FileID") to 4,
        Triple(5, "SInt64", "m_PathID") to 8
    )
    val transformTree = listOf(
        Triple(0, "Transform", "Base") to -1,
        Triple(1, "PPtr<GameObject>", "m_GameObject") to -1,
        Triple(2, "int", "m_FileID") to 4,
        Triple(2, "SInt64", "m_PathID") to 8,
        Triple(1, "PPtr<Transform>", "m_Father") to -1,
        Triple(2, "int", "m_FileID") to 4,
        Triple(2, "SInt64", "m_PathID") to 8,
        Triple(1, "vector", "m_Children") to -1,
        Triple(2, "Array", "Array") to -1,
        Triple(3, "int", "size") to 4,
        Triple(3, "PPtr<Transform>", "data") to -1,
        Triple(4, "int", "m_FileID") to 4,
        Triple(4, "SInt64", "m_PathID") to 8
    )

    fun typeTree(nodes: List<Pair<Triple<Int, String, String>, Int>>): ByteArray {
        val stringTable = ByteArrayOutputStream()
        val offsets = linkedMapOf<String, Long>()
        nodes.forEach { (node, _) ->
            listOf(node.second, node.third).forEach { value ->
                if (value !in offsets) {
                    offsets[value] = stringTable.size().toLong()
                    stringTable.write(value.toByteArray(Charsets.UTF_8))
                    stringTable.write(0)
                }
            }
        }
        return ByteArrayOutputStream().apply {
            u32le(nodes.size.toLong())
            u32le(stringTable.size().toLong())
            nodes.forEachIndexed { index, (node, byteSize) ->
                u16le(1)
                write(node.first)
                write(0)
                u32le(offsets.getValue(node.second))
                u32le(offsets.getValue(node.third))
                u32le(byteSize.toLong())
                u32le(index.toLong())
                u32le(0)
                u64le(0)
            }
            write(stringTable.toByteArray())
        }.toByteArray()
    }
    val gameObjectTreeBytes = typeTree(gameObjectTree)
    val transformTreeBytes = typeTree(transformTree)
    val typeIndices = mapOf(1 to 0, 4 to 1)
    require(objects.all { it.classId in typeIndices }) { "Only GameObject and Transform fixtures are supported." }

    val metadata = ByteArrayOutputStream().apply {
        write("2022.3.10f1".toByteArray(Charsets.UTF_8))
        write(0)
        u32le(5)
        write(1) // TypeTree enabled
        u32le(2) // serialized type count

        u32le(1) // GameObject class ID
        write(0)
        u16le(0xffff)
        write(ByteArray(16))
        write(gameObjectTreeBytes)
        u32le(0) // type dependencies

        u32le(4) // Transform class ID
        write(0)
        u16le(0xffff)
        write(ByteArray(16))
        write(transformTreeBytes)
        u32le(0) // type dependencies

        u32le(objects.size.toLong())
        while (size() % 4 != 0) write(0)
        var byteStart = 0L
        objects.forEach { obj ->
            u64le(obj.pathId)
            u64le(byteStart)
            u32le(obj.payload.size.toLong())
            u32le(typeIndices.getValue(obj.classId).toLong())
            byteStart += obj.payload.size
        }
        u32le(0) // MonoScript references
        u32le(externalPaths.size.toLong())
        externalPaths.forEach { externalPath ->
            write("CAB-123".toByteArray(Charsets.US_ASCII))
            write(0)
            write(ByteArray(16))
            u32le(0)
            write(externalPath.toByteArray(Charsets.US_ASCII))
            write(0)
        }
        u32le(0) // SerializeReference type count
    }.toByteArray()

    val dataOffset = 48 + metadata.size
    val bytes = ByteArray(dataOffset + objects.sumOf { it.payload.size })
    put32be(bytes, 8, 22)
    put64be(bytes, 16, metadata.size.toLong())
    put64be(bytes, 24, bytes.size.toLong())
    put64be(bytes, 32, dataOffset.toLong())
    bytes[40] = 0
    metadata.copyInto(bytes, 48)
    var position = dataOffset
    objects.forEach { objectSummary ->
        objectSummary.payload.copyInto(bytes, position)
        position += objectSummary.payload.size
    }
    return bytes
}

private fun unitySerializedFileWithGameObjectScene(
    objects: List<Pair<Long, ByteArray>>
): ByteArray {
    require(objects.isNotEmpty()) { "At least one GameObject fixture is required." }
    val treeNodes = listOf(
        Triple(0, "GameObject", "Base") to -1,
        Triple(1, "string", "m_Name") to -1,
        Triple(1, "bool", "m_IsActive") to 1
    )
    val stringTable = ByteArrayOutputStream()
    val offsets = linkedMapOf<String, Long>()
    treeNodes.forEach { (node, _) ->
        listOf(node.second, node.third).forEach { value ->
            if (value !in offsets) {
                offsets[value] = stringTable.size().toLong()
                stringTable.write(value.toByteArray(Charsets.UTF_8))
                stringTable.write(0)
            }
        }
    }
    val treeBytes = ByteArrayOutputStream().apply {
        u32le(treeNodes.size.toLong())
        u32le(stringTable.size().toLong())
        treeNodes.forEachIndexed { index, (node, byteSize) ->
            u16le(1) // TypeTree node record version
            write(node.first) // level
            write(0) // type flags
            u32le(offsets.getValue(node.second))
            u32le(offsets.getValue(node.third))
            u32le(byteSize.toLong())
            u32le(index.toLong())
            u32le(0) // meta flags
            u64le(0) // reference type hash
        }
        write(stringTable.toByteArray())
    }.toByteArray()

    val metadata = ByteArrayOutputStream().apply {
        write("2022.3.10f1".toByteArray(Charsets.UTF_8))
        write(0)
        u32le(5) // target platform
        write(1) // TypeTree enabled
        u32le(1) // serialized type count
        u32le(1) // GameObject class ID
        write(0) // not stripped
        u16le(0xffff) // no script type index
        write(ByteArray(16)) // old type hash
        write(treeBytes)
        u32le(0) // type dependencies
        u32le(objects.size.toLong())
        while (size() % 4 != 0) write(0)
        var byteStart = 0L
        objects.forEach { (pathId, payload) ->
            u64le(pathId)
            u64le(byteStart)
            u32le(payload.size.toLong())
            u32le(0) // type index
            byteStart += payload.size
        }
        u32le(0) // MonoScript references
        u32le(0) // external references
        u32le(0) // SerializeReference types
    }.toByteArray()

    val dataOffset = 48 + metadata.size
    val payloadSize = objects.sumOf { it.second.size }
    val bytes = ByteArray(dataOffset + payloadSize)
    put32be(bytes, 8, 22)
    put64be(bytes, 16, metadata.size.toLong())
    put64be(bytes, 24, bytes.size.toLong())
    put64be(bytes, 32, dataOffset.toLong())
    bytes[40] = 0 // little-endian metadata
    metadata.copyInto(bytes, 48)
    var position = dataOffset
    objects.forEach { (_, payload) ->
        payload.copyInto(bytes, position)
        position += payload.size
    }
    return bytes
}

private fun minimalGameObjectPayload(name: String, active: Boolean): ByteArray {
    val output = ByteArrayOutputStream()
    val encoded = name.toByteArray(Charsets.UTF_8)
    output.u32le(encoded.size.toLong())
    output.write(encoded)
    while (output.size() % 4 != 0) output.write(0)
    output.write(if (active) 1 else 0)
    return output.toByteArray()
}

private fun minimalUnitySerializedFile(formatVersion: Int = 22): ByteArray {
    val metadata = ByteArrayOutputStream().apply {
        write("2022.3.10f1".toByteArray(Charsets.UTF_8))
        write(0)
        u32le(5) // target platform
        write(0) // TypeTree disabled
        u32le(0) // serialized type count
        u32le(0) // object count
        u32le(0) // script type reference count
        u32le(0) // external reference count
        u32le(0) // SerializeReference type count
    }.toByteArray()

    val headerSize = if (formatVersion >= 22) 48 else 20
    val dataOffset = headerSize + metadata.size
    val bytes = ByteArray(dataOffset)
    if (formatVersion >= 22) {
        put32be(bytes, 8, formatVersion)
        put64be(bytes, 16, metadata.size.toLong())
        put64be(bytes, 24, bytes.size.toLong())
        put64be(bytes, 32, dataOffset.toLong())
        bytes[40] = 0 // little-endian metadata
    } else {
        put32be(bytes, 0, metadata.size)
        put32be(bytes, 4, bytes.size)
        put32be(bytes, 8, formatVersion)
        put32be(bytes, 12, dataOffset)
        bytes[16] = 0 // little-endian metadata
    }
    metadata.copyInto(bytes, headerSize)
    return bytes
}

private fun put32be(bytes: ByteArray, offset: Int, value: Int) {
    for (index in 0 until 4) {
        bytes[offset + index] = (value ushr (8 * (3 - index))).toByte()
    }
}

private fun put64be(bytes: ByteArray, offset: Int, value: Long) {
    for (index in 0 until 8) {
        bytes[offset + index] = (value ushr (8 * (7 - index))).toByte()
    }
}

private fun minimalNativePe(): ByteArray {
    val bytes = ByteArray(1024)
    val pe = 0x80
    val optional = pe + 24
    val optionalSize = 240
    val section = optional + optionalSize

    bytes[0] = 0x4d
    bytes[1] = 0x5a
    put32(bytes, 0x3c, pe)
    bytes[pe] = 0x50
    bytes[pe + 1] = 0x45
    put16(bytes, pe + 4, 0x8664) // IMAGE_FILE_MACHINE_AMD64
    put16(bytes, pe + 6, 1) // one section
    put16(bytes, pe + 20, optionalSize)
    put16(bytes, optional, 0x20b)
    put32(bytes, optional + 108, 16) // NumberOfRvaAndSizes // PE32+

    // IMAGE_DIRECTORY_ENTRY_IMPORT points to two records and a zero terminator.
    put32(bytes, optional + 120, 0x1000)
    put32(bytes, optional + 124, 60)

    // Map RVA 0x1000..0x11ff to file offsets 0x200..0x3ff.
    put32(bytes, section + 8, 0x200)
    put32(bytes, section + 12, 0x1000)
    put32(bytes, section + 16, 0x200)
    put32(bytes, section + 20, 0x200)

    put32(bytes, 0x20c, 0x1040)
    put32(bytes, 0x210, 0x1080)
    put32(bytes, 0x220, 0x1050)
    put32(bytes, 0x224, 0x10c0)

    put64(bytes, 0x280, 0x10a0L)
    put64(bytes, 0x288, Long.MIN_VALUE or 7L)
    put64(bytes, 0x290, 0L)
    put64(bytes, 0x2c0, 0x10d0L)
    put64(bytes, 0x2c8, 0L)

    put16(bytes, 0x2a0, 5)
    "CreateFileW".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0x2a2)
    put16(bytes, 0x2d0, 1)
    "MessageBoxW".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0x2d2)
    "KERNEL32.dll".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0x240)
    "USER32.dll".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0x250)
    return bytes
}

private fun managedPeWithMetadata(): ByteArray {
    val metadata = minimalClrMetadataRoot()
    val bytes = ByteArray(0x800)
    val pe = 0x80
    val optional = pe + 24
    val optionalSize = 240
    val section = optional + optionalSize
    val clrHeaderOffset = 0x200
    val metadataOffset = clrHeaderOffset + 72

    bytes[0] = 0x4d
    bytes[1] = 0x5a
    put32(bytes, 0x3c, pe)
    bytes[pe] = 0x50
    bytes[pe + 1] = 0x45
    put16(bytes, pe + 4, 0x8664)
    put16(bytes, pe + 6, 1)
    put16(bytes, pe + 20, optionalSize)
    put16(bytes, optional, 0x20b)
    put32(bytes, optional + 108, 16) // NumberOfRvaAndSizes

    // IMAGE_DIRECTORY_ENTRY_COM_DESCRIPTOR (index 14).
    put32(bytes, optional + 108, 16) // NumberOfRvaAndSizes
    put32(bytes, optional + 224, 0x1000)
    put32(bytes, optional + 228, 72)

    put32(bytes, section + 8, 0x600)
    put32(bytes, section + 12, 0x1000)
    put32(bytes, section + 16, 0x600)
    put32(bytes, section + 20, 0x200)

    // IMAGE_COR20_HEADER and its metadata directory.
    put32(bytes, clrHeaderOffset, 72)
    put16(bytes, clrHeaderOffset + 4, 2)
    put16(bytes, clrHeaderOffset + 6, 5)
    put32(bytes, clrHeaderOffset + 8, 0x1000 + 72)
    put32(bytes, clrHeaderOffset + 12, metadata.size)
    put32(bytes, clrHeaderOffset + 16, 1)
    metadata.copyInto(bytes, metadataOffset)
    return bytes
}

private fun minimalClrMetadataRoot(): ByteArray {
    val strings = ByteArrayOutputStream()
    strings.write(0)
    fun addString(value: String): Int {
        val index = strings.size()
        strings.write(value.toByteArray(Charsets.UTF_8))
        strings.write(0)
        return index
    }
    val moduleName = addString("Sample.dll")
    val typeName = addString("PlayerController")
    val namespace = addString("Game")
    val assemblyName = addString("Assembly-CSharp")
    val stringHeap = strings.toByteArray()

    val tables = ByteArrayOutputStream().apply {
        u32le(0)
        write(2); write(0); write(0); write(1)
        u64le((1L shl 0) or (1L shl 2) or (1L shl 32))
        u64le(0)
        u32le(1); u32le(1); u32le(1)
        // Module row.
        u16le(0); u16le(moduleName); u16le(0); u16le(0); u16le(0)
        // TypeDef row: public class Game.PlayerController.
        u32le(1); u16le(typeName); u16le(namespace); u16le(0); u16le(1); u16le(1)
        // Assembly row: Assembly-CSharp, Version=1.2.3.4.
        u32le(0); u16le(1); u16le(2); u16le(3); u16le(4); u32le(0)
        u16le(0); u16le(assemblyName); u16le(0)
    }.toByteArray()
    val blobHeap = byteArrayOf(0)

    fun paddedNameSize(name: String): Int = ((name.length + 1 + 3) / 4) * 4
    val streamHeadersSize = 8 + paddedNameSize("#~") +
        8 + paddedNameSize("#Strings") + 8 + paddedNameSize("#Blob")
    val prefixSize = 28
    val tablesOffset = prefixSize + streamHeadersSize
    val stringsOffset = tablesOffset + tables.size
    val blobsOffset = stringsOffset + stringHeap.size

    val root = ByteArrayOutputStream().apply {
        u32le(0x424a5342L)
        u16le(1); u16le(1); u32le(0)
        u32le(8)
        write(byteArrayOf('v'.code.toByte(), '4'.code.toByte(), '.'.code.toByte(), '0'.code.toByte(), 0, 0, 0, 0))
        u16le(0); u16le(3)
        streamHeader(tablesOffset, tables.size, "#~")
        streamHeader(stringsOffset, stringHeap.size, "#Strings")
        streamHeader(blobsOffset, blobHeap.size, "#Blob")
        write(tables)
        write(stringHeap)
        write(blobHeap)
    }.toByteArray()
    return root
}

private fun ByteArrayOutputStream.streamHeader(offset: Int, size: Int, name: String) {
    u32le(offset.toLong())
    u32le(size.toLong())
    write(name.toByteArray(Charsets.US_ASCII))
    write(0)
    while (this.size() % 4 != 0) write(0)
}

private fun ByteArrayOutputStream.u16le(value: Int) {
    write(value and 0xff)
    write((value ushr 8) and 0xff)
}

private fun ByteArrayOutputStream.u32le(value: Long) {
    for (index in 0 until 4) write((value ushr (8 * index)).toInt() and 0xff)
}

private fun ByteArrayOutputStream.u64le(value: Long) {
    for (index in 0 until 8) write((value ushr (8 * index)).toInt() and 0xff)
}

private fun put16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
}

private fun put32(bytes: ByteArray, offset: Int, value: Int) {
    for (i in 0 until 4) bytes[offset + i] = (value ushr (8 * i)).toByte()
}

private fun put64(bytes: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 8) bytes[offset + i] = (value ushr (8 * i)).toByte()
}

private class MemoryScanNode(
    override val name: String?,
    override val isDirectory: Boolean,
    val contents: ByteArray = byteArrayOf(),
    private val children: List<MemoryScanNode> = emptyList(),
    val failOnOpen: Boolean = false,
    val failOnList: Boolean = false,
    val inputStreamFactory: (() -> InputStream)? = null
) : ScanNode {
    override val size: Long
        get() = if (isDirectory) 0L else contents.size.toLong()

    override fun listFiles(): List<ScanNode> {
        if (failOnList) throw IllegalStateException("synthetic directory listing failure")
        return children
    }
}

private class MemoryScanFileAccess : ScanFileAccess {
    override fun openInputStream(node: ScanNode): InputStream? {
        val file = node as? MemoryScanNode
            ?: throw IllegalArgumentException("Unknown in-memory scan node.")
        if (file.failOnOpen) throw IllegalStateException("synthetic provider read failure")
        return if (file.isDirectory) null else file.inputStreamFactory?.invoke() ?: ByteArrayInputStream(file.contents)
    }
}
