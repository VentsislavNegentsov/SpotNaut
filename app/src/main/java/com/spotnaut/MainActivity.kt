package com.spotnaut

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import retrofit2.http.Url
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// --- 1. Data Models & 100 POI Taxonomy ---

enum class AppLanguage { BG, EN }

data class Center(val lat: Double, val lon: Double)

data class Element(
    val id: Long,
    val lat: Double?,
    val lon: Double?,
    val center: Center?,
    val tags: Map<String, String>?
) {
    val actualLat: Double? get() = lat ?: center?.lat
    val actualLon: Double? get() = lon ?: center?.lon

    fun belongsToCategory(category: PoiCategory): Boolean {
        return tags?.get(category.osmKey) == category.osmValue
    }

    fun getLocalizedTitle(category: PoiCategory, lang: AppLanguage): String {
        val nameBg = tags?.get("name:bg")
        val nameEn = tags?.get("name:en")
        val nameDefault = tags?.get("name")

        val name = when (lang) {
            AppLanguage.BG -> nameBg ?: nameDefault
            AppLanguage.EN -> nameEn ?: nameDefault
        }
        return name ?: "${category.icon} ${category.label(lang)}"
    }
}

data class OverpassResponse(val elements: List<Element>)

data class OsrmResponse(val routes: List<OsrmRoute>?)
data class OsrmRoute(
    val geometry: OsrmGeometry?,
    val legs: List<OsrmLeg>?
)
data class OsrmGeometry(
    val coordinates: List<List<Double>>?
)
data class OsrmLeg(val steps: List<OsrmStep>?)
data class OsrmStep(
    val distance: Double,
    val name: String?,
    val maneuver: OsrmManeuver?
)
data class OsrmManeuver(
    val type: String?,
    val modifier: String?,
    val location: List<Double>?
)

data class NavigationData(
    val points: List<GeoPoint>,
    val steps: List<OsrmStep>
)

enum class MainCategory(
    val labelBg: String,
    val labelEn: String,
    val icon: String
) {
    WATER_HYGIENE("Вода & Хигиена", "Water & Hygiene", "💧"),
    LEISURE("Отдих & Спорт", "Leisure & Sport", "🌳"),
    TRANSPORT("Транспорт", "Transport", "🚲"),
    ECO("Еко & Рециклиране", "Eco & Recycling", "♻️"),
    CULTURE("Култура & Изкуство", "Culture & Art", "🎨"),
    FOOD_DRINK("Храна & Напитки", "Food & Drink", "☕"),
    HEALTH_SAFETY("Здраве & Спешни", "Health & Emergency", "🏥"),
    SERVICES("Услуги & Финанси", "Services & Finance", "🏧"),
    NATURE_OUTDOOR("Природа & Забележителности", "Nature & Sights", "🏔️"),
    SHOPPING("Пазаруване", "Shopping", "🛒"),
    FAMILY("Деца & Семейство", "Family & Kids", "🧸");

    fun label(lang: AppLanguage): String = if (lang == AppLanguage.BG) labelBg else labelEn
}

