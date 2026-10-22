package com.example.myapp;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity
        implements SensorEventListener, LocationListener {

    private static final int LOCATION_REQUEST = 1001;

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private Sensor accelerometer;
    private Sensor magnetometer;
    private LocationManager locationManager;

    private final float[] rotationMatrix = new float[9];
    private final float[] orientation = new float[3];
    private final float[] gravity = new float[3];
    private final float[] magnetic = new float[3];

    private boolean hasGravity = false;
    private boolean hasMagnetic = false;
    private boolean hasLocation = false;
    private boolean qiblaMode = false;
    private boolean headingLocked = false;
    private boolean webPageReady = false;
    private int pendingWebPage = 2;

    private double latitude = 0;
    private double longitude = 0;
    private double qiblaBearing = 0;

    private float heading = 0;
    private int currentPage = 0;

    private TextView degreeText;
    private TextView directionText;
    private TextView infoText;
    private TextView statusText;
    private Button lockButton;

    private CompassView compassView;
    private WebView webView;
    private LinearLayout root;
    private FrameLayout contentFrame;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        if (sensorManager != null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }

        createInterface();
        createWebView();
        showPage(0);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView makeText(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        return b;
    }

    private void createInterface() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(8));
        root.setBackgroundColor(Color.rgb(12, 17, 30));

        root.addView(makeText("COMPASS V3.1", 25, Color.WHITE, true));
        root.addView(makeText("البوصلة • القبلة • الجغرافيا", 15, Color.LTGRAY, false));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);

        String[] names = {"البوصلة", "القبلة", "الخريطة", "الكرة 3D"};
        for (int i = 0; i < names.length; i++) {
            final int page = i;
            Button button = makeButton(names[i]);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1f);
            p.setMargins(dp(2), dp(8), dp(2), dp(8));
            tabs.addView(button, p);
            button.setOnClickListener(v -> showPage(page));
        }
        root.addView(tabs);

        contentFrame = new FrameLayout(this);
        root.addView(contentFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        statusText = makeText("جاهز • بانتظار المستشعرات", 13, Color.LTGRAY, false);
        root.addView(statusText);
        setContentView(root);
    }

    private void createWebView() {
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setAllowFileAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        if (Build.VERSION.SDK_INT >= 21) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        webView.setBackgroundColor(Color.rgb(12, 17, 30));

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                webPageReady = true;
                runWebPage(pendingWebPage);
                if (hasLocation) sendLocationToWeb();
            }
        });

        webView.loadDataWithBaseURL(
                "https://localhost/",
                createMapHtml(),
                "text/html",
                "UTF-8",
                null);
    }

    private String createMapHtml() {
        return "<!DOCTYPE html><html><head>"
        + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
        + "<link rel='stylesheet' href='https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.css'/>"
        + "<style>html,body,#map,#globe{margin:0;width:100%;height:100%;background:#0c111e;color:white;}"
        + "#globe{display:none}.legend{position:absolute;z-index:999;bottom:8px;left:8px;"
        + "right:8px;background:#101827dd;color:white;padding:8px;font-size:12px;border-radius:8px;}</style>"
        + "</head><body><div id='map'></div><div id='globe'></div>"
        + "<div class='legend' id='legend'>غرينتش • خط الاستواء • المداران • الدائرتان القطبيتان</div>"
        + "<script src='https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js' onerror=\"var f=document.createElement('script');f.src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js';document.head.appendChild(f);\"></script>"
        + "<script>"
        + "var map=null,userMarker=null,globeInstance=null,globeReady=false,lastLat=null,lastLon=null;"
        + "function initMap(){if(typeof L==='undefined'){document.getElementById('legend').innerText='تعذر تحميل الخريطة. تحقق من الإنترنت.';return;}"
        + "map=L.map('map').setView([20,0],2);"
        + "L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:18,attribution:'© OpenStreetMap'}).addTo(map);"
        + "function addLine(coords,color,name){L.polyline(coords,{color:color,weight:2,dashArray:'6 5'}).addTo(map).bindPopup(name);}"
        + "addLine([[-90,0],[90,0]],'#ffffff','خط غرينتش 0°');"
        + "addLine([[0,-180],[0,180]],'#00e5ff','خط الاستواء 0°');"
        + "addLine([[23.44,-180],[23.44,180]],'#ffb74d','مدار السرطان');"
        + "addLine([[-23.44,-180],[-23.44,180]],'#ffb74d','مدار الجدي');"
        + "addLine([[66.56,-180],[66.56,180]],'#ce93d8','الدائرة القطبية الشمالية');"
        + "addLine([[-66.56,-180],[-66.56,180]],'#ce93d8','الدائرة القطبية الجنوبية');"
        + "}"
        + "function setUserLocation(lat,lon){lastLat=lat;lastLon=lon;"
        + "if(map&&window.L){if(userMarker)map.removeLayer(userMarker);"
        + "userMarker=L.marker([lat,lon]).addTo(map).bindPopup('موقعك الحالي');}"
        + "if(globeReady&&globeInstance){globeInstance.pointsData([{lat:lat,lng:lon,name:'موقعك الحالي'}]);}}"
        + "function showMap(){document.getElementById('map').style.display='block';"
        + "document.getElementById('globe').style.display='none';document.getElementById('legend').style.display='block';"
        + "if(!map)initMap();setTimeout(function(){if(map)map.invalidateSize();"
        + "if(lastLat!==null&&map){setUserLocation(lastLat,lastLon);map.setView([lastLat,lastLon],5);}},250);}"
        + "function showGlobe(){document.getElementById('map').style.display='none';"
        + "document.getElementById('globe').style.display='block';document.getElementById('legend').style.display='none';"
        + "if(!globeReady)createGlobe();else if(lastLat!==null)globeInstance.pointsData([{lat:lastLat,lng:lastLon,name:'موقعك الحالي'}]);}"
        + "function createGlobe(){var s=document.createElement('script');s.src='https://cdn.jsdelivr.net/npm/globe.gl';"
        + "s.onerror=function(){document.getElementById('globe').innerHTML='<div style=\"padding:16px\">تعذر تحميل الكرة الأرضية. أعد المحاولة لاحقاً.</div>';};"
        + "s.onload=function(){var el=document.getElementById('globe');"
        + "globeInstance=Globe()(el).globeImageUrl('https://cdn.jsdelivr.net/npm/three-globe/example/img/earth-blue-marble.jpg')"
        + ".backgroundColor('#0c111e').showAtmosphere(true).atmosphereColor('#4fc3f7').atmosphereAltitude(0.15);"
        + "var paths=[];function line(lat){var a=[];for(var lon=-180;lon<=180;lon+=2)a.push([lon,lat]);return a;}"
        + "function meridian(lon){var a=[];for(var lat=-90;lat<=90;lat+=2)a.push([lon,lat]);return a;}"
        + "paths.push({name:'خط الاستواء',path:line(0)});paths.push({name:'مدار السرطان',path:line(23.44)});"
        + "paths.push({name:'مدار الجدي',path:line(-23.44)});paths.push({name:'الدائرة القطبية الشمالية',path:line(66.56)});"
        + "paths.push({name:'الدائرة القطبية الجنوبية',path:line(-66.56)});paths.push({name:'غرينتش',path:meridian(0)});"
        + "globeInstance.pathsData(paths).pathPoints('path').pathPointLat(p=>p[1]).pathPointLng(p=>p[0])"
        + ".pathColor(d=>d.name==='خط الاستواء'?'#00e5ff':d.name==='غرينتش'?'#ffffff':d.name.indexOf('القطبية')>=0?'#ce93d8':'#ffb74d')"
        + ".pathStroke(0.7).pathDashLength(0.8).pathDashGap(0.2).pathLabel(d=>d.name);"
        + "globeReady=true;if(lastLat!==null)globeInstance.pointsData([{lat:lastLat,lng:lastLon,name:'موقعك الحالي'}]);"
        + "};document.head.appendChild(s);}"
        + "window.addEventListener('error',function(){document.getElementById('legend').innerText='حدث خطأ أثناء تحميل الخريطة أو المكتبات.';});"
        + "initMap();</script></body></html>";
    }

    private void showPage(int page) {
        currentPage = page;
        contentFrame.removeAllViews();

        if (page == 2 || page == 3) {
            contentFrame.addView(webView, new FrameLayout.LayoutParams(-1, -1));
            pendingWebPage = page;
            if (webPageReady) runWebPage(page);
            statusText.setText(page == 2
                    ? "الخريطة تحتاج إلى اتصال بالإنترنت"
                    : "الكرة الأرضية ثلاثية الأبعاد تحتاج إلى الإنترنت");
            return;
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(8), dp(8), dp(8), dp(8));
        scroll.addView(panel);
        contentFrame.addView(scroll);

        if (page == 0) {
            qiblaMode = false;
            degreeText = makeText("0.0°", 42, Color.WHITE, true);
            directionText = makeText("شمال • North", 23, Color.rgb(255, 90, 90), true);
            compassView = new CompassView(this);
            panel.addView(degreeText);
            panel.addView(directionText);
            panel.addView(compassView, new LinearLayout.LayoutParams(-1, dp(330)));

            Button locationButton = makeButton("تحديد موقعي الجغرافي");
            lockButton = makeButton(headingLocked ? "إلغاء تثبيت الاتجاه" : "تثبيت الاتجاه");
            panel.addView(locationButton);
            panel.addView(lockButton);
            infoText = makeText(hasLocation ? formatLocation() : "الموقع: غير محدد", 14, Color.LTGRAY, false);
            panel.addView(infoText);
            locationButton.setOnClickListener(v -> requestLocation());
            lockButton.setOnClickListener(v -> {
                headingLocked = !headingLocked;
                lockButton.setText(headingLocked ? "إلغاء تثبيت الاتجاه" : "تثبيت الاتجاه");
                Toast.makeText(this, headingLocked ? "تم تثبيت الاتجاه الحالي" : "تم إلغاء تثبيت الاتجاه",
                        Toast.LENGTH_SHORT).show();
            });
            updateHeading(heading);
        } else {
            qiblaMode = true;
            panel.addView(makeText("تحديد اتجاه القبلة", 27, Color.WHITE, true));
            panel.addView(makeText("اسمح بالوصول إلى الموقع لحساب الاتجاه.", 16, Color.LTGRAY, false));
            compassView = new CompassView(this);
            panel.addView(compassView, new LinearLayout.LayoutParams(-1, dp(300)));
            infoText = makeText(hasLocation ? "" : "لم يتم تحديد الموقع بعد", 16, Color.WHITE, true);
            panel.addView(infoText);
            Button b = makeButton("تحديد القبلة من موقعي");
            panel.addView(b);
            b.setOnClickListener(v -> requestLocation());
            if (hasLocation) calculateQibla();
        }
    }

    private void runWebPage(int page) {
        if (webView == null || !webPageReady) return;
        String function = page == 3 ? "showGlobe()" : "showMap()";
        webView.evaluateJavascript(function, null);
        if (hasLocation) sendLocationToWeb();
    }

    private void sendLocationToWeb() {
        if (webView == null || !webPageReady || !hasLocation) return;
        webView.evaluateJavascript("setUserLocation(" + latitude + "," + longitude + ")", null);
    }

    private String formatLocation() {
        return String.format(Locale.US, "خط العرض: %.5f°\nخط الطول: %.5f°", latitude, longitude);
    }

    private boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocation() {
        if (!hasLocationPermission()) {
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, LOCATION_REQUEST);
            }
            return;
        }
        if (locationManager == null) {
            statusText.setText("خدمة الموقع غير متاحة على هذا الجهاز");
            return;
        }

        try {
            Location best = null;
            boolean fineGranted = Build.VERSION.SDK_INT < 23
                    || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

            if (fineGranted && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (gps != null) best = gps;
            }

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                Location network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (network != null && (best == null || network.getTime() > best.getTime())) best = network;
            }

            if (best != null) onLocationChanged(best);

            if (fineGranted && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 5, this);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 10, this);
            }

            statusText.setText(best == null ? "جارٍ تحديد الموقع..." : "تم استخدام آخر موقع معروف؛ جارٍ تحديثه...");
        } catch (SecurityException e) {
            statusText.setText("إذن الموقع غير كافٍ. اسمح بالموقع من إعدادات التطبيق.");
        } catch (Exception e) {
            statusText.setText("تعذر تحديد الموقع. تأكد من تشغيل خدمة الموقع.");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_REQUEST) {
            boolean granted = false;
            for (int result : grantResults) if (result == PackageManager.PERMISSION_GRANTED) granted = true;
            if (granted) requestLocation();
            else Toast.makeText(this, "يلزم إذن الموقع لحساب القبلة وعرض موقعك", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        latitude = location.getLatitude();
        longitude = location.getLongitude();
        hasLocation = true;
        sendLocationToWeb();

        if (infoText != null) {
            if (qiblaMode) calculateQibla();
            else infoText.setText(formatLocation());
        }
        statusText.setText("تم تحديد الموقع • الدقة التقريبية: " + Math.round(location.getAccuracy()) + " متر");
    }

    private void calculateQibla() {
        double kaabaLat = Math.toRadians(21.4225);
        double kaabaLon = Math.toRadians(39.8262);
        double userLat = Math.toRadians(latitude);
        double userLon = Math.toRadians(longitude);
        double deltaLon = kaabaLon - userLon;

        double y = Math.sin(deltaLon);
        double x = Math.cos(userLat) * Math.tan(kaabaLat)
                - Math.sin(userLat) * Math.cos(deltaLon);

        qiblaBearing = (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
        if (infoText != null) {
            infoText.setText(String.format(Locale.US,
                    "اتجاه القبلة: %.1f°\nموقعك: %.5f, %.5f\nاتجاه الهاتف: %.1f°",
                    qiblaBearing, latitude, longitude, heading));
        }
        if (compassView != null) compassView.invalidate();
    }

    private void updateHeading(float degrees) {
        float normalized = (degrees + 360f) % 360f;
        if (headingLocked) return;
        heading = normalized;

        String[] directions = {
                "شمال • North", "شمال شرقي • NE", "شرق • East", "جنوب شرقي • SE",
                "جنوب • South", "جنوب غربي • SW", "غرب • West", "شمال غربي • NW"
        };
        int index = ((int) ((heading + 22.5f) / 45f)) % 8;
        if (degreeText != null) degreeText.setText(String.format(Locale.US, "%.1f°", heading));
        if (directionText != null) directionText.setText(directions[index]);
        if (qiblaMode && infoText != null && hasLocation) {
            infoText.setText(String.format(Locale.US,
                    "اتجاه القبلة: %.1f°\nموقعك: %.5f, %.5f\nاتجاه الهاتف: %.1f°",
                    qiblaBearing, latitude, longitude, heading));
        }
        if (compassView != null) compassView.invalidate();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.getOrientation(rotationMatrix, orientation);
            updateHeading((float) Math.toDegrees(orientation[0]));
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, gravity, 0, 3);
            hasGravity = true;
            updateFallback();
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetic, 0, 3);
            hasMagnetic = true;
            updateFallback();
        }
    }

    private void updateFallback() {
        if (!hasGravity || !hasMagnetic) return;
        if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, magnetic)) {
            SensorManager.getOrientation(rotationMatrix, orientation);
            updateHeading((float) Math.toDegrees(orientation[0]));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (statusText != null && accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            statusText.setText("دقة المستشعر منخفضة؛ أبعد الهاتف عن المغناطيس وحرّكه على شكل رقم 8");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager == null) return;
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
        } else {
            if (accelerometer != null) sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
            if (magnetometer != null) sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (SecurityException ignored) {}
        }
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}

    private class CompassView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CompassView(Context context) { super(context); }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(cx, cy) * 0.88f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(25, 36, 57));
            canvas.drawCircle(cx, cy, r, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.rgb(90, 125, 165));
            canvas.drawCircle(cx, cy, r, paint);

            for (int i = 0; i < 360; i += 5) {
                canvas.save();
                canvas.rotate(i, cx, cy);
                paint.setColor(Color.LTGRAY);
                paint.setStrokeWidth(i % 30 == 0 ? dp(2) : dp(1));
                float start = r - (i % 30 == 0 ? dp(17) : dp(9));
                canvas.drawLine(cx, cy - start, cx, cy - r + dp(4), paint);
                canvas.restore();
            }

            canvas.save();
            canvas.rotate(-heading, cx, cy);
            drawLabel(canvas, "N", cx, cy - r + dp(38), Color.RED, dp(22));
            drawLabel(canvas, "S", cx, cy + r - dp(17), Color.WHITE, dp(18));
            drawLabel(canvas, "W", cx - r + dp(18), cy + dp(6), Color.WHITE, dp(18));
            drawLabel(canvas, "E", cx + r - dp(18), cy + dp(6), Color.WHITE, dp(18));

            Path north = new Path();
            north.moveTo(cx, cy - r + dp(53));
            north.lineTo(cx - dp(14), cy);
            north.lineTo(cx, cy - dp(8));
            north.lineTo(cx + dp(14), cy);
            north.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.RED);
            canvas.drawPath(north, paint);
            canvas.restore();

            if (qiblaMode && hasLocation) {
                canvas.save();
                canvas.rotate((float) (qiblaBearing - heading), cx, cy);
                Path qibla = new Path();
                qibla.moveTo(cx, cy - r + dp(53));
                qibla.lineTo(cx - dp(12), cy + dp(8));
                qibla.lineTo(cx, cy);
                qibla.lineTo(cx + dp(12), cy + dp(8));
                qibla.close();
                paint.setColor(Color.GREEN);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(qibla, paint);
                canvas.restore();
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(cx, cy, dp(5), paint);
            if (qiblaMode) drawLabel(canvas, "السهم الأخضر: القبلة",
                    cx, getHeight() - dp(10), Color.GREEN, dp(12));
        }

        private void drawLabel(Canvas canvas, String text, float x, float y, int color, float size) {
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawText(text, x, y, paint);
        }
    }
}
