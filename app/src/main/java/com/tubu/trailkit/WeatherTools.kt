package com.tubu.trailkit
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
private data class WeatherReport(val place:String,val temperature:Double,val feels:Double,val humidity:Int,val rain:Double,val wind:Double,val description:String)
private fun weatherDescription(code:Int)=when(code){0->"晴朗";1,2->"晴间多云";3->"阴天";45,48->"雾";51,53,55->"毛毛雨";61,63,65->"降雨";66,67->"冻雨";71,73,75,77->"降雪";80,81,82->"阵雨";85,86->"阵雪";95,96,99->"雷暴";else->"天气情况未知"}
private suspend fun fetchWeather(place:String):WeatherReport=withContext(Dispatchers.IO){
 val encoded=URLEncoder.encode(place,"UTF-8")
 val geo=JSONObject(URL("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=zh&format=json").readText())
 val result=geo.optJSONArray("results")?.optJSONObject(0)?:error("没有找到地点，请输入更具体的地名")
 val lat=result.getDouble("latitude");val lon=result.getDouble("longitude")
 val label=listOf(result.optString("name"),result.optString("admin1"),result.optString("country")).filter{it.isNotBlank()}.distinct().joinToString(" · ")
 val raw=JSONObject(URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m&timezone=auto").readText())
 val c=raw.getJSONObject("current")
 WeatherReport(label,c.getDouble("temperature_2m"),c.getDouble("apparent_temperature"),c.getInt("relative_humidity_2m"),c.getDouble("precipitation"),c.getDouble("wind_speed_10m"),weatherDescription(c.getInt("weather_code")))
}
@Composable fun TrailWeatherDialog(place:String,onClose:()->Unit){
 var report by remember{mutableStateOf<WeatherReport?>(null)};var error by remember{mutableStateOf("")};var loading by remember{mutableStateOf(true)};var refresh by remember{mutableIntStateOf(0)}
 LaunchedEffect(place,refresh){loading=true;try{report=fetchWeather(place.ifBlank{error("请先填写目的地")});error=""}catch(e:Exception){error=e.message?:"天气查询失败"}finally{loading=false}}
 AlertDialog(onDismissRequest=onClose,title={Text("目的地天气")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
  when{loading->CircularProgressIndicator();report!=null->{
   Text(report!!.place,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold,color=Color(0xFF183C32))
   Text(report!!.description,fontSize=14.sp);Text(report!!.temperature.toString()+"°C",fontSize=32.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
   Text("体感 "+report!!.feels+"°C · 湿度 "+report!!.humidity+"%")
   Text("降水 "+report!!.rain+" mm · 风速 "+report!!.wind+" km/h")
   Text("山地天气变化快，此数据仅作出发前参考；高海拔、山谷和营地天气可能明显不同。",fontSize=11.sp,color=Color(0xFF9A5A32))
  };else->Text(error,color=MaterialTheme.colorScheme.error)}
 }},confirmButton={TextButton(onClick=onClose){Text("关闭")}},dismissButton={TextButton(onClick={refresh++}){Text("刷新")}})
}
