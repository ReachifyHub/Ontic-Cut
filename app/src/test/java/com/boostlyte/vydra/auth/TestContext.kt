package com.boostlyte.vydra.auth

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences

/**
 * Minimal context wrapper supplying in-memory TestSharedPreferences for JVM testing.
 */
class TestContext : ContextWrapper(null) {

    private val sharedPrefsMap = HashMap<String, TestSharedPreferences>()

    override fun getApplicationContext(): Context {
        return this
    }

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        val key = name ?: "default"
        return sharedPrefsMap.getOrPut(key) { TestSharedPreferences() }
    }
}
