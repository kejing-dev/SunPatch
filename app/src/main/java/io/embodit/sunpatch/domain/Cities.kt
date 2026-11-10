package io.embodit.sunpatch.domain

/** A city of the built-in list (design `content.cities`): public coordinates and an IANA zone (R1-4). */
data class City(val key: String, val nameZh: String, val nameEn: String, val lat: Double, val lon: Double, val zoneId: String) {
    fun name(zh: Boolean) = if (zh) nameZh else nameEn

    /** "39.9N 116.4E" */
    val coords: String
        get() = "%.1f%s %.1f%s".format(kotlin.math.abs(lat), if (lat >= 0) "N" else "S", kotlin.math.abs(lon), if (lon >= 0) "E" else "W")
}

object Cities {
    private fun c(key: String, zh: String, en: String, lat: Double, lon: Double, zone: String) = City(key, zh, en, lat, lon, zone)

    private const val CN = "Asia/Shanghai"

    val all: List<City> = listOf(
        c("beijing", "北京", "Beijing", 39.90, 116.40, CN),
        c("shanghai", "上海", "Shanghai", 31.23, 121.47, CN),
        c("tianjin", "天津", "Tianjin", 39.13, 117.20, CN),
        c("chongqing", "重庆", "Chongqing", 29.56, 106.55, CN),
        c("harbin", "哈尔滨", "Harbin", 45.80, 126.53, CN),
        c("changchun", "长春", "Changchun", 43.82, 125.32, CN),
        c("shenyang", "沈阳", "Shenyang", 41.81, 123.43, CN),
        c("hohhot", "呼和浩特", "Hohhot", 40.84, 111.75, CN),
        c("shijiazhuang", "石家庄", "Shijiazhuang", 38.04, 114.51, CN),
        c("taiyuan", "太原", "Taiyuan", 37.87, 112.55, CN),
        c("jinan", "济南", "Jinan", 36.65, 117.12, CN),
        c("zhengzhou", "郑州", "Zhengzhou", 34.75, 113.62, CN),
        c("xian", "西安", "Xi'an", 34.34, 108.94, CN),
        c("lanzhou", "兰州", "Lanzhou", 36.06, 103.83, CN),
        c("xining", "西宁", "Xining", 36.62, 101.78, CN),
        c("yinchuan", "银川", "Yinchuan", 38.49, 106.23, CN),
        c("urumqi", "乌鲁木齐", "Urumqi", 43.83, 87.62, CN),
        c("lhasa", "拉萨", "Lhasa", 29.65, 91.17, CN),
        c("chengdu", "成都", "Chengdu", 30.57, 104.07, CN),
        c("guiyang", "贵阳", "Guiyang", 26.65, 106.63, CN),
        c("kunming", "昆明", "Kunming", 25.04, 102.71, CN),
        c("wuhan", "武汉", "Wuhan", 30.59, 114.31, CN),
        c("changsha", "长沙", "Changsha", 28.23, 112.94, CN),
        c("nanchang", "南昌", "Nanchang", 28.68, 115.86, CN),
        c("hefei", "合肥", "Hefei", 31.82, 117.23, CN),
        c("nanjing", "南京", "Nanjing", 32.06, 118.80, CN),
        c("hangzhou", "杭州", "Hangzhou", 30.27, 120.16, CN),
        c("fuzhou", "福州", "Fuzhou", 26.07, 119.30, CN),
        c("guangzhou", "广州", "Guangzhou", 23.13, 113.26, CN),
        c("nanning", "南宁", "Nanning", 22.82, 108.37, CN),
        c("haikou", "海口", "Haikou", 20.04, 110.20, CN),
        c("hongkong", "香港", "Hong Kong", 22.32, 114.17, "Asia/Hong_Kong"),
        c("macau", "澳门", "Macau", 22.20, 113.54, "Asia/Macau"),
        c("taipei", "台北", "Taipei", 25.03, 121.57, "Asia/Taipei"),
        c("tokyo", "东京", "Tokyo", 35.68, 139.69, "Asia/Tokyo"),
        c("seoul", "首尔", "Seoul", 37.57, 126.98, "Asia/Seoul"),
        c("singapore", "新加坡", "Singapore", 1.35, 103.82, "Asia/Singapore"),
        c("bangkok", "曼谷", "Bangkok", 13.76, 100.50, "Asia/Bangkok"),
        c("jakarta", "雅加达", "Jakarta", -6.21, 106.85, "Asia/Jakarta"),
        c("newdelhi", "新德里", "New Delhi", 28.61, 77.21, "Asia/Kolkata"),
        c("dubai", "迪拜", "Dubai", 25.20, 55.27, "Asia/Dubai"),
        c("moscow", "莫斯科", "Moscow", 55.76, 37.62, "Europe/Moscow"),
        c("istanbul", "伊斯坦布尔", "Istanbul", 41.01, 28.98, "Europe/Istanbul"),
        c("berlin", "柏林", "Berlin", 52.52, 13.40, "Europe/Berlin"),
        c("paris", "巴黎", "Paris", 48.86, 2.35, "Europe/Paris"),
        c("london", "伦敦", "London", 51.51, -0.13, "Europe/London"),
        c("madrid", "马德里", "Madrid", 40.42, -3.70, "Europe/Madrid"),
        c("rome", "罗马", "Rome", 41.90, 12.50, "Europe/Rome"),
        c("amsterdam", "阿姆斯特丹", "Amsterdam", 52.37, 4.90, "Europe/Amsterdam"),
        c("stockholm", "斯德哥尔摩", "Stockholm", 59.33, 18.07, "Europe/Stockholm"),
        c("cairo", "开罗", "Cairo", 30.04, 31.24, "Africa/Cairo"),
        c("johannesburg", "约翰内斯堡", "Johannesburg", -26.20, 28.05, "Africa/Johannesburg"),
        c("newyork", "纽约", "New York", 40.71, -74.01, "America/New_York"),
        c("losangeles", "洛杉矶", "Los Angeles", 34.05, -118.24, "America/Los_Angeles"),
        c("chicago", "芝加哥", "Chicago", 41.88, -87.63, "America/Chicago"),
        c("toronto", "多伦多", "Toronto", 43.65, -79.38, "America/Toronto"),
        c("vancouver", "温哥华", "Vancouver", 49.28, -123.12, "America/Vancouver"),
        c("mexicocity", "墨西哥城", "Mexico City", 19.43, -99.13, "America/Mexico_City"),
        c("saopaulo", "圣保罗", "São Paulo", -23.55, -46.63, "America/Sao_Paulo"),
        c("buenosaires", "布宜诺斯艾利斯", "Buenos Aires", -34.60, -58.38, "America/Argentina/Buenos_Aires"),
        c("sydney", "悉尼", "Sydney", -33.87, 151.21, "Australia/Sydney"),
        c("melbourne", "墨尔本", "Melbourne", -37.81, 144.96, "Australia/Melbourne"),
        c("auckland", "奥克兰", "Auckland", -36.85, 174.76, "Pacific/Auckland"),
        c("honolulu", "檀香山", "Honolulu", 21.31, -157.86, "Pacific/Honolulu"),
    )

    val BEIJING: City = all.first()

    fun byKey(key: String?): City? = all.firstOrNull { it.key.equals(key, ignoreCase = true) }

    fun search(q: String): List<City> {
        val s = q.trim().lowercase()
        if (s.isEmpty()) return all
        return all.filter { it.key.contains(s) || it.nameEn.lowercase().contains(s) || it.nameZh.contains(s) }
    }

    /** "Use coordinates" row (design: zone = device zone, stated in the row). */
    fun custom(lat: Double, lon: Double, zoneId: String) = City("custom", "经纬度", "Coordinates", lat, lon, zoneId)
}
