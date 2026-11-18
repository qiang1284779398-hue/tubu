package com.tubu.trailkit

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.content.ContextCompat
import java.util.Locale
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant

class TrailNavigationService : Service(), LocationListener {
    private lateinit var manager:LocationManager
    private val channelId="trailkit_navigation"
    private val notificationId=4107
    private var trackWriter:BufferedWriter?=null

    override fun onCreate() {
        super.onCreate()
        manager=getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        when(intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_STOP_RECORDING -> { finishRecording(); return START_STICKY }
            ACTION_START_RECORDING -> beginRecording()
        }
        startForeground(notificationId,buildNotification("正在启动 GPS 定位…"))
        val fine=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        if(!fine) {
            updateNotification("缺少精确定位权限，请返回 TrailKit 授权")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if(!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                updateNotification("GPS 已关闭，请开启手机定位")
            } else {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,2000L,3f,this)
                manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(::onLocationChanged)
            }
        } catch(_:SecurityException) {
            updateNotification("定位权限不足")
            stopSelf()
        } catch(_:Exception) {
            updateNotification("GPS 启动失败，请检查定位设置")
        }
        return START_STICKY
    }

    override fun onLocationChanged(location:Location) {
        val text=String.format(Locale.US,"%.5f, %.5f · 精度 ±%.0f m · %.1f km/h",
            location.latitude,location.longitude,location.accuracy,if(location.hasSpeed())location.speed*3.6f else 0f)
        updateNotification(text)
        try {
            trackWriter?.apply {
                write(String.format(Locale.US,"<trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%.1f</ele><time>%s</time></trkpt>\n",location.latitude,location.longitude,if(location.hasAltitude())location.altitude else 0.0,Instant.ofEpochMilli(location.time)))
                flush()
            }
        } catch(_:Exception) { finishRecording() }
    }

    private fun beginRecording() {
        finishRecording()
        try {
            val file=File(filesDir,"trailkit_active_track.gpx")
            trackWriter=BufferedWriter(OutputStreamWriter(FileOutputStream(file,false),StandardCharsets.UTF_8)).apply {
                write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"TrailKit\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>TrailKit 实时轨迹</name><trkseg>\n")
                flush()
            }
            updateNotification("轨迹记录已开始")
        } catch(_:Exception) { trackWriter=null;updateNotification("无法创建轨迹记录文件") }
    }

    private fun finishRecording() {
        try { trackWriter?.write("</trkseg></trk></gpx>");trackWriter?.flush();trackWriter?.close() } catch(_:Exception) {}
        trackWriter=null
    }

    private fun createChannel() {
        if(Build.VERSION.SDK_INT>=26) {
            val channel=NotificationChannel(channelId,"TrailKit 徒步导航",NotificationManager.IMPORTANCE_LOW).apply {
                description="导航期间保持 GPS 定位运行"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message:String):Notification {
        val stopIntent=Intent(this,TrailNavigationService::class.java).setAction(ACTION_STOP)
        val stopPending=PendingIntent.getService(this,1,stopIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openIntent=packageManager.getLaunchIntentForPackage(packageName)
        val openPending=openIntent?.let{PendingIntent.getActivity(this,2,it,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)}
        val builder=if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,channelId) else Notification.Builder(this)
        builder.setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("TrailKit · 徒步导航运行中")
            .setContentText(message)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause,"停止导航",stopPending)
        if(openPending!=null)builder.setContentIntent(openPending)
        return builder.build()
    }

    private fun updateNotification(message:String) {
        getSystemService(NotificationManager::class.java).notify(notificationId,buildNotification(message))
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?) {}
    override fun onProviderEnabled(provider:String) { updateNotification("GPS 已开启，正在获取定位") }
    override fun onProviderDisabled(provider:String) { updateNotification("GPS 已关闭，请开启手机定位") }

    override fun onDestroy() {
        finishRecording()
        try { manager.removeUpdates(this) } catch(_:Exception) {}
        if(Build.VERSION.SDK_INT>=24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent:Intent?):IBinder?=null

    companion object {
        const val ACTION_STOP="com.tubu.trailkit.STOP_NAVIGATION"
        const val ACTION_START_RECORDING="com.tubu.trailkit.START_RECORDING"
        const val ACTION_STOP_RECORDING="com.tubu.trailkit.STOP_RECORDING"
    }
}
