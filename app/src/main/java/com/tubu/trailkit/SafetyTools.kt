package com.tubu.trailkit
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
@Composable fun TrailSafetyDialog(project:Project,onClose:()->Unit){
 val context=LocalContext.current
 var contact by remember{mutableStateOf(Regex("[+]?[0-9][0-9 -]{5,}").find(project.emergency)?.value?.trim()?:"")}
 val phone=contact.filter{it.isDigit()||it=='+'}
 val message="【TrailKit 徒步安全信息】\n项目："+project.name+"\n目的地："+project.place+"\n路线："+project.route+"\n住宿/营地："+project.lodging+"\n补给/水源："+project.resupply+"\n紧急联系人："+project.emergency+"\n请协助确认我的安全状况。"
 AlertDialog(onDismissRequest=onClose,title={Text("户外安全中心")},text={Column(verticalArrangement=Arrangement.spacedBy(9.dp)){
  Text("紧急联系人和路线资料会整理成求助信息。发送或拨号前请检查内容。")
  OutlinedTextField(contact,{contact=it},label={Text("紧急联系电话")},singleLine=true)
  Text("本功能不会自动拨打电话或自动发送短信；需要你确认并通过系统电话/短信界面操作。",style=MaterialTheme.typography.bodySmall)
 }},confirmButton={Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
  TextButton(onClick={context.startActivity(Intent(Intent.ACTION_SENDTO,Uri.parse("smsto:"+phone)).putExtra("sms_body",message))}){Text("短信求助")}
  TextButton(onClick={if(phone.isNotBlank())context.startActivity(Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+phone)))},enabled=phone.isNotBlank()){Text("拨打电话")}
 }},dismissButton={Row{
  TextButton(onClick={context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,message),"分享求助信息"))}){Text("分享求助信息")}
  TextButton(onClick=onClose){Text("关闭")}
 }})
}
