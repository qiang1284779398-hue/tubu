package com.tubu.trailkit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.MotionEvent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.net.HttpURLConnection
import java.net.URL
import java.nio.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

@Composable
internal fun Terrain3DDialog(projectName:String,encodedTrack:String,visible:Boolean,onClose:()->Unit){
 if(!visible)return
 val points=remember(encodedTrack){decodeTrackPoints(encodedTrack)}
 var status by remember{mutableStateOf("正在启动高分辨率 DEM 地形…")}
 val ctx=androidx.compose.ui.platform.LocalContext.current
 val view=remember(points){TerrainSurface(ctx,points){status=it}}
 DisposableEffect(view){onDispose{view.onPause()}}
 Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
  Column(Modifier.fillMaxSize()){
   Surface(color=MaterialTheme.colorScheme.surface,tonalElevation=4.dp){Column(Modifier.fillMaxWidth().padding(14.dp)){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
     Column(Modifier.weight(1f)){Text("NATIVE OPENGL ES · TERRAIN",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary);Text("$projectName · 全轨迹三维地形",style=MaterialTheme.typography.titleMedium)}
     TextButton(onClick=onClose){Text("关闭")}
    }
    Text("沉浸式山地视角 · 单指转头 · 双指行走 · 捏合变焦",style=MaterialTheme.typography.bodySmall)
   }}
   AndroidView(factory={view},modifier=Modifier.weight(1f).fillMaxWidth())
   Surface(color=MaterialTheme.colorScheme.surface){Text(status,Modifier.fillMaxWidth().padding(12.dp),style=MaterialTheme.typography.bodySmall)}
  }
 }
}
private class TerrainSurface(c:android.content.Context,pts:List<Pair<Double,Double>>,report:(String)->Unit):GLSurfaceView(c){
 private val renderer=TerrainRenderer(pts,report)
 private var lastX=0f;private var lastY=0f;private var lastSpan=0f
 init{setEGLContextClientVersion(2);preserveEGLContextOnPause=true;setRenderer(renderer);renderMode=RENDERMODE_CONTINUOUSLY}
 private fun span(e:MotionEvent):Float{val dx=e.getX(0)-e.getX(1);val dy=e.getY(0)-e.getY(1);return hypot(dx,dy)}
 private fun centerX(e:MotionEvent)=if(e.pointerCount>1)(e.getX(0)+e.getX(1))*.5f else e.x
 private fun centerY(e:MotionEvent)=if(e.pointerCount>1)(e.getY(0)+e.getY(1))*.5f else e.y
 override fun onTouchEvent(e:MotionEvent):Boolean{
  when(e.actionMasked){
   MotionEvent.ACTION_DOWN->{lastX=e.x;lastY=e.y;lastSpan=0f}
   MotionEvent.ACTION_POINTER_DOWN->{lastX=centerX(e);lastY=centerY(e);lastSpan=span(e)}
   MotionEvent.ACTION_MOVE->{
    val cx=centerX(e);val cy=centerY(e)
    if(e.pointerCount==1){renderer.look(cx-lastX,cy-lastY)}
    if(e.pointerCount>=2){
     renderer.move(cx-lastX,cy-lastY,height.coerceAtLeast(1))
     val s=span(e);if(lastSpan>0f&&s>0f)renderer.zoom(s/lastSpan)
     lastSpan=s
    }
    lastX=cx;lastY=cy
   }
   MotionEvent.ACTION_POINTER_UP->{
    val idx=if(e.actionIndex==0)1 else 0
    if(idx<e.pointerCount){lastX=e.getX(idx);lastY=e.getY(idx)}
    lastSpan=0f
   }
   MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{lastSpan=0f}
  }
  return true
 }
}
private class TerrainRenderer(val pts:List<Pair<Double,Double>>,val report:(String)->Unit):GLSurfaceView.Renderer{
 @Volatile private var yaw=0f
 @Volatile private var pitch=-8f
 @Volatile private var cameraX=0f
 @Volatile private var cameraY=-1.02f
 @Volatile private var cameraZ=0.18f
 @Volatile private var cameraFov=72f
 private var program=0
 private var texture=0
 private var pendingTexture:Bitmap?=null
 private var ready=false
 private var vertices:FloatBuffer?=null
 private var indices:ShortBuffer?=null
 private var indexCount=0
 private var routeBuffer:FloatBuffer?=null
 private var routeCount=0
 private var w=1;private var h=1
 private val proj=FloatArray(16);private val view=FloatArray(16);private val model=FloatArray(16);private val vp=FloatArray(16);private val mvp=FloatArray(16)
 fun look(dx:Float,dy:Float){
  yaw=(yaw+dx*.24f)%360f
  pitch=(pitch-dy*.22f).coerceIn(-72f,68f)
 }
 fun move(dx:Float,dy:Float,viewHeight:Int){
  val step=2.8f/viewHeight.coerceAtLeast(1)
  val strafe=dx*step
  val forward=-dy*step
  val a=Math.toRadians(yaw.toDouble())
  cameraX=(cameraX+cos(a).toFloat()*strafe+sin(a).toFloat()*forward).coerceIn(-2.2f,2.2f)
  cameraY=(cameraY-sin(a).toFloat()*strafe+cos(a).toFloat()*forward).coerceIn(-2.2f,2.2f)
 }
 fun zoom(scale:Float){if(scale.isFinite()&&scale>0f)cameraFov=(cameraFov/scale).coerceIn(16f,96f)}
 override fun onSurfaceCreated(gl:GL10?,cfg:EGLConfig?){
  GLES20.glClearColor(.12f,.15f,.13f,1f);GLES20.glEnable(GLES20.GL_DEPTH_TEST);GLES20.glDisable(GLES20.GL_CULL_FACE)
  val vs="attribute vec3 p;attribute vec2 uv;uniform mat4 m;varying vec2 t;void main(){gl_Position=m*vec4(p,1.);t=uv;}"
  val fs="precision mediump float;uniform sampler2D tex;uniform int route;varying vec2 t;void main(){if(route==1){gl_FragColor=vec4(1.0,0.30,0.055,1.0);}else{gl_FragColor=vec4(texture2D(tex,t).rgb,1.0);}}"
  program=GLES20.glCreateProgram()
  fun shader(type:Int,src:String):Int{val id=GLES20.glCreateShader(type);GLES20.glShaderSource(id,src);GLES20.glCompileShader(id);val a=IntArray(1);GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,a,0);check(a[0]!=0){GLES20.glGetShaderInfoLog(id)};return id}
  try{GLES20.glAttachShader(program,shader(GLES20.GL_VERTEX_SHADER,vs));GLES20.glAttachShader(program,shader(GLES20.GL_FRAGMENT_SHADER,fs));GLES20.glLinkProgram(program);val a=IntArray(1);GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,a,0);check(a[0]!=0){GLES20.glGetProgramInfoLog(program)}}
  catch(e:Exception){report("GPU 着色器错误："+e.message);return}
  report("正在按 GPX 全轨迹范围下载高分辨率 DEM…")
  Thread{loadTerrain()}.start()
 }
 private fun mercatorX(lon:Double,z:Int)=((lon+180.0)/360.0)*(256.0*(1 shl z))
 private fun mercatorY(lat:Double,z:Int):Double{
  val clamped=lat.coerceIn(-85.05112878,85.05112878);val r=Math.toRadians(clamped)
  return (1.0-ln(tan(r)+1.0/cos(r))/Math.PI)*.5*(256.0*(1 shl z))
 }
 private fun terrarium(pixel:Int):Float=Color.red(pixel)*256f+Color.green(pixel)+Color.blue(pixel)/256f-32768f
 private fun sampleHeight(a:FloatArray,x:Float,y:Float,size:Int):Float{
  val xx=x.coerceIn(0f,(size-1).toFloat());val yy=y.coerceIn(0f,(size-1).toFloat())
  val x0=xx.toInt();val y0=yy.toInt();val x1=(x0+1).coerceAtMost(size-1);val y1=(y0+1).coerceAtMost(size-1)
  val fx=xx-x0;val fy=yy-y0
  val p=a[y0*size+x0]*(1-fx)+a[y0*size+x1]*fx
  val q=a[y1*size+x0]*(1-fx)+a[y1*size+x1]*fx
  return p*(1-fy)+q*fy
 }
 private fun terrainColor(e:Float):Int{
  val stops=arrayOf(
   Triple(1800f,Color.rgb(64,105,65),Color.rgb(91,126,70)),
   Triple(2800f,Color.rgb(91,126,70),Color.rgb(139,145,91)),
   Triple(3600f,Color.rgb(139,145,91),Color.rgb(174,151,112)),
   Triple(4400f,Color.rgb(174,151,112),Color.rgb(151,151,145)),
   Triple(5200f,Color.rgb(151,151,145),Color.rgb(226,226,215))
  )
  val i=when{e<2800f->0;e<3600f->1;e<4400f->2;e<5200f->3;else->4}
  val (lo,a,b)=stops[i];val hi=when(i){0->2800f;1->3600f;2->4400f;3->5200f;else->6000f}
  val t=((e-lo)/(hi-lo)).coerceIn(0f,1f)
  return Color.rgb(
   (Color.red(a)+(Color.red(b)-Color.red(a))*t).roundToInt(),
   (Color.green(a)+(Color.green(b)-Color.green(a))*t).roundToInt(),
   (Color.blue(a)+(Color.blue(b)-Color.blue(a))*t).roundToInt()
  )
 }
 private fun loadTerrain(){
  var mosaic:Bitmap?=null
  try{
   require(pts.size>=2){"GPX 轨迹点不足"}
   val projected=pts.map{mercatorX(it.second,12) to mercatorY(it.first,12)}
   var minX=projected.minOf{it.first};var maxX=projected.maxOf{it.first}
   var minY=projected.minOf{it.second};var maxY=projected.maxOf{it.second}
   val padX=max(48.0,(maxX-minX)*.075);val padY=max(48.0,(maxY-minY)*.075)
   minX-=padX;maxX+=padX;minY-=padY;maxY+=padY
   var z=14
   while(z>10){
    val factor=2.0.pow((12-z).toDouble())
    val tileCols=floor(maxX/factor/256).toInt()-floor(minX/factor/256).toInt()+1
    val tileRows=floor(maxY/factor/256).toInt()-floor(minY/factor/256).toInt()+1
    if(tileCols*tileRows<=64 && tileCols<=16 && tileRows<=16)break
    z--
   }
   val scale=2.0.pow((12-z).toDouble())
   minX/=scale;maxX/=scale;minY/=scale;maxY/=scale
   val tx0=floor(minX/256).toInt();val tx1=floor(maxX/256).toInt()
   val ty0=floor(minY/256).toInt();val ty1=floor(maxY/256).toInt()
   val cols=tx1-tx0+1;val rows=ty1-ty0+1
   require(cols in 1..16&&rows in 1..16&&cols*rows<=64){"轨迹范围过大，无法安全加载 DEM"}
   val composite=Bitmap.createBitmap(cols*256,rows*256,Bitmap.Config.ARGB_8888);mosaic=composite
   val canvas=Canvas(composite)
   fun get(url:String):ByteArray{
    val c=URL(url).openConnection() as HttpURLConnection;c.connectTimeout=15000;c.readTimeout=25000;c.setRequestProperty("User-Agent","TrailKit Outdoor App")
    return try{c.inputStream.use{it.readBytes()}}finally{c.disconnect()}
   }
   var loaded=0
   for(ty in ty0..ty1)for(tx in tx0..tx1){
    val bytes=get("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$z/$tx/$ty.png")
    val tile=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:error("DEM 瓦片解码失败")
    canvas.drawBitmap(tile,(tx-tx0)*256f,(ty-ty0)*256f,null);tile.recycle();loaded++
    report("正在拼接全线地形 DEM："+loaded+"/"+(cols*rows)+" 张瓦片…")
   }
   val rasterSize=1537;val heights=FloatArray(rasterSize*rasterSize)
   val left=minX-tx0*256.0;val top=minY-ty0*256.0
   val spanPxX=max(1.0,maxX-minX);val spanPxY=max(1.0,maxY-minY)
   for(y in 0 until rasterSize){
    val sy=(top+y/(rasterSize-1.0)*spanPxY).roundToInt().coerceIn(0,rows*256-1)
    for(x in 0 until rasterSize){
     val sx=(left+x/(rasterSize-1.0)*spanPxX).roundToInt().coerceIn(0,cols*256-1)
     heights[y*rasterSize+x]=terrarium(composite.getPixel(sx,sy))
    }
   }
   val minElev=heights.minOrNull()?:0f;val maxElev=heights.maxOrNull()?:1f
   val centerLat=pts.map{it.first}.average()
   val metersPerPixel=cos(Math.toRadians(centerLat))*2.0*Math.PI*6378137.0/(256.0*(1 shl z))
   val groundSpan=max(spanPxX,spanPxY)*metersPerPixel
   val aspectX=(spanPxX/max(spanPxX,spanPxY)).toFloat()
   val aspectY=(spanPxY/max(spanPxX,spanPxY)).toFloat()
   val v=FloatArray(255*255*5);var k=0
   for(y in 0 until 255)for(x in 0 until 255){
    val u=x/254f;val vv=y/254f;val elev=sampleHeight(heights,u*(rasterSize-1),vv*(rasterSize-1),rasterSize)
    val zPos=((elev-(minElev+maxElev)*.5f)/groundSpan*5.0).toFloat()
    v[k++]=(u-.5f)*1.72f*aspectX;v[k++]=(.5f-vv)*1.72f*aspectY;v[k++]=zPos;v[k++]=u;v[k++]=vv
   }
   val ix=ShortArray(254*254*6);k=0
   for(y in 0 until 254)for(x in 0 until 254){
    val a=y*255+x;val b=a+1;val c=a+255;val d=c+1
    ix[k++]=a.toShort();ix[k++]=c.toShort();ix[k++]=b.toShort();ix[k++]=b.toShort();ix[k++]=c.toShort();ix[k++]=d.toShort()
   }
   val texSize=1536;val pixels=IntArray(texSize*texSize);val rasterMeters=groundSpan/(texSize-1).toDouble()
   for(y in 0 until texSize)for(x in 0 until texSize){
    val sx=(x.toDouble()*(rasterSize-1)/(texSize-1)).roundToInt().coerceIn(0,rasterSize-1)
    val sy=(y.toDouble()*(rasterSize-1)/(texSize-1)).roundToInt().coerceIn(0,rasterSize-1)
    val idx=sy*rasterSize+sx;val elev=heights[idx]
    val dx=(heights[sy*rasterSize+(sx+1).coerceAtMost(rasterSize-1)]-heights[sy*rasterSize+(sx-1).coerceAtLeast(0)])/(2f*rasterMeters.toFloat().coerceAtLeast(1f))
    val dy=(heights[(sy+1).coerceAtMost(rasterSize-1)*rasterSize+sx]-heights[(sy-1).coerceAtLeast(0)*rasterSize+sx])/(2f*rasterMeters.toFloat().coerceAtLeast(1f))
    val shade=(.88f-dx*.42f+dy*.32f).coerceIn(.58f,1.22f);val base=terrainColor(elev)
    var rr=(Color.red(base)*shade).roundToInt().coerceIn(0,255)
    var gg=(Color.green(base)*shade).roundToInt().coerceIn(0,255)
    var bb=(Color.blue(base)*shade).roundToInt().coerceIn(0,255)
    val contour=((elev%100f+100f)%100f)
    if(contour<2.2f||contour>97.8f){rr=(rr*.78f).roundToInt();gg=(gg*.78f).roundToInt();bb=(bb*.78f).roundToInt()}
    pixels[y*texSize+x]=Color.rgb(rr,gg,bb)
   }
   val texBitmap=Bitmap.createBitmap(texSize,texSize,Bitmap.Config.ARGB_8888)
   texBitmap.setPixels(pixels,0,texSize,0,0,texSize,texSize)
   val route=ArrayList<Float>(pts.size*3)
   for(p in pts){
    val gx=mercatorX(p.second,z);val gy=mercatorY(p.first,z)
    val u=((gx-minX)/spanPxX).toFloat();val vv=((gy-minY)/spanPxY).toFloat()
    if(u in 0f..1f&&vv in 0f..1f){
     val elev=sampleHeight(heights,u*(rasterSize-1),vv*(rasterSize-1),rasterSize)
     val zPos=((elev-(minElev+maxElev)*.5f)/groundSpan*5.0).toFloat()+.009f
     route.add((u-.5f)*1.72f*aspectX);route.add((.5f-vv)*1.72f*aspectY);route.add(zPos)
    }
   }
   val vb=ByteBuffer.allocateDirect(v.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(v).apply{position(0)}
   val ib=ByteBuffer.allocateDirect(ix.size*2).order(ByteOrder.nativeOrder()).asShortBuffer().put(ix).apply{position(0)}
   val rb=ByteBuffer.allocateDirect(route.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
   route.forEach{rb.put(it)};rb.position(0)
   synchronized(this){vertices=vb;indices=ib;indexCount=ix.size;routeBuffer=rb;routeCount=route.size/3;pendingTexture=texBitmap;ready=true}
   val nearEdge=sampleHeight(heights,(rasterSize-1)*.5f,(rasterSize-1)*.94f,rasterSize)
   cameraX=0f;cameraY=-.92f;cameraZ=((nearEdge-(minElev+maxElev)*.5f)/groundSpan*5.0).toFloat()+.035f
   report("沉浸式山地视角已就绪："+loaded+" 张 DEM · 单指环视 · 双指移动 · 捏合缩放。")
  }catch(e:Exception){report("地形加载失败："+(e.message?:e.javaClass.simpleName)+"；请检查网络后重试。")}
  finally{mosaic?.recycle()}
 }
 override fun onSurfaceChanged(gl:GL10?,width:Int,height:Int){w=width.coerceAtLeast(1);h=height.coerceAtLeast(1);GLES20.glViewport(0,0,w,h)}
 override fun onDrawFrame(gl:GL10?){
  GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT);if(program==0)return
  Matrix.perspectiveM(proj,0,cameraFov,w.toFloat()/h,.015f,80f)
  val yr=Math.toRadians(yaw.toDouble());val pr=Math.toRadians(pitch.toDouble())
  val cp=cos(pr).toFloat()
  val fx=sin(yr).toFloat()*cp;val fy=cos(yr).toFloat()*cp;val fz=sin(pr).toFloat()
  Matrix.setLookAtM(view,0,cameraX,cameraY,cameraZ,cameraX+fx,cameraY+fy,cameraZ+fz,0f,0f,1f)
  Matrix.setIdentityM(model,0)
  Matrix.multiplyMM(vp,0,proj,0,view,0);Matrix.multiplyMM(mvp,0,vp,0,model,0)
  GLES20.glUseProgram(program)
  if(texture==0){
   val image=synchronized(this){pendingTexture.also{pendingTexture=null}}
   if(image!=null){
    val t=IntArray(1);GLES20.glGenTextures(1,t,0);texture=t[0];GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,image,0);image.recycle()
   }
  }
  if(!ready||texture==0)return
  GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"m"),1,false,mvp,0)
  GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture)
  GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"tex"),0)
  val p=GLES20.glGetAttribLocation(program,"p");val uv=GLES20.glGetAttribLocation(program,"uv")
  GLES20.glEnableVertexAttribArray(p);GLES20.glEnableVertexAttribArray(uv)
  val vb=vertices!!;vb.position(0);GLES20.glVertexAttribPointer(p,3,GLES20.GL_FLOAT,false,20,vb)
  vb.position(3);GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,20,vb)
  GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"route"),0);GLES20.glDrawElements(GLES20.GL_TRIANGLES,indexCount,GLES20.GL_UNSIGNED_SHORT,indices)
  if(routeCount>1){
   GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"route"),1)
   routeBuffer!!.position(0);GLES20.glVertexAttribPointer(p,3,GLES20.GL_FLOAT,false,12,routeBuffer)
   GLES20.glLineWidth(5f);GLES20.glDrawArrays(GLES20.GL_LINE_STRIP,0,routeCount)
  }
  GLES20.glDisableVertexAttribArray(p);GLES20.glDisableVertexAttribArray(uv)
 }
}
