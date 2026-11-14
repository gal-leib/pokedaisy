package com.pokedaisy.app.ios

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import com.pokedaisy.app.DocsApi
import com.pokedaisy.app.companion.data.RomArt
import com.pokedaisy.app.companion.data.isFile
import com.pokedaisy.app.companion.data.mkdirs
import com.pokedaisy.app.companion.data.readBytes
import com.pokedaisy.app.companion.data.readText
import com.pokedaisy.app.companion.data.writeBytes
import com.pokedaisy.app.companion.data.writeTextAtomically
import com.pokedaisy.app.companion.ui.DeviceDates
import com.pokedaisy.app.companion.ui.DocsSource
import com.pokedaisy.app.companion.ui.DocsSources
import com.pokedaisy.app.companion.ui.PictureCodec
import com.pokedaisy.app.companion.ui.theme.FontSource
import com.pokedaisy.app.companion.ui.theme.ThemeStore
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.dataWithContentsOfURL
import platform.posix.memcpy

/**
 * The iPad app's hosts for the shared screens, the iOS side of what PokeDaisyApp sets on Android: fonts from the app
 * bundle, pictures through Skia, THEME / DARK MODE in NSUserDefaults, the manual from the website kept in [filesDir],
 * dates the device's way. Cheat codes stay unchecked until the iOS core has mGBA's parser (CheatParser left unset).
 */
object IosHosts {
    fun install(filesDir: Path) {
        filesDir.mkdirs()
        RomArt.filesDir = filesDir
        ThemeStore.current = DefaultsThemeStore
        DocsSources.current = WebDocsSource(filesDir / "docs")
        FontSource.current = BundleFonts
        PictureCodec.current = SkiaPictures
        DeviceDates.current = FoundationDates
    }
}

/** THEME and DARK MODE, under Prefs' keys and defaults (8 = POKéDAISY, the old 0-7 read as it; 0 = dark mode OFF). */
private object DefaultsThemeStore : ThemeStore {
    private val d get() = NSUserDefaults.standardUserDefaults
    private fun int(key: String, default: Int) = if (d.objectForKey(key) == null) default else d.integerForKey(key).toInt()

    override var appTheme: Int
        get() = int("app_theme", 8).let { if (it in 0..7) 8 else it }
        set(v) = d.setInteger(v.toLong(), "app_theme")
    override var themeBeforeSeasonal: Int
        get() = int("theme_before_seasonal", 8).let { if (it in 0..7) 8 else it }
        set(v) = d.setInteger(v.toLong(), "theme_before_seasonal")
    override var darkMode: Int
        get() = int("dark_mode", 0)
        set(v) = d.setInteger(v.toLong(), "dark_mode")
}

/** A file's bytes from the URL (a file:// or https:// one), or null. Blocking. */
@OptIn(ExperimentalForeignApi::class)
private fun bytesOf(url: NSURL): ByteArray? {
    val data: NSData = NSData.dataWithContentsOfURL(url) ?: return null
    val n = data.length.toInt()
    if (n == 0) return ByteArray(0)
    return ByteArray(n).also { out -> out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) } }
}

/** The fonts the Android app keeps in its assets, copied into the app bundle's fonts folder. */
private object BundleFonts : FontSource {
    private fun file(name: String): ByteArray? =
        NSBundle.mainBundle.pathForResource(name, "ttf", "fonts")?.let { bytesOf(NSURL.fileURLWithPath(it)) }

    private fun font(name: String, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal) =
        file(name)?.let { Font(name, it, weight, style) }

    // Pixel Operator first; Japanese and Korean after it, so a glyph it lacks falls through to them, then the system's.
    private val pixel by lazy {
        listOfNotNull(font("PixelOperator"), font("PixelMplusJP"), font("GalmuriKO")).let { if (it.isEmpty()) FontFamily.Default else FontFamily(it) }
    }
    private val reading by lazy {
        listOfNotNull(
            font("AtkinsonHyperlegible-Regular"),
            font("AtkinsonHyperlegible-Bold", FontWeight.Bold),
            font("AtkinsonHyperlegible-Italic", style = FontStyle.Italic),
        ).let { if (it.isEmpty()) FontFamily.Default else FontFamily(it) }
    }
    private val games = HashMap<String, FontFamily?>()

