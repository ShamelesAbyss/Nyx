package com.nyx.ai.context

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SymbolIndexTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val kotlinSource = """
        package com.nyx.demo

        import java.io.File
        import kotlinx.coroutines.flow.Flow as Stream

        /**
         * Doc mentions HiddenThing but comments are not references.
         */
        data class Widget(val id: String) : Comparable<Widget> {
            fun render(cache: Cache): String {
                val helper = Helper()
                return helper.format(id)
            }
        }

        object Registry {
            fun lookup(name: String): Widget? = null
        }

        interface Renderer {
            fun render(): String
        }

        enum class Mode { FAST, SLOW }

        fun topLevel(value: Widget): String = value.id
    """.trimIndent()

    @Test
    fun kotlinSourcesExposePackageDeclarationsImportsAndLineNumbers() {
        val index = SymbolIndex(temporaryFolder.newFolder("kotlin"))
        assertTrue(index.update("src/Demo.kt", kotlinSource))

        val file = index.indexedFile("src/Demo.kt")!!
        assertEquals("com.nyx.demo", file.packageName)
        assertEquals(
            listOf("java.io.File", "kotlinx.coroutines.flow.Flow as Stream"),
            file.imports,
        )
        assertTrue(file.definitions.any { it.name == "Widget" && it.kind == "class" && it.line == 9 })
        assertTrue(file.definitions.any { it.name == "Registry" && it.kind == "object" })
        assertTrue(file.definitions.any { it.name == "Renderer" && it.kind == "interface" })
        assertTrue(file.definitions.any { it.name == "Mode" && it.kind == "enum" })
        assertTrue(file.definitions.any { it.name == "lookup" && it.kind == "function" })
        assertTrue(file.definitions.any { it.name == "topLevel" && it.line == 26 })

        val renders = file.definitions.filter { it.name == "render" }
        assertEquals(2, renders.size)
        assertTrue(renders.any { it.line == 10 && it.signature.startsWith("fun render") && it.signature.contains("Cache") })
        assertTrue(renders.any { it.line == 21 })

        assertTrue(index.referencesTo("Widget").any { it.line == 26 })
        assertTrue(index.referencesTo("Helper").any { it.line == 11 })
        assertTrue(index.referencesTo("format").any { it.line == 12 })
        assertTrue(index.referencesTo("Stream").any { it.kind == "import" && it.line == 4 })
        assertTrue(index.referencesTo("HiddenThing").isEmpty())
    }

    @Test
    fun unchangedContentSkipsReindexing() {
        val index = SymbolIndex(temporaryFolder.newFolder("unchanged"))
        assertTrue(index.update("A.kt", "class A { fun one() {} }"))
        val first = index.indexedFile("A.kt")!!

        assertFalse(index.update("A.kt", "class A { fun one() {} }"))
        val second = index.indexedFile("A.kt")!!
        assertEquals(first.updatedAt, second.updatedAt)
        assertEquals(first.definitions, second.definitions)
        assertEquals(first.sha256, second.sha256)
    }

    @Test
    fun changedContentReplacesOnlyThatFile() {
        val index = SymbolIndex(temporaryFolder.newFolder("changed"))
        assertTrue(index.update("A.kt", "class A { fun oldName() {} }"))
        assertTrue(index.update("B.kt", "class B { fun stable() {} }"))

        assertTrue(index.update("A.kt", "class A { fun newName() {} }"))
        assertTrue(index.definitionsOf("oldName").isEmpty())
        assertEquals(1, index.definitionsOf("newName").size)
        assertEquals(1, index.definitionsOf("stable").size)
        assertEquals(listOf("A.kt", "B.kt"), index.allPaths())
    }

    @Test
    fun removalForgetsFileButRetainsOthers() {
        val index = SymbolIndex(temporaryFolder.newFolder("remove"))
        index.update("A.kt", "class A")
        index.update("B.kt", "class B")

        assertTrue(index.remove("A.kt"))
        assertFalse(index.remove("A.kt"))
        assertTrue(index.definitionsOf("A").isEmpty())
        assertEquals(listOf("B.kt"), index.allPaths())
        assertFalse(index.remove("../escape.kt"))
    }

    @Test
    fun symbolQueriesReturnDefinitionsAndReferences() {
        val index = SymbolIndex(temporaryFolder.newFolder("queries"))
        index.update("src/Demo.kt", kotlinSource)

        val matches = index.findRelevant("widget render", 10)
        assertFalse(matches.isEmpty())
        assertTrue(matches.any { it.name == "Widget" && it.kind == "class" })
        assertTrue(matches.any { it.name == "render" && it.kind == "function" })
        assertTrue(matches.zipWithNext().all { (left, right) -> left.score >= right.score })

        assertEquals(2, index.definitionsOf("render").size)
        assertTrue(index.referencesTo("Widget").isNotEmpty())
        assertTrue(index.findRelevant("", 5).isEmpty())
        assertTrue(index.findRelevant("widget", 0).isEmpty())
        assertTrue(index.definitionsOf("Missing").isEmpty())
        assertTrue(index.referencesTo("Missing").isEmpty())
    }

    @Test
    fun indexPersistsAcrossInstancesWithoutTemporaryFiles() {
        val root = temporaryFolder.newFolder("persist")
        val index = SymbolIndex(root)
        assertTrue(index.update("A.kt", "package p\nclass Alpha\nclass Beta(val alpha: Alpha)"))
        assertTrue(index.indexFile.isFile)

        val reopened = SymbolIndex(root)
        assertEquals(index.allPaths(), reopened.allPaths())
        assertEquals(index.definitionsOf("Alpha"), reopened.definitionsOf("Alpha"))
        assertEquals(index.referencesTo("Alpha"), reopened.referencesTo("Alpha"))
        assertTrue(reopened.referencesTo("Alpha").any { it.line == 3 })
        assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun corruptIndexFallsBackAndCanBeRebuilt() {
        val root = temporaryFolder.newFolder("corrupt")
        File(root, "symbol-index.json").writeText("not-json")

        val index = SymbolIndex(root)
        assertTrue(index.allPaths().isEmpty())
        assertTrue(index.update("A.kt", "class A"))
        assertEquals(listOf("A.kt"), SymbolIndex(root).allPaths())
        assertTrue(root.walkTopDown().any { it.name.contains("corrupt") })
    }

    @Test
    fun fileAndSymbolBudgetsAreEnforced() {
        val root = temporaryFolder.newFolder("caps")
        val index = SymbolIndex(root, maxFiles = 2)
        assertTrue(index.update("a.kt", "class A"))
        Thread.sleep(5)
        assertTrue(index.update("b.kt", "class B"))
        Thread.sleep(5)
        assertTrue(index.update("c.kt", "class C"))
        assertEquals(listOf("b.kt", "c.kt"), index.allPaths())

        val definitionsCapped = SymbolIndex(temporaryFolder.newFolder("definition-caps"), maxDefinitionsPerFile = 2)
        assertTrue(definitionsCapped.update("A.kt", "class A\nclass B\nclass C\nfun delta() {}"))
        assertEquals(2, definitionsCapped.indexedFile("A.kt")!!.definitions.size)

        val referencesCapped = SymbolIndex(temporaryFolder.newFolder("reference-caps"), maxReferencesPerFile = 1)
        assertTrue(referencesCapped.update("A.kt", "import java.io.File\nclass A(val file: File)"))
        assertEquals(1, referencesCapped.indexedFile("A.kt")!!.references.size)
    }

    @Test
    fun javaSourcesExposeClassesMethodsAndReferences() {
        val index = SymbolIndex(temporaryFolder.newFolder("java"))
        val source = """
            package com.nyx.demo;

            import java.util.List;

            public final class JavaWidget {
                private final String name;
                public JavaWidget(String name) { this.name = name; }
                public String render(List<String> parts) {
                    return Helper.format(name);
                }
            }
        """.trimIndent()

        assertTrue(index.update("src/JavaWidget.java", source))
        val definitions = index.indexedFile("src/JavaWidget.java")!!.definitions
        assertTrue(definitions.any { it.name == "JavaWidget" && it.kind == "class" && it.line == 5 })
        val render = definitions.single { it.name == "render" }
        assertEquals(8, render.line)
        assertTrue(render.signature.startsWith("String render"))
        assertTrue(index.referencesTo("Helper").any { it.line == 9 })
        assertTrue(index.referencesTo("List").any { it.kind == "import" && it.line == 3 })
    }

    @Test
    fun invalidPathsAreRejectedAndNestedTypesAreNotTopLevel() {
        val index = SymbolIndex(temporaryFolder.newFolder("paths"))
        rejects { index.update("../escape.kt", "class X") }
        rejects { index.update("/absolute.kt", "class X") }
        rejects { index.update("src/../escape.kt", "class X") }
        rejects { index.update("src\\windows.kt", "class X") }
        assertTrue(index.allPaths().isEmpty())

        assertTrue(index.update("Nested.kt", "class Outer { class Inner }"))
        assertTrue(index.definitionsOf("Outer").isNotEmpty())
        assertTrue(index.definitionsOf("Inner").isEmpty())
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
