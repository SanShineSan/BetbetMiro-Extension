package com.nomat

import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro

class Hydrax : VidHidePro() {
    override var name = "Hydrax"
    override var mainUrl = "https://playhydrax.com"
}

class NomatFileLions : VidHidePro() {
    override var name = "FileLions"
    override var mainUrl = "https://filelions.to"
}

class NomatFileMoon : Filesim() {
    override var name = "FileMoon"
    override var mainUrl = "https://filemoon.sx"
}

class NomatStreamWish : StreamWishExtractor() {
    override var name = "StreamWish"
    override var mainUrl = "https://streamwish.to"
}

class NomatStreamHide : StreamWishExtractor() {
    override var name = "StreamHide"
    override var mainUrl = "https://streamhide.to"
}
