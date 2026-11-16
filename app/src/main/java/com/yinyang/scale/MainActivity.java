package com.yinyang.scale;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import android.view.WindowManager;
import android.webkit.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class MainActivity extends Activity {
  private static final String SERVICE="_yyscale._tcp.";
  private final ExecutorService worker=Executors.newCachedThreadPool();
  private final SecureRandom random=new SecureRandom();
  private final com.google.mlkit.vision.text.TextRecognizer ocr=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
  private WebView web;
  private NsdManager nsd;
  private NsdManager.RegistrationListener reg;
  private NsdManager.DiscoveryListener discovery;
  private WifiManager.MulticastLock multicast;
  private ServerSocket server;
  private volatile Socket peer;
  private volatile BufferedWriter output;
  private volatile boolean running=false,sender=false;
  private volatile String code="",wanted="";
  private CameraDevice camera;
  private CameraCaptureSession capture;
  private ImageReader images;
  private HandlerThread cameraThread;
  private Handler cameraHandler;
  private volatile long lastFrame=0;
  private volatile String previous="";
  private volatile int repeated=0;
  private volatile boolean processing=false;

  @Override @SuppressWarnings("deprecation") public void onCreate(Bundle saved){
    super.onCreate(saved);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    nsd=(NsdManager)getSystemService(NSD_SERVICE);
    WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    if(wifi!=null){multicast=wifi.createMulticastLock("yinyang-discovery");multicast.setReferenceCounted(false);multicast.acquire();}
    web=new WebView(this);
    web.getSettings().setJavaScriptEnabled(true);
    web.getSettings().setDomStorageEnabled(true);
    web.addJavascriptInterface(new Bridge(),"Native");
    web.setWebChromeClient(new WebChromeClient());
    web.setWebViewClient(new WebViewClient());
    setContentView(web);
    web.loadUrl("file:///android_asset/index.html");
  }

  private void event(String type,String value){
    try{
      JSONObject o=new JSONObject();o.put("type",type);o.put("value",value==null?"":value);
      String script="window.onNativeEvent&&window.onNativeEvent("+o.toString()+")";
      runOnUiThread(()->{if(web!=null)web.evaluateJavascript(script,null);});
    }catch(Exception ignored){}
  }

  public class Bridge{
    @JavascriptInterface public void startSender(){runOnUiThread(()->{
      stopEverything();
      if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
        requestPermissions(new String[]{Manifest.permission.CAMERA},31);
      else startSenderInternal();
    });}
    @JavascriptInterface public void connect(String pin){runOnUiThread(()->startReceiver(pin));}
    @JavascriptInterface public void stop(){runOnUiThread(()->stopEverything());}
    @JavascriptInterface public void setTorch(boolean on){runOnUiThread(()->torch(on));}
  }

  @Override public void onRequestPermissionsResult(int request,String[] perms,int[] grants){
    super.onRequestPermissionsResult(request,perms,grants);
    if(request==31){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)startSenderInternal();else event("error","Нужно разрешение на камеру");}
  }

  private void startSenderInternal(){
    running=true;sender=true;
    code=String.format(Locale.US,"%06d",random.nextInt(1_000_000));
    event("code",code);event("status","Камера готовится…");
    worker.execute(()->{
      try{
        ServerSocket s=new ServerSocket(0);server=s;
        if(!running){s.close();return;}
        advertise(s.getLocalPort());
        while(running&&!s.isClosed()){
          Socket incoming=s.accept();incoming.setSoTimeout(7000);
          String pin=new BufferedReader(new InputStreamReader(incoming.getInputStream(),"UTF-8")).readLine();
          if(!code.equals(pin)){incoming.close();continue;}
          incoming.setSoTimeout(0);
          synchronized(this){
            if(peer!=null)try{peer.close();}catch(Exception ignored){}
            peer=incoming;
            output=new BufferedWriter(new OutputStreamWriter(incoming.getOutputStream(),"UTF-8"));
          }
          event("status","Телефон Ян подключён");
        }
      }catch(Exception ex){if(running)event("error","Ошибка сервера: "+ex.getMessage());}
    });
    startCamera();
  }

  private void advertise(int port){
    NsdServiceInfo i=new NsdServiceInfo();i.setServiceName("yys-"+code);i.setServiceType(SERVICE);i.setPort(port);
    NsdManager.RegistrationListener listener=new NsdManager.RegistrationListener(){
      public void onServiceRegistered(NsdServiceInfo x){event("status","Ожидаю Ян • Код "+code);}
      public void onRegistrationFailed(NsdServiceInfo x,int e){event("error","Не удалось открыть обнаружение Wi-Fi: "+e);}
      public void onServiceUnregistered(NsdServiceInfo x){}
      public void onUnregistrationFailed(NsdServiceInfo x,int e){}
    };
    reg=listener;
    runOnUiThread(()->{if(running)try{nsd.registerService(i,NsdManager.PROTOCOL_DNS_SD,listener);}catch(Exception e){event("error",e.getMessage());}});
  }

  private void startReceiver(String pin){
    if(pin==null||!pin.matches("\\d{6}")){event("error","Введи шестизначный код");return;}
    stopEverything();running=true;sender=false;wanted=pin;findPeer();
  }

  private void findPeer(){
    if(!running||sender)return;
    event("status","Ищу камеру "+wanted+" в локальной Wi-Fi-сети…");
    NsdManager.DiscoveryListener listener=new NsdManager.DiscoveryListener(){
      public void onDiscoveryStarted(String x){}
      public void onDiscoveryStopped(String x){}
      public void onStartDiscoveryFailed(String x,int e){event("error","Поиск Wi-Fi недоступен: "+e);}
      public void onStopDiscoveryFailed(String x,int e){}
      public void onServiceLost(NsdServiceInfo x){}
      public void onServiceFound(NsdServiceInfo x){
        if(!running||!x.getServiceName().equalsIgnoreCase("yys-"+wanted))return;
        nsd.resolveService(x,new NsdManager.ResolveListener(){
          public void onResolveFailed(NsdServiceInfo a,int e){event("error","Ошибка обнаружения: "+e);}
          public void onServiceResolved(NsdServiceInfo a){if(running)worker.execute(()->receive(a));}
        });
      }
    };
    discovery=listener;
    try{nsd.discoverServices(SERVICE,NsdManager.PROTOCOL_DNS_SD,listener);}
    catch(Exception ex){event("error","Ошибка поиска: "+ex.getMessage());}
  }

  private void receive(NsdServiceInfo service){
    if(!running||peer!=null)return;
    Socket socket=new Socket();
    try{
      socket.connect(new InetSocketAddress(service.getHost(),service.getPort()),5000);
      socket.setSoTimeout(20000);
      BufferedWriter auth=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),"UTF-8"));
      auth.write(wanted);auth.newLine();auth.flush();
      synchronized(this){if(!running||peer!=null)return;peer=socket;}
      event("status","Подключено к камере Инь");
      NsdManager.DiscoveryListener old=discovery;discovery=null;
      if(old!=null)runOnUiThread(()->{try{nsd.stopServiceDiscovery(old);}catch(Exception ignored){}});
      BufferedReader br=new BufferedReader(new InputStreamReader(socket.getInputStream(),"UTF-8"));
      String line;
      while(running&&(line=br.readLine())!=null){
        if(line.length()>1800000)continue;
        try{
          JSONObject json=new JSONObject(line);
          for(String key:new String[]{"image","weight","raw","verified"}){
            if(json.has(key))event(key.equals("image")?"frame":key,json.optString(key,""));
          }
        }catch(Exception ignored){}
      }
    }catch(Exception ex){if(running)event("status","Связь прервана, переподключаюсь…");}
    finally{
      try{socket.close();}catch(Exception ignored){}
      synchronized(this){if(peer==socket)peer=null;}
      if(running&&!sender)runOnUiThread(()->new Handler(getMainLooper()).postDelayed(()->{if(running&&!sender&&peer==null)startReceiver(wanted);},1800));
    }
  }

  @SuppressWarnings("deprecation") private void startCamera(){
    try{
      cameraThread=new HandlerThread("yin-camera");cameraThread.start();cameraHandler=new Handler(cameraThread.getLooper());
      CameraManager mgr=(CameraManager)getSystemService(CAMERA_SERVICE);
      String cameraId=null;
      for(String id:mgr.getCameraIdList()){
        Integer facing=mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
        if(facing!=null&&facing==CameraCharacteristics.LENS_FACING_BACK){cameraId=id;break;}
      }
      if(cameraId==null){event("error","Нет задней камеры");return;}
      android.hardware.camera2.params.StreamConfigurationMap map=mgr.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      android.util.Size[] sizes=map.getOutputSizes(ImageFormat.JPEG);
      android.util.Size pick=sizes[0];long best=Long.MAX_VALUE;
      for(android.util.Size s:sizes){long pixels=(long)s.getWidth()*s.getHeight();long score=Math.abs(pixels-640L*480L);if(score<best){pick=s;best=score;}}
      images=ImageReader.newInstance(pick.getWidth(),pick.getHeight(),ImageFormat.JPEG,2);
      images.setOnImageAvailableListener(r->{
        Image img=r.acquireLatestImage();if(img==null)return;
        try{
          long now=SystemClock.elapsedRealtime();
          if(now-lastFrame<700)return;
          lastFrame=now;
          ByteBuffer buf=img.getPlanes()[0].getBuffer();
          byte[] bytes=new byte[buf.remaining()];buf.get(bytes);
          worker.execute(()->process(bytes));
        }finally{img.close();}
      },cameraHandler);
      mgr.openCamera(cameraId,new CameraDevice.StateCallback(){
        @Override public void onOpened(CameraDevice dev){
          camera=dev;
          try{
            CaptureRequest.Builder req=dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(images.getSurface());
            req.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            req.set(CaptureRequest.JPEG_ORIENTATION,90);
            dev.createCaptureSession(Collections.singletonList(images.getSurface()),new CameraCaptureSession.StateCallback(){
              @Override public void onConfigured(CameraCaptureSession s){capture=s;try{s.setRepeatingRequest(req.build(),null,cameraHandler);}catch(Exception e){event("error",e.getMessage());}}
              @Override public void onConfigureFailed(CameraCaptureSession s){event("error","Ошибка запуска видео");}
            },cameraHandler);
          }catch(Exception e){event("error",e.getMessage());}
        }
        @Override public void onDisconnected(CameraDevice d){d.close();event("error","Камера отключена");}
        @Override public void onError(CameraDevice d,int e){d.close();event("error","Камера: ошибка "+e);}
      },cameraHandler);
    }catch(Exception e){event("error","Камера: "+e.getMessage());}
  }

  private void process(byte[] bytes){
    if(!running||!sender)return;
    String frame=android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP);
    event("frame",frame);
    try{JSONObject j=new JSONObject();j.put("image",frame);send(j);}catch(Exception ignored){}
    if(processing)return;
    Bitmap bmp=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
    if(bmp==null)return;
    processing=true;
    ocr.process(InputImage.fromBitmap(bmp,0)).addOnCompleteListener(task->{
      processing=false;bmp.recycle();
      if(!running||!sender)return;
      if(!task.isSuccessful()){event("error","OCR недоступен");return;}
      String raw=task.getResult().getText();
      String value=extract(raw);
      if(!value.isEmpty()&&value.equals(previous))repeated++;else{previous=value;repeated=1;}
      boolean verified=!value.isEmpty()&&repeated>=2;
      event("weight",value);event("raw",raw);event("verified",String.valueOf(verified));
      try{
        JSONObject j=new JSONObject();j.put("weight",value);j.put("raw",raw);j.put("verified",verified);send(j);
      }catch(Exception ignored){}
    });
  }

  private static String extract(String raw){
    if(raw==null)return "";
    String s=raw.replace('О','0').replace('о','0').replace(',','.');
    Matcher units=Pattern.compile("(\\d{1,4}(?:\\.\\d{1,3})?)\\s*(?:кг|kg)",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(s);
    if(units.find())return units.group(1).replace('.',',');
    Matcher m=Pattern.compile("(?<!\\d)(\\d{1,3}\\.\\d{1,3})(?!\\d)").matcher(s);
    return m.find()?m.group(1).replace('.',','):"";
  }

  private synchronized void send(JSONObject j){
    if(output==null)return;
    try{output.write(j.toString());output.newLine();output.flush();}
    catch(Exception e){try{peer.close();}catch(Exception ignored){}peer=null;output=null;event("status","Ожидаю повторное подключение Ян");}
  }

  private void torch(boolean on){
    try{
      if(camera==null||capture==null||images==null)return;
      CaptureRequest.Builder b=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
      b.addTarget(images.getSurface());b.set(CaptureRequest.FLASH_MODE,on?CaptureRequest.FLASH_MODE_TORCH:CaptureRequest.FLASH_MODE_OFF);
      b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
      capture.setRepeatingRequest(b.build(),null,cameraHandler);
    }catch(Exception e){event("error","Фонарик недоступен");}
  }

  private synchronized void stopEverything(){
    running=false;sender=false;
    if(capture!=null){try{capture.stopRepeating();capture.close();}catch(Exception ignored){}capture=null;}
    if(camera!=null){try{camera.close();}catch(Exception ignored){}camera=null;}
    if(images!=null){try{images.close();}catch(Exception ignored){}images=null;}
    if(cameraThread!=null){cameraThread.quitSafely();cameraThread=null;}
    if(peer!=null){try{peer.close();}catch(Exception ignored){}peer=null;}
    output=null;
    if(server!=null){try{server.close();}catch(Exception ignored){}server=null;}
    if(reg!=null){try{nsd.unregisterService(reg);}catch(Exception ignored){}reg=null;}
    if(discovery!=null){try{nsd.stopServiceDiscovery(discovery);}catch(Exception ignored){}discovery=null;}
  }

  @Override public void onBackPressed(){stopEverything();super.onBackPressed();}
  @Override protected void onDestroy(){
    stopEverything();if(multicast!=null&&multicast.isHeld())multicast.release();
    ocr.close();worker.shutdownNow();if(web!=null){web.destroy();web=null;}super.onDestroy();
  }
}
