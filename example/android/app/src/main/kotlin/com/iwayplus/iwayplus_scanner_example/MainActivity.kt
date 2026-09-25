package com.iwayplus.iwayplus_scanner_example

import android.os.Bundle
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity

class MainActivity : FlutterActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // A smoke test is watched, not used: carried from indoors to outdoors to
    // see GPS back off and recover. A locked screen backgrounds the app, and
    // Android then throttles its location updates, which would hide exactly
    // the behaviour being tested.
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }
}
