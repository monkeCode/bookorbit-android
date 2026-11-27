package com.bookorbit.feature.reader

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.bookorbit.core.storage.LocalRef
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Runs the shipped bridge/renderer, not a replacement position detector. No sessions or server. */
class ReaderPositionRestoreTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun initialRestoreAndNarrationFollowingAreNotUserProgressButPageTurnsAre() {
        val file = File(compose.activity.cacheDir, "position-restore-${UUID.randomUUID()}.epub")
        fun chapter(label: String) = """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$label</title></head><body>""" +
            (1..90).joinToString("") { "<p id=\"p$it\">$label paragraph $it. ${"Synthetic already-readable words. ".repeat(20)}</p>" } + "</body></html>"
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, value: String) { zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("OPS/package.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">synthetic-position</dc:identifier><dc:title>Synthetic positions</dc:title><dc:language>en</dc:language></metadata><manifest><item id="ch1" href="one.xhtml" media-type="application/xhtml+xml"/><item id="ch2" href="two.xhtml" media-type="application/xhtml+xml" media-overlay="sm"/><item id="sm" href="two.smil" media-type="application/smil+xml"/><item id="audio" href="audio.wav" media-type="audio/wav"/></manifest><spine><itemref idref="ch1"/><itemref idref="ch2"/></spine></package>""")
            entry("OPS/one.xhtml", chapter("First")); entry("OPS/two.xhtml", chapter("Second"))
            entry("OPS/two.smil", """<smil xmlns="http://www.w3.org/ns/SMIL"><body><seq><par><text src="two.xhtml#p1"/><audio src="audio.wav" clipBegin="0s" clipEnd="10s"/></par></seq></body></smil>""")
            entry("OPS/audio.wav", "not played by this test")
        }
        try {
            val controller = ReaderController()
            val ready = CountDownLatch(1)
            val events = java.util.concurrent.CopyOnWriteArrayList<ReaderEvent>()
            controller.listener = { event -> events += event; if (event is ReaderEvent.Ready) ready.countDown() }
            compose.setContent { ReaderWebView(controller, onRendererGone = { fail("Synthetic renderer stopped") }) }
            assertTrue(ready.await(20, TimeUnit.SECONDS))
            val cfi = "epubcfi(/6/4!/4/2/1:0)"
            controller.open(OpenParams(LocalRef.PlainFile(file), "epub", cfi, null, ReaderSettings()), compose.activity)
            compose.waitUntil(20_000) { events.filterIsInstance<ReaderEvent.Relocate>().any { it.sectionIndex == 1 } }
            assertTrue(events.filterIsInstance<ReaderEvent.Relocate>().none { it.movedByUser })
            assertTrue(events.filterIsInstance<ReaderEvent.Error>().isEmpty())
            val count = events.size
            compose.runOnIdle { controller.next() }
            compose.waitUntil(10_000) { events.drop(count).filterIsInstance<ReaderEvent.Relocate>().any { it.movedByUser } }
            compose.waitUntil(10_000) { events.filterIsInstance<ReaderEvent.ReadAlong>().any { it.available } }
            val beforeNarration = events.size
            compose.runOnIdle { controller.nativeReadAlong(ReadAlongPosition(1, "OPS/two.xhtml#p1", "OPS/audio.wav", 0.0)) }
            compose.waitUntil(10_000) { events.drop(beforeNarration).filterIsInstance<ReaderEvent.Relocate>().isNotEmpty() }
            assertTrue(events.drop(beforeNarration).filterIsInstance<ReaderEvent.Relocate>().none { it.movedByUser })
            compose.runOnIdle { controller.restorePosition(cfi, 0.5, "exact-epoch") }
            compose.waitUntil(10_000) { events.filterIsInstance<ReaderEvent.PositionRestored>().any { it.ticket == "exact-epoch" && it.success } }
            compose.runOnIdle { controller.restorePosition("not-an-exact-cfi", 0.0, "invalid-epoch") }
            compose.waitUntil(10_000) { events.filterIsInstance<ReaderEvent.PositionRestored>().any { it.ticket == "invalid-epoch" && !it.success } }
        } finally { file.delete() }
    }
}