enum class PoiCategory(
    val mainCategory: MainCategory,
    val labelBg: String,
    val labelEn: String,
    val icon: String,
    val osmKey: String,
    val osmValue: String,
    val colorHex: String
) {
    // 1. Water & Hygiene (6)
    FOUNTAINS(MainCategory.WATER_HYGIENE, "Чешми", "Fountains", "🚰", "amenity", "drinking_water", "#0288D1"),
    TOILETS(MainCategory.WATER_HYGIENE, "Тоалетни", "Toilets", "🚻", "amenity", "toilets", "#7B1FA2"),
    SPRINGS(MainCategory.WATER_HYGIENE, "Извори", "Springs", "🏞️", "natural", "spring", "#00ACC1"),
    SHOWERS(MainCategory.WATER_HYGIENE, "Душове", "Public Showers", "🚿", "amenity", "shower", "#0097A7"),
    BATHS(MainCategory.WATER_HYGIENE, "Минерални бани", "Thermal Baths", "♨️", "amenity", "public_bath", "#00838F"),
    HANDWASHING(MainCategory.WATER_HYGIENE, "Мивки за ръце", "Handwash Stations", "🧼", "amenity", "washbasin", "#00BCD4"),

    // 2. Leisure & Sport (12)
    BENCHES(MainCategory.LEISURE, "Пейки", "Benches", "🪑", "amenity", "bench", "#8D6E63"),
    PLAYGROUNDS(MainCategory.LEISURE, "Площадки", "Playgrounds", "🛝", "leisure", "playground", "#E91E63"),
    FITNESS(MainCategory.LEISURE, "Външен фитнес", "Outdoor Gym", "🏋️", "leisure", "fitness_station", "#4CAF50"),
    DOG_PARKS(MainCategory.LEISURE, "Кучешки паркове", "Dog Parks", "🐕", "leisure", "dog_park", "#388E3C"),
    PICNIC(MainCategory.LEISURE, "Пикник", "Picnic Areas", "🧺", "leisure", "picnic_site", "#FF9800"),
    BBQ(MainCategory.LEISURE, "Барбекю", "BBQ Spots", "🍖", "amenity", "bbq", "#E65100"),
    SKATE_PARK(MainCategory.LEISURE, "Скейт парк", "Skate Park", "🛹", "leisure", "skate_park", "#795548"),
    SPORTS_PITCH(MainCategory.LEISURE, "Спортно игрище", "Sports Pitch", "⚽", "leisure", "pitch", "#2E7D32"),
    SWIMMING_POOL(MainCategory.LEISURE, "Басейни", "Swimming Pools", "🏊", "leisure", "swimming_pool", "#0288D1"),
    TENNIS_COURT(MainCategory.LEISURE, "Тенис кортове", "Tennis Courts", "🎾", "sport", "tennis", "#8BC34A"),
    SPORTS_CENTRE(MainCategory.LEISURE, "Спортни центрове", "Sports Centers", "🏟️", "leisure", "sports_centre", "#1B5E20"),
    PARKS(MainCategory.LEISURE, "Градски паркове", "City Parks", "🏞️", "leisure", "park", "#2E7D32"),

    // 3. Transport (10)
    EV_CHARGING(MainCategory.TRANSPORT, "EV Зарядни", "EV Chargers", "⚡", "amenity", "charging_station", "#FBC02D"),
    BIKE_PARKING(MainCategory.TRANSPORT, "Велостойки", "Bike Parking", "🚲", "amenity", "bicycle_parking", "#009688"),
    BIKE_RENTAL(MainCategory.TRANSPORT, "Колела под наем", "Bike Rental", "🚴", "amenity", "bicycle_rental", "#00BCD4"),
    BIKE_REPAIR(MainCategory.TRANSPORT, "Велоремонт", "Bike Repair", "🔧", "amenity", "bike_repair_station", "#607D8B"),
    BUS_STOP(MainCategory.TRANSPORT, "Автобусни спирки", "Bus Stops", "🚌", "highway", "bus_stop", "#1565C0"),
    SUBWAY_ENTRANCE(MainCategory.TRANSPORT, "Метростанции", "Metro Stations", "🚇", "railway", "subway_entrance", "#0D47A1"),
    TRAIN_STATION(MainCategory.TRANSPORT, "ЖП гари", "Train Stations", "🚆", "building", "train_station", "#1A237E"),
    PARKING(MainCategory.TRANSPORT, "Паркинги", "Parking Spots", "🅿️", "amenity", "parking", "#1976D2"),
    TAXI(MainCategory.TRANSPORT, "Такси стоянки", "Taxi Ranks", "🚕", "amenity", "taxi", "#F57F17"),
    FUEL(MainCategory.TRANSPORT, "Бензиностанции", "Gas Stations", "⛽", "amenity", "fuel", "#D32F2F"),

    // 4. Eco & Recycling (6)
    RECYCLING(MainCategory.ECO, "Рециклиране", "Recycling", "♻️", "amenity", "recycling", "#00796B"),
    WASTE_BASKET(MainCategory.ECO, "Кошчета за боклук", "Trash Cans", "🗑️", "amenity", "waste_basket", "#455A64"),
    CLOTHES_CONTAINER(MainCategory.ECO, "Дрехи рециклиране", "Clothes Recycling", "👕", "amenity", "waste_disposal", "#00897B"),
    COMPOST(MainCategory.ECO, "Компост", "Compost", "🌱", "amenity", "compost", "#33691E"),
    DOG_WASTE(MainCategory.ECO, "Кошчета за кучета", "Dog Waste Bins", "💩", "amenity", "waste_disposal", "#5D4037"),
    BATTERY_DISPOSAL(MainCategory.ECO, "Батерии контейнери", "Battery Drop-off", "🔋", "amenity", "waste_disposal", "#F57F17"),

    // 5. Culture & Art (10)
    ART(MainCategory.CULTURE, "Стрийт Арт", "Street Art", "🎨", "tourism", "artwork", "#F57C00"),
    BOOKCASE(MainCategory.CULTURE, "Улични библиотеки", "Public Bookcases", "📚", "amenity", "public_bookcase", "#8D6E63"),
    PARCEL_LOCKER(MainCategory.CULTURE, "Шкафчета", "Parcel Lockers", "📦", "amenity", "parcel_locker", "#FF5722"),
    MONUMENTS(MainCategory.CULTURE, "Паметници", "Monuments", "🗿", "historic", "monument", "#78909C"),
    MUSEUM(MainCategory.CULTURE, "Музеи", "Museums", "🏛️", "tourism", "museum", "#5D4037"),
    THEATRE(MainCategory.CULTURE, "Театри", "Theatres", "🎭", "amenity", "theatre", "#AD1457"),
    CASTLE(MainCategory.CULTURE, "Замъци & Крепости", "Castles & Ruins", "🏰", "historic", "castle", "#4E342E"),
    PLACE_OF_WORSHIP(MainCategory.CULTURE, "Храмове", "Places of Worship", "⛪", "amenity", "place_of_worship", "#6A1B9A"),
    CINEMA(MainCategory.CULTURE, "Кина", "Cinemas", "🍿", "amenity", "cinema", "#C2185B"),
    LIBRARY(MainCategory.CULTURE, "Библиотеки", "Public Libraries", "📖", "amenity", "library", "#1565C0"),

    // 6. Food & Drink (12)
    CAFES(MainCategory.FOOD_DRINK, "Кафенета", "Cafes", "☕", "amenity", "cafe", "#6D4C41"),
    RESTAURANTS(MainCategory.FOOD_DRINK, "Ресторанти", "Restaurants", "🍽️", "amenity", "restaurant", "#D84315"),
    FAST_FOOD(MainCategory.FOOD_DRINK, "Бързо хранене", "Fast Food", "🍔", "amenity", "fast_food", "#EF6C00"),
    PUB(MainCategory.FOOD_DRINK, "Пъбове & Барове", "Pubs & Bars", "🍺", "amenity", "pub", "#C62828"),
    ICE_CREAM(MainCategory.FOOD_DRINK, "Сладолед", "Ice Cream", "🍦", "amenity", "ice_cream", "#F48FB1"),
    BAKERY(MainCategory.FOOD_DRINK, "Пекарни", "Bakeries", "🥐", "shop", "bakery", "#A1887F"),
    PASTRY(MainCategory.FOOD_DRINK, "Сладкарници", "Pastry Shops", "🍰", "shop", "pastry", "#E91E63"),
    FOOD_COURT(MainCategory.FOOD_DRINK, "Фууд корт", "Food Courts", "🍱", "amenity", "food_court", "#FF9800"),
    WINE_SHOP(MainCategory.FOOD_DRINK, "Винени магазини", "Wine Shops", "🍷", "shop", "wine", "#880E4F"),
    COFFEE_ROAST(MainCategory.FOOD_DRINK, "Пекарни за кафе", "Coffee Roasters", "☕", "shop", "coffee", "#4E342E"),
    TEA_HOUSE(MainCategory.FOOD_DRINK, "Чаени къщи", "Tea Houses", "🍵", "amenity", "tea_house", "#558B2F"),
    FOOD_TRUCK(MainCategory.FOOD_DRINK, "Камиони за храна", "Food Trucks", "🚚", "amenity", "fast_food", "#FF6F00"),

    // 7. Health & Safety (8)
    PHARMACY(MainCategory.HEALTH_SAFETY, "Аптеки", "Pharmacies", "💊", "amenity", "pharmacy", "#E53935"),
    DEFIBRILLATOR(MainCategory.HEALTH_SAFETY, "Дефибрилатори (AED)", "AED Defibrillators", "🫀", "emergency", "defibrillator", "#D32F2F"),
    HOSPITAL(MainCategory.HEALTH_SAFETY, "Болници", "Hospitals", "🏥", "amenity", "hospital", "#C62828"),
    CLINIC(MainCategory.HEALTH_SAFETY, "Поликлиники", "Medical Clinics", "🩺", "amenity", "clinic", "#1E88E5"),
    POLICE(MainCategory.HEALTH_SAFETY, "Полиция", "Police Stations", "👮", "amenity", "police", "#283593"),
    FIRE_STATION(MainCategory.HEALTH_SAFETY, "Пожарна", "Fire Stations", "🚒", "amenity", "fire_station", "#B71C1C"),
    DENTIST(MainCategory.HEALTH_SAFETY, "Зъболекари", "Dental Clinics", "🦷", "amenity", "dentist", "#00ACC1"),
    VET(MainCategory.HEALTH_SAFETY, "Ветеринари", "Veterinary Clinics", "🐾", "amenity", "veterinary", "#8E24AA"),

    // 8. Services & Finance (10)
    ATM(MainCategory.SERVICES, "Банкомати", "ATMs", "🏧", "amenity", "atm", "#2E7D32"),
    BANK(MainCategory.SERVICES, "Банкови клонове", "Banks", "🏦", "amenity", "bank", "#1B5E20"),
    POST_OFFICE(MainCategory.SERVICES, "Пощенски клонове", "Post Offices", "📯", "amenity", "post_office", "#F9A825"),
    LAUNDRY(MainCategory.SERVICES, "Перални", "Laundromats", "🧺", "shop", "laundry", "#0288D1"),
    TAILOR(MainCategory.SERVICES, "Шивачи", "Tailors & Repair", "🧵", "shop", "tailor", "#7B1FA2"),
    CAR_REPAIR(MainCategory.SERVICES, "Автосервизи", "Auto Mechanics", "👨‍🔧", "shop", "car_repair", "#455A64"),
    CAR_WASH(MainCategory.SERVICES, "Автомивки", "Car Washes", "🚘", "amenity", "car_wash", "#0288D1"),
    COWORKING(MainCategory.SERVICES, "Споделени офиси", "Co-working Spaces", "💻", "amenity", "coworking_space", "#512DA8"),
    PHOTO_BOOTH(MainCategory.SERVICES, "Фото кабини", "Photo Booths", "📸", "amenity", "photo_booth", "#D81B60"),
    HAIRDRESSER(MainCategory.SERVICES, "Фризьори", "Hairdressers", "✂️", "shop", "hairdresser", "#C2185B"),

    // 9. Nature & Outdoor (10)
    VIEWPOINTS(MainCategory.NATURE_OUTDOOR, "Панорамни гледки", "Viewpoints", "🌅", "tourism", "viewpoint", "#9C27B0"),
    ATTRACTION(MainCategory.NATURE_OUTDOOR, "Туристически обект", "Attractions", "🎡", "tourism", "attraction", "#AB47BC"),
    CAMPING(MainCategory.NATURE_OUTDOOR, "Къмпинг зони", "Campsites", "⛺", "tourism", "camp_site", "#558B2F"),
    PEAK(MainCategory.NATURE_OUTDOOR, "Планински върхове", "Peaks", "⛰️", "natural", "peak", "#4E342E"),
    INFORMATION(MainCategory.NATURE_OUTDOOR, "Инфо центрове", "Info Points", "ℹ️", "tourism", "information", "#0277BD"),
    CAVE(MainCategory.NATURE_OUTDOOR, "Пещери", "Cave Entrances", "🦇", "natural", "cave_entrance", "#3E2723"),
    WATERFALL(MainCategory.NATURE_OUTDOOR, "Водопади", "Waterfalls", "🌊", "waterway", "waterfall", "#0288D1"),
    SHELTER(MainCategory.NATURE_OUTDOOR, "Заслони", "Mountain Huts", "🏚️", "amenity", "shelter", "#6D4C41"),
    VIEW_TOWER(MainCategory.NATURE_OUTDOOR, "Кули за наблюдение", "Observation Towers", "🔭", "tourism", "viewpoint", "#512DA8"),
    BEACH(MainCategory.NATURE_OUTDOOR, "Плажове", "Beaches", "🏖️", "natural", "beach", "#FBC02D"),

    // 10. Shopping & Retail (10)
    SUPERMARKET(MainCategory.SHOPPING, "Супермаркети", "Supermarkets", "🛒", "shop", "supermarket", "#43A047"),
    CONVENIENCE(MainCategory.SHOPPING, "Денонощни магазини", "Convenience Stores", "🏪", "shop", "convenience", "#388E3C"),
    MALL(MainCategory.SHOPPING, "Търговски центрове", "Malls", "🏬", "shop", "mall", "#1B5E20"),
    MARKET(MainCategory.SHOPPING, "Пазари", "Farmers Markets", "🧺", "amenity", "marketplace", "#EF6C00"),
    CLOTHES(MainCategory.SHOPPING, "Магазини за дрехи", "Clothing Stores", "👗", "shop", "clothes", "#AD1457"),
    HARDWARE(MainCategory.SHOPPING, "Железарии", "Hardware Stores", "🔨", "shop", "hardware", "#607D8B"),
    ELECTRONICS(MainCategory.SHOPPING, "Електроника", "Electronics Stores", "📱", "shop", "electronics", "#1565C0"),
    BOOKSTORE(MainCategory.SHOPPING, "Книжарници", "Bookstores", "📚", "shop", "books", "#6D4C41"),
    PET_SHOP(MainCategory.SHOPPING, "Зоомагазини", "Pet Shops", "🐶", "shop", "pet", "#8D6E63"),
    FLORIST(MainCategory.SHOPPING, "Цветарски магазини", "Flower Shops", "💐", "shop", "florist", "#EC407A"),

    // 11. Family & Kids (6)
    KINDERGARTEN(MainCategory.FAMILY, "Детски градини", "Kindergartens", "🧸", "amenity", "kindergarten", "#F48FB1"),
    TOY_STORE(MainCategory.FAMILY, "Магазини за играчки", "Toy Stores", "🧸", "shop", "toys", "#FF4081"),
    THEME_PARK(MainCategory.FAMILY, "Увеселителни паркове", "Theme Parks", "🎢", "tourism", "theme_park", "#7C4DFF"),
    BABY_HATCH(MainCategory.FAMILY, "Стаи за бебета", "Baby Changing Rooms", "👶", "amenity", "baby_hatch", "#40C4FF"),
    YOUTH_CENTRE(MainCategory.FAMILY, "Младежки центрове", "Youth Centers", "🛹", "amenity", "youth_centre", "#64FFDA"),
    ZOO(MainCategory.FAMILY, "Зоопаркове", "Zoos & Animal Parks", "🦁", "tourism", "zoo", "#4CAF50");

    fun label(lang: AppLanguage): String = if (lang == AppLanguage.BG) labelBg else labelEn
}

