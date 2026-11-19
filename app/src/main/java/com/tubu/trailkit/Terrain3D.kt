package com.tubu.trailkit
import android.graphics.BitmapFactory
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
 var status by remember{mutableStateOf("正在启动原生 OpenGL ES…")}
 val ctx=androidx.compose.ui.platform.LocalContext.current
 val view=remember(points){TerrainSurface(ctx,points){status=it}}
 DisposableEffect(view){onDispose{view.onPause()}}
 Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
  Column(Modifier.fillMaxSize()){
   Surface(color=MaterialTheme.colorScheme.surface,tonalElevation=4.dp){Column(Modifier.fillMaxWidth().padding(14.dp)){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
     Column(Modifier.weight(1f)){Text("NATIVE OPENGL ES · TRUE 3D",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary);Text("$projectName · DEM 三维地形",style=MaterialTheme.typography.titleMedium)}
     TextButton(onClick=onClose){Text("关闭")}
    }
    Text("原生 GPU 地形网格 · 卫星纹理 · 高程数据 · GPX 轨迹",style=MaterialTheme.typography.bodySmall)
   }}
   AndroidView(factory={view},modifier=Modifier.weight(1f).fillMaxWidth())
   Surface(color=MaterialTheme.colorScheme.surface){Text(status,Modifier.fillMaxWidth().padding(12.dp),style=MaterialTheme.typography.bodySmall)}
  }
 }
}
private class TerrainSurface(c:android.content.Context,pts:List<Pair<Double,Double>>,report:(String)->Unit):GLSurfaceView(c){
 private val r=TerrainRenderer(pts,report);private var x=0f;private var y=0f;private var pinch=0f
 init{setEGLContextClientVersion(2);preserveEGLContextOnPause=true;setRenderer(r);renderMode=RENDERMODE_CONTINUOUSLY}
 private fun span(e:MotionEvent):Float{val dx=e.getX(0)-e.getX(1);val dy=e.getY(0)-e.getY(1);return hypot(dx,dy)}
 override fun onTouchEvent(e:MotionEvent):Boolean{
  when(e.actionMasked){
   MotionEvent.ACTION_DOWN->{x=e.x;y=e.y;pinch=0f}
   MotionEvent.ACTION_POINTER_DOWN->{if(e.pointerCount>=2)pinch=span(e)}
   MotionEvent.ACTION_MOVE->{
    if(e.pointerCount>=2){val next=span(e);if(pinch>0f&&next>0f)r.zoom(next/pinch);pinch=next}
    else{r.rotate((e.x-x)*.45f,(e.y-y)*.35f);x=e.x;y=e.y}
   }
   MotionEvent.ACTION_POINTER_UP->{pinch=0f;val idx=if(e.actionIndex==0)1 else 0;if(idx<e.pointerCount){x=e.getX(idx);y=e.getY(idx)}}
   MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{pinch=0f}
  }
  return true
 }
}
private class TerrainRenderer(val pts:List<Pair<Double,Double>>,val report:(String)->Unit):GLSurfaceView.Renderer{
 @Volatile var yaw=-22f;@Volatile var pitch=52f;@Volatile var cameraDistance=3.65f
 var program=0;var tex=0;var pendingImage:android.graphics.Bitmap?=null;var ready=false;var vb:FloatBuffer?=null;var ib:ShortBuffer?=null;var ic=0;var rb:FloatBuffer?=null;var rc=0
 var w=1;var h=1
 val proj=FloatArray(16);val view=FloatArray(16);val model=FloatArray(16);val vp=FloatArray(16);val mvp=FloatArray(16)
 fun rotate(dx:Float,dy:Float){yaw=(yaw+dx)%360f;pitch=(pitch+dy).coerceIn(18f,78f)}
 fun zoom(scale:Float){if(scale.isFinite()&&scale>0f)cameraDistance=(cameraDistance/scale).coerceIn(1.55f,9.0f)}
 override fun onSurfaceCreated(gl:GL10?,cfg:EGLConfig?){
  GLES20.glClearColor(.055f,.09f,.075f,1f);GLES20.glEnable(GLES20.GL_DEPTH_TEST)
  val vs="attribute vec3 p;attribute vec2 uv;uniform mat4 m;varying vec2 t;void main(){gl_Position=m*vec4(p,1.);t=uv;}"
  val fs="precision mediump float;uniform sampler2D tex;uniform int route;varying vec2 t;void main(){gl_FragColor=route==1?vec4(1.,.55,.12,1.):vec4(texture2D(tex,t).rgb*1.12,1.);}"
  program=GLES20.glCreateProgram()
  fun shader(type:Int,s:String):Int{val id=GLES20.glCreateShader(type);GLES20.glShaderSource(id,s);GLES20.glCompileShader(id);val a=IntArray(1);GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,a,0);if(a[0]==0)throw Exception(GLES20.glGetShaderInfoLog(id));return id}
  try{GLES20.glAttachShader(program,shader(GLES20.GL_VERTEX_SHADER,vs));GLES20.glAttachShader(program,shader(GLES20.GL_FRAGMENT_SHADER,fs));GLES20.glLinkProgram(program);val a=IntArray(1);GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,a,0);check(a[0]!=0){GLES20.glGetProgramInfoLog(program)}}
  catch(e:Exception){report("GPU 着色器错误："+e.message);return}
  report("原生 GPU 已启动，正在加载雨崩卫星影像和 DEM…")
  Thread{load()}.start()
 }
 private fun load(){
  try{
   val z=9;val n=1 shl z;val cx=floor((98.88+180)/360*n).toInt();val lat=Math.toRadians(28.45);val cy=floor((1-ln(tan(lat)+1/cos(lat))/Math.PI)/2*n).toInt()
   fun get(u:String):ByteArray{val c=URL(u).openConnection() as HttpURLConnection;c.connectTimeout=15000;c.readTimeout=20000;return try{c.inputStream.use{it.readBytes()}}finally{c.disconnect()}}
   val dbytes=get("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$z/$cx/$cy.png")
   val ibytes=get("https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$cy/$cx")
   val dem=BitmapFactory.decodeByteArray(dbytes,0,dbytes.size)?:error("DEM 解码失败")
   val img=BitmapFactory.decodeByteArray(ibytes,0,ibytes.size)?:error("卫星影像解码失败")
   val hs=FloatArray(257*257)
   for(y in 0..256)for(x in 0..256){val c=dem.getPixel(x.coerceAtMost(dem.width-1),y.coerceAtMost(dem.height-1));hs[y*257+x]=(((c shr 16)and 255)*256+((c shr 8)and 255)+(c and 255)/256f)-32768f}
   val v=FloatArray(129*129*5);var k=0
   for(y in 0..128)for(x in 0..128){val px=x*2;val py=y*2;val elev=((hs[py*257+px]-3000)/2500).coerceIn(-1.1f,1.1f)*.12f;v[k++]=x/128f*2-1;v[k++]=1-y/128f*2;v[k++]=elev;v[k++]=x/128f;v[k++]=y/128f}
   val ix=ShortArray(128*128*6);k=0
   for(y in 0 until 128)for(x in 0 until 128){val a=y*129+x;val b=a+1;val c=a+129;val d=c+1;ix[k++]=a.toShort();ix[k++]=c.toShort();ix[k++]=b.toShort();ix[k++]=b.toShort();ix[k++]=c.toShort();ix[k++]=d.toShort()}
   val route=ArrayList<Float>();val total=n*256.0
   for(p in pts){val gx=(p.second+180)/360*total;val lr=Math.toRadians(p.first);val gy=(1-ln(tan(lr)+1/cos(lr))/Math.PI)/2*total;val xx=gx-cx*256;val yy=gy-cy*256
    if(xx in 0.0..256.0&&yy in 0.0..256.0){val px=xx.roundToInt().coerceIn(0,256);val py=yy.roundToInt().coerceIn(0,256);route.add((xx/128-1).toFloat());route.add((1-yy/128).toFloat());route.add(((hs[py*257+px]-3000)/2500).coerceIn(-1.1f,1.1f)*.12f+.004f)}
   }
   val vbuf=ByteBuffer.allocateDirect(v.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(v);vbuf.position(0)
   val ibuf=ByteBuffer.allocateDirect(ix.size*2).order(ByteOrder.nativeOrder()).asShortBuffer().put(ix);ibuf.position(0)
   val rbuf=ByteBuffer.allocateDirect(route.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer();route.forEach{rbuf.put(it)};rbuf.position(0)
   synchronized(this){vb=vbuf;ib=ibuf;ic=ix.size;rb=rbuf;rc=route.size/3;pendingImage=img;ready=true}
   report("高程网格与影像数据已就绪，正在提交 GPU 纹理…");dem.recycle()
  }catch(e:Exception){report("地形加载失败："+(e.message?:e.javaClass.simpleName)+"；请检查网络。")}
 }
 override fun onSurfaceChanged(gl:GL10?,width:Int,height:Int){w=width.coerceAtLeast(1);h=height.coerceAtLeast(1);GLES20.glViewport(0,0,w,h)}
 override fun onDrawFrame(gl:GL10?){
  GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT);if(program==0)return
  Matrix.perspectiveM(proj,0,43f,w.toFloat()/h,.1f,20f);Matrix.setLookAtM(view,0,0f,-cameraDistance*.766f,cameraDistance*.643f,0f,0f,0f,0f,0f,1f);Matrix.setIdentityM(model,0);Matrix.rotateM(model,0,pitch,1f,0f,0f);Matrix.rotateM(model,0,yaw,0f,0f,1f);Matrix.multiplyMM(vp,0,proj,0,view,0);Matrix.multiplyMM(mvp,0,vp,0,model,0)
  GLES20.glUseProgram(program)
  if(tex==0){val image=synchronized(this){pendingImage.also{pendingImage=null}};if(image!=null){val t=IntArray(1);GLES20.glGenTextures(1,t,0);tex=t[0];GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,tex);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,image,0);image.recycle();report("原生 3D 地形绘制完成 · 卫星影像 + DEM 网格 + GPX 轨迹。单指旋转，双指捏合缩放。")}}
  GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"m"),1,false,mvp,0);GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,tex);GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"tex"),0)
  if(!ready||tex==0)return
  val p=GLES20.glGetAttribLocation(program,"p");val uv=GLES20.glGetAttribLocation(program,"uv");GLES20.glEnableVertexAttribArray(p);GLES20.glEnableVertexAttribArray(uv)
  val a=vb!!;a.position(0);GLES20.glVertexAttribPointer(p,3,GLES20.GL_FLOAT,false,20,a);a.position(3);GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,20,a)
  GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"route"),0);GLES20.glDrawElements(GLES20.GL_TRIANGLES,ic,GLES20.GL_UNSIGNED_SHORT,ib)
  if(rc>1){GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"route"),1);rb!!.position(0);GLES20.glVertexAttribPointer(p,3,GLES20.GL_FLOAT,false,12,rb);GLES20.glLineWidth(4f);GLES20.glDrawArrays(GLES20.GL_LINE_STRIP,0,rc)}
  GLES20.glDisableVertexAttribArray(p);GLES20.glDisableVertexAttribArray(uv)
 }
}
