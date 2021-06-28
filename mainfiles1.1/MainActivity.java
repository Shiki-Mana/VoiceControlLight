package com.example.voicetest2;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.baidu.speech.EventListener;
import com.baidu.speech.EventManager;
import com.baidu.speech.EventManagerFactory;
import com.baidu.speech.asr.SpeechConstant;
import com.google.gson.Gson;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;


public class MainActivity extends AppCompatActivity implements EventListener {
    private Button button1,button2;
    protected TextView txtResult;//识别结果
    protected Button startBtn;//开始识别  一直不说话会自动停止，需要再次打开
    protected Button stopBtn;//停止识别

    private EventManager asr;//语音识别核心库

    private Boolean isFirstStart = true;//判断是否为第一次启动
    public static String productKey;
    public static  String deviceName;
    public static String deviceSecret;
    public static String regionId;

    private static String pubTopic;
    private static String setTopic;
    private static final String payloadJson =
            "{"+
                    "\"id\": %s,"+
                    "\"params\": {"+
                    "\"LightSwitch\": %s,},"+
                    "\"method\": \"thing.event.property.post\"}";
    private static MqttClient mqttClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initView();
        initPermission();

        //初始化EventManager对象
        asr = EventManagerFactory.create(this, "asr");
        //注册自己的输出事件类
        asr.registerListener(this); //  EventListener 中 onEvent方法

        //第一次启动
        isFirstStart=true;
        //按钮2未连接=不可用
        button2.setEnabled(false);
        button2.setText("当前已与 IOT STUDIO 断开连接");

