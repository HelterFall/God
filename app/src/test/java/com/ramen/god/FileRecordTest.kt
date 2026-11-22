package com.ramen.god

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRecordTest {
    @Test
    fun relativePathIdentityIsStableAcrossRepeatedScans() {
        val first = FileRecord.create(
            path = "Data/Resources/sharedassets0.assets",
            name = "sharedassets0.assets",
            directory = false,
            size = 1024,
            parentId = "file:parent",
            providerKey = null
        )
        val second = FileRecord.create(
            path = "Data/Resources/sharedassets0.assets",
            name = "sharedassets0.assets",
            directory = false,
            size = 1024,
            parentId = "file:parent",
            providerKey = null
        )

        assertEquals(first.id, second.id)
        assertTrue(first.id.startsWith("file:"))
        assertEquals(69, first.id.length)
        assertEquals(FileIdentitySource.RELATIVE_PATH, first.identitySource)
        assertEquals(FileInventoryStatus.DISCOVERED, first.inventoryStatus)
    }

    @Test
    fun providerIdentitySurvivesRenameWithoutExportingProviderKey() {
        val providerKey = "content://provider/tree/root/document/42"
        val beforeRename = FileRecord.create(
            path = "Data/old-name.assets",
            name = "old-name.assets",
            directory = false,
            size = 512,
            parentId = null,
            providerKey = providerKey
        )
        val afterRename = FileRecord.create(
            path = "Data/new-name.assets",
            name = "new-name.assets",
            directory = false,
            size = 512,
            parentId = null,
            providerKey = providerKey
        )

        assertEquals(beforeRename.id, afterRename.id)
        assertEquals(FileIdentitySource.PROVIDER_KEY, beforeRename.identitySource)
        assertTrue(beforeRename.toJsonObject().toString().contains("provider-key"))
        assertTrue(!beforeRename.toJsonObject().toString().contains(providerKey))
    }

    @Test
    fun duplicateRelativePathsReceiveDistinctIdsBySiblingOccurrence() {
        val first = FileRecord.create(
            path = "Data/shared.assets",
            name = "shared.assets",
            directory = false,
            size = 100,
            parentId = null,
            providerKey = null,
            siblingOccurrence = 0
        )
        val second = FileRecord.create(
            path = "Data/shared.assets",
            name = "shared.assets",
            directory = false,
            size = 200,
            parentId = null,
            providerKey = null,
            siblingOccurrence = 1
        )

        assertNotEquals(first.id, second.id)
        assertEquals("Data/shared.assets", first.path)
        assertEquals(first.path, second.path)
    }
}
