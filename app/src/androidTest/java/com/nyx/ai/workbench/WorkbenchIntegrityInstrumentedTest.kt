package com.nyx.ai.workbench

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Platform parser/URI checks only; SAF grants and recipient lifecycle need separate device acceptance. */
@RunWith(AndroidJUnit4::class)
class WorkbenchIntegrityInstrumentedTest {
    @Test fun androidManifestParserRejectsCoerciveNumericAndStringValues() {
        val valid = WorkbenchManifestCodec.encode(WorkbenchManifest("instrumented-objective", Long.MAX_VALUE, 0))
        assertEquals(Long.MAX_VALUE, WorkbenchManifestCodec.decode(valid).createdAt)
        listOf("1.9", "4294967297", "\"1\"", "1e0", "9223372036854775808").forEach { token ->
            val json = JSONObject(ArtifactFormats.utf8(valid)).put("version", "BEDROCK_NUMBER").toString()
                .replace("\"BEDROCK_NUMBER\"", token)
            assertThrows(Exception::class.java) { WorkbenchManifestCodec.decode(json.toByteArray()) }
        }
        val wrongString = JSONObject(ArtifactFormats.utf8(valid)).put("status", true).toString()
        assertThrows(Exception::class.java) { WorkbenchManifestCodec.decode(wrongString.toByteArray()) }
    }

    @Test fun saveDestinationRequiresContentSchemeAndNonblankAuthority() {
        listOf("file:///private/report.txt", "https://example.com/report.txt", "content:/report.txt", "content:///report.txt").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { AndroidArtifactHandoff.validateDestination(Uri.parse(value)) }
        }
        AndroidArtifactHandoff.validateDestination(Uri.parse("content://fixture.provider/document/report"))
        // A syntactically valid URI alone does not establish that a user granted picker authority.
    }
}
