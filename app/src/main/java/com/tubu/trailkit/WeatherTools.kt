package com.tubu.trailkit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

private data class WeatherReport(
    val place:String,val temperature:Double,val feels:Double,val humidity:Int,val rain:Double,val wind:Double,
    val description:String,val hourly:List<String>,val daily:List<String>,val risk:String
)
private fun weatherDescription(code:Int)=when(code){0->"晴朗";1,2->"晴间多云";3->"阴天";45,48->"雾";51,53,55->"毛毛雨";61,63,65->"降雨";66,67->"冻雨";71,73,75,77->"降雪";80,81,82->"阵雨";85,86->"阵雪";95,96,99->"雷暴";else->"天气情况未知"}
private suspend fun fetchWeather(place:String):WeatherReport=withContext(Dispatchers.IO){
    val encoded=URLEncoder.encode(place,"UTF-8")
    val geo=JSONObject(URL("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=zh&format=json").readText())
    val result=geo.optJSONArray("results")?.optJSONObject(0)?:error("没有找到地点，请输入更具体的地名")
    val lat=result.getDouble("latitude");val lon=result.getDouble("longitude")
    val label=listOf(result.optString("name"),result.optString("admin1"),result.optString("country")).filter{it.isNotBlank()}.distinct().joinToString(" · ")
    val url="https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m&hourly=temperature_2m,precipitation_probability,wind_speed_10m,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max,weather_code&forecast_days=7&timezone=auto"
    val raw=JSONObject(URL(url).readText())
    val c=raw.getJSONObject("current")
    val h=raw.getJSONObject("hourly")
    val hTimes=h.getJSONArray("time");val hTemp=h.getJSONArray("temperature_2m");val hRain=h.getJSONArray("precipitation_probability")
    val hWind=h.getJSONArray("wind_speed_10m");val hCode=h.getJSONArray("weather_code")
    val currentTime=c.optString("time")
    val start=(0 until hTimes.length()).firstOrNull{hTimes.optString(it)>=currentTime}?:0
    val hourly=(start until minOf(start+12,hTimes.length())).map { i ->
        "${hTimes.getString(i).substringAfter("T")}  ${hTemp.getDouble(i).toInt()}° · 降水 ${hRain.getInt(i)}% · 风 ${hWind.getDouble(i).toInt()} km/h · ${weatherDescription(hCode.getInt(i))}"
    }
    val d=raw.getJSONObject("daily")
    val dTimes=d.getJSONArray("time");val dMax=d.getJSONArray("temperature_2m_max");val dMin=d.getJSONArray("temperature_2m_min")
    val dRain=d.getJSONArray("precipitation_probability_max");val dWind=d.getJSONArray("wind_speed_10m_max");val dCode=d.getJSONArray("weather_code")
    val daily=(0 until dTimes.length()).map { i ->
        "${dTimes.getString(i)}  ${dMin.getDouble(i).toInt()}° / ${dMax.getDouble(i).toInt()}° · 降水 ${dRain.getInt(i)}% · 最大风 ${dWind.getDouble(i).toInt()} km/h · ${weatherDescription(dCode.getInt(i))}"
    }
    val temp=c.getDouble("temperature_2m");val rain=c.getDouble("precipitation");val wind=c.getDouble("wind_speed_10m");val code=c.getInt("weather_code")
    val risk=when {
        code in listOf(95,96,99) -> "雷暴风险：避免山脊、孤立树木和开阔地，及时寻找安全避险处。"
        code in listOf(66,67,71,73,75,77,85,86) -> "冻雨 / 降雪风险：注意结冰、能见度和保暖，评估路线是否需要取消。"
        wind>=40 -> "强风风险：当前风速较高，山脊和垭口阵风可能更强，谨慎评估。"
        rain>=2 || code in listOf(61,63,65,80,81,82) -> "降水风险：关注湿滑路面、溪流水位和失温风险。"
        temp<=2 -> "低温风险：检查保暖层、防风层和备用干燥衣物。"
        else -> "暂无明显天气风险信号；山区天气变化快，出发前仍需复核预报。"
    }
    WeatherReport(label,temp,c.getDouble("apparent_temperature"),c.getInt("relative_humidity_2m"),rain,wind,weatherDescription(code),hourly,daily,risk)
}
@Composable fun TrailWeatherDialog(place:String,onClose:()->Unit){
    var report by remember{mutableStateOf<WeatherReport?>(null)}
    var error by remember{mutableStateOf("")}
    var loading by remember{mutableStateOf(true)}
    var refresh by remember{mutableIntStateOf(0)}
    LaunchedEffect(place,refresh){
        loading=true
        try{report=fetchWeather(place.ifBlank{throw IllegalArgumentException("请先填写目的地")});error=""}
        catch(e:Exception){error=e.message?:"天气查询失败"}
        finally{loading=false}
    }
    AlertDialog(onDismissRequest=onClose,title={Text("目的地天气 · 徒步预报")},text={
        Column(Modifier.heightIn(max=540.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)){
            when {
                loading -> CircularProgressIndicator()
                report!=null -> {
                    val r=report!!
                    Text(r.place,fontWeight=FontWeight.Bold,color=Color(0xFF183C32))
                    Text(r.description,fontSize=14.sp)
                    Text("${r.temperature}°C",fontSize=34.sp,fontWeight=FontWeight.Bold,color=Color(0xFF183C32))
                    Text("体感 ${r.feels}°C · 湿度 ${r.humidity}% · 降水 ${r.rain} mm · 风速 ${r.wind} km/h",fontSize=12.sp)
                    Surface(color=Color(0xFFFFF0DF),shape=MaterialTheme.shapes.medium) { Text(r.risk,Modifier.padding(12.dp),fontSize=12.sp,color=Color(0xFF87522D)) }
                    HorizontalDivider()
                    Text("未来 12 小时",fontWeight=FontWeight.Bold,color=Color(0xFF183C32))
                    r.hourly.forEach { Text(it,fontSize=11.sp) }
                    HorizontalDivider()
                    Text("未来 7 天",fontWeight=FontWeight.Bold,color=Color(0xFF183C32))
                    r.daily.forEach { Text(it,fontSize=11.sp) }
                    Text("数据来自 Open-Meteo。天气预报仅供行程决策参考；高海拔、山谷和营地天气可能与网格预报明显不同。雷暴、强风或降雪时应以现场情况和安全判断为准。",fontSize=10.sp,color=Color(0xFF8A6850))
                }
                else -> Text(error,color=MaterialTheme.colorScheme.error)
            }
        }
    },confirmButton={TextButton(onClick=onClose){Text("关闭")}},dismissButton={TextButton(onClick={refresh++}){Text("刷新")}})
}