    override fun pixel(): FontFamily = pixel
    override fun reading(): FontFamily = reading
    override fun game(file: Path): FontFamily? {
        val stamp = FileSystem.SYSTEM.metadataOrNull(file)?.let { "$file:${it.size}:${it.lastModifiedAtMillis}" } ?: return null
        return games.getOrPut(stamp) {
            runCatching { file.readBytes() }.getOrNull()
                ?.let { FontFamily(listOfNotNull(Font(stamp, it), font("PixelOperator"), font("PixelMplusJP"), font("GalmuriKO"))) }
        }
    }
}

/** Pictures through Skia, Compose's own renderer on iOS. */
private object SkiaPictures : PictureCodec {
    override fun fromArgb(argb: IntArray, width: Int, height: Int): ImageBitmap {
        // ARGB ints as BGRA bytes: Skia's N32 on a little-endian device.
        val bytes = ByteArray(width * height * 4)
        for (i in argb.indices) {
            val c = argb[i]
            bytes[i * 4] = c.toByte()
            bytes[i * 4 + 1] = (c shr 8).toByte()
            bytes[i * 4 + 2] = (c shr 16).toByte()
            bytes[i * 4 + 3] = (c ushr 24).toByte()
        }
        return Image.makeRaster(ImageInfo.makeN32(width, height, ColorAlphaType.UNPREMUL), bytes, width * 4).toComposeImageBitmap()
    }

    override fun decode(bytes: ByteArray): ImageBitmap? = runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
}

/** The manual from the website's docs API, kept in [dir] for offline reading (DocsStore's layout on Android). */
private class WebDocsSource(private val dir: Path) : DocsSource {
    private val manualFile = dir / "docs.json"

    override fun cached(): DocsApi.Manual? =
        runCatching { DocsApi.parse(manualFile.readText()) }.getOrNull()

    override fun refresh(): DocsApi.Refresh {
        val url = NSURL.URLWithString(DocsApi.URL) ?: return DocsApi.Refresh.Failed(offline = false)
        val json = bytesOf(url)?.decodeToString() ?: return DocsApi.Refresh.Failed(offline = true)
        val manual = runCatching { DocsApi.parse(json) }.getOrNull() ?: return DocsApi.Refresh.Failed(offline = false)
        if (manualFile.isFile && runCatching { manualFile.readText() }.getOrNull() == json) {
            return DocsApi.Refresh.Unchanged
        }
        dir.mkdirs()
        manualFile.writeTextAtomically(json)
        return DocsApi.Refresh.Updated(manual)
    }

    override fun image(manual: DocsApi.Manual, image: DocsApi.Block.Image): ImageBitmap? = runCatching {
        val file = dir / "img" / ((image.src + "@" + image.version).encodeUtf8().sha1().hex().take(20) + ".png")
        if (!file.isFile) {
            val bytes = NSURL.URLWithString(manual.site + image.src)?.let(::bytesOf) ?: return@runCatching null
            file.writeBytes(bytes)
        }
        SkiaPictures.decode(file.readBytes())
    }.getOrNull()
}

/** Dates and times the device's way (STATES' save times). */
@OptIn(BetaInteropApi::class)
private object FoundationDates : DeviceDates {
    private val format = NSDateFormatter().apply {
        dateStyle = NSDateFormatterShortStyle
        timeStyle = NSDateFormatterShortStyle
    }

    override fun shortDateTime(epochMillis: Long): String =
        format.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))
}

/** The app's files folder: Application Support / PokeDaisy. */
internal fun appFilesDir(): Path {
    val base = platform.Foundation.NSSearchPathForDirectoriesInDomains(
        platform.Foundation.NSApplicationSupportDirectory, platform.Foundation.NSUserDomainMask, true,
    ).firstOrNull() as? String ?: platform.Foundation.NSTemporaryDirectory()
    return base.toPath() / "PokeDaisy"
}
