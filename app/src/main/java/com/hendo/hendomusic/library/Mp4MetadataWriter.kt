package com.hendo.hendomusic.library

import org.mp4parser.Box
import org.mp4parser.IsoFile
import org.mp4parser.PropertyBoxParserImpl
import org.mp4parser.boxes.apple.AppleAlbumBox
import org.mp4parser.boxes.apple.AppleArtist2Box
import org.mp4parser.boxes.apple.AppleArtistBox
import org.mp4parser.boxes.apple.AppleItemListBox
import org.mp4parser.boxes.apple.AppleNameBox
import org.mp4parser.boxes.iso14496.part12.HandlerBox
import org.mp4parser.boxes.iso14496.part12.MetaBox
import org.mp4parser.boxes.iso14496.part12.MovieBox
import org.mp4parser.boxes.iso14496.part12.UserDataBox
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/** Adds standard iTunes text atoms to framework-created M4A files. */
internal fun writeFrameworkM4aMetadata(
    file: File,
    title: String,
    artist: String,
    album: String,
    albumArtist: String?,
) {
    val rewritten = File.createTempFile("hendo-mp4-tagged-", ".m4a", file.parentFile)
    try {
        val mappings = Properties().apply {
            val stream = PropertyBoxParserImpl::class.java.classLoader
                ?.getResourceAsStream("isoparser2-default.properties")
                ?: error("MP4 atom 매핑을 불러오지 못했습니다.")
            stream.use(::load)
        }
        FileInputStream(file).channel.use { input ->
            IsoFile(input, PropertyBoxParserImpl(mappings)).use { iso ->
                val movie = iso.getBoxes(MovieBox::class.java).firstOrNull()
                    ?: error("M4A movie atom을 찾지 못했습니다.")
                val userData = movie.boxes.filterIsInstance<UserDataBox>().firstOrNull()
                    ?: UserDataBox().also(movie::addBox)
                val metadata = userData.boxes.filterIsInstance<MetaBox>().firstOrNull()
                    ?: MetaBox().also { box ->
                        box.addBox(HandlerBox().apply { handlerType = "mdir" })
                        userData.addBox(box)
                    }
                val items = metadata.boxes.filterIsInstance<AppleItemListBox>().firstOrNull()
                    ?: AppleItemListBox().also(metadata::addBox)

                items.replaceTextAtom(AppleNameBox.TYPE, AppleNameBox().apply { value = title })
                items.replaceTextAtom("©ART", AppleArtistBox().apply { value = artist })
                items.replaceTextAtom("©alb", AppleAlbumBox().apply { value = album })
                items.boxes.removeAll { it.type == "aART" }
                if (!albumArtist.isNullOrBlank()) {
                    items.addBox(AppleArtist2Box().apply { value = albumArtist })
                }

                FileOutputStream(rewritten).channel.use { output -> iso.getBox(output) }
            }
        }
        rewritten.copyTo(file, overwrite = true)
    } finally {
        rewritten.delete()
    }
}

private fun AppleItemListBox.replaceTextAtom(type: String, replacement: Box) {
    boxes.removeAll { it.type == type }
    addBox(replacement)
}
