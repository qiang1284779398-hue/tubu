package com.tubu.trailkit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun TrailKitAdvancedDialog(
    project: Project,
    onClose: () -> Unit,
    onAddSuggested: (List<Gear>) -> Unit
) {
    val categories = listOf("背负系统","帐篷与营地系统","睡眠系统","衣物系统","鞋靴系统","炊事与饮水系统","行进系统","电子与导航系统","急救与卫生系统")
    val forest = Color(0xFF183C32)
    val muted = Color(0xFF78827A)
    val totalWeight = project.gear.sumOf { it.weightGrams * it.quantity }
    val unknownWeight = project.gear.count { it.weightGrams <= 0.0 }
    val packedCount = project.gear.count { it.packed }
    val suggestions = listOf(
        Gear("suggest-first-aid","急救包","急救与卫生系统",0.0,1,"基础急救用品","",180.0),
        Gear("suggest-headlamp","头灯及备用电池","电子与导航系统",0.0,1,"夜行和营地照明","",120.0),
        Gear("suggest-rain","雨衣 / 防雨层","衣物系统",0.0,1,"按天气检查防水能力","",250.0),
        Gear("suggest-power","充电宝与充电线","电子与导航系统",0.0,1,"检查容量和电量","",220.0),
        Gear("suggest-water","饮水与净水方案","炊事与饮水系统",0.0,1,"按水源情况准备","",300.0),
        Gear("suggest-map","离线地图与导航备份","电子与导航系统",0.0,1,"提前下载地图并准备备用导航","",30.0),
        Gear("suggest-warm","保暖层与备用袜","衣物系统",0.0,1,"按最低气温准备","",350.0),
        Gear("suggest-emergency","应急保温毯 / 哨子","急救与卫生系统",0.0,1,"应急信号与失温防护","",80.0),
        Gear("suggest-poles","登山杖","行进系统",0.0,1,"检查锁扣与杖尖","",500.0),
        Gear("suggest-gaiter","雪套 / 防砂石护腿","鞋靴系统",0.0,1,"泥雪碎石路段按需携带","",180.0)
    ).filterNot { item -> project.gear.any { it.name.equals(item.name,true) } }
    AlertDialog(
        onDismissRequest=onClose,
        title={ Text("智能装备与负重分析") },
        text={
            Column(Modifier.heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(project.name,fontWeight=FontWeight.Bold,color=forest)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    MetricCard("装备总重",String.format(Locale.CHINA,"%.2f kg",totalWeight/1000.0))
                    MetricCard("已打包",packedCount.toString()+" / "+project.gear.size)
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    MetricCard("未录重量",unknownWeight.toString()+" 件")
                    MetricCard("装备价值","¥"+String.format(Locale.CHINA,"%.2f",project.gear.sumOf{it.price*it.quantity}))
                }
                LinearProgressIndicator(progress={if(project.gear.isEmpty()) 0f else packedCount.toFloat()/project.gear.size},modifier=Modifier.fillMaxWidth(),color=forest)
                Text("负重数据质量",fontWeight=FontWeight.Bold,color=forest)
                Text(if(unknownWeight>0) "有 "+unknownWeight+" 件装备尚未录入重量。当前总重只是已录入部分，补齐重量后分析才准确。" else "装备重量字段已全部填写。食品、水、燃料等消耗品也要单独加入清单。",fontSize=12.sp,color=muted)
                categories.forEach { category ->
                    val weight=project.gear.filter{normalizeGearCategory(it.category)==category}.sumOf{it.weightGrams*it.quantity}
                    if(weight>0) {
                        val share=(weight/totalWeight).toFloat().coerceIn(0f,1f)
                        Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                Text(category,fontSize=12.sp)
                                Text(weight.toInt().toString()+" g · "+String.format(Locale.CHINA,"%.0f",share*100)+"%",fontSize=11.sp,color=muted)
                            }
                            LinearProgressIndicator(progress={share},modifier=Modifier.fillMaxWidth(),color=forest)
                        }
                    }
                }
                HorizontalDivider()
                Text("出发前智能检查",fontWeight=FontWeight.Bold,color=forest)
                Text("通用检查建议，不代表每条路线都必须携带全部物品。请结合季节、天气、路线和个人情况判断。",fontSize=12.sp,color=muted)
                suggestions.forEach { Text("• "+it.name+" · "+it.note,fontSize=12.sp) }
                OutlinedButton(onClick={onAddSuggested(suggestions);onClose()},modifier=Modifier.fillMaxWidth()) { Text("将建议装备加入清单") }
                Text("提示：建议装备加入后重量是参考估值，实际重量需要按你购买的装备重新录入。",fontSize=11.sp,color=muted)

            }
        },
        confirmButton={TextButton(onClick=onClose){Text("完成")}}
    )
}

@Composable
private fun MetricCard(label:String,value:String) {
    Card(Modifier.width(145.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFFE9EEE7))) {
        Column(Modifier.padding(11.dp)) {
            Text(label,fontSize=11.sp,color=Color(0xFF78827A))
            Text(value,fontWeight=FontWeight.Bold,color=Color(0xFF183C32),fontSize=16.sp)
        }
    }
}
