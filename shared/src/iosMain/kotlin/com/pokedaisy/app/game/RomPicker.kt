@file:OptIn(ExperimentalForeignApi::class)

package com.pokedaisy.app.game

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeData
import platform.darwin.NSObject

/**
 * Adds games from Files (the iPad's own storage, iCloud Drive, a USB drive...): the system's document picker for
 * .gba, .gb and .gbc files, each picked file copied into the app's `Documents/roms/` (in Files too, once the app's
 * Info.plist shares its documents), where a game plays from and keeps its save beside it in `Documents/saves/` ([savePathFor]). A file of the
 * same name is replaced. [pick] presents it over [from] and hands back the copies' paths (none if cancelled).
 */
object RomPicker {
    /** The ROM files the picker offers. */
    val EXTENSIONS = listOf("gba", "gb", "gbc")

    private var delegate: NSObject? = null

    fun pick(from: UIViewController, onPicked: (List<String>) -> Unit) {
        val types = EXTENSIONS.mapNotNull { UTType.typeWithFilenameExtension(it, conformingToType = UTTypeData) }
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)
        picker.allowsMultipleSelection = true
        // The picker holds its delegate weakly: kept here until it answers.
        val d = object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                delegate = null
                onPicked(didPickDocumentsAtURLs.mapNotNull { (it as? NSURL)?.let(::keep) })
            }

            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                delegate = null
                onPicked(emptyList())
            }
        }
        delegate = d
        picker.delegate = d
        from.presentViewController(picker, animated = true, completion = null)
    }

    /** The app's folder of games. */
    val romsDir: String get() = documents("roms")

    /** Where the game at [romPath] keeps its battery save: `Documents/saves/<name>.sav`. */
    fun savePathFor(romPath: String): String {
        val name = romPath.substringAfterLast('/').substringBeforeLast('.')
        return "${documents("saves")}/$name.sav"
    }

    /** Moves the picker's copy at [url] into [romsDir]; its path there, or null if it couldn't. */
    private fun keep(url: NSURL): String? {
        val name = url.lastPathComponent ?: return null
        if (name.substringAfterLast('.', "").lowercase() !in EXTENSIONS) return null
        val fm = NSFileManager.defaultManager
        val dest = "$romsDir/$name"
        fm.removeItemAtPath(dest, error = null)
        val src = url.path ?: return null
        return if (fm.moveItemAtPath(src, toPath = dest, error = null) || fm.copyItemAtPath(src, toPath = dest, error = null)) {
            dest
        } else {
            null
        }
    }

    /** `Documents/<sub>`, made if it isn't there. */
    private fun documents(sub: String): String {
        val fm = NSFileManager.defaultManager
        val docs = (fm.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).firstOrNull() as? NSURL)?.path
            ?: error("no Documents folder")
        val dir = "$docs/$sub"
        fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return dir
    }
}
