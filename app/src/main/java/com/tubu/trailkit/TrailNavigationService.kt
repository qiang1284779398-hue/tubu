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
    private var lastTrackLocation:Location?=null

    override fun onCreate() {
        super.onCreate()
        manager=getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        when(intent?.action) {
            ACTION_STOP_RECORDING -> { finishRecording(); return START_STICKY }
            ACTION_START_RECORDING -> beginRecording(intent?.getStringExtra(EXTRA_PROJECT_ID) ?: "default")
        }
        startForeground(notificationId,buildNotification("正在启动 GPS 定位…"))
        try { manager.removeUpdates(this) } catch(_:Exception) {}
        val fine=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        val coarse=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
        if(!fine && !coarse) {
            updateNotification("缺少定位权限，请返回 TrailKit 授权")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            var providersRequested=0
            if(fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,0f,this,android.os.Looper.getMainLooper())
                manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(::onLocationChanged)
                providersRequested++
            }
            if(manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,2500L,0f,this,android.os.Looper.getMainLooper())
                manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let(::onLocationChanged)
                providersRequested++
            }
            try { manager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER,5000L,0f,this,android.os.Looper.getMainLooper()); providersRequested++ } catch(_:Exception) {}
            if(providersRequested==0) updateNotification("定位源未启用，请打开手机系统定位")
        } catch(_:SecurityException) {
            updateNotification("定位权限不足")
            stopSelf()
        } catch(_:Exception) {
            updateNotification("GPS 启动失败，请检查定位设置")
        }
        return START_STICKY
    }

    override fun onLocationChanged(location:Location) {
        val age=System.currentTimeMillis()-location.time
        val source=when(location.provider) { LocationManager.GPS_PROVIDER -> "GPS"; LocationManager.NETWORK_PROVIDER -> "网络"; LocationManager.PASSIVE_PROVIDER -> "被动"; else -> location.provider ?: "定位" }
        val text=(if(trackWriter!=null)"记录中 · " else "")+"$source · ${String.format(Locale.US,"%.5f, %.5f",location.latitude,location.longitude)} · 精度 ±${location.accuracy.toInt()} m · ${if(age<=15000L)"实时" else "延迟 ${age/1000}s"}"
        updateNotification(text)
        try {
            val writer=trackWriter
            if(writer!=null) {
                if(age in 0L..20000L && location.accuracy<=100f) {
                    val previous=lastTrackLocation
                    val distance=previous?.distanceTo(location)?.toDouble() ?: Double.POSITIVE_INFINITY
                    val elapsed=previous?.let { location.time-it.time } ?: Long.MAX_VALUE
                    if(previous==null || distance>=3.0 || (elapsed>=8000L && distance>=1.5)) {
                        val elevation=if(location.hasAltitude()) String.format(Locale.US,"<ele>%.1f</ele>",location.altitude) else ""
                        writer.write(String.format(Locale.US,"<trkpt lat=\"%.7f\" lon=\"%.7f\">%s<time>%s</time></trkpt>\n",location.latitude,location.longitude,elevation,Instant.ofEpochMilli(location.time)))
                        writer.flush()
                        lastTrackLocation=Location(location)
                    }
                }
            }
        } catch(_:Exception) { finishRecording() }
    }

    private fun beginRecording(projectId:String) {
        finishRecording()
        lastTrackLocation=null
        try {
            val file=File(filesDir,"trailkit_track_${projectId.hashCode()}.gpx")
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
        lastTrackLocation=null
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
        val openIntent=packageManager.getLaunchIntentForPackage(packageName)
        val openPending=openIntent?.let{PendingIntent.getActivity(this,2,it,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)}
        val builder=if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,channelId) else Notification.Builder(this)
        builder.setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("TrailKit · 徒步导航运行中")
            .setContentText(message)
            .setOngoing(true)
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
        const val ACTION_START_RECORDING="com.tubu.trailkit.START_RECORDING"
        const val ACTION_STOP_RECORDING="com.tubu.trailkit.STOP_RECORDING"
        const val EXTRA_PROJECT_ID="project_id"
    }
}