val OVERPASS_SERVERS = listOf(
    "https://overpass-api.de/api/interpreter",
    "https://lz4.overpass-api.de/api/interpreter",
    "https://z.overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.openstreetmap.fr/api/interpreter",
    "https://overpass.private.coffee/api/interpreter"
)

interface OverpassApi {
    @FormUrlEncoded
    @POST
    suspend fun getNodes(@Url url: String, @Field("data") query: String): OverpassResponse

    companion object {
        fun create(): OverpassApi {
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "SpotNaut/1.0 (Android)")
                        .build()
                    chain.proceed(request)
                }
                .build()

            return Retrofit.Builder()
                .baseUrl("https://overpass-api.de/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(OverpassApi::class.java)
        }
    }
}

// --- Local Disk Cache Helpers ---
private fun getCacheFile(context: Context, lat: Double, lon: Double, radius: Float): File {
    val roundedLat = String.format("%.2f", lat).toDouble()
    val roundedLon = String.format("%.2f", lon).toDouble()
    return File(context.filesDir, "osm_cache_all_${roundedLat}_${roundedLon}_${radius}.json")
}

private fun saveResponseToDisk(context: Context, center: GeoPoint, radius: Float, response: OverpassResponse) {
    try {
        val file = getCacheFile(context, center.latitude, center.longitude, radius)
        file.writeText(Gson().toJson(response))
    } catch (e: Exception) {
        Log.e("SpotNaut", "Error saving cache", e)
    }
}

private fun loadResponseFromDisk(context: Context, center: GeoPoint, radius: Float): OverpassResponse? {
    try {
        val file = getCacheFile(context, center.latitude, center.longitude, radius)
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < 86400000L) {
            return Gson().fromJson(file.readText(), OverpassResponse::class.java)
        }
    } catch (e: Exception) {
        Log.e("SpotNaut", "Error loading cache", e)
    }
    return null
}

private fun buildAllCategoriesOverpassQuery(lat: Double, lon: Double, radiusMeters: Int): String {
    return """
        [out:json][timeout:10];
        nwr(around:$radiusMeters,$lat,$lon)[~"^(amenity|leisure|highway|natural|tourism|historic|shop|emergency|building|railway|sport|waterway)$"~"."];
        out center;
    """.trimIndent()
}

// --- Splash Screen Component ---

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(3000L)
        onFinished()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "SpotNaut App Icon",
                    modifier = Modifier.size(240.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "SpotNaut",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "by Ventsislav Negentsov",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "ventsislavnegentsov@gmail.com",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                )
            }
        }
    }
}

// --- Destination Arrow Indicator UI Component ---

@Composable
fun DestinationArrowIndicator(
    angle: Float,
    distanceMeters: Int,
    modifier: Modifier = Modifier,
    arrowColor: Color = MaterialTheme.colorScheme.primary
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        ) {
            Text(
                text = "${distanceMeters} m",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }

        Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Canvas(
                    modifier = Modifier
                        .size(26.dp)
                        .graphicsLayer(rotationZ = angle)
                ) {
                    val w = size.width
                    val h = size.height

                    val arrowPath = Path().apply {
                        moveTo(w * 0.5f, 0f)
                        lineTo(w * 0.85f, h * 0.95f)
                        lineTo(w * 0.5f, h * 0.72f)
                        lineTo(w * 0.15f, h * 0.95f)
                        close()
                    }
                    drawPath(arrowPath, color = arrowColor)
                }
            }
        }
    }
}

// --- Automotive Navigation Vector Arrow UI ---