        //加载properties文件相关信息
        ResourceBundle resource=ResourceBundle.getBundle("assets/thing");
        productKey=resource.getString("productKey");
        deviceName=resource.getString("deviceName");
        deviceSecret=resource.getString("deviceSecret");
        regionId=resource.getString("regionId");
        pubTopic= "/sys/" + productKey + "/" + deviceName + "/thing/event/property/post";
        setTopic="/sys/" + productKey + "/" + deviceName + "/user/data";

    }


    //初始化连接的配置
    private static void initAliyunIoTClient() {
        try {
            //连接所需要的信息：服务器地址，客户端id，用户名，密码
            String clientId = "java" + System.currentTimeMillis();
            Map<String, String> params = new HashMap<>(16);
            params.put("productKey", productKey);
            params.put("deviceName", deviceName);
            params.put("clientId", clientId);
            String timestamp = String.valueOf(System.currentTimeMillis());
            params.put("timestamp", timestamp);
            // 这里阿里云的服务器地区为cn-shanghai
            String targetServer = "tcp://" + productKey + ".iot-as-mqtt."+regionId+".aliyuncs.com:1883";
            String mqttclientId = clientId + "|securemode=3,signmethod=hmacsha1,timestamp=" + timestamp + "|";
            String mqttUsername = deviceName + "&" + productKey;
            String mqttPassword = sign(params, deviceSecret, "hmacsha1");//获得密码
            connectMqtt(targetServer, mqttclientId, mqttUsername, mqttPassword);
        } catch (Exception e) {
            System.out.println("initAliyunIoTClient error " + e.getMessage());
        }
    }
    //连接MQTT
    public  static void connectMqtt(String url,String clientId,String mqttUsername,String mqttPassword) throws Exception {
        MemoryPersistence persistence = new MemoryPersistence();
        mqttClient = new MqttClient(url,clientId,persistence);
        MqttConnectOptions connectOptions = new MqttConnectOptions();
        connectOptions.setMqttVersion(4);
        connectOptions.setCleanSession(false);
        connectOptions.setAutomaticReconnect(false);
        connectOptions.setUserName(mqttUsername);
        connectOptions.setPassword(mqttPassword.toCharArray());
        connectOptions.setKeepAliveInterval(60);
        mqttClient.connect(connectOptions);
    }
    public static String sign(Map<String,String>params,String deviceSecret,String signMethod){
        String[] sortedKeys = params.keySet().toArray(new String[]{});
        Arrays.sort(sortedKeys);
        StringBuilder canonicalizedQueryString = new StringBuilder();
        for (String key: sortedKeys){
            if ("sign".equalsIgnoreCase(key)){
                continue;
            }
            canonicalizedQueryString.append(key).append(params.get(key));
        }
        try {
            String key = deviceSecret;
            return encryptHMAC(signMethod,canonicalizedQueryString.toString(),key);
        }catch (Exception e){
            throw  new RuntimeException(e);
        }
    }
    public static String encryptHMAC(String signMethod,String content,String key) throws Exception {
        SecretKey secretKey = new SecretKeySpec(key.getBytes("utf-8"),signMethod);
        Mac mac = Mac.getInstance(secretKey.getAlgorithm());
        mac.init(secretKey);
        byte[] data = mac.doFinal(content.getBytes("utf-8"));
        return bytesToHexString(data);
    }

    //转16进制
    public static final String bytesToHexString(byte[] bArray){
        StringBuffer sb = new StringBuffer(bArray.length);
        String sTemp;
        for (int i=0;i<bArray.length;i++){
            sTemp = Integer.toHexString(0xFF & bArray[i]);
            if (sTemp.length()<2){
                sb.append(0);
            }
            sb.append(sTemp.toUpperCase());
        }
        return sb.toString();
    }

    private class myButton1 implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            initAliyunIoTClient();
            button1.setEnabled(false);
            button1.setText("已连接至IOT STUDIO");
            button2.setEnabled(true);
            button2.setText("断开与IOT STUDIO 的连接");
        }
    }
    private class myButton2 implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            try {
                if (mqttClient.isConnected()){
                    mqttClient.disconnect();
                    Toast.makeText(getApplicationContext(),"已断开连接",Toast.LENGTH_SHORT).show();
                    button1.setEnabled(true);
                    button1.setText("已连接至IOT STUDIO");
                    button2.setEnabled(false);
                    button2.setText("当前与IOT STUDIO 断开连接");

                }

            }catch (Exception e){
                e.printStackTrace();
                Toast.makeText(getApplicationContext(),"发生错误，断开连接失败。",Toast.LENGTH_SHORT).show();
            }
        }
    }

    //设备属性上报
    private static  String postDeviceProperties(String status){
        String payload= null ;
        try {
            payload = String.format(payloadJson,System.currentTimeMillis(),status);
            System.out.println("post:"+payload);
            MqttMessage message = new MqttMessage(payload.getBytes("utf-8"));
            message.setQos(1);
            mqttClient.publish(pubTopic,message);
        }catch (Exception e){
            e.printStackTrace();
        }finally {
            return payload;
        }
    }

    /**
     * 初始化控件
     */
    private void initView() {
        button1 = findViewById(R.id.bt1);
        button1.setOnClickListener(new myButton1());
        button2 = findViewById(R.id.bt2);
        button2.setOnClickListener(new myButton2());

        txtResult = findViewById(R.id.tv_txt);
        startBtn = findViewById(R.id.btn_start);
        stopBtn = findViewById(R.id.btn_stop);
        startBtn.setOnClickListener(new mystartBtn());
        stopBtn.setOnClickListener(new mystopBtn());
    }

    private class mystartBtn implements View.OnClickListener {
        //开始
        @Override
        public void onClick(View v) {
            asr.send(SpeechConstant.ASR_START, "{}", null, 0, 0);
        }
    }
    private class mystopBtn implements View.OnClickListener {
        //结束
        @Override
        public void onClick(View v) {
            asr.send(SpeechConstant.ASR_STOP, "{}", null, 0, 0);
        }
    }

    /**
     * android 6.0 以上需要动态申请权限
     */
    private void initPermission() {
        String permissions[] = {Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.INTERNET,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };

        ArrayList<String> toApplyList = new ArrayList<String>();

        for (String perm : permissions) {
            if (PackageManager.PERMISSION_GRANTED != ContextCompat.checkSelfPermission(this, perm)) {
                toApplyList.add(perm);
            }
        }
        String tmpList[] = new String[toApplyList.size()];
        if (!toApplyList.isEmpty()) {
            ActivityCompat.requestPermissions(this, toApplyList.toArray(tmpList), 123);
        }

    }

    /**
     * 权限申请回调，可以作进一步处理
     * @param requestCode
     * @param permissions
     * @param grantResults
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        // 此处为android 6.0以上动态授权的回调，用户自行实现。
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }


    /**
     * 自定义输出事件类 EventListener 回调方法
     */
    @Override
    public void onEvent(String name, String params, byte[] data, int offset, int length) {

        if (name.equals(SpeechConstant.CALLBACK_EVENT_ASR_PARTIAL)) {
            // 识别相关的结果都在这里
            if (params == null || params.isEmpty()) {
                return;
            }
            if (params.contains("\"final_result\"")) {
                // 一句话的最终识别结果
                Log.i("ars.event",params);
                txtResult.setText(params);
                Gson gson = new Gson();
                ASRresponse asRresponse = gson.fromJson(params, ASRresponse.class);//数据解析转实体bean

                if(asRresponse == null) return;
                //从日志中，得出Best_result的值才是需要的，但是后面跟了一个中文输入法下的逗号，
                if(asRresponse.getBest_result().contains("。")){//包含逗号  则将逗号替换为空格，这个地方还会问题，还可以进一步做出来，你知道吗？
                    txtResult.setText(asRresponse.getBest_result().replace('。',' ').trim());//替换为空格之后，通过trim去掉字符串的首尾空格
                    if(params.contains("开灯")&& mqttClient.isConnected()) {
                        String payload=postDeviceProperties("1");
                    }
                    if(params.contains("关灯")&& mqttClient.isConnected()){
                        String payload=postDeviceProperties("0");
                    }
                }else {//不包含
                    txtResult.setText(asRresponse.getBest_result().trim());
                }
            }
        }

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        //发送取消事件
        asr.send(SpeechConstant.ASR_CANCEL, "{}", null, 0, 0);
        //退出事件管理器
        // 必须与registerListener成对出现，否则可能造成内存泄露
        asr.unregisterListener(this);
    }



}