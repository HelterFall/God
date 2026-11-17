package com.ramen.god

internal data class UnityTypeTreeNodeSummary(
    val level: Int,
    val typeName: String?,
    val fieldName: String?,
    val byteSize: Int,
    val typeFlags: Int,
    val metaFlags: Int
)

internal data class UnityTypeTreeSummary(
    val classId: Int,
    val nodeCount: Long,
    val rootType: String?,
    val rootName: String?,
    val fieldNames: List<String>,
    val nodes: List<UnityTypeTreeNodeSummary> = emptyList(),
    /** Full bounded node graph retained only for core scene object classes. */
    val decoderNodes: List<UnityTypeTreeNodeSummary> = emptyList(),
    val completeForDecoding: Boolean = false,
    val typeIndex: Int? = null
)

internal data class UnitySerializedTypeSummary(
    val classId: Int,
    val scriptTypeIndex: Int,
    val scriptId: String?,
    val className: String? = null,
    val namespace: String? = null,
    val assemblyName: String? = null
)

internal data class UnityObjectSummary(val pathId: Long, val byteStart: Long, val byteSize: Long, val typeIndex: Int, val classId: Int?)

internal data class UnityObjectTypeSummary(val classId: Int, val count: Long, val samplePathIds: List<Long>)

internal data class UnityScriptTypeReference(val fileId: Int, val pathId: Long)

internal data class UnityExternalReference(val fileId: Int, val path: String, val type: Int)

internal data class UnityScriptTypeBinding(
    val classId: Int,
    val scriptTypeIndex: Int,
    val scriptId: String?,
    val fileId: Int?,
    val pathId: Long?,
    val externalPath: String?,
    val targetObjectClassId: Int?,
    val objectByteStart: Long?,
    val objectByteSize: Long?,
    val matchedExternalFilePath: String? = null,
    val monoScriptName: String? = null,
    val className: String? = null,
    val namespace: String? = null,
    val assemblyName: String? = null,
    val executionOrder: Int? = null,
    val resolution: String
)

/** Bounded metadata summary for modern Unity SerializedFiles. */
internal data class UnityMetadataSummary(
    val unityVersion: String?,
    val targetPlatform: Long?,
    val typeTreeEnabled: Boolean?,
    val typeCount: Long?,
    val objectCount: Long? = null,
    val scriptTypeCount: Long? = null,
    val externalReferenceCount: Long? = null,
    val objectSamples: List<UnityObjectSummary> = emptyList(),
    val objectTypeCounts: List<UnityObjectTypeSummary> = emptyList(),
    val monoScriptObjects: List<UnityObjectSummary> = emptyList(),
    val monoScripts: List<UnityMonoScriptSummary> = emptyList(),
    val typeTrees: List<UnityTypeTreeSummary> = emptyList(),
    val serializedTypes: List<UnitySerializedTypeSummary> = emptyList(),
    val serializedReferenceTypeCount: Long? = null,
    val serializedReferenceTypes: List<UnitySerializedTypeSummary> = emptyList(),
    val sharedSubtreeCount: Long? = null,
    val scriptTypeReferences: List<UnityScriptTypeReference> = emptyList(),
    val externalReferences: List<UnityExternalReference> = emptyList(),
    val scriptTypeBindings: List<UnityScriptTypeBinding> = emptyList(),
    val decodedObjects: List<UnityDecodedObjectSummary> = emptyList(),
    val sceneGraph: UnitySceneGraphResult? = null,
    val sceneDecodeNote: String? = null,
    val note: String? = null
)

