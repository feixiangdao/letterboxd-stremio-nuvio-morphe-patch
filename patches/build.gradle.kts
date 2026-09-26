group = "app.playerbridge"

patches {
    about {
        name = "Letterboxd Player Bridge"
        description = "Adds separate Stremio and Nuvio buttons to Letterboxd film pages."
        source = "https://github.com/feixiangdao/letterboxd-stremio-nuvio-morphe-patch"
        author = "feixiangdao"
        contact = "https://github.com/feixiangdao"
        website = "https://github.com/feixiangdao/letterboxd-stremio-nuvio-morphe-patch"
        license = "GPLv3"
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}
