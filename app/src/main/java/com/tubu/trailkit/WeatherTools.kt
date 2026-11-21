package com.tubu.trailkit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class WeatherReport(
    val place:String,val temperature:Double,val feels:Double,val humidity:Int,val rain:Double,
    val wind:Double,val gust:Double,val description:String,val hourly:List<String>,
    val daily:List<String>,val risk:String
)

private fun weatherDescription(code:Int)=when(code){
    0->"晴朗";1,2->"晴间多云";3->"阴天";45,48->"雾";51,53,55->"毛毛雨"
    61,63,65->"降雨";66,67->"冻雨";71,73,75,77->"降雪";80,81,82->"阵雨"
    85,86->"阵雪";95,96,99->"雷暴";else->"天气情况未知"
}

private fun readWeatherUrl(url:String):String {
    val connection=(URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout=8_000
        readTimeout=12_000
        requestMethod="GET"
        setRequestProperty("Accept","application/json")
        setRequestProperty("User-Agent","TrailKit-Android/1.6")
        instanceFollowRedirects=true
    }
    try {
        val status=connection.responseCode
        val stream=if(status in 200..299)connection.inputStream else connection.errorStream
        val body=stream?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}.orEmpty()
        if(status !in 200..299) error("天气服务暂不可用（HTTP $status）")
        return body
    } finally { connection.disconnect() }
}

private suspend fun fetchWeather(place:String):WeatherReport=withContext(Dispatchers.IO){
    val encoded=URLEncoder.encode(place,"UTF-8")
    val geo=JSONObject(readWeatherUrl("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=zh&format=json"))
    val result=geo.optJSONArray("results")?.optJSONObject(0)?:error("没有找到地点，请输入更具体的地名")
    val lat=result.getDouble("latitude");val lon=result.getDouble("longitude")
    val label=listOf(result.optString("name"),result.optString("admin1"),result.optString("country")).filter{it.isNotBlank()}.distinct().joinToString(" · ")
    val url="https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m,wind_gusts_10m&hourly=temperature_2m,precipitation_probability,wind_speed_10m,wind_gusts_10m,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max,wind_gusts_10m_max,weather_code&forecast_days=7&timezone=auto"
    val raw=JSONObject(readWeatherUrl(url))
    val c=raw.getJSONObject("current")
    val h=raw.getJSONObject("hourly")
    val hTimes=h.getJSONArray("time");val hTemp=h.getJSONArray("temperature_2m")
    val hRain=h.getJSONArray("precipitation_probability");val hWind=h.getJSONArray("wind_speed_10m")
    val hGust=h.optJSONArray("wind_gusts_10m");val hCode=h.getJSONArray("weather_code")
    val currentTime=c.optString("time")
    val start=(0 until hTimes.length()).firstOrNull{hTimes.optString(it)>=currentTime}?:0
    val hourly=(start until minOf(start+12,hTimes.length())).map { i ->
        val gust=hGust?.optDouble(i,Double.NaN)?.takeIf{it.isFinite()}
        "${hTimes.getString(i).substringAfter("T")}  ${hTemp.getDouble(i).toInt()}° · 降水 ${hRain.getInt(i)}% · 风 ${hWind.getDouble(i).toInt()} km/h" +
            (gust?.let{" · 阵风 ${it.toInt()} km/h"}.orEmpty()) + " · ${weatherDescription(hCode.getInt(i))}"
    }
    val d=raw.getJSONObject("daily")
    val dTimes=d.getJSONArray("time");val dMax=d.getJSONArray("temperature_2m_max");val dMin=d.getJSONArray("temperature_2m_min")
    val dRain=d.getJSONArray("precipitation_probability_max");val dWind=d.getJSONArray("wind_speed_10m_max")
    val dGust=d.optJSONArray("wind_gusts_10m_max");val dCode=d.getJSONArray("weather_code")
    val daily=(0 until dTimes.length()).map { i ->
        val gust=dGust?.optDouble(i,Double.NaN)?.takeIf{it.isFinite()}
        "${dTimes.getString(i)}  ${dMin.getDouble(i).toInt()}° / ${dMax.getDouble(i).toInt()}° · 降水 ${dRain.getInt(i)}% · 最大风 ${dWind.getDouble(i).toInt()} km/h" +
            (gust?.let{" · 最大阵风 ${it.toInt()} km/h"}.orEmpty()) + " · ${weatherDescription(dCode.getInt(i))}"
    }
    val temp=c.getDouble("temperature_2m");val rain=c.getDouble("precipitation")
    val wind=c.getDouble("wind_speed_10m");val gust=c.optDouble("wind_gusts_10m",wind)
    val code=c.getInt("weather_code")
    val risk=when {
        code in listOf(95,96,99) -> "雷暴风险：避免山脊、孤立树木和开阔地，及时寻找安全避险处。"
        code in listOf(66,67,71,73,75,77,85,86) -> "冻雨 / 降雪风险：注意结冰、能见度和保暖，评估路线是否需要取消。"
        gust>=70.0 -> "强阵风风险：当前阵风约 ${gust.toInt()} km/h。山脊、垭口和帐篷营地可能更危险，应谨慎评估或取消暴露路段。"
        wind>=40.0 -> "强风风险：当前平均风速较高，山脊和垭口阵风可能更强，谨慎评估。"
        rain>=2 || code in listOf(61,63,65,80,81,82) -> "降水风险：关注湿滑路面、溪流水位和失温风险。"
        temp<=2 -> "低温风险：检查保暖层、防风层和备用干燥衣物。"
        else -> "暂无明显天气风险信号；山区天气变化快，山区实况与网格预报可能不同，出发前仍需复核。"
    }
    WeatherReport(label,temp,c.getDouble("apparent_temperature"),c.getInt("relative_humidity_2m"),rain,wind,gust,weatherDescription(code),hourly,daily,risk)
}