internal object UnityMetadataParser {
    private const val MIN_SUPPORTED_VERSION = 19L
    private const val MAX_SUPPORTED_VERSION = 26L
    private const val MAX_VERSION_STRING = 64
    private const val MAX_TYPE_COUNT = 200_000L
    private const val MAX_OBJECT_COUNT = 2_000_000L
    private const val MAX_AUX_COUNT = 500_000L
    private const val MAX_OBJECT_SAMPLES = 500
    private const val MAX_MONOSCRIPT_OBJECT_SAMPLES = 1000
    private const val MAX_SCRIPT_TYPE_SAMPLES = 500
    private const val MAX_EXTERNAL_REFERENCE_SAMPLES = 200
    private const val MAX_TYPE_TREE_NODES = 100_000L
    private const val MAX_TYPE_TREE_SAMPLES = 100
    private const val MAX_MONOBEHAVIOUR_DECODER_TREES = 12
    private const val MAX_SERIALIZED_TYPE_SAMPLES = 500
    private const val MAX_SERIALIZED_REFERENCE_TYPE_SAMPLES = 200
    private const val MAX_TYPE_TREE_FIELDS = 20
    private const val MAX_TYPE_TREE_NODE_SAMPLES = 128
    private const val MAX_DECODER_TYPE_TREE_NODES = 4096
    private const val MAX_TYPE_TREE_BYTES = 4 * 1024 * 1024
    private const val TYPE_TREE_NODE_SIZE = 32L

    // Unity common-string offsets used by TypeTree nodes. The base table is
    // the 109-entry AssetStudio table; the final five entries are present in
    // newer Unity 2022+/6 editor snapshots.
    private val COMMON_TYPE_TREE_STRINGS = mapOf(
        0 to "AABB", 5 to "AnimationClip", 19 to "AnimationCurve",
        34 to "AnimationState", 49 to "Array", 55 to "Base",
        60 to "BitField", 69 to "bitset", 76 to "bool", 81 to "char",
        86 to "ColorRGBA", 96 to "Component", 106 to "data",
        111 to "deque", 117 to "double", 124 to "dynamic_array",
        138 to "FastPropertyName", 155 to "first", 161 to "float",
        167 to "Font", 172 to "GameObject", 183 to "Generic Mono",
        196 to "GradientNEW", 208 to "GUID", 213 to "GUIStyle",
        222 to "int", 226 to "list", 231 to "long long", 241 to "map",
        245 to "Matrix4x4f", 256 to "MdFour", 263 to "MonoBehaviour",
        277 to "MonoScript", 288 to "m_ByteSize", 299 to "m_Curve",
        307 to "m_EditorClassIdentifier", 331 to "m_EditorHideFlags",
        349 to "m_Enabled", 359 to "m_ExtensionPtr", 374 to "m_GameObject",
        387 to "m_Index", 395 to "m_IsArray", 405 to "m_IsStatic",
        416 to "m_MetaFlag", 427 to "m_Name", 434 to "m_ObjectHideFlags",
        452 to "m_PrefabInternal", 469 to "m_PrefabParentObject",
        490 to "m_Script", 499 to "m_StaticEditorFlags", 519 to "m_Type",
        526 to "m_Version", 536 to "Object", 543 to "pair",
        548 to "PPtr<Component>", 564 to "PPtr<GameObject>",
        581 to "PPtr<Material>", 596 to "PPtr<MonoBehaviour>",
        616 to "PPtr<MonoScript>", 633 to "PPtr<Object>",
        646 to "PPtr<Prefab>", 659 to "PPtr<Sprite>",
        672 to "PPtr<TextAsset>", 688 to "PPtr<Texture>",
        702 to "PPtr<Texture2D>", 718 to "PPtr<Transform>",
        734 to "Prefab", 741 to "Quaternionf", 753 to "Rectf",
        759 to "RectInt", 767 to "RectOffset", 778 to "second",
        785 to "set", 789 to "short", 795 to "size", 800 to "SInt16",
        807 to "SInt32", 814 to "SInt64", 821 to "SInt8",
        827 to "staticvector", 840 to "string", 847 to "TextAsset",
        857 to "TextMesh", 866 to "Texture", 874 to "Texture2D",
        884 to "Transform", 894 to "TypelessData", 907 to "UInt16",
        914 to "UInt32", 921 to "UInt64", 928 to "UInt8",
        934 to "unsigned int", 947 to "unsigned long long",
        966 to "unsigned short", 981 to "vector", 988 to "Vector2f",
        997 to "Vector3f", 1006 to "Vector4f",
        1015 to "m_ScriptingClassIdentifier", 1042 to "Gradient",
        1051 to "Type*", 1057 to "int2_storage", 1070 to "int3_storage",
        1083 to "BoundsInt", 1093 to "m_CorrespondingSourceObject",
        1121 to "m_PrefabInstance", 1138 to "m_PrefabAsset",
        1152 to "FileSize", 1161 to "Hash128",
        1169 to "RenderingLayerMask", 1188 to "fixed_array",
        1200 to "EntityId", 1209 to "LoadableObjectId",
        1226 to "LoadableSceneId"
    )

