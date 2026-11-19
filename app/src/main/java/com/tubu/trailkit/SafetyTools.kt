package com.tubu.trailkit
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
@Composable fun TrailSafetyDialog(project:Project,onClose:()->Unit){
 val context=LocalContext.current
 var contact by remember{mutableStateOf(Regex("[+]?[0-9][0-9 -]{5,}").find(project.emergency)?.value?.trim()?:"")}
 var coordinates by remember{mutableStateOf("")}
 var locationStatus by remember{mutableStateOf("尚未获取定位")}
 val locationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->
  val granted=result[Manifest.permission.ACCESS_FINE_LOCATION]==true||result[Manifest.permission.ACCESS_COARSE_LOCATION]==true
  if(granted){
   try{
    val manager=context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val provider=if(result[Manifest.permission.ACCESS_FINE_LOCATION]==true && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
    manager.requestSingleUpdate(provider,object:LocationListener{
     override fun onLocationChanged(location:Location){coordinates=String.format(java.util.Locale.US,"%.6f, %.6f",location.latitude,location.longitude);locationStatus="定位已获取 · 精度约 "+location.accuracy.toInt()+" 米"}
     @Deprecated("Deprecated in Java") override fun onStatusChanged(provider:String?,status:Int,extras:android.os.Bundle?){}
     override fun onProviderEnabled(provider:String){}
     override fun onProviderDisabled(provider:String){}
    },Looper.getMainLooper())
    locationStatus="正在获取 GPS 定位，请在开阔处稍候"
   }catch(e:Exception){locationStatus="定位失败："+(e.message?:"请检查定位开关")}
  }else locationStatus="未授予定位权限"
 }
 val phone=contact.filter{it.isDigit()||it=='+'}
 val mapLink=if(coordinates.isNotBlank()) "https://maps.google.com/?q="+coordinates.replace(" ","") else ""
 val message="【TrailKit 徒步安全信息】\n项目："+project.name+"\n目的地："+project.place+"\n当前位置："+(coordinates.ifBlank{"未获取"})+(if(mapLink.isNotBlank())"\n地图定位："+mapLink else "")+"\n路线："+project.route+"\n住宿/营地："+project.lodging+"\n补给/水源："+project.resupply+"\n紧急联系人："+contact+"\n请协助确认我的安全状况。"
 AlertDialog(onDismissRequest=onClose,title={Text("户外安全中心")},text={Column(verticalArrangement=Arrangement.spacedBy(9.dp)){
  Text("紧急联系人和路线资料会整理成求助信息。发送或拨号前请检查内容。")
  OutlinedTextField(contact,{contact=it},label={Text("紧急联系电话")},singleLine=true)
  OutlinedButton(onClick={locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))},modifier=Modifier.fillMaxWidth()){Text("获取当前位置")}
  Text(locationStatus,style=MaterialTheme.typography.bodySmall)
  if(coordinates.isNotBlank()) Text(coordinates,style=MaterialTheme.typography.bodyMedium)
  Text("本功能不会自动拨打电话或自动发送短信；需要你确认并通过系统电话/短信界面操作。",style=MaterialTheme.typography.bodySmall)
 }},confirmButton={Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
  TextButton(onClick={context.startActivity(Intent(Intent.ACTION_SENDTO,Uri.parse("smsto:"+phone)).putExtra("sms_body",message))}){Text("短信求助")}
  TextButton(onClick={if(phone.isNotBlank())context.startActivity(Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+phone)))},enabled=phone.isNotBlank()){Text("拨打电话")}
 }},dismissButton={Row{
  TextButton(onClick={context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,message),"分享求助信息"))}){Text("分享求助信息")}
  TextButton(onClick=onClose){Text("关闭")}
 }})
}
