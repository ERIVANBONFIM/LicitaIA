package com.licitaia.core.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestoredAttachmentNamingTest {
    private val pdfHead = "%PDF-1.7\n".toByteArray()
    private val uuid = "0b2d8b1e-2b6e-4a4e-9c0d-7a9e2f1b3c4d"

    @Test fun originalNameExtensionWins() {
        assertEquals("$uuid.pdf", RestoredAttachmentNaming.fileName(uuid, "Certidao Negativa.PDF", "application/octet-stream", pdfHead))
        assertEquals("$uuid.docx", RestoredAttachmentNaming.fileName(uuid, "/storage/emulated/0/Download/proposta.docx", null, ByteArray(0)))
    }

    @Test fun mimeIsUsedWhenNameHasNoExtension() {
        assertEquals("$uuid.pdf", RestoredAttachmentNaming.fileName(uuid, "1234", "application/pdf", ByteArray(0)))
        assertEquals("$uuid.jpg", RestoredAttachmentNaming.fileName(uuid, null, "image/jpeg; charset=binary", ByteArray(0)))
        assertEquals("$uuid.xlsx", RestoredAttachmentNaming.fileName(uuid, null, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ByteArray(0)))
    }

    @Test fun contentSignatureIsLastResortForLegacyBackups() {
        assertEquals("$uuid.pdf", RestoredAttachmentNaming.fileName(uuid, null, null, pdfHead))
        assertEquals("$uuid.png", RestoredAttachmentNaming.fileName(uuid, null, null, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)))
        assertEquals("$uuid.jpg", RestoredAttachmentNaming.fileName(uuid, "content://media/external/12", null, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
    }

    @Test fun unknownTypeKeepsPlainUuid() {
        assertEquals(uuid, RestoredAttachmentNaming.fileName(uuid, null, null, byteArrayOf(0, 1, 2, 3)))
        assertEquals(uuid, RestoredAttachmentNaming.fileName(uuid, "content://media/external/12", "application/x-unknown", byteArrayOf(0x50, 0x4B, 3, 4)))
    }

    @Test fun suspiciousExtensionsAreIgnored() {
        assertNull(RestoredAttachmentNaming.extensionOf("arquivo."))
        assertNull(RestoredAttachmentNaming.extensionOf(".hidden"))
        assertNull(RestoredAttachmentNaming.extensionOf("a.exten$%"))
        assertNull(RestoredAttachmentNaming.extensionOf("a.muitolongaextensao"))
        assertEquals("p7s", RestoredAttachmentNaming.extensionOf("doc.pdf.p7s"))
    }

    @Test fun mimeOfLocalPathIsDerivedFromExtension() {
        assertEquals("application/pdf", RestoredAttachmentNaming.mimeOf("/data/user/0/app/files/proposals/1.pdf"))
        assertEquals("image/jpeg", RestoredAttachmentNaming.mimeOf("foto.jpeg"))
        assertNull(RestoredAttachmentNaming.mimeOf("sem-extensao"))
    }
}