    fun parse(prefix: ByteArray, header: UnityFileHeader): UnityMetadataSummary? {
        if (header.kind != "SerializedFile") return null
        val version = header.formatVersion ?: return null
        if (version !in MIN_SUPPORTED_VERSION..MAX_SUPPORTED_VERSION) {
            return UnityMetadataSummary(
                null, null, null, null,
                note = "Чтение метаданных пока поддерживается только для форматов 19–26."
            )
        }
        val base = header.headerSize
        if (prefix.size <= base) return null
        val little = header.endianness == "Little Endian"
        val cursor = Cursor(prefix, base, little)

        val unityVersion = cursor.readCString(MAX_VERSION_STRING) ?: return null
        if (unityVersion.isBlank()) return null
        val targetPlatform = cursor.readU32() ?: return null
        val typeTreeByte = cursor.readU8() ?: return null
        if (typeTreeByte !in 0..1) return null
        val typeCount = cursor.readU32() ?: return null
        if (typeCount > MAX_TYPE_COUNT) return null

        val partial = UnityMetadataSummary(
            unityVersion, targetPlatform, typeTreeByte == 1, typeCount
        )
        val typeTreeEnabled = typeTreeByte == 1

        val typeClassIds = ArrayList<Int>(typeCount.toInt())
        val typeTrees = ArrayList<UnityTypeTreeSummary>()
        val serializedTypes = ArrayList<UnitySerializedTypeSummary>(minOf(typeCount, MAX_SERIALIZED_TYPE_SAMPLES.toLong()).toInt())
        repeat(typeCount.toInt()) { typeIndex ->
            val typeEntry = readTypeEntry(cursor, version, typeTreeEnabled)
                ?: return partial.copy(note = "Таблица типов обрезана или содержит неподдерживаемые размеры; object count не прочитан.")
            typeClassIds.add(typeEntry.classId)
            if (serializedTypes.size < MAX_SERIALIZED_TYPE_SAMPLES) {
                serializedTypes.add(UnitySerializedTypeSummary(typeEntry.classId, typeEntry.scriptTypeIndex, typeEntry.scriptId))
            }
            typeEntry.typeTree?.let { parsedTree ->
                val tree = parsedTree.copy(typeIndex = typeIndex)
                val retainedMonoBehaviourTrees = typeTrees.count {
                    it.classId == 114 && it.completeForDecoding
                }
                val keepCompleteTree = tree.completeForDecoding && when (tree.classId) {
                    1 -> typeTrees.none { it.classId == 1 && it.completeForDecoding }
                    4 -> typeTrees.none { it.classId == 4 && it.completeForDecoding }
                    114 -> retainedMonoBehaviourTrees < MAX_MONOBEHAVIOUR_DECODER_TREES
                    else -> false
                }
                if (keepCompleteTree) {
                    typeTrees.add(tree)
                } else if (typeTrees.size < MAX_TYPE_TREE_SAMPLES) {
                    typeTrees.add(tree.copy(decoderNodes = emptyList(), completeForDecoding = false))
                }
            }
        }

        val objectCount = cursor.readU32()
            ?: return partial.copy(note = "Не удалось дочитать metadata до таблицы объектов.")
        if (objectCount > MAX_OBJECT_COUNT) {
            return partial.copy(note = "Число объектов превышает безопасный лимит.")
        }

        val objectSamples = ArrayList<UnityObjectSummary>(minOf(objectCount, MAX_OBJECT_SAMPLES.toLong()).toInt())
        val monoScriptObjects = ArrayList<UnityObjectSummary>()
        val objectTypeCounts = linkedMapOf<Int, Long>()
        val objectTypePathSamples = linkedMapOf<Int, MutableList<Long>>()
        val dataSectionSize = if (header.fileSize != null && header.dataOffset != null && header.fileSize >= header.dataOffset) {
            header.fileSize - header.dataOffset
        } else null
        repeat(objectCount.toInt()) {
            if (!cursor.align4()) {
                return partial.copy(objectCount = objectCount, objectSamples = objectSamples, note = "Таблица объектов обрезана.")
            }
            val pathId = cursor.readU64()
            val relativeStart = if (version >= 22L) cursor.readU64() else cursor.readU32()
            val byteSize = cursor.readU32()
            val typeIndex = cursor.readI32()
            if (pathId == null || relativeStart == null || byteSize == null || typeIndex == null) {
                return partial.copy(objectCount = objectCount, objectSamples = objectSamples, note = "Таблица объектов обрезана.")
            }
            if (dataSectionSize != null && (relativeStart < 0L || byteSize > dataSectionSize || relativeStart > dataSectionSize - byteSize)) {
                return partial.copy(objectCount = objectCount, objectSamples = objectSamples,
                    note = "Запись объекта указывает за пределы data section; дальнейший разбор остановлен.")
            }
            val classId = typeClassIds.getOrNull(typeIndex)
            if (classId != null) {
                objectTypeCounts[classId] = (objectTypeCounts[classId] ?: 0L) + 1L
                val samples = objectTypePathSamples.getOrPut(classId) { mutableListOf() }
                if (samples.size < 10) samples.add(pathId)
            }
            val summary = UnityObjectSummary(pathId, relativeStart, byteSize, typeIndex, classId)
            if (objectSamples.size < MAX_OBJECT_SAMPLES) objectSamples.add(summary)
            if (classId == 115 && monoScriptObjects.size < MAX_MONOSCRIPT_OBJECT_SAMPLES) {
                monoScriptObjects.add(summary)
            }
        }

        val objectTypeSummaries = objectTypeCounts.entries
            .sortedByDescending { it.value }
            .map { (classId, count) -> UnityObjectTypeSummary(classId, count, objectTypePathSamples[classId].orEmpty()) }

        val scriptCount = cursor.readU32()
            ?: return partial.copy(objectCount = objectCount, note = "Не удалось дочитать metadata до списка MonoScript.")
        if (scriptCount > MAX_AUX_COUNT) {
            return partial.copy(objectCount = objectCount, note = "Число ссылок MonoScript превышает безопасный лимит.")
        }
        val scriptTypeReferences = ArrayList<UnityScriptTypeReference>(minOf(scriptCount, MAX_SCRIPT_TYPE_SAMPLES.toLong()).toInt())
        repeat(scriptCount.toInt()) {
            val fileId = cursor.readI32()
            if (fileId == null || !cursor.align4()) {
                return partial.copy(objectCount = objectCount, scriptTypeCount = scriptCount, objectSamples = objectSamples,
                    note = "Список MonoScript обрезан.")
            }
            val pathId = cursor.readU64()
            if (pathId == null) {
                return partial.copy(objectCount = objectCount, scriptTypeCount = scriptCount, objectSamples = objectSamples,
                    note = "Список MonoScript обрезан.")
            }
            if (scriptTypeReferences.size < MAX_SCRIPT_TYPE_SAMPLES) {
                scriptTypeReferences.add(UnityScriptTypeReference(fileId, pathId))
            }
        }

        val externalCount = cursor.readU32()
            ?: return partial.copy(
                objectCount = objectCount, scriptTypeCount = scriptCount,
                note = "Не удалось дочитать metadata до списка внешних файлов."
            )
        if (externalCount > MAX_AUX_COUNT) {
            return partial.copy(objectCount = objectCount, scriptTypeCount = scriptCount,
                note = "Число внешних ссылок превышает безопасный лимит.")
        }
        val externalReferences = ArrayList<UnityExternalReference>(minOf(externalCount, MAX_EXTERNAL_REFERENCE_SAMPLES.toLong()).toInt())
        repeat(externalCount.toInt()) { index ->
            if (cursor.readCString(4096) == null || !cursor.skip(16)) {
                return partial.copy(objectCount = objectCount, scriptTypeCount = scriptCount,
                    externalReferenceCount = externalCount, objectSamples = objectSamples,
                    scriptTypeReferences = scriptTypeReferences, note = "Список внешних ссылок обрезан.")
            }
            val referenceType = cursor.readI32()
            val path = cursor.readCString(4096)
            if (referenceType == null || path == null) {
                return partial.copy(objectCount = objectCount, scriptTypeCount = scriptCount,
                    externalReferenceCount = externalCount, objectSamples = objectSamples,
                    scriptTypeReferences = scriptTypeReferences, note = "Список внешних ссылок обрезан.")
            }
            if (externalReferences.size < MAX_EXTERNAL_REFERENCE_SAMPLES) {
                externalReferences.add(UnityExternalReference(index + 1, path, referenceType))
            }
        }

        val referenceTypeCount = if (version >= 20L) cursor.readU32()
            ?: return partial.copy(objectCount = objectCount, scriptTypeCount = scriptCount,
                externalReferenceCount = externalCount, objectSamples = objectSamples,
                scriptTypeReferences = scriptTypeReferences, externalReferences = externalReferences,
                note = "Не удалось прочитать число SerializeReference-типов.") else null
        if (referenceTypeCount != null && referenceTypeCount > MAX_AUX_COUNT) {
            return partial.copy(objectCount = objectCount, scriptTypeCount = scriptCount,
                externalReferenceCount = externalCount, objectSamples = objectSamples,
                scriptTypeReferences = scriptTypeReferences, externalReferences = externalReferences,
                serializedReferenceTypeCount = referenceTypeCount,
                note = "Число SerializeReference-типов превышает безопасный лимит.")
        }
        val serializedReferenceTypes = ArrayList<UnitySerializedTypeSummary>(
            minOf(referenceTypeCount ?: 0L, MAX_SERIALIZED_REFERENCE_TYPE_SAMPLES.toLong()).toInt()
        )
        if (referenceTypeCount != null) {
            repeat(referenceTypeCount.toInt()) {
                val refType = readTypeEntry(cursor, version, typeTreeEnabled, isReferenceType = true)
                    ?: return partial.copy(objectCount = objectCount, scriptTypeCount = scriptCount,
                        externalReferenceCount = externalCount, objectSamples = objectSamples,
                        scriptTypeReferences = scriptTypeReferences, externalReferences = externalReferences,
                        serializedReferenceTypeCount = referenceTypeCount,
                        serializedReferenceTypes = serializedReferenceTypes,
                        note = "Таблица SerializeReference type entries обрезана или повреждена.")
                if (serializedReferenceTypes.size < MAX_SERIALIZED_REFERENCE_TYPE_SAMPLES) {
                    serializedReferenceTypes.add(UnitySerializedTypeSummary(
                        refType.classId, refType.scriptTypeIndex, refType.scriptId,
                        refType.className, refType.namespace, refType.assemblyName
                    ))
                }
            }
        }
        val sharedSubtreeCount = if (version >= 26L) cursor.readU32()
            ?: return partial.copy(objectCount = objectCount, scriptTypeCount = scriptCount,
                externalReferenceCount = externalCount, objectSamples = objectSamples,
                scriptTypeReferences = scriptTypeReferences, externalReferences = externalReferences,
                serializedReferenceTypeCount = referenceTypeCount,
                serializedReferenceTypes = serializedReferenceTypes,
                note = "Не удалось прочитать число shared TypeTree subtrees.") else null
        if (sharedSubtreeCount != null && sharedSubtreeCount > MAX_AUX_COUNT) {
            return partial.copy(objectCount = objectCount, scriptTypeCount = scriptCount,
                externalReferenceCount = externalCount, objectSamples = objectSamples,
                scriptTypeReferences = scriptTypeReferences, externalReferences = externalReferences,
                serializedReferenceTypeCount = referenceTypeCount,
                serializedReferenceTypes = serializedReferenceTypes,
                sharedSubtreeCount = sharedSubtreeCount,
                note = "Число shared TypeTree subtrees превышает безопасный лимит.")
        }

        val scriptTypeBindings = buildScriptTypeBindings(
            serializedTypes, scriptTypeReferences, externalReferences, monoScriptObjects
        )

        return partial.copy(
            objectCount = objectCount,
            scriptTypeCount = scriptCount,
            externalReferenceCount = externalCount,
            objectSamples = objectSamples,
            objectTypeCounts = objectTypeSummaries,
            monoScriptObjects = monoScriptObjects,
            typeTrees = typeTrees,
            serializedTypes = serializedTypes,
            serializedReferenceTypeCount = referenceTypeCount,
            serializedReferenceTypes = serializedReferenceTypes,
            sharedSubtreeCount = sharedSubtreeCount,
            scriptTypeReferences = scriptTypeReferences,
            externalReferences = externalReferences,
            scriptTypeBindings = scriptTypeBindings,
            note = if (version >= 26L)
                "Прочитаны таблицы объектов, MonoScript-ссылок, внешних файлов и SerializeReference types; shared TypeTree subtree payloads пока не разбираются."
            else "Прочитаны таблицы объектов, MonoScript-ссылок, внешних файлов и SerializeReference types."
        )
    }