private fun weatherCacheKey(place:String)="forecast_${place.trim().lowercase(Locale.ROOT)}"

private fun saveWeatherCache(context:Context,place:String,report:WeatherReport,savedAt:Long) {
    val value=JSONObject()
        .put("place",report.place)
        .put("temperature",report.temperature)
        .put("feels",report.feels)
        .put("humidity",report.humidity)
        .put("rain",report.rain)
        .put("wind",report.wind)
        .put("gust",report.gust)
        .put("description",report.description)
        .put("hourly",org.json.JSONArray().apply{report.hourly.forEach{put(it)}})
        .put("daily",org.json.JSONArray().apply{report.daily.forEach{put(it)}})
        .put("risk",report.risk)
        .put("savedAt",savedAt)
    context.getSharedPreferences("trailkit_weather_cache",Context.MODE_PRIVATE)
        .edit().putString(weatherCacheKey(place),value.toString()).apply()
}

private fun loadWeatherCache(context:Context,place:String):Pair<WeatherReport,Long>? {
    if(place.isBlank())return null
    return try {
        val raw=context.getSharedPreferences("trailkit_weather_cache",Context.MODE_PRIVATE)
            .getString(weatherCacheKey(place),null) ?: return null
        val value=JSONObject(raw)
        val hourlyArray=value.optJSONArray("hourly") ?: org.json.JSONArray()
        val dailyArray=value.optJSONArray("daily") ?: org.json.JSONArray()
        val hourly=List(hourlyArray.length()){hourlyArray.optString(it)}
        val daily=List(dailyArray.length()){dailyArray.optString(it)}
        val report=WeatherReport(
            value.optString("place",place),
            value.optDouble("temperature",Double.NaN),
            value.optDouble("feels",Double.NaN),
            value.optInt("humidity"),
            value.optDouble("rain",Double.NaN),
            value.optDouble("wind",Double.NaN),
            value.optDouble("gust",Double.NaN),
            value.optString("description","天气情况未知"),
            hourly,daily,value.optString("risk","缓存天气，仅供参考")
        )
        report to value.optLong("savedAt",0L)
    } catch(_:Exception){null}
}

