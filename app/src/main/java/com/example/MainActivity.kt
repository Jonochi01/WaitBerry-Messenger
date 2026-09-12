package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SignalWifiConnectedNoInternet4
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme

private const val TARGET_URL = "https://messenger.waitberry.com"

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      MyApplicationTheme {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background
        ) {
          WaitberryMessengerScreen(
            url = TARGET_URL,
            activity = this
          )
        }
      }
    }
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WaitberryMessengerScreen(
  url: String,
  activity: Activity,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current

  var webViewInstance by remember { mutableStateOf<WebView?>(null) }
  var loadingProgress by remember { mutableFloatStateOf(0f) }
  var isLoading by remember { mutableStateOf(true) }
  var isInitialLoad by remember { mutableStateOf(true) }
  var hasError by remember { mutableStateOf(false) }
  var lastBackPressTime by remember { mutableLongStateOf(0L) }

  // Reference to file upload callback from WebChromeClient
  var filePathCallbackRef by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

  // File picker launcher for WebChromeClient onShowFileChooser
  val fileChooserLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) { result ->
    val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
      val intentData = result.data
      val clipData = intentData?.clipData
      if (clipData != null) {
        Array(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
      } else {
        intentData?.data?.let { arrayOf(it) }
      }
    } else {
      null
    }
    filePathCallbackRef?.onReceiveValue(uris)
    filePathCallbackRef = null
  }

  // Permission launcher for Camera and Microphone (for messenger voice notes / video calls)
  val permissionsLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestMultiplePermissions()
  ) { permissions ->
    // Permissions handled
  }

  LaunchedEffect(Unit) {
    val neededPermissions = mutableListOf<String>()
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      neededPermissions.add(Manifest.permission.CAMERA)
    }
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      neededPermissions.add(Manifest.permission.RECORD_AUDIO)
    }
    if (neededPermissions.isNotEmpty()) {
      permissionsLauncher.launch(neededPermissions.toTypedArray())
    }
  }

  // Handle system back navigation
  BackHandler(enabled = true) {
    if (webViewInstance?.canGoBack() == true) {
      webViewInstance?.goBack()
    } else {
      val now = System.currentTimeMillis()
      if (now - lastBackPressTime < 2000L) {
        activity.finish()
      } else {
        lastBackPressTime = now
        Toast.makeText(context, "Tekan sekali lagi untuk keluar", Toast.LENGTH_SHORT).show()
      }
    }
  }

  // Ensure cookies are saved when user leaves screen
  DisposableEffect(Unit) {
    onDispose {
      CookieManager.getInstance().flush()
    }
  }

  val animatedProgress by animateFloatAsState(
    targetValue = loadingProgress,
    label = "loadingProgress"
  )

  Box(
    modifier = modifier
      .fillMaxSize()
      .statusBarsPadding()
  ) {
    // 1. Fullscreen WebView without any URL/link toolbar
    AndroidView(
      modifier = Modifier
        .fillMaxSize()
        .testTag("webview_container"),
      factory = { ctx ->
        WebView(ctx).apply {
          layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )

          // Enable HTML5, JavaScript and Storage for modern web messenger
          settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            displayZoomControls = false
            userAgentString = settings.userAgentString.replace("; wv", "")
          }

          // Cookie handling
          val cookieManager = CookieManager.getInstance()
          cookieManager.setAcceptCookie(true)
          cookieManager.setAcceptThirdPartyCookies(this, true)

          // WebChromeClient for progress, permission requests and file upload
          webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
              loadingProgress = newProgress / 100f
              if (newProgress >= 100) {
                isLoading = false
                isInitialLoad = false
              } else {
                isLoading = true
              }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
              request?.let {
                // Grant camera & audio permissions requested by WebRTC in WebView
                it.grant(it.resources)
              }
            }

            override fun onShowFileChooser(
              webView: WebView?,
              filePathCallback: ValueCallback<Array<Uri>>?,
              fileChooserParams: FileChooserParams?
            ): Boolean {
              filePathCallbackRef?.onReceiveValue(null)
              filePathCallbackRef = filePathCallback

              val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
              }

              return try {
                fileChooserLauncher.launch(intent)
                true
              } catch (e: Exception) {
                filePathCallbackRef?.onReceiveValue(null)
                filePathCallbackRef = null
                false
              }
            }
          }

          // WebViewClient to keep messenger links inside WebView & catch external links
          webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
              super.onPageStarted(view, url, favicon)
              isLoading = true
              hasError = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
              super.onPageFinished(view, url)
              isLoading = false
              isInitialLoad = false
              CookieManager.getInstance().flush()
            }

            override fun onReceivedError(
              view: WebView?,
              request: WebResourceRequest?,
              error: WebResourceError?
            ) {
              super.onReceivedError(view, request, error)
              if (request?.isForMainFrame == true) {
                hasError = true
                isLoading = false
              }
            }

            override fun shouldOverrideUrlLoading(
              view: WebView?,
              request: WebResourceRequest?
            ): Boolean {
              val requestUri = request?.url ?: return false
              val scheme = requestUri.scheme?.lowercase() ?: ""
              val host = requestUri.host?.lowercase() ?: ""

              // Special intent schemes (phone, mail, whatsapp, etc.)
              if (scheme != "http" && scheme != "https") {
                return try {
                  val intent = Intent(Intent.ACTION_VIEW, requestUri)
                  context.startActivity(intent)
                  true
                } catch (e: Exception) {
                  false
                }
              }

              // Keep waitberry domain and auth redirects inside this WebView
              if (host.contains("waitberry.com") ||
                  host.contains("accounts.google.com") ||
                  host.contains("facebook.com") ||
                  host.contains("apple.com")
              ) {
                return false
              }

              // External link: open in standard browser
              return try {
                val intent = Intent(Intent.ACTION_VIEW, requestUri)
                context.startActivity(intent)
                true
              } catch (e: Exception) {
                false
              }
            }
          }

          webViewInstance = this
          loadUrl(url)
        }
      },
      update = {
        // Keep webview reference updated
        webViewInstance = it
      }
    )

    // 2. Sleek thin loading progress bar at the very top (vanishes once finished)
    AnimatedVisibility(
      visible = isLoading && !hasError && !isInitialLoad,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier
        .fillMaxWidth()
        .align(Alignment.TopCenter)
    ) {
      LinearProgressIndicator(
        progress = { animatedProgress },
        modifier = Modifier
          .fillMaxWidth()
          .height(3.dp),
        color = Color(0xFF8B5CF6),
        trackColor = Color.Transparent
      )
    }

    // 3. Branded Initial Splash / Loading Screen
    AnimatedVisibility(
      visible = isInitialLoad && !hasError,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier.fillMaxSize()
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(
            Brush.verticalGradient(
              colors = listOf(
                Color(0xFF130E26),
                Color(0xFF0F0B1E)
              )
            )
          )
          .testTag("loading_view"),
        contentAlignment = Alignment.Center
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
          modifier = Modifier.padding(24.dp)
        ) {
          Image(
            painter = painterResource(id = R.drawable.ic_waitberry_logo),
            contentDescription = "Waitberry Logo",
            modifier = Modifier
              .size(96.dp)
              .clip(RoundedCornerShape(24.dp))
          )

          Spacer(modifier = Modifier.height(24.dp))

          Text(
            text = "Waitberry",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
          )

          Spacer(modifier = Modifier.height(8.dp))

          Text(
            text = stringResource(id = R.string.loading_text),
            fontSize = 14.sp,
            color = Color(0xFFA5B4FC)
          )

          Spacer(modifier = Modifier.height(24.dp))

          CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            color = Color(0xFFA855F7),
            strokeWidth = 3.dp
          )
        }
      }
    }

    // 4. Offline / Connection Error Screen with Retry Button
    AnimatedVisibility(
      visible = hasError,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier.fillMaxSize()
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.background)
          .padding(24.dp),
        contentAlignment = Alignment.Center
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
          modifier = Modifier.fillMaxWidth()
        ) {
          Box(
            modifier = Modifier
              .size(80.dp)
              .clip(CircleShape)
              .background(Color(0xFFF3E8FF)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Rounded.SignalWifiConnectedNoInternet4,
              contentDescription = null,
              tint = Color(0xFF7C3AED),
              modifier = Modifier.size(40.dp)
            )
          }

          Spacer(modifier = Modifier.height(20.dp))

          Text(
            text = stringResource(id = R.string.connection_error_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
          )

          Spacer(modifier = Modifier.height(8.dp))

          Text(
            text = stringResource(id = R.string.connection_error_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
          )

          Spacer(modifier = Modifier.height(28.dp))

          Button(
            onClick = {
              hasError = false
              isLoading = true
              webViewInstance?.reload()
            },
            colors = ButtonDefaults.buttonColors(
              containerColor = Color(0xFF7C3AED),
              contentColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.testTag("retry_button")
          ) {
            Icon(
              imageVector = Icons.Rounded.Refresh,
              contentDescription = null,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
              text = stringResource(id = R.string.btn_retry),
              fontWeight = FontWeight.SemiBold
            )
          }
        }
      }
    }
  }
}

// Retained for tests and preview compatibility
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
  Text(text = "Hello $name!", modifier = modifier)
}