    private fun buildScriptTypeBindings(
        serializedTypes: List<UnitySerializedTypeSummary>,
        scriptReferences: List<UnityScriptTypeReference>,
        externalReferences: List<UnityExternalReference>,
        monoScriptObjects: List<UnityObjectSummary>
    ): List<UnityScriptTypeBinding> {
        return serializedTypes.mapNotNull { type ->
            if (type.scriptTypeIndex < 0) return@mapNotNull null
            val reference = scriptReferences.getOrNull(type.scriptTypeIndex)
            if (reference == null) {
                return@mapNotNull UnityScriptTypeBinding(
                    classId = type.classId,
                    scriptTypeIndex = type.scriptTypeIndex,
                    scriptId = type.scriptId,
                    fileId = null,
                    pathId = null,
                    externalPath = null,
                    targetObjectClassId = null,
                    objectByteStart = null,
                    objectByteSize = null,
                    resolution = "script-reference-not-in-sample"
                )
            }
            val localObject = if (reference.fileId == 0) {
                monoScriptObjects.firstOrNull { it.pathId == reference.pathId }
            } else null
            val externalPath = if (reference.fileId > 0) {
                externalReferences.firstOrNull { it.fileId == reference.fileId }?.path
            } else null
            val resolution = when {
                reference.fileId < 0 -> "invalid-file-id"
                reference.fileId == 0 && localObject != null -> "local-monoscript-object-found"
                reference.fileId == 0 -> "local-target-not-in-monoscript-sample"
                externalPath != null -> "external-file-path-known"
                else -> "external-file-not-in-sample"
            }
            UnityScriptTypeBinding(
                classId = type.classId,
                scriptTypeIndex = type.scriptTypeIndex,
                scriptId = type.scriptId,
                fileId = reference.fileId,
                pathId = reference.pathId,
                externalPath = externalPath,
                targetObjectClassId = localObject?.classId,
                objectByteStart = localObject?.byteStart,
                objectByteSize = localObject?.byteSize,
                resolution = resolution
            )
        }
    }

