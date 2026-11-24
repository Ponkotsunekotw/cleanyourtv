package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;

/**
 * Tiny LAN HTTP server for uploading a wallpaper image from a phone browser.
 */
final class WallpaperServer {
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Context ctx;
    private final Runnable onUploaded;
    private ServerSocket server;
    private volatile boolean running;

    WallpaperServer(Context ctx, Runnable onUploaded) {
        this.ctx = ctx.getApplicationContext();
        this.onUploaded = onUploaded;
    }

    int start() throws IOException {
        try {
            server = new ServerSocket(8765);
        } catch (IOException e) {
            server = new ServerSocket(0);
        }
        running = true;
        int port = server.getLocalPort();
        new Thread(this::acceptLoop, "wallpaper-http").start();
        return port;
    }

    void stop() {
        running = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
            server = null;
        }
    }

    static String localIpAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    private void acceptLoop() {
        while (running && server != null && !server.isClosed()) {
            try (Socket sock = server.accept()) {
                sock.setSoTimeout(15_000);
                handle(sock);
            } catch (IOException ignored) {
            }
        }
    }

    private void handle(Socket sock) throws IOException {
        BufferedInputStream in = new BufferedInputStream(sock.getInputStream());
        OutputStream out = sock.getOutputStream();

        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) return;
        String[] parts = requestLine.split(" ");
        String method = parts.length > 0 ? parts[0] : "GET";
        String path = parts.length > 1 ? parts[1] : "/";

        int contentLength = 0;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            String lower = line.toLowerCase(Locale.US);
            if (lower.startsWith("content-length:")) {
                try {
                    contentLength = Integer.parseInt(lower.substring(15).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if ("GET".equals(method) && "/".equals(path)) {
            byte[] body = HTML_PAGE.getBytes(StandardCharsets.UTF_8);
            writeResponse(out, "200 OK", "text/html; charset=utf-8", body);
            return;
        }

        if ("POST".equals(method) && "/upload".equals(path)) {
            if (contentLength <= 0 || contentLength > MAX_BYTES) {
                writeJson(out, "400 Bad Request", "{\"ok\":false,\"error\":\"Invalid file size\"}");
                return;
            }
            byte[] raw = readExact(in, contentLength);
            if (saveWallpaper(raw)) {
                writeJson(out, "200 OK", "{\"ok\":true}");
                MAIN.post(onUploaded);
            } else {
                writeJson(out, "400 Bad Request", "{\"ok\":false,\"error\":\"Unsupported image\"}");
            }
            return;
        }

        writeResponse(out, "404 Not Found", "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8));
    }

    private boolean saveWallpaper(byte[] raw) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return false;

        int sample = 1;
        while (opts.outWidth / sample > 3840 || opts.outHeight / sample > 2160) sample *= 2;
        BitmapFactory.Options dec = new BitmapFactory.Options();
        dec.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, dec);
        if (bmp == null) return false;

        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) {
            bmp.recycle();
            return false;
        }
        if (!dir.exists()) dir.mkdirs();
        File tmp = new File(dir, Config.WALLPAPER_NAME + ".tmp");
        File dst = new File(dir, Config.WALLPAPER_NAME);
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos);
            fos.flush();
        } catch (IOException e) {
            tmp.delete();
            return false;
        } finally {
            bmp.recycle();
        }
        if (dst.exists()) dst.delete();
        if (!tmp.renameTo(dst)) return false;

        ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(Config.WALLPAPER_CHOICE_KEY)
                .apply();
        return true;
    }

    static void restoreDefaultWallpaper(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir != null) {
            File dst = new File(dir, Config.WALLPAPER_NAME);
            if (dst.exists()) dst.delete();
        }
        ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(Config.WALLPAPER_CHOICE_KEY)
                .apply();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
        }
        if (b == -1 && buf.size() == 0) return null;
        return buf.toString(StandardCharsets.UTF_8.name());
    }

    private static byte[] readExact(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new IOException("Unexpected EOF");
            off += n;
        }
        return buf;
    }

    private static void writeJson(OutputStream out, String status, String json) throws IOException {
        writeResponse(out, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeResponse(OutputStream out, String status, String type, byte[] body) throws IOException {
        String hdr = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(hdr.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static final String HTML_PAGE =
            "<!doctype html><html lang='zh-Hant'><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,user-scalable=no'>"
            + "<title>CleanYourTV 桌布上傳</title>"
            + "<style>body{margin:0;background:#0e0f12;color:#f5f5f7;font-family:-apple-system,sans-serif;"
            + "display:flex;align-items:center;justify-content:center;min-height:100vh;padding:16px;box-sizing:border-box}"
            + ".card{width:100%;max-width:440px;background:#1c1e24;border-radius:20px;padding:22px;text-align:center;"
            + "box-shadow:0 20px 50px rgba(0,0,0,.5)}h1{font-size:20px;margin:0 0 6px}"
            + "p{color:#9ca3af;font-size:13px;margin:0 0 16px}input[type=file]{display:none}"
            + ".btn{display:block;width:100%;padding:13px;border-radius:12px;border:0;font-size:15px;font-weight:600;"
            + "cursor:pointer;margin-top:10px;box-sizing:border-box}.pick{background:#2d313a;color:#fff}"
            + ".send{background:#3b82f6;color:#fff}.send:disabled{opacity:.5}"
            + "#ed{display:none;margin-top:14px}"
            + ".box{position:relative;width:100%;aspect-ratio:16/9;background:#000;border-radius:10px;overflow:hidden;"
            + "border:2px solid rgba(255,255,255,.35);touch-action:none;cursor:grab}"
            + ".box canvas{width:100%;height:100%;display:block}"
            + ".ctrl{display:flex;align-items:center;gap:10px;margin-top:12px;font-size:13px;color:#cbd5e1}"
            + ".ctrl input[type=range]{flex:1;accent-color:#3b82f6}"
            + ".rst{background:#2d313a;color:#e2e8f0;border:0;border-radius:8px;padding:6px 10px;font-size:12px;cursor:pointer}"
            + "#msg{margin-top:12px;font-size:14px;color:#34d399}</style></head><body>"
            + "<div class='card'><h1>更換電視桌布</h1><p>選擇圖片後可拖動與縮放，裁切為 16:9 電視畫面</p>"
            + "<label class='btn pick' for='f'>選擇圖片</label>"
            + "<input id='f' type='file' accept='image/*'>"
            + "<div id='ed'><div class='box' id='box'><canvas id='cv'></canvas></div>"
            + "<div class='ctrl'><span>縮放</span><input id='zm' type='range' min='1' max='4' step='0.01' value='1'>"
            + "<button type='button' class='rst' id='rst'>重置</button></div>"
            + "<button id='go' class='btn send'>上傳並設為桌布</button></div>"
            + "<div id='msg'></div></div><script>"
            + "const f=document.getElementById('f'),ed=document.getElementById('ed'),box=document.getElementById('box'),"
            + "cv=document.getElementById('cv'),ctx=cv.getContext('2d'),zm=document.getElementById('zm'),"
            + "rst=document.getElementById('rst'),go=document.getElementById('go'),msg=document.getElementById('msg');"
            + "let img=null,sc=1,ox=0,oy=0,drag=false,lx=0,ly=0,pDist=0,pSc=1;"
            + "function baseScale(){return Math.max(cv.width/img.width,cv.height/img.height);}"
            + "function clamp(){const s=baseScale()*sc,w=img.width*s,h=img.height*s;"
            + "const mx=Math.max(0,(w-cv.width)/2),my=Math.max(0,(h-cv.height)/2);"
            + "ox=Math.max(-mx,Math.min(mx,ox));oy=Math.max(-my,Math.min(my,oy));}"
            + "function draw(){if(!img)return;clamp();ctx.fillStyle='#000';ctx.fillRect(0,0,cv.width,cv.height);"
            + "const s=baseScale()*sc,w=img.width*s,h=img.height*s;"
            + "ctx.drawImage(img,(cv.width-w)/2+ox,(cv.height-h)/2+oy,w,h);}"
            + "function resetView(){sc=1;ox=0;oy=0;zm.value='1';draw();}"
            + "f.onchange=()=>{const file=f.files[0];if(!file)return;msg.textContent='';"
            + "const u=URL.createObjectURL(file),im=new Image();im.onload=()=>{img=im;ed.style.display='block';"
            + "cv.width=960;cv.height=540;resetView();URL.revokeObjectURL(u);};im.src=u;};"
            + "zm.oninput=()=>{sc=parseFloat(zm.value);draw();};rst.onclick=resetView;"
            + "box.onpointerdown=e=>{drag=true;lx=e.clientX;ly=e.clientY;box.setPointerCapture(e.pointerId);};"
            + "box.onpointermove=e=>{if(!drag||!img)return;const r=cv.width/box.clientWidth;"
            + "ox+=(e.clientX-lx)*r;oy+=(e.clientY-ly)*r;lx=e.clientX;ly=e.clientY;draw();};"
            + "box.onpointerup=()=>{drag=false;};box.onpointercancel=()=>{drag=false;};"
            + "box.ontouchstart=e=>{if(e.touches.length===2){drag=false;"
            + "pDist=Math.hypot(e.touches[0].clientX-e.touches[1].clientX,e.touches[0].clientY-e.touches[1].clientY);"
            + "pSc=sc;}};"
            + "box.ontouchmove=e=>{if(e.touches.length===2&&img){e.preventDefault();"
            + "const d=Math.hypot(e.touches[0].clientX-e.touches[1].clientX,e.touches[0].clientY-e.touches[1].clientY);"
            + "sc=Math.max(1,Math.min(4,pSc*(d/pDist)));zm.value=sc.toFixed(2);draw();}};"
            + "go.onclick=async()=>{if(!img)return;go.disabled=true;msg.style.color='#9ca3af';msg.textContent='裁切並上傳中…';"
            + "const out=document.createElement('canvas');out.width=1920;out.height=1080;"
            + "const octx=out.getContext('2d'),k=out.width/cv.width,s=baseScale()*sc*k,w=img.width*s,h=img.height*s;"
            + "octx.fillStyle='#000';octx.fillRect(0,0,out.width,out.height);"
            + "octx.drawImage(img,(out.width-w)/2+ox*k,(out.height-h)/2+oy*k,w,h);"
            + "out.toBlob(async blob=>{try{const r=await fetch('/upload',{method:'POST',body:blob});"
            + "const j=await r.json();if(j.ok){msg.style.color='#34d399';msg.textContent='成功！電視桌布已更新';}"
            + "else{msg.style.color='#f87171';msg.textContent='失敗：'+(j.error||'未知錯誤');go.disabled=false;}"
            + "}catch(e){msg.style.color='#f87171';msg.textContent='連線失敗，請確認在同一 Wi-Fi';go.disabled=false;}"
            + "},'image/jpeg',0.92);};"
            + "</script></body></html>";
}
