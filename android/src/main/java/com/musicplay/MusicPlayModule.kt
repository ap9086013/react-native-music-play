package com.musicplay

import com.facebook.react.bridge.ReactApplicationContext

class MusicPlayModule(reactContext: ReactApplicationContext) :
  NativeMusicPlaySpec(reactContext) {

  override fun multiply(a: Double, b: Double): Double {
    return a * b
  }

  companion object {
    const val NAME = NativeMusicPlaySpec.NAME
  }
}