    private data class ParsedTypeEntry(
        val classId: Int,
        val scriptTypeIndex: Int,
        val scriptId: String?,
        val typeTree: UnityTypeTreeSummary?,
        val className: String? = null,
        val namespace: String? = null,
        val assemblyName: String? = null
    )

    private fun readTypeEntry(
        cursor: Cursor,
        version: Long,
        typeTreeEnabled: Boolean,
        isReferenceType: Boolean = false
    ): ParsedTypeEntry? {
        val classId = cursor.readI32() ?: return null
        if (version >= 16L && cursor.readU8() == null) return null
        val scriptTypeIndex = if (version >= 17L) cursor.readI16() ?: return null else -1
        var scriptId: String? = null
        // Supported SerializedFiles (v19+) store scriptID for undefined types,
        // MonoBehaviour, and any type backed by a MonoScript reference.
        if (classId == -1 || classId == 114 || scriptTypeIndex >= 0) {
            val scriptIdBytes = cursor.readBytes(16) ?: return null
            scriptId = scriptIdBytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        }
        if (!cursor.skip(16)) return null // old type hash

        val tree: UnityTypeTreeSummary? = if (!typeTreeEnabled) {
            null
        } else if (version >= 23L) {
            if (!cursor.skip(16)) return null // TypeTree content hash
            val treeSize = cursor.readU32() ?: return null
            if (treeSize > MAX_TYPE_TREE_BYTES.toLong()) return null
            if (treeSize < 8L) {
                if (!cursor.skip(treeSize)) return null
                null
            } else {
                val blob = cursor.readBytes(treeSize.toInt()) ?: return null
                parseTypeTreeBlob(blob, cursor.isLittleEndian(), classId) ?: return null
            }
        } else {
            val nodeCount = cursor.readU32() ?: return null
            val stringSize = cursor.readU32() ?: return null
            if (nodeCount > MAX_TYPE_TREE_NODES || stringSize > MAX_TYPE_TREE_BYTES) return null
            val nodeBytesCount = nodeCount * TYPE_TREE_NODE_SIZE
            if (nodeBytesCount / TYPE_TREE_NODE_SIZE != nodeCount ||
                nodeBytesCount + stringSize > MAX_TYPE_TREE_BYTES) return null
            val nodeBytes = cursor.readBytes(nodeBytesCount.toInt()) ?: return null
            val strings = cursor.readBytes(stringSize.toInt()) ?: return null
            parseTypeTreeNodes(nodeBytes, strings, nodeCount, cursor.isLittleEndian(), classId) ?: return null
        }

        var className: String? = null
        var namespace: String? = null
        var assemblyName: String? = null
        if (version >= 21L) {
            if (isReferenceType) {
                className = cursor.readCString(4096) ?: return null
                namespace = cursor.readCString(4096) ?: return null
                assemblyName = cursor.readCString(4096) ?: return null
            } else {
                val dependencyCount = cursor.readU32() ?: return null
                if (dependencyCount > MAX_AUX_COUNT || !cursor.skip(dependencyCount * 4L)) return null
            }
        }
        return ParsedTypeEntry(classId, scriptTypeIndex, scriptId, tree, className, namespace, assemblyName)
    }