@Composable
fun AutomotiveManeuverIcon(
    step: OsrmStep?,
    modifier: Modifier = Modifier.size(52.dp),
    arrowColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    secondaryColor: Color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.35f)
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeW = w * 0.12f
        val stroke = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val secondaryStroke = Stroke(width = strokeW * 0.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)

        val maneuver = step?.maneuver
        val type = maneuver?.type ?: "straight"
        val mod = maneuver?.modifier ?: "straight"

        when {
            type == "arrive" -> {
                val path = Path().apply {
                    moveTo(w * 0.5f, h * 0.8f)
                    lineTo(w * 0.5f, h * 0.25f)
                }
                drawPath(path, arrowColor, style = stroke)
                drawCircle(color = arrowColor, radius = w * 0.18f, center = Offset(w * 0.5f, h * 0.25f))
            }
            type == "roundabout" || type == "rotary" -> {
                drawArc(
                    color = arrowColor,
                    startAngle = 40f,
                    sweepAngle = 280f,
                    useCenter = false,
                    topLeft = Offset(w * 0.2f, h * 0.25f),
                    size = Size(w * 0.6f, h * 0.6f),
                    style = stroke
                )
                val arrowHead = Path().apply {
                    moveTo(w * 0.72f, h * 0.18f)
                    lineTo(w * 0.85f, h * 0.32f)
                    lineTo(w * 0.65f, h * 0.38f)
                }
                drawPath(arrowHead, arrowColor, style = stroke)
            }
            mod == "uturn" -> {
                val path = Path().apply {
                    moveTo(w * 0.7f, h * 0.85f)
                    lineTo(w * 0.7f, h * 0.45f)
                    cubicTo(w * 0.7f, h * 0.15f, w * 0.3f, h * 0.15f, w * 0.3f, h * 0.45f)
                    lineTo(w * 0.3f, h * 0.82f)
                }
                drawPath(path, arrowColor, style = stroke)
                val arrowHead = Path().apply {
                    moveTo(w * 0.18f, h * 0.68f)
                    lineTo(w * 0.30f, h * 0.85f)
                    lineTo(w * 0.42f, h * 0.68f)
                }
                drawPath(arrowHead, arrowColor, style = stroke)
            }
            mod == "right" -> {
                val bgPath = Path().apply {
                    moveTo(w * 0.3f, h * 0.85f)
                    lineTo(w * 0.3f, h * 0.2f)
                }
                drawPath(bgPath, secondaryColor, style = secondaryStroke)

                val mainPath = Path().apply {
                    moveTo(w * 0.3f, h * 0.85f)
                    lineTo(w * 0.3f, h * 0.45f)
                    lineTo(w * 0.75f, h * 0.45f)
                }
                drawPath(mainPath, arrowColor, style = stroke)
                val arrowHead = Path().apply {
                    moveTo(w * 0.60f, h * 0.32f)
                    lineTo(w * 0.78f, h * 0.45f)
                    lineTo(w * 0.60f, h * 0.58f)
                }
                drawPath(arrowHead, arrowColor, style = stroke)
            }
            mod == "left" -> {
                val bgPath = Path().apply {
                    moveTo(w * 0.7f, h * 0.85f)
                    lineTo(w * 0.7f, h * 0.2f)
                }
                drawPath(bgPath, secondaryColor, style = secondaryStroke)

                val mainPath = Path().apply {
                    moveTo(w * 0.7f, h * 0.85f)
                    lineTo(w * 0.7f, h * 0.45f)
                    lineTo(w * 0.25f, h * 0.45f)
                }
                drawPath(mainPath, arrowColor, style = stroke)
                val arrowHead = Path().apply {
                    moveTo(w * 0.40f, h * 0.32f)
                    lineTo(w * 0.22f, h * 0.45f)
                    lineTo(w * 0.40f, h * 0.58f)
                }
                drawPath(arrowHead, arrowColor, style = stroke)
            }
            mod == "slight right" -> {
                val mainPath = Path().apply {
                    moveTo(w * 0.35f, h * 0.85f)
                    lineTo(w * 0.35f, h * 0.55f)
                    lineTo(w * 0.72f, h * 0.25f)
                }
                drawPath(mainPath, arrowColor, style = stroke)
                val arrowHead = Path().apply {
                    moveTo(w * 0.52f, h * 0.22f)
                    lineTo(w * 0.75f, h * 0.22f)
                    lineTo(w * 0.75f, h * 0.45f)
                }
                drawPath(arrowHead, arrowColor, style = stroke)
            }
            mod == "slight left" -> {
                val mainPath = Path().apply {
                    moveTo(w * 0.65f, h * 0.85f)
                    lineTo(w * 0.65f, h * 0.55f)
                    lineTo(w * 0.28f, h * 0.25f)
                }
                drawPath(mainPath, arrowColor, style = stroke)
                val arrowHead = Path().apply {
                    moveTo(w * 0.48f, h * 0.22f)
                    lineTo(w * 0.25f, h * 0.22f)
                    lineTo(w * 0.25f, h * 0.45f)
                }
                drawPath(arrowHead, arrowColor, style = stroke)
            }
            mod == "sharp right" -> {
                val mainPath = Path().apply {
                    moveTo(w * 0.35f, h * 0.85f)
                    lineTo(w * 0.35f, h * 0.35f)
                    lineTo(w * 0.75f, h * 0.7f)
                }
                drawPath(mainPath, arrowColor, style = stroke)
                val arrowHead = Path().apply {
                    moveTo(w * 0.75f, h * 0.48f)
                    lineTo(w * 0.78f, h * 0.72f)
                    lineTo(w * 0.54f, h * 0.72f)
                }
                drawPath(arrowHead, arrowColor, style = stroke)
            }
            mod == "sharp left" -> {
                val mainPath = Path().apply {
                    moveTo(w * 0.65f, h * 0.85f)
                    lineTo(w * 0.65f, h * 0.35f)
                    lineTo(w * 0.25f, h * 0.7f)
                }
                drawPath(mainPath, arrowColor, style = stroke)
                val arrowHead = Path().apply {
                    moveTo(w * 0.25f, h * 0.48f)
                    lineTo(w * 0.22f, h * 0.72f)
                    lineTo(w * 0.46f, h * 0.72f)
                }
                drawPath(arrowHead, arrowColor, style = stroke)
            }
            type == "fork" -> {
                val leftFork = Path().apply {
                    moveTo(w * 0.5f, h * 0.85f)
                    lineTo(w * 0.5f, h * 0.55f)
                    lineTo(w * 0.25f, h * 0.25f)
                }
                val rightFork = Path().apply {
                    moveTo(w * 0.5f, h * 0.55f)
                    lineTo(w * 0.75f, h * 0.25f)
                }

                val isRight = mod.contains("right")
                drawPath(leftFork, if (isRight) secondaryColor else arrowColor, style = stroke)
                drawPath(rightFork, if (isRight) arrowColor else secondaryColor, style = stroke)
            }
            else -> {
                val mainPath = Path().apply {
                    moveTo(w * 0.5f, h * 0.85f)
                    lineTo(w * 0.5f, h * 0.22f)
                }
                drawPath(mainPath, arrowColor, style = stroke)
                val arrowHead = Path().apply {
                    moveTo(w * 0.32f, h * 0.38f)
                    lineTo(w * 0.50f, h * 0.18f)
                    lineTo(w * 0.68f, h * 0.38f)
                }
                drawPath(arrowHead, arrowColor, style = stroke)
            }
        }
    }
}

// --- Helper Functions ---

suspend fun fetchStreetRouteDetails(start: GeoPoint, target: GeoPoint): NavigationData = withContext(Dispatchers.IO) {
    try {
        val url = "https://router.project-osrm.org/route/v1/foot/" +
                "${start.longitude},${start.latitude};${target.longitude},${target.latitude}" +
                "?overview=full&geometries=geojson&steps=true"

        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val json = response.body?.string() ?: return@withContext NavigationData(listOf(start, target), emptyList())

        val osrmResponse = Gson().fromJson(json, OsrmResponse::class.java)
        val route = osrmResponse.routes?.firstOrNull()
        val coords = route?.geometry?.coordinates?.map { GeoPoint(it[1], it[0]) } ?: listOf(start, target)
        val steps = route?.legs?.firstOrNull()?.steps ?: emptyList()

        NavigationData(coords, steps)
    } catch (e: Exception) {
        Log.e("SpotNaut", "Route fetching error", e)
        NavigationData(listOf(start, target), emptyList())
    }
}

fun sliceRouteFromCurrentLocation(userPoint: GeoPoint, fullPoints: List<GeoPoint>): List<GeoPoint> {
    if (fullPoints.isEmpty()) return emptyList()

    var minDistance = Double.MAX_VALUE
    var closestIndex = 0

    for (i in fullPoints.indices) {
        val dist = userPoint.distanceToAsDouble(fullPoints[i])
        if (dist < minDistance) {
            minDistance = dist
            closestIndex = i
        }
    }

    val remaining = fullPoints.subList(closestIndex, fullPoints.size).toMutableList()
    remaining.add(0, userPoint)
    return remaining
}

fun isUserOffRoute(userPoint: GeoPoint, fullPoints: List<GeoPoint>, thresholdMeters: Double = 35.0): Boolean {
    if (fullPoints.isEmpty()) return false
    val minDistance = fullPoints.minOf { userPoint.distanceToAsDouble(it) }
    return minDistance > thresholdMeters
}