@Composable fun TrailWeatherDialog(place:String,onClose:()->Unit){
    val context=LocalContext.current
    val cached=remember(context,place){loadWeatherCache(context,place)}
    var report by remember(context,place){mutableStateOf(cached?.first)}
    var cachedAt by remember(context,place){mutableLongStateOf(cached?.second?:0L)}
    var error by remember{mutableStateOf("")}
    var loading by remember{mutableStateOf(true)}
    var refresh by remember{mutableIntStateOf(0)}
    LaunchedEffect(context,place,refresh){
        loading=true
        var fetched:WeatherReport?=null
        var lastError=""
        for(attempt in 0..1) {
            try {
                fetched=fetchWeather(place.trim().ifBlank{throw IllegalArgumentException("请先填写目的地")})
                break
            } catch(e:Exception) {
                lastError=e.message?:"天气查询失败"
                if(attempt==0 && place.isNotBlank()) delay(700L)
            }
        }
        val latest=fetched
        if(latest!=null) {
            val savedAt=System.currentTimeMillis()
            report=latest
            cachedAt=savedAt
            error=""
            withContext(Dispatchers.IO){saveWeatherCache(context,place,latest,savedAt)}
        } else {
            error=lastError.ifBlank{"天气查询失败"}+
                if(report!=null) "；当前显示上次成功获取的缓存预报" else "；请检查网络后重试"
        }
        loading=false
    }
    val cacheTime=if(cachedAt>0L)SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(Date(cachedAt)) else "未知"
    AlertDialog(onDismissRequest=onClose,title={Text("目的地天气 · 徒步预报")},text={
        Column(Modifier.heightIn(max=540.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)){
            when {
                report!=null -> {
                    val r=report!!
                    if(loading) Text("正在更新天气…暂时显示上次成功数据",fontSize=11.sp,color=Color(0xFF8A6850))
                    else if(error.isNotBlank()) Surface(color=Color(0xFFFFF0DF),shape=MaterialTheme.shapes.medium) {
                        Text(error,Modifier.padding(9.dp),fontSize=11.sp,color=Color(0xFF87522D))
                    }
                    Text("最后成功获取：$cacheTime",fontSize=10.sp,color=Color(0xFF78827A))
                    Text(r.place,fontWeight=FontWeight.Bold,color=Color(0xFF183C32))
                    Text(r.description,fontSize=14.sp)
                    Text("${r.temperature}°C",fontSize=34.sp,fontWeight=FontWeight.Bold,color=Color(0xFF183C32))
                    Text("体感 ${r.feels}°C · 湿度 ${r.humidity}% · 降水 ${r.rain} mm · 风速 ${r.wind.toInt()} km/h · 阵风 ${r.gust.toInt()} km/h",fontSize=12.sp)
                    Surface(color=Color(0xFFFFF0DF),shape=MaterialTheme.shapes.medium) { Text(r.risk,Modifier.padding(12.dp),fontSize=12.sp,color=Color(0xFF87522D)) }
                    HorizontalDivider()
                    Text("未来 12 小时",fontWeight=FontWeight.Bold,color=Color(0xFF183C32))
                    r.hourly.forEach { Text(it,fontSize=11.sp) }
                    HorizontalDivider()
                    Text("未来 7 天",fontWeight=FontWeight.Bold,color=Color(0xFF183C32))
                    r.daily.forEach { Text(it,fontSize=11.sp) }
                    Text("数据来自 Open-Meteo。缓存预报不是实时天气，可能已经过期；高海拔、山谷和营地天气可能与网格预报明显不同。出发前请联网刷新，并以现场情况为准。",fontSize=10.sp,color=Color(0xFF8A6850))
                }
                loading -> CircularProgressIndicator()
                else -> Text(error,color=MaterialTheme.colorScheme.error)
            }
        }
    },confirmButton={TextButton(onClick=onClose){Text("关闭")}},dismissButton={TextButton(onClick={refresh++},enabled=!loading){Text("刷新")}})
}