    private fun parseTypeTreeBlob(bytes: ByteArray, little: Boolean, classId: Int): UnityTypeTreeSummary? {
        if (bytes.size < 16 || bytes[0] != 'm'.code.toByte() || bytes[1] != 'h'.code.toByte() ||
            bytes[2] != 't'.code.toByte() || bytes[3] != 't'.code.toByte()) return null
        val info = Cursor(bytes, 4, little)
        if (info.readI32() == null) return null // TypeTree blob format version
        val nodeCount = info.readU32() ?: return null
        val stringSize = info.readU32() ?: return null
        if (nodeCount > MAX_TYPE_TREE_NODES || stringSize > MAX_TYPE_TREE_BYTES) return null
        val nodeBytesCount = nodeCount * TYPE_TREE_NODE_SIZE
        if (nodeBytesCount / TYPE_TREE_NODE_SIZE != nodeCount ||
            nodeBytesCount + stringSize > MAX_TYPE_TREE_BYTES) return null
        val nodeBytes = info.readBytes(nodeBytesCount.toInt()) ?: return null
        val strings = info.readBytes(stringSize.toInt()) ?: return null
        return parseTypeTreeNodes(nodeBytes, strings, nodeCount, little, classId)
    }

    private fun parseTypeTreeNodes(
        nodeBytes: ByteArray,
        stringBuffer: ByteArray,
        nodeCount: Long,
        little: Boolean,
        classId: Int
    ): UnityTypeTreeSummary? {
        if (nodeCount == 0L) return UnityTypeTreeSummary(classId, 0, null, null, emptyList())
        val nodes = Cursor(nodeBytes, 0, little)
        var rootType: String? = null
        var rootName: String? = null
        val fields = ArrayList<String>()
        val nodeSamples = ArrayList<UnityTypeTreeNodeSummary>(minOf(nodeCount, MAX_TYPE_TREE_NODE_SAMPLES.toLong()).toInt())
        val retainDecoderNodes = classId == 1 || classId == 4 || classId == 114
        val decoderNodes = if (retainDecoderNodes && nodeCount <= MAX_DECODER_TYPE_TREE_NODES) {
            ArrayList<UnityTypeTreeNodeSummary>(nodeCount.toInt())
        } else null
        repeat(nodeCount.toInt()) { index ->
            if (nodes.readI16() == null) return null
            val level = nodes.readU8() ?: return null
            val typeFlags = nodes.readU8() ?: return null
            val typeOffset = nodes.readU32() ?: return null
            val nameOffset = nodes.readU32() ?: return null
            val byteSize = nodes.readI32() ?: return null
            if (nodes.readI32() == null) return null // node index
            val metaFlags = nodes.readI32() ?: return null
            if (nodes.readU64() == null) return null // reference type hash
            val typeName = readTreeString(stringBuffer, typeOffset)
            val fieldName = readTreeString(stringBuffer, nameOffset)
            val node = UnityTypeTreeNodeSummary(level, typeName, fieldName, byteSize, typeFlags, metaFlags)
            if (nodeSamples.size < MAX_TYPE_TREE_NODE_SAMPLES) nodeSamples.add(node)
            decoderNodes?.add(node)
            if (index == 0) {
                rootType = typeName
                rootName = fieldName
            } else if (level > 0 && !fieldName.isNullOrBlank() &&
                fields.size < MAX_TYPE_TREE_FIELDS && fieldName !in fields) {
                fields.add(fieldName)
            }
        }
        val completeNodes = decoderNodes.orEmpty()
        return UnityTypeTreeSummary(
            classId, nodeCount, rootType, rootName, fields, nodeSamples,
            decoderNodes = completeNodes,
            completeForDecoding = retainDecoderNodes && completeNodes.size.toLong() == nodeCount
        )
    }

