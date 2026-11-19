package com.eddyizm.tempus.homemusic

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class SheetThemeTest {
    @Test
    fun the_amoled_sheet_overlay_does_not_tint_every_view_in_the_sheet() {
        // A backgroundTint in a theme reaches every view in the sheet: the ♥ toggle, drawn as its
        // background, turned black on black in the track and album menus.
        val styles = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values-night/styles.xml")).getElementsByTagName("style")
        val overlay = (0 until styles.length).map { styles.item(it) as Element }
            .single { it.getAttribute("name") == "ThemeOverlay.App.BottomSheetDialog.Amoled" }
        val items = overlay.getElementsByTagName("item")
        val names = (0 until items.length).map { (items.item(it) as Element).getAttribute("name") }

        assertTrue("colorSurface" in names)
        assertFalse("backgroundTint" in names)
        assertFalse("android:backgroundTint" in names)
    }
}