fun getManeuverText(step: OsrmStep?, lang: AppLanguage): String {
    if (step == null) {
        return if (lang == AppLanguage.BG) "Следвайте маршрута" else "Follow the route"
    }

    val maneuver = step.maneuver
    val modifier = maneuver?.modifier
    val type = maneuver?.type
    val streetName = if (!step.name.isNullOrBlank()) step.name else ""

    val text = when {
        type == "arrive" -> if (lang == AppLanguage.BG) "Пристигане на целта" else "Arriving at destination"
        type == "roundabout" || type == "rotary" -> if (lang == AppLanguage.BG) "На кръговото излезте" else "At roundabout take exit"
        modifier == "slight right" -> if (lang == AppLanguage.BG) "Завийте леко надясно" else "Bear right"
        modifier == "right" -> if (lang == AppLanguage.BG) "Завийте надясно" else "Turn right"
        modifier == "sharp right" -> if (lang == AppLanguage.BG) "Остър десен завой" else "Sharp right turn"
        modifier == "slight left" -> if (lang == AppLanguage.BG) "Завийте леко наляво" else "Bear left"
        modifier == "left" -> if (lang == AppLanguage.BG) "Завийте наляво" else "Turn left"
        modifier == "sharp left" -> if (lang == AppLanguage.BG) "Остър ляв завой" else "Sharp left turn"
        modifier == "uturn" -> if (lang == AppLanguage.BG) "Обратен завой" else "U-turn"
        else -> if (lang == AppLanguage.BG) "Продължете по" else "Continue on"
    }

    return if (streetName.isBlank()) text else "$text по $streetName"
}

fun updateZoomBasedOnSpeed(mapView: MapView, speedMps: Float) {
    val speedKmH = if (speedMps <= 0f) 0f else speedMps * 3.6f

    val targetZoom = when {
        speedKmH < 5f -> 18.5
        speedKmH < 25f -> 17.0
        speedKmH < 50f -> 15.5
        else -> 14.0
    }

    if (abs(mapView.zoomLevelDouble - targetZoom) > 0.2) {
        mapView.controller.zoomTo(targetZoom, 400L)
    }
}

private fun createEmojiMarkerIcon(
    context: Context,
    emoji: String,
    backgroundColorHex: String,
    isSelected: Boolean = false
): Drawable {
    val density = context.resources.displayMetrics.density
    val sizePx = ((if (isSelected) 46 else 40) * density).toInt()
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)

    val baseColor = AndroidColor.parseColor(backgroundColorHex)
    val colorToUse = if (isSelected) {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(baseColor, hsv)
        hsv[2] *= 0.45f
        AndroidColor.HSVToColor(hsv)
    } else {
        baseColor
    }

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorToUse
        style = Paint.Style.FILL
    }

    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isSelected) AndroidColor.YELLOW else AndroidColor.WHITE
        style = Paint.Style.STROKE
        strokeWidth = (if (isSelected) 3.5f else 2.5f) * density
    }

    val radius = (sizePx / 2f) - (3 * density)
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, radius, bgPaint)
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, radius, strokePaint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = (if (isSelected) 22f else 19f) * density
        textAlign = Paint.Align.CENTER
    }
    val textY = (sizePx / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
    canvas.drawText(emoji, sizePx / 2f, textY, textPaint)

    return BitmapDrawable(context.resources, bitmap)
}

private fun formatSpotDetails(category: PoiCategory, tags: Map<String, String>?, lang: AppLanguage): String {
    if (tags.isNullOrEmpty()) {
        return if (lang == AppLanguage.BG) "Няма допълнителни данни" else "No additional details"
    }

    val details = mutableListOf<String>()

    tags["operator"]?.let { details.add(if (lang == AppLanguage.BG) "Стопанин: $it" else "Operator: $it") }
    tags["opening_hours"]?.let { details.add(if (lang == AppLanguage.BG) "Работно време: $it" else "Opening hours: $it") }
    tags["fee"]?.let {
        val feeText = if (it == "no") (if (lang == AppLanguage.BG) "Безплатно" else "Free")
        else (if (lang == AppLanguage.BG) "Платено ($it)" else "Fee ($it)")
        details.add(if (lang == AppLanguage.BG) "Такса: $feeText" else "Fee: $feeText")
    }

    val desc = when (lang) {
        AppLanguage.BG -> tags["description:bg"] ?: tags["description"]
        AppLanguage.EN -> tags["description:en"] ?: tags["description"]
    }
    desc?.let { details.add(if (lang == AppLanguage.BG) "Описание: $it" else "Description: $it") }

    return details.ifEmpty {
        listOf(if (lang == AppLanguage.BG) "Обект от OSM категория '${category.labelBg}'" else "Object from OSM category '${category.labelEn}'")
    }.joinToString("\n")
}

private fun openGoogleMaps(context: Context, target: GeoPoint, label: String) {
    val uri = Uri.parse("google.navigation:q=${target.latitude},${target.longitude}")
    val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    } else {
        val genericUri = Uri.parse("geo:${target.latitude},${target.longitude}?q=${target.latitude},${target.longitude}($label)")
        context.startActivity(Intent(Intent.ACTION_VIEW, genericUri))
    }
}

// --- Main Activity ---

class MainActivity : ComponentActivity() {
    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        Configuration.getInstance().userAgentValue = packageName

        setContent {
            MainScreen()
        }
    }
}