    private fun readTreeString(buffer: ByteArray, rawOffset: Long): String? {
        // Bit 31 selects the engine's built-in CommonString table; otherwise
        // the offset points into the file-local string buffer.
        if ((rawOffset and 0x80000000L) != 0L) {
            val commonOffset = (rawOffset and 0x7fffffffL).toInt()
            return COMMON_TYPE_TREE_STRINGS[commonOffset]
        }
        if (rawOffset > Int.MAX_VALUE.toLong()) return null
        val offset = rawOffset.toInt()
        if (offset < 0 || offset >= buffer.size) return null
        var end = offset
        val limit = minOf(buffer.size, offset + 256)
        while (end < limit && buffer[end].toInt() != 0) end++
        if (end == limit) return null
        return String(buffer, offset, end - offset, Charsets.UTF_8)
    }

    private class Cursor(
        private val bytes: ByteArray,
        private var position: Int,
        private val little: Boolean
    ) {
        fun isLittleEndian(): Boolean = little

        fun readBytes(count: Int): ByteArray? {
            if (count < 0 || position < 0 || position.toLong() + count > bytes.size) return null
            return bytes.copyOfRange(position, position + count).also { position += count }
        }

        fun readU8(): Int? {
            if (position >= bytes.size) return null
            return bytes[position++].toInt() and 0xff
        }

        fun readI16(): Int? {
            val value = readUnsigned(2) ?: return null
            return value.toInt().toShort().toInt()
        }

        fun readI32(): Int? {
            val value = readUnsigned(4) ?: return null
            return value.toInt()
        }

        fun readU32(): Long? = readUnsigned(4)

        fun readU64(): Long? {
            val value = readUnsigned(8) ?: return null
            return value
        }

        fun readCString(maximum: Int): String? {
            val endLimit = minOf(bytes.size, position + maximum + 1)
            var end = position
            while (end < endLimit && bytes[end].toInt() != 0) end++
            if (end >= endLimit) return null
            val result = String(bytes, position, end - position, Charsets.US_ASCII)
            position = end + 1
            return result
        }

        fun skip(count: Long): Boolean {
            if (count < 0L || count > Int.MAX_VALUE.toLong()) return false
            val next = position.toLong() + count
            if (next > bytes.size) return false
            position = next.toInt()
            return true
        }

        fun align4(): Boolean {
            val relative = position - (if (position >= 0) 0 else 0)
            val aligned = (relative + 3) and -4
            if (aligned > bytes.size) return false
            position = aligned
            return true
        }

        private fun readUnsigned(count: Int): Long? {
            if (position < 0 || position + count > bytes.size) return null
            var value = 0L
            if (little) {
                for (i in count - 1 downTo 0) value = (value shl 8) or (bytes[position + i].toLong() and 0xff)
            } else {
                for (i in 0 until count) value = (value shl 8) or (bytes[position + i].toLong() and 0xff)
            }
            position += count
            return value
        }
    }
}
