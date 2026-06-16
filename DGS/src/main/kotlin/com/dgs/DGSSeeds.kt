package com.dgs

object DGSSeeds {
    const val MAIN_URL = "https://deepgoretube.site"

    object Path {
        const val HOME = "/home/"
        const val ACCIDENT = "/categories/accident/"
        const val ANIMAL = "/categories/animal/"
        const val ANIMAL_ATTACK = "/categories/animal-attack/"
        const val BEHEADING = "/categories/beheading/"
        const val BIZARRE = "/categories/bizarre/"
        const val CARTEL = "/categories/cartel/"
        const val CCTV = "/categories/cctv/"
        const val CRIME = "/categories/crime/"
        const val DISEASE = "/categories/disease/"
        const val DISGUSTING = "/categories/disgusting/"
        const val EXECUTION = "/categories/execution/"
        const val FIGHTS = "/categories/fights/"
        const val FIRE = "/categories/fire/"
        const val GIRLS_FIGHT = "/categories/girls-fight/"
        const val LYNCHED = "/categories/lynched/"
        const val MEDICAL = "/categories/medical/"
        const val MURDER = "/categories/murder/"
        const val MUTILATION = "/categories/mutilation/"
        const val NATURE = "/categories/nature/"
        const val POLICE = "/categories/police/"
        const val PROTESTS = "/categories/protests/"
        const val PUNISHMENT = "/categories/punishment/"
        const val PURE_GORE = "/categories/pure-gore/"
        const val RAIL_ACCIDENT = "/categories/rail-accident/"
        const val SELF_MUTILATION = "/categories/self-mutilation/"
        const val TORTURE = "/categories/torture/"
        const val TRAFFIC_ACCIDENT = "/categories/traffic-accident/"
        const val WAR = "/categories/war/"
        const val WORK_ACCIDENT = "/categories/work-accident/"
    }

    fun mainPageRows(): Array<Pair<String, String>> = arrayOf(
        Path.HOME to "Home",
        Path.ACCIDENT to "Accident",
        Path.WORK_ACCIDENT to "Work Accident",
        Path.TRAFFIC_ACCIDENT to "Traffic Accident",
        Path.RAIL_ACCIDENT to "Rail Accident",
        Path.WAR to "War",
        Path.POLICE to "Police",
        Path.PROTESTS to "Protests",
        Path.PUNISHMENT to "Punishment",
        Path.PURE_GORE to "Pure Gore",
        Path.TORTURE to "Torture",
        Path.SELF_MUTILATION to "Self-mutilation",
        Path.MURDER to "Murder",
        Path.CRIME to "Crime",
        Path.CCTV to "CCTV",
        Path.BIZARRE to "Bizarre",
        Path.CARTEL to "Cartel",
        Path.EXECUTION to "Execution",
        Path.BEHEADING to "Beheading",
        Path.MUTILATION to "Mutilation",
        Path.FIGHTS to "Fights",
        Path.FIRE to "Fire",
        Path.GIRLS_FIGHT to "Girls Fight",
        Path.LYNCHED to "Lynched",
        Path.MEDICAL to "Medical",
        Path.DISEASE to "Disease",
        Path.DISGUSTING to "Disgusting",
        Path.ANIMAL to "Animal",
        Path.ANIMAL_ATTACK to "Animal Attack",
        Path.NATURE to "Nature",
    )
}