// --- UI Screen Layout ---

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("SpotNautPrefs", Context.MODE_PRIVATE) }

    var isDarkMode by remember { mutableStateOf(prefs.getBoolean("is_dark_mode", false)) }
    var showSplash by remember { mutableStateOf(true) }

    MaterialTheme(
        colorScheme = if (isDarkMode) darkColorScheme() else lightColorScheme()
    ) {
        val coroutineScope = rememberCoroutineScope()
        val api = remember { OverpassApi.create() }

        var loadedAllElements by remember { mutableStateOf<List<Element>?>(null) }
        var currentLoadedCenter by remember { mutableStateOf<GeoPoint?>(null) }
        var currentLoadedRadius by remember { mutableFloatStateOf(0f) }

        var currentLanguage by remember { mutableStateOf(AppLanguage.BG) }
        var selectedMainCategory by remember { mutableStateOf(MainCategory.WATER_HYGIENE) }
        var selectedPoiCategory by remember { mutableStateOf<PoiCategory?>(null) }
        var isSubCategoryListVisible by remember { mutableStateOf(true) }

        var radiusKm by remember { mutableStateOf(2.0f) }

        var isLoading by remember { mutableStateOf(false) }
        var activeJob by remember { mutableStateOf<Job?>(null) }

        var isInitialSettling by remember { mutableStateOf(true) }

        var showMenu by remember { mutableStateOf(false) }
        var showAboutDialog by remember { mutableStateOf(false) }

        var searchCenterGeoPoint by remember { mutableStateOf(GeoPoint(42.6977, 23.3219)) }

        var currentlySelectedMarker by remember { mutableStateOf<Marker?>(null) }
        var selectedTargetGeoPoint by remember { mutableStateOf<GeoPoint?>(null) }
        var selectedTargetTitle by remember { mutableStateOf<String?>(null) }
        var selectedTargetDetails by remember { mutableStateOf<String?>(null) }

        var isGuidanceActive by remember { mutableStateOf(false) }

        var lastGpsBearing by remember { mutableFloatStateOf(0f) }
        var deviceAzimuth by remember { mutableFloatStateOf(0f) }
        var hasGpsBearingEverBeenSet by remember { mutableStateOf(false) }

        var rawRoutePoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
        var displayRoutePoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
        var navigationSteps by remember { mutableStateOf<List<OsrmStep>>(emptyList()) }

        var activeStepIndex by remember { mutableIntStateOf(0) }
        var reachedCurrentManeuverJunction by remember { mutableStateOf(false) }

        var currentLocation by remember { mutableStateOf<Location?>(null) }
        var lastRouteFetchTime by remember { mutableLongStateOf(0L) }

        fun updateSearchCenterIfMoved(newPoint: GeoPoint) {
            if (searchCenterGeoPoint.distanceToAsDouble(newPoint) > 50.0) {
                searchCenterGeoPoint = newPoint
            }
        }

        val currentSubCategories = remember(selectedMainCategory) {
            PoiCategory.entries.filter { it.mainCategory == selectedMainCategory }
        }

        fun deselectCurrentMarker() {
            currentlySelectedMarker?.let { prevMarker ->
                val prevCat = (prevMarker.relatedObject as? PoiCategory)
                if (prevCat != null) {
                    prevMarker.icon = createEmojiMarkerIcon(context, prevCat.icon, prevCat.colorHex, isSelected = false)
                }
                prevMarker.closeInfoWindow()
            }
            currentlySelectedMarker = null
            selectedTargetGeoPoint = null
            selectedTargetTitle = null
            selectedTargetDetails = null
        }

        val mapEventsOverlay = remember {
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    if (!isGuidanceActive) {
                        deselectCurrentMarker()
                    }
                    return false
                }

                override fun longPressHelper(p: GeoPoint): Boolean {
                    searchCenterGeoPoint = p
                    return true
                }
            })
        }

        val mapView = remember {
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(15.0)
                controller.setCenter(searchCenterGeoPoint)
            }
        }

        val navigationPolyline = remember {
            Polyline().apply {
                outlinePaint.color = AndroidColor.parseColor("#0288D1")
                outlinePaint.strokeWidth = 16f
            }
        }

        DisposableEffect(Unit) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    currentLocation = location
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 800L, 0.5f, listener)
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1.0f, listener)
            } catch (e: SecurityException) {
                Log.e("SpotNaut", "Location permission missing", e)
            }

            onDispose { locationManager.removeUpdates(listener) }
        }

        val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
        val rotationSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }

        val currentIsGuidanceActive by rememberUpdatedState(isGuidanceActive)
        val currentHasGpsBearing by rememberUpdatedState(hasGpsBearingEverBeenSet)

        DisposableEffect(sensorManager, rotationSensor) {
            if (rotationSensor == null) {
                onDispose { }
            } else {
                var lastAzimuth = 0f
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                            val rotationMatrix = FloatArray(9)
                            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                            val orientation = FloatArray(3)
                            SensorManager.getOrientation(rotationMatrix, orientation)

                            var azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                            if (azimuth < 0) azimuth += 360f

                            deviceAzimuth = azimuth

                            if (currentIsGuidanceActive && currentHasGpsBearing) return

                            if (abs(azimuth - lastAzimuth) > 1.5f) {
                                lastAzimuth = azimuth
                                mapView.post {
                                    mapView.mapOrientation = -azimuth
                                    mapView.invalidate()
                                }
                            }
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }

                sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
                onDispose { sensorManager.unregisterListener(listener) }
            }
        }

        LaunchedEffect(Unit) {
            mapView.overlays.add(mapEventsOverlay)
        }

        LaunchedEffect(isDarkMode) {
            if (isDarkMode) {
                val inverseMatrix = ColorMatrix(floatArrayOf(
                    -1.0f, 0.0f, 0.0f, 0.0f, 255.0f,
                    0.0f, -1.0f, 0.0f, 0.0f, 255.0f,
                    0.0f, 0.0f, -1.0f, 0.0f, 255.0f,
                    0.0f, 0.0f, 0.0f, 1.0f, 0.0f
                ))
                mapView.overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(inverseMatrix))
            } else {
                mapView.overlayManager.tilesOverlay.setColorFilter(null)
            }
            mapView.invalidate()
        }

        val myLocationOverlay = remember {
            MyLocationNewOverlay(GpsMyLocationProvider(context), mapView).apply {
                enableMyLocation()
            }
        }

        LaunchedEffect(isGuidanceActive) {
            if (isGuidanceActive) {
                hasGpsBearingEverBeenSet = false
                activeStepIndex = 0
                reachedCurrentManeuverJunction = false
                mapView.controller.zoomTo(18.5, 500L)
                val userPoint = currentLocation?.let { GeoPoint(it.latitude, it.longitude) }
                    ?: myLocationOverlay.myLocation
                    ?: getUserLocation(context)?.let { GeoPoint(it.latitude, it.longitude) }

                userPoint?.let { mapView.controller.animateTo(it) }
            } else {
                hasGpsBearingEverBeenSet = false
                activeStepIndex = 0
                reachedCurrentManeuverJunction = false
                mapView.mapOrientation = 0f
            }
        }

        LaunchedEffect(currentLocation, isGuidanceActive) {
            val loc = currentLocation
            if (isGuidanceActive && loc != null) {
                val userPoint = GeoPoint(loc.latitude, loc.longitude)

                mapView.controller.animateTo(userPoint)
                val speedMps = if (loc.hasSpeed()) loc.speed else 0f
                updateZoomBasedOnSpeed(mapView, speedMps)

                if (loc.hasBearing() && speedMps > 0.5f) {
                    lastGpsBearing = loc.bearing
                    hasGpsBearingEverBeenSet = true
                    mapView.mapOrientation = -loc.bearing
                } else if (speedMps <= 0.5f && hasGpsBearingEverBeenSet) {
                    mapView.mapOrientation = -lastGpsBearing
                }

                if (navigationSteps.isNotEmpty()) {
                    val nextStepIdx = activeStepIndex + 1
                    if (nextStepIdx < navigationSteps.size) {
                        val targetManeuverLoc = navigationSteps[nextStepIdx].maneuver?.location
                        if (targetManeuverLoc != null && targetManeuverLoc.size >= 2) {
                            val junctionPoint = GeoPoint(targetManeuverLoc[1], targetManeuverLoc[0])
                            val distToJunction = userPoint.distanceToAsDouble(junctionPoint)

                            if (distToJunction <= 15.0) {
                                reachedCurrentManeuverJunction = true
                            }

                            if (reachedCurrentManeuverJunction && distToJunction >= 12.0) {
                                activeStepIndex = nextStepIdx
                                reachedCurrentManeuverJunction = false
                            }
                        }
                    }
                }

                val now = System.currentTimeMillis()
                if (selectedTargetGeoPoint != null && (rawRoutePoints.isEmpty() || isUserOffRoute(userPoint, rawRoutePoints))) {
                    if (now - lastRouteFetchTime > 4000) {
                        lastRouteFetchTime = now
                        val navData = fetchStreetRouteDetails(userPoint, selectedTargetGeoPoint!!)
                        rawRoutePoints = navData.points
                        navigationSteps = navData.steps
                        activeStepIndex = 0
                        reachedCurrentManeuverJunction = false
                    }
                }

                if (rawRoutePoints.isNotEmpty()) {
                    displayRoutePoints = sliceRouteFromCurrentLocation(userPoint, rawRoutePoints)
                }
            }
        }

        LaunchedEffect(isGuidanceActive, selectedTargetGeoPoint) {
            if (isGuidanceActive && selectedTargetGeoPoint != null) {
                val userPoint = currentLocation?.let { GeoPoint(it.latitude, it.longitude) }
                    ?: myLocationOverlay.myLocation
                    ?: getUserLocation(context)?.let { GeoPoint(it.latitude, it.longitude) }

                if (userPoint != null) {
                    val navData = fetchStreetRouteDetails(userPoint, selectedTargetGeoPoint!!)
                    rawRoutePoints = navData.points
                    displayRoutePoints = sliceRouteFromCurrentLocation(userPoint, rawRoutePoints)
                    navigationSteps = navData.steps
                    activeStepIndex = 0
                    reachedCurrentManeuverJunction = false
                }
            } else {
                rawRoutePoints = emptyList()
                displayRoutePoints = emptyList()
                navigationSteps = emptyList()
                activeStepIndex = 0
                reachedCurrentManeuverJunction = false
            }
        }

        LaunchedEffect(displayRoutePoints) {
            if (displayRoutePoints.isNotEmpty()) {
                navigationPolyline.setPoints(displayRoutePoints)
                if (!mapView.overlays.contains(navigationPolyline)) {
                    mapView.overlays.add(navigationPolyline)
                }
            } else {
                mapView.overlays.remove(navigationPolyline)
            }
            mapView.invalidate()
        }

        LaunchedEffect(myLocationOverlay) {
            myLocationOverlay.runOnFirstFix {
                val loc = myLocationOverlay.myLocation
                if (loc != null) {
                    val point = GeoPoint(loc.latitude, loc.longitude)
                    (context as? Activity)?.runOnUiThread {
                        updateSearchCenterIfMoved(point)
                        mapView.controller.animateTo(point)
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            delay(1500)
            isInitialSettling = false
        }

        fun renderCategoryElements(allElements: List<Element>, category: PoiCategory, lang: AppLanguage) {
            val filtered = allElements.filter { it.belongsToCategory(category) }
            val normalPoiIcon = createEmojiMarkerIcon(context, category.icon, category.colorHex, isSelected = false)
            val selectedPoiIcon = createEmojiMarkerIcon(context, category.icon, category.colorHex, isSelected = true)

            filtered.forEach { element ->
                val lat = element.actualLat
                val lon = element.actualLon
                if (lat != null && lon != null) {
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(lat, lon)
                        title = element.getLocalizedTitle(category, lang)
                        snippet = formatSpotDetails(category, element.tags, lang)
                        icon = normalPoiIcon
                        relatedObject = category
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    }

                    marker.setOnMarkerClickListener { m, _ ->
                        currentlySelectedMarker?.let { prevMarker ->
                            val prevCat = (prevMarker.relatedObject as? PoiCategory) ?: category
                            prevMarker.icon = createEmojiMarkerIcon(context, prevCat.icon, prevCat.colorHex, isSelected = false)
                        }

                        m.icon = selectedPoiIcon
                        currentlySelectedMarker = m
                        m.showInfoWindow()

                        selectedTargetGeoPoint = m.position
                        selectedTargetTitle = m.title
                        selectedTargetDetails = m.snippet
                        true
                    }

                    mapView.overlays.add(marker)
                }
            }
            mapView.invalidate()

            if (filtered.isEmpty()) {
                val msg = if (lang == AppLanguage.BG) "Няма намерени '${category.labelBg}' в този радиус"
                else "No '${category.labelEn}' found in this radius"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }

        fun loadOrFilterData(category: PoiCategory, center: GeoPoint, radius: Float, lang: AppLanguage, forceReload: Boolean = false) {
            activeJob?.cancel()
            deselectCurrentMarker()
            isGuidanceActive = false

            mapView.overlays.clear()
            mapView.overlays.add(mapEventsOverlay)
            mapView.overlays.add(myLocationOverlay)

            val radiusMeters = (radius * 1000).toInt()
            val centerMarker = Marker(mapView).apply {
                position = center
                title = if (lang == AppLanguage.BG) "Избрана локация" else "Selected location"
                snippet = if (lang == AppLanguage.BG) "Център (радиус ${String.format("%.1f", radius)} км)" else "Center (radius ${String.format("%.1f", radius)} km)"
                icon = createEmojiMarkerIcon(context, "📍", "#D32F2F")
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            mapView.overlays.add(centerMarker)

            val circle = Polygon().apply {
                points = Polygon.pointsAsCircle(center, radiusMeters.toDouble())
                fillPaint.color = AndroidColor.argb(35, 33, 150, 243)
                outlinePaint.color = AndroidColor.argb(120, 33, 150, 243)
                outlinePaint.strokeWidth = 3f
            }
            mapView.overlays.add(circle)
            mapView.invalidate()

            val isMemoryHit = !forceReload &&
                    loadedAllElements != null &&
                    currentLoadedCenter != null &&
                    currentLoadedCenter!!.distanceToAsDouble(center) < 300.0 &&
                    abs(currentLoadedRadius - radius) < 0.1f

            if (isMemoryHit) {
                renderCategoryElements(loadedAllElements!!, category, lang)
                return
            }

            if (!forceReload) {
                val diskResponse = loadResponseFromDisk(context, center, radius)
                if (diskResponse != null) {
                    loadedAllElements = diskResponse.elements
                    currentLoadedCenter = center
                    currentLoadedRadius = radius
                    renderCategoryElements(diskResponse.elements, category, lang)
                    return
                }
            }

            activeJob = coroutineScope.launch {
                isLoading = true
                try {
                    val query = buildAllCategoriesOverpassQuery(center.latitude, center.longitude, radiusMeters)
                    var bestResponse: OverpassResponse? = null
                    var lastException: Exception? = null

                    for (serverUrl in OVERPASS_SERVERS) {
                        try {
                            val res = api.getNodes(serverUrl, query)
                            if (res.elements.isNotEmpty()) {
                                bestResponse = res
                                break
                            } else if (bestResponse == null) {
                                bestResponse = res
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            lastException = e
                            Log.d("SpotNaut", "Server $serverUrl error. Switching...")
                        }
                    }

                    val finalResponse = bestResponse ?: throw (lastException ?: Exception("Мрежова грешка при свързване."))

                    loadedAllElements = finalResponse.elements
                    currentLoadedCenter = center
                    currentLoadedRadius = radius

                    saveResponseToDisk(context, center, radius, finalResponse)
                    renderCategoryElements(finalResponse.elements, category, lang)

                } catch (e: CancellationException) {
                    // Ignore cancellation
                } catch (e: Exception) {
                    Log.e("SpotNaut", "Error fetching data", e)
                    Toast.makeText(context, e.localizedMessage ?: "Грешка при зареждане", Toast.LENGTH_LONG).show()
                } finally {
                    isLoading = false
                }
            }
        }

        val locationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.values.contains(true)) {
                myLocationOverlay.enableMyLocation()
                getUserLocation(context)?.let { userLocation ->
                    val point = GeoPoint(userLocation.latitude, userLocation.longitude)
                    updateSearchCenterIfMoved(point)
                    mapView.controller.animateTo(point)
                }
            }
        }

        LaunchedEffect(Unit) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        LaunchedEffect(selectedPoiCategory, searchCenterGeoPoint, radiusKm, currentLanguage, isInitialSettling) {
            if (isInitialSettling) return@LaunchedEffect
            delay(100)
            selectedPoiCategory?.let { category ->
                loadOrFilterData(category, searchCenterGeoPoint, radiusKm, currentLanguage)
            } ?: run {
                deselectCurrentMarker()
                mapView.overlays.clear()
                mapView.overlays.add(mapEventsOverlay)
                mapView.overlays.add(myLocationOverlay)
                mapView.invalidate()
            }
        }

        val loc = currentLocation ?: myLocationOverlay.lastFix
        val userGeoPoint = loc?.let { GeoPoint(it.latitude, it.longitude) }
            ?: myLocationOverlay.myLocation
            ?: getUserLocation(context)?.let { GeoPoint(it.latitude, it.longitude) }

        val destinationRelativeAngle = remember(userGeoPoint, selectedTargetGeoPoint, deviceAzimuth) {
            if (userGeoPoint != null && selectedTargetGeoPoint != null) {
                val results = FloatArray(2)
                Location.distanceBetween(
                    userGeoPoint.latitude, userGeoPoint.longitude,
                    selectedTargetGeoPoint!!.latitude, selectedTargetGeoPoint!!.longitude,
                    results
                )
                val targetAbsoluteBearing = (results[1] + 360f) % 360f
                (targetAbsoluteBearing - deviceAzimuth + 360f) % 360f
            } else 0f
        }

        val distanceToTargetMeters = remember(userGeoPoint, selectedTargetGeoPoint) {
            if (userGeoPoint != null && selectedTargetGeoPoint != null) {
                userGeoPoint.distanceToAsDouble(selectedTargetGeoPoint).toInt()
            } else 0
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize()
            )

            if (isGuidanceActive && selectedTargetGeoPoint != null) {
                val activeStep = remember(activeStepIndex, navigationSteps) {
                    if (navigationSteps.isEmpty()) null
                    else navigationSteps.getOrNull(activeStepIndex + 1) ?: navigationSteps.lastOrNull()
                }

                val distToNextStepMeters = remember(loc, activeStep) {
                    val stepLoc = activeStep?.maneuver?.location
                    if (stepLoc != null && stepLoc.size >= 2 && loc != null) {
                        val stepPoint = GeoPoint(stepLoc[1], stepLoc[0])
                        val userPoint = GeoPoint(loc.latitude, loc.longitude)
                        userPoint.distanceToAsDouble(stepPoint).toInt()
                    } else 0
                }

                val instructionText = getManeuverText(activeStep, currentLanguage)

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    shadowElevation = 12.dp,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            AutomotiveManeuverIcon(
                                step = activeStep,
                                modifier = Modifier
                                    .size(50.dp)
                                    .padding(end = 10.dp)
                            )

                            Column {
                                val distText = if (distToNextStepMeters >= 1000) {
                                    String.format("%.1f км", distToNextStepMeters / 1000f)
                                } else {
                                    "$distToNextStepMeters м"
                                }

                                Text(
                                    text = if (currentLanguage == AppLanguage.BG) "След $distText" else "In $distText",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = instructionText,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
                                    maxLines = 2
                                )
                            }
                        }

                        Button(
                            onClick = { isGuidanceActive = false },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(if (currentLanguage == AppLanguage.BG) "Край" else "End", fontSize = 13.sp)
                        }
                    }
                }
            } else {
                // Top control bar
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        shadowElevation = 10.dp,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Line 1: Main Category bar
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(MainCategory.entries) { mainCat ->
                                    val isSelected = selectedMainCategory == mainCat
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            if (selectedMainCategory == mainCat) {
                                                isSubCategoryListVisible = !isSubCategoryListVisible
                                            } else {
                                                selectedMainCategory = mainCat
                                                selectedPoiCategory = null // Default: no sub-item selected
                                                isSubCategoryListVisible = true
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = "${mainCat.icon} ${mainCat.label(currentLanguage)}",
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    )
                                }
                            }

                            // Items from selected category displayed as a multi-column grid without scrolling
                            if (isSubCategoryListVisible) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 2.dp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                )

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    currentSubCategories.chunked(2).forEach { rowItems ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            rowItems.forEach { poi ->
                                                val isSelected = selectedPoiCategory == poi
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                    onClick = {
                                                        selectedPoiCategory = poi
                                                        isSubCategoryListVisible = false // Auto-hide grid on selection so map is clearly viewed
                                                    },
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Box(
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = "${poi.icon} ${poi.label(currentLanguage)}",
                                                            fontSize = 11.sp,
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                                            textAlign = TextAlign.Center,
                                                            maxLines = 2
                                                        )
                                                    }
                                                }
                                            }
                                            if (rowItems.size < 2) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3-dots menu row aligned to the right
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            shadowElevation = 6.dp,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            Box {
                                IconButton(
                                    onClick = { showMenu = true },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Text("⋮", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                }

                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(if (currentLanguage == AppLanguage.BG) "За приложението" else "About") },
                                        onClick = {
                                            showMenu = false
                                            showAboutDialog = true
                                        }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(if (currentLanguage == AppLanguage.BG) "Изход" else "Exit") },
                                        onClick = {
                                            showMenu = false
                                            (context as? Activity)?.finish()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 2.dp)
                        .fillMaxWidth()
                )
            }

            if (selectedTargetGeoPoint != null && !isGuidanceActive) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 82.dp, start = 12.dp, end = 12.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    shadowElevation = 10.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedTargetTitle ?: "",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { deselectCurrentMarker() },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Text("✕", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (!selectedTargetDetails.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = selectedTargetDetails ?: "",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                                maxLines = 3
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { isGuidanceActive = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(
                                    text = if (currentLanguage == AppLanguage.BG) "Вградена\nНавигация" else "Built-in\nGuidance",
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    selectedTargetGeoPoint?.let { pt ->
                                        openGoogleMaps(context, pt, selectedTargetTitle ?: "Target")
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(
                                    text = "Google Maps\nWaze",
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            // Destination Compass Arrow with Distance Badge
            if (selectedTargetGeoPoint != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(bottom = if (!isGuidanceActive) 230.dp else 90.dp, end = 16.dp)
                ) {
                    DestinationArrowIndicator(
                        angle = destinationRelativeAngle,
                        distanceMeters = distanceToTargetMeters,
                        arrowColor = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Bottom control bar
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp, start = 8.dp, end = 8.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        shadowElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    ) {
                        TextButton(
                            onClick = {
                                currentLanguage = if (currentLanguage == AppLanguage.BG) AppLanguage.EN else AppLanguage.BG
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Text(
                                text = if (currentLanguage == AppLanguage.BG) "🇧🇬 BG" else "🇬🇧 EN",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        shadowElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    ) {
                        IconButton(
                            onClick = {
                                isDarkMode = !isDarkMode
                                prefs.edit().putBoolean("is_dark_mode", isDarkMode).apply()
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Text(text = if (isDarkMode) "🌙" else "☀️", fontSize = 18.sp)
                        }
                    }

                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        shadowElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (currentLanguage == AppLanguage.BG) "Радиус: ${String.format("%.1f", radiusKm)} км"
                                else "Radius: ${String.format("%.1f", radiusKm)} km",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Slider(
                                value = radiusKm,
                                onValueChange = { radiusKm = it },
                                valueRange = 1.0f..4.0f,
                                steps = 5,
                                modifier = Modifier.height(22.dp)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        shadowElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    ) {
                        IconButton(
                            onClick = {
                                if (!isInitialSettling && !isLoading && selectedPoiCategory != null) {
                                    loadOrFilterData(selectedPoiCategory!!, searchCenterGeoPoint, radiusKm, currentLanguage, forceReload = true)
                                }
                            },
                            enabled = !isLoading,
                            modifier = Modifier.size(40.dp)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text("🔄", fontSize = 16.sp)
                            }
                        }
                    }

                    // My Location Button
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        shadowElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    ) {
                        IconButton(
                            onClick = {
                                val myLoc = myLocationOverlay.myLocation
                                val point = if (myLoc != null) GeoPoint(myLoc.latitude, myLoc.longitude)
                                else getUserLocation(context)?.let { GeoPoint(it.latitude, it.longitude) }

                                if (point != null) {
                                    searchCenterGeoPoint = point
                                    mapView.controller.animateTo(point)
                                } else {
                                    val msg = if (currentLanguage == AppLanguage.BG) "Търсене на GPS сигнал..." else "Searching for GPS signal..."
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Text("🎯", fontSize = 18.sp)
                        }
                    }
                }
            }

            if (showAboutDialog) {
                val scrollState = rememberScrollState()

                val aboutContentText = remember(currentLanguage) {
                    val sb = StringBuilder()
                    if (currentLanguage == AppLanguage.BG) {
                        sb.append("SpotNaut е вашият интерактивен градски спътник за бързо откриване на 100 вида градски обекта около вас и упътване до тях чрез OpenStreetMap данни.\n\n")
                        sb.append("📍 Всички обекти по категории:\n\n")
                        MainCategory.entries.forEach { mainCat ->
                            val pois = PoiCategory.entries.filter { it.mainCategory == mainCat }
                            sb.append("${mainCat.icon} ${mainCat.labelBg}:\n")
                            sb.append(pois.joinToString(", ") { it.labelBg })
                            sb.append("\n\n")
                        }
                    } else {
                        sb.append("SpotNaut is your interactive companion to quickly discover 100 types of nearby urban points of interest and navigate directly to them using OpenStreetMap data.\n\n")
                        sb.append("📍 All searchable objects by category:\n\n")
                        MainCategory.entries.forEach { mainCat ->
                            val pois = PoiCategory.entries.filter { it.mainCategory == mainCat }
                            sb.append("${mainCat.icon} ${mainCat.labelEn}:\n")
                            sb.append(pois.joinToString(", ") { it.labelEn })
                            sb.append("\n\n")
                        }
                    }
                    sb.toString().trim()
                }

                AlertDialog(
                    onDismissRequest = { showAboutDialog = false },
                    title = {
                        Column {
                            Text("SpotNaut", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text("by Ventsislav Negentsov", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                            Text("ventsislavnegentsov@gmail.com", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    text = {
                        Box(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                            Text(
                                text = aboutContentText,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAboutDialog = false }) {
                            Text(if (currentLanguage == AppLanguage.BG) "Затвори" else "Close")
                        }
                    }
                )
            }

            AnimatedVisibility(
                visible = showSplash,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                SplashScreen(onFinished = { showSplash = false })
            }
        }
    }
}

// --- Helper Location Retrieval ---

@SuppressLint("MissingPermission")
private fun getUserLocation(context: Context): Location? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
}