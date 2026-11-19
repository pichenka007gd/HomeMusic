package com.eddyizm.tempus.homemusic

import androidx.core.content.FileProvider

/** Own subclass so the manifest entry never clashes with a library's FileProvider. */
class HubFileProvider : FileProvider()
